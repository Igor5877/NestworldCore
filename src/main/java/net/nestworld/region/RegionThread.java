package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
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
    private final Object tickSignal = new Object();

    public RegionThread(WorldRegion region, ServerLevel level) {
        super("NestWorld-Region-" + region.getId());
        setDaemon(true);
        this.region = region;
        this.level = level;
        region.owningThread = this;
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
            // Block until the pool requests a tick
            synchronized (tickSignal) {
                while (running && tickLatch == null) {
                    try { tickSignal.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                latch = tickLatch;
                tickLatch = null;
            }
            if (!running) {
                if (latch != null) latch.countDown();
                break;
            }

            long tickStart = System.nanoTime();
            try {
                long stamp = region.getChunkLock().writeLock();
                try {
                    tickEntities();
                    tickBlockEntities();
                } finally {
                    region.getChunkLock().unlockWrite(stamp);
                }
            } catch (Throwable crash) {
                handleCrash(crash);
                // after crash, running == false; latch is still counted down below
            } finally {
                region.recordTickDuration(System.nanoTime() - tickStart);
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
        for (java.util.UUID uuid : region.getOwnedEntityIds()) {
            Entity entity = level.getEntity(uuid);
            if (entity == null || entity.isRemoved()) continue;

            level.getProfiler().push(entity::getEncodeId);
            try {
                entity.checkDespawn();
                if (!entity.isRemoved()) entity.tick();
            } catch (Throwable t) {
                LOGGER.warn("[{}] Entity {} tick error: {}", getName(), uuid, t.getMessage());
            } finally {
                level.getProfiler().pop();
            }
        }
    }

    /**
     * Ticks all block entities in the region's chunk columns.
     * Uses getChunkNow() — never triggers a synchronous chunk load.
     * Cross-region capability accesses during this phase are served
     * from BoundaryManager's ghost-zone cache (read-only, no lock needed).
     */
    private void tickBlockEntities() {
        for (int cx = region.getMinChunkX(); cx <= region.getMaxChunkX(); cx++) {
            for (int cz = region.getMinChunkZ(); cz <= region.getMaxChunkZ(); cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be.isRemoved()) continue;
                    try {
                        if (be.getBlockState().getBlock() instanceof EntityBlock eb) {
                            @SuppressWarnings("unchecked")
                            BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>)
                                    eb.getTicker(level, be.getBlockState(), be.getType());
                            if (ticker != null) {
                                ticker.tick(level, be.getBlockPos(), be.getBlockState(), be);
                            }
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("[{}] BlockEntity at {} tick error: {}", getName(), be.getBlockPos(), t.getMessage());
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Crash recovery
    // -----------------------------------------------------------------------

    private void handleCrash(Throwable t) {
        LOGGER.error("[{}] CRASH in region {} — starting emergency save", getName(), region, t);
        try {
            for (int cx = region.getMinChunkX(); cx <= region.getMaxChunkX(); cx++) {
                for (int cz = region.getMinChunkZ(); cz <= region.getMaxChunkZ(); cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) level.getChunkSource().save(false);
                }
            }
            LOGGER.info("[{}] Emergency save complete for {}", getName(), region);
        } catch (Throwable saveErr) {
            LOGGER.error("[{}] Emergency save failed for {}", getName(), region, saveErr);
        }
        running = false;
        // Pool monitors thread liveness and will spawn a replacement
    }
}
