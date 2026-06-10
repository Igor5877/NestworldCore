package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * One dedicated thread per active WorldRegion.
 *
 * Tick protocol each game tick:
 *   Main thread calls requestTick() on every RegionThread.
 *   Each RegionThread wakes, acquires the region write-lock, ticks its entities
 *   and block-entities, then arrives at the CyclicBarrier.
 *   Main thread also calls barrier.await() — all threads sync before the
 *   main tick continues with networking, chunk I/O, etc.
 *
 * On crash: emergency-saves owned chunks, marks itself stopped; the
 * RegionThreadPool detects the dead thread and spawns a replacement.
 */
public class RegionThread extends Thread {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionThread");

    final WorldRegion region;
    private final ServerLevel level;

    /** Replaced atomically by the pool after every split/merge (barrier resizes). */
    private volatile CyclicBarrier barrier;

    private volatile boolean running = true;
    private volatile boolean tickPending = false;
    private final Object tickSignal = new Object();

    public RegionThread(WorldRegion region, ServerLevel level, CyclicBarrier barrier) {
        super("NestWorld-Region-" + region.getId());
        setDaemon(true);
        this.region = region;
        this.level = level;
        this.barrier = barrier;
        region.owningThread = this;
    }

    // -----------------------------------------------------------------------
    // Pool-facing control API
    // -----------------------------------------------------------------------

    /** Signals this thread to execute one tick. Non-blocking. */
    public void requestTick() {
        synchronized (tickSignal) {
            tickPending = true;
            tickSignal.notifyAll();
        }
    }

    /** Called by the pool after a split or merge to swap in a resized barrier. */
    public void updateBarrier(CyclicBarrier newBarrier) {
        this.barrier = newBarrier;
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
            // Block until the pool requests a tick
            synchronized (tickSignal) {
                while (running && !tickPending) {
                    try { tickSignal.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                tickPending = false;
            }
            if (!running) break;

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
                // after crash, running == false; we still arrive at the barrier once
            } finally {
                region.recordTickDuration(System.nanoTime() - tickStart);
                awaitBarrier();
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
     * to skip entities whose UUID is listed in any region's ownedEntityIds —
     * preventing double-ticking.
     */
    private void tickEntities() {
        for (Entity entity : level.getAllEntities()) {
            if (entity.isRemoved()) continue;
            if (!region.ownsEntity(entity.getUUID())) continue;

            level.getProfiler().push(entity::getEncodeId);
            try {
                entity.checkDespawn();
                if (!entity.isRemoved()) entity.tick();
            } catch (Throwable t) {
                LOGGER.warn("[{}] Entity {} tick error: {}", getName(), entity.getUUID(), t.getMessage());
            } finally {
                level.getProfiler().pop();
            }
        }
    }

    /**
     * Ticks all block entities in the region's chunk columns.
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

    private void awaitBarrier() {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException ignored) {
            // Barrier was reset during a split/merge — no action needed
        }
    }
}
