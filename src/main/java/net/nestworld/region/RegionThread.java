package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;

/**
 * One dedicated thread per active WorldRegion.
 *
 * Tick protocol each game tick:
 *   Main thread calls requestTick(latch) on every RegionThread.
 *   Each RegionThread wakes, acquires the region write-lock, ticks its entities
 *   and block-entities, then counts down the per-tick latch.
 *   The main thread waits on the latch while servicing the chunk-source task
 *   queue (ServerChunkCache.pollTask) so that any synchronous chunk request a
 *   region thread makes (getChunk().join()) is completed by the main thread
 *   instead of deadlocking.
 *
 * On crash: emergency-saves owned chunks, marks itself stopped; the
 * RegionThreadPool detects the dead thread and spawns a replacement.
 */
public class RegionThread extends Thread {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionThread");

    final WorldRegion region;
    private final ServerLevel level;

    private volatile boolean running = true;
    /** Latch for the tick currently being requested; null when idle. */
    private volatile CountDownLatch tickLatch = null;
    /** When set, the next round executes this instead of an entity tick. */
    private java.util.List<Runnable> pendingWork = null; // guarded by tickSignal
    private final Object tickSignal = new Object();
    /** Work-round nanos folded into the next entity round's TPS record. */
    private long workRoundNanos = 0;

    // Timing diagnostics, logged every 200 ticks
    private long entNanos = 0;
    private int timedTicks = 0, lastTickedCount = 0;

    // -----------------------------------------------------------------------
    // Per-thread chunk cache — read by ServerChunkCache.nestworldGetLoadedChunk
    // (see ServerChunkCache.java.patch). Entity ticking performs thousands of
    // block reads per tick; without this cache every read pays a chunk-holder
    // map lookup + future unwrap. Direct-mapped, 16 slots, cleared each tick.
    // -----------------------------------------------------------------------
    private final long[] chunkCacheKeys = new long[16];
    private final net.minecraft.world.level.chunk.LevelChunk[] chunkCacheVals =
            new net.minecraft.world.level.chunk.LevelChunk[16];

    public net.minecraft.world.level.chunk.LevelChunk getCachedChunk(long key) {
        int idx = (int) (key ^ (key >>> 32)) & 15;
        return chunkCacheKeys[idx] == key ? chunkCacheVals[idx] : null;
    }

    public void cacheChunk(long key, net.minecraft.world.level.chunk.LevelChunk chunk) {
        int idx = (int) (key ^ (key >>> 32)) & 15;
        chunkCacheKeys[idx] = key;
        chunkCacheVals[idx] = chunk;
    }

    private void clearChunkCache() {
        java.util.Arrays.fill(chunkCacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(chunkCacheVals, null);
    }

    public RegionThread(WorldRegion region, ServerLevel level) {
        super("NestWorld-Region-" + region.getId());
        setDaemon(true);
        this.region = region;
        this.level = level;
        region.owningThread = this;
        clearChunkCache();
    }

    // -----------------------------------------------------------------------
    // Per-thread Alternate Current handler, bounded to this region's chunks.
    // Wire updates triggered from this thread (scheduled ticks, entities on
    // pressure plates) run here; networks reaching outside the region abort
    // and are deferred to the main thread (see NestworldRedstone).
    // -----------------------------------------------------------------------
    private alternate.current.wire.WireHandler acWireHandler;

    /**
     * Re-entrancy depth of NestworldRedstone wire calls on this thread. A
     * NetworkOutOfBounds abort may only reset the handler at depth 1 —
     * resetting mid-update from a nested neighborChanged would corrupt the
     * outer update's queues (observed as NPEs in entity ticks).
     */
    int wireCallDepth = 0;

    public alternate.current.wire.WireHandler acWireHandler() {
        if (acWireHandler == null) {
            acWireHandler = new alternate.current.wire.WireHandler(
                    level, level.nestworldWireHandler.getConfig());
            acWireHandler.setBounds(
                    region.getMinChunkX() << 4, region.getMinChunkZ() << 4,
                    ((region.getMaxChunkX() + 1) << 4) - 1, ((region.getMaxChunkZ() + 1) << 4) - 1);
        }
        return acWireHandler;
    }

    // -----------------------------------------------------------------------
    // Pool-facing control API
    // -----------------------------------------------------------------------

    /**
     * Signals this thread to execute one tick. Non-blocking.
     * The thread counts the latch down when its tick completes (or crashes).
     */
    public void requestTick(CountDownLatch latch) {
        synchronized (tickSignal) {
            tickLatch = latch;
            tickSignal.notifyAll();
        }
    }

    /**
     * Signals this thread to run an arbitrary work list (e.g. this region's
     * bucket of due scheduled block ticks) under the region write lock instead
     * of an entity tick. Non-blocking; the latch is counted down when done.
     */
    public void requestWork(java.util.List<Runnable> work, CountDownLatch latch) {
        synchronized (tickSignal) {
            pendingWork = work;
            tickLatch = latch;
            tickSignal.notifyAll();
        }
    }

    public void shutdown() {
        running = false;
        synchronized (tickSignal) { tickSignal.notifyAll(); }
    }

    public boolean isCrashed() { return !running && isAlive(); }

    // -----------------------------------------------------------------------
    // Main loop
    // -----------------------------------------------------------------------

    @Override
    public void run() {
        while (running) {
            CountDownLatch latch;
            java.util.List<Runnable> work;
            // Block until the pool requests a tick or a work round
            synchronized (tickSignal) {
                while (running && tickLatch == null) {
                    try { tickSignal.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                latch = tickLatch;
                tickLatch = null;
                work = pendingWork;
                pendingWork = null;
            }
            if (!running) {
                if (latch != null) latch.countDown();
                break;
            }

            long tickStart = System.nanoTime();
            try {
                long stamp = region.getChunkLock().writeLock();
                try {
                    clearChunkCache(); // chunks may have unloaded since last tick
                    if (work != null) {
                        for (Runnable r : work) {
                            try {
                                r.run();
                            } catch (Throwable t) {
                                LOGGER.warn("[{}] scheduled-tick error: {}", getName(), t.toString());
                            }
                        }
                    } else {
                        long e0 = System.nanoTime();
                        tickEntities();
                        entNanos += System.nanoTime() - e0;
                        if (++timedTicks >= 200) {
                            LOGGER.info("[{}] avg ms over {} ticks: entities={} ({} owned, {} ticked)",
                                    getName(), timedTicks,
                                    String.format("%.2f", entNanos / 1e6 / timedTicks),
                                    region.getOwnedEntityIds().size(), lastTickedCount);
                            entNanos = 0; timedTicks = 0;
                        }
                    }
                } finally {
                    region.getChunkLock().unlockWrite(stamp);
                }
            } catch (Throwable crash) {
                handleCrash(crash);
                // after crash, running == false; latch is still counted down below
            } finally {
                // Work rounds and the entity round are halves of the same game
                // tick — fold work time into the entity round's TPS record so
                // region cost (split heuristic) reflects the full tick.
                if (work != null) {
                    workRoundNanos += System.nanoTime() - tickStart;
                    // Entity-less regions are skipped by the entity round, so
                    // record here or their cost would never update.
                    if (region.getOwnedEntityIds().isEmpty()) {
                        region.recordTickDuration(workRoundNanos);
                        workRoundNanos = 0;
                    }
                } else {
                    region.recordTickDuration(System.nanoTime() - tickStart + workRoundNanos);
                    workRoundNanos = 0;
                }
                latch.countDown();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tick logic
    // -----------------------------------------------------------------------

    /**
     * Ticks every entity assigned to this region.
     * Entity ownership is maintained by BoundaryEntityTransfer each tick.
     *
     * Note: ServerLevel's own entity-tick loop is patched (see
     * patches/minecraft/net/minecraft/server/level/ServerLevel.java.patch)
     * to skip the vanilla loop entirely while NestWorld manages the overworld —
     * preventing double-ticking.
     */
    private void tickEntities() {
        int ticked = 0;
        for (java.util.UUID uuid : region.getOwnedEntityIds()) {
            Entity entity = level.getEntity(uuid);
            if (entity == null || entity.isRemoved() || entity.isPassenger()) continue;

            try {
                entity.checkDespawn();
                if (entity.isRemoved()) continue;
                // Mirror vanilla: only tick entities inside entity-ticking chunks,
                // otherwise idle mobs at the edge of loaded terrain burn CPU on AI.
                if (!level.isPositionEntityTicking(entity.blockPosition())) continue;
                level.tickNonPassenger(entity);
                ticked++;
            } catch (Throwable t) {
                LOGGER.warn("[{}] Entity {} tick error: {}", getName(), uuid, t.getMessage());
            }
        }
        lastTickedCount = ticked;
    }

    // Block entities are ticked by the vanilla path on the main thread
    // (the ServerLevel patch skips only the entity loop), which applies the
    // correct ticking-chunk gating for regions of any size.

    // -----------------------------------------------------------------------
    // Crash recovery
    // -----------------------------------------------------------------------

    private void handleCrash(Throwable t) {
        LOGGER.error("[{}] CRASH in region {} — starting emergency save", getName(), region, t);
        try {
            level.getChunkSource().save(false);
            LOGGER.info("[{}] Emergency save complete for {}", getName(), region);
        } catch (Throwable saveErr) {
            LOGGER.error("[{}] Emergency save failed for {}", getName(), region, saveErr);
        }
        running = false;
        // Pool monitors thread liveness and will spawn a replacement
    }
}
