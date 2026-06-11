package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Manages the pool of RegionThreads and drives each game tick.
 *
 * <p>Tick protocol:
 * <ol>
 *   <li>Main server thread calls {@link #tickAllRegions()}.</li>
 *   <li>A fresh per-tick {@link CountDownLatch} (one count per region thread)
 *       is handed to every RegionThread via {@link RegionThread#requestTick}.</li>
 *   <li>All region threads tick their entities/block-entities in parallel.</li>
 *   <li>While waiting, the main thread services the chunk-source task queue
 *       ({@code ServerChunkCache.pollTask()}) so synchronous chunk requests
 *       made by region threads ({@code getChunk().join()}) complete instead
 *       of deadlocking against the waiting main thread.</li>
 *   <li>Each region thread counts the latch down when done; the main thread
 *       continues once the latch reaches zero.</li>
 * </ol>
 */
public class RegionThreadPool {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionThreadPool");

    /** Hard cap before we abandon a tick to avoid the 60 s watchdog kill. */
    private static final long TICK_WAIT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ServerLevel level;
    private final CopyOnWriteArrayList<RegionThread> threads = new CopyOnWriteArrayList<>();

    public RegionThreadPool(ServerLevel level) {
        this.level = level;
    }

    // -----------------------------------------------------------------------
    // Thread lifecycle
    // -----------------------------------------------------------------------

    /** Spawns a new RegionThread for the given region and starts it. */
    public RegionThread spawn(WorldRegion region) {
        RegionThread thread = new RegionThread(region, level);
        threads.add(thread);
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
        LOGGER.info("Removed region {} (total active regions: {})", region.getId(), threads.size());
    }

    // -----------------------------------------------------------------------
    // Tick driver
    // -----------------------------------------------------------------------

    /**
     * Called once per game tick from the patched MinecraftServer tick loop.
     * Wakes all region threads, then services chunk tasks until every thread
     * has finished ticking its region.
     */
    public void tickAllRegions() {
        respawnCrashedThreads();

        if (threads.isEmpty()) return;

        // Regions with no owned entities have nothing to do — leave their
        // threads parked. With many regions (deep split trees) the wakeup +
        // latch round-trip for dozens of idle threads costs real milliseconds.
        // Threads still holding budget-deferred work must wake regardless, or
        // a one-shot scheduled-tick burst in an entity-less region would
        // freeze mid-cascade until the next work round happens to reach it.
        java.util.List<RegionThread> workers = new java.util.ArrayList<>();
        for (RegionThread t : threads) {
            if (!t.region.getOwnedEntityIds().isEmpty() || t.getLastWorkDeferred() > 0) workers.add(t);
        }
        if (workers.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(workers.size());

        // Signal the working threads to start their tick
        for (RegionThread t : workers) t.requestTick(latch);

        awaitLatch(latch);
    }

    /**
     * Runs each region's work list (e.g. its bucket of due scheduled block
     * ticks) on that region's thread, in parallel, and blocks until all are
     * done. Called from the main thread mid-tick (inside ServerLevel.tick).
     */
    public void runWorkRound(java.util.Map<WorldRegion, java.util.List<Runnable>> assignments) {
        if (assignments.isEmpty()) return;

        java.util.List<RegionThread> workers = new java.util.ArrayList<>();
        for (RegionThread t : threads) {
            java.util.List<Runnable> work = assignments.get(t.region);
            if (work != null && !work.isEmpty()) workers.add(t);
        }
        if (workers.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(workers.size());
        for (RegionThread t : workers) t.requestWork(assignments.get(t.region), latch);

        awaitLatch(latch);
    }

    /**
     * Waits for a round to finish while servicing the chunk task queue —
     * this is what lets region threads safely call getChunk().join().
     */
    private void awaitLatch(CountDownLatch latch) {
        long start = System.nanoTime();
        while (latch.getCount() > 0) {
            boolean didWork = level.getChunkSource().pollTask();
            if (!didWork) {
                LockSupport.parkNanos(50_000); // 50 µs
            }
            if (System.nanoTime() - start > TICK_WAIT_LIMIT_NANOS) {
                LOGGER.error("Region round exceeded {} s — abandoning wait ({} threads still running)",
                        TimeUnit.NANOSECONDS.toSeconds(TICK_WAIT_LIMIT_NANOS), latch.getCount());
                break;
            }
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
    // Accessors
    // -----------------------------------------------------------------------

    public List<RegionThread> getThreads() { return threads; }
    public int getRegionCount() { return threads.size(); }
    public ServerLevel getLevel() { return level; }
}
