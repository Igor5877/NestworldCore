package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

/**
 * Manages the pool of RegionThreads and drives each game tick.
 *
 * <p>Tick protocol:
 * <ol>
 *   <li>Main server thread calls {@link #tickAllRegions()}.</li>
 *   <li>Every RegionThread is woken via {@link RegionThread#requestTick()}.</li>
 *   <li>All region threads tick their entities/block-entities in parallel.</li>
 *   <li>Each thread arrives at the {@link CyclicBarrier} when done.</li>
 *   <li>The main thread also calls {@code barrier.await()} — all threads
 *       synchronise here before the main tick continues.</li>
 * </ol>
 *
 * <p>After a split or merge the barrier is rebuilt (party count changes) and
 * pushed to every live RegionThread via {@link RegionThread#updateBarrier}.
 */
public class RegionThreadPool {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionThreadPool");

    private final ServerLevel level;
    private final CopyOnWriteArrayList<RegionThread> threads = new CopyOnWriteArrayList<>();

    /**
     * Barrier used each tick. Party count = number of region threads + 1 (main thread).
     * Rebuilt whenever a thread is added or removed.
     */
    private volatile CyclicBarrier barrier;

    public RegionThreadPool(ServerLevel level) {
        this.level = level;
        // Start with a barrier for just the main thread (no regions yet)
        this.barrier = new CyclicBarrier(1);
    }

    // -----------------------------------------------------------------------
    // Thread lifecycle
    // -----------------------------------------------------------------------

    /** Spawns a new RegionThread for the given region and starts it. */
    public RegionThread spawn(WorldRegion region) {
        RegionThread thread = new RegionThread(region, level, barrier);
        threads.add(thread);
        rebuildBarrier();
        thread.start();
        LOGGER.info("Spawned {} (total active regions: {})", thread.getName(), threads.size());
        return thread;
    }

    /**
     * Gracefully shuts down the thread for the given region.
     * Call this before a merge so the thread no longer ticks the region.
     */
    public void remove(WorldRegion region) {
        threads.removeIf(t -> {
            if (t.region == region) {
                t.shutdown();
                return true;
            }
            return false;
        });
        rebuildBarrier();
        LOGGER.info("Removed region {} (total active regions: {})", region.getId(), threads.size());
    }

    // -----------------------------------------------------------------------
    // Tick driver
    // -----------------------------------------------------------------------

    /**
     * Called once per game tick from the patched MinecraftServer tick loop.
     * Wakes all region threads, then blocks until every thread has finished
     * ticking its region.
     *
     * Detects crashed threads and schedules replacements via
     * {@link #respawnCrashedThreads()} before the barrier wait so the
     * new thread participates in the very next tick.
     */
    public void tickAllRegions() {
        respawnCrashedThreads();

        if (threads.isEmpty()) return;

        // Signal all threads to start their tick
        for (RegionThread t : threads) t.requestTick();

        // Wait until every region thread reaches the barrier
        try {
            barrier.await();
        } catch (Exception e) {
            LOGGER.warn("Barrier await interrupted on main thread: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Crash recovery
    // -----------------------------------------------------------------------

    private void respawnCrashedThreads() {
        for (RegionThread t : threads) {
            if (!t.isAlive()) {
                LOGGER.warn("Thread {} is dead — respawning in 5 s", t.getName());
                WorldRegion region = t.region;
                threads.remove(t);
                rebuildBarrier();

                Thread respawner = new Thread(() -> {
                    try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
                    spawn(region);
                    LOGGER.info("Respawned RegionThread for {}", region);
                }, "NestWorld-Respawner-" + region.getId());
                respawner.setDaemon(true);
                respawner.start();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Barrier management
    // -----------------------------------------------------------------------

    /** Rebuilds the barrier whenever the thread count changes. Pushes new barrier to all live threads. */
    private void rebuildBarrier() {
        // Party count: all region threads + the main server thread
        int parties = threads.size() + 1;
        CyclicBarrier newBarrier = new CyclicBarrier(parties);
        this.barrier = newBarrier;
        for (RegionThread t : threads) t.updateBarrier(newBarrier);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public List<RegionThread> getThreads() { return threads; }
    public int getRegionCount() { return threads.size(); }
}
