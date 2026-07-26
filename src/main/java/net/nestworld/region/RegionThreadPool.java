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
     * Call this before a split/merge so the thread no longer ticks the region.
     */
    public void remove(WorldRegion region) {
        threads.removeIf(t -> {
            if (t.region == region) {
                t.shutdown();
                return true;
            }
            return false;
        });
        // A disabled region being split/merged away must drop out of
        // disabledRegions too — otherwise its (now-stale) WorldRegion object
        // keeps getting entity-ticked on main by NestworldRegionSystem forever,
        // even after doSplit/doMerge handed its entity UUIDs to a new region
        // with its own live thread, double-ticking those entities.
        disabledRegions.remove(region.getId());
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
     * Like runWorkRound, but silently drops a region's list when its thread
     * still carries unfinished work from a previous round. For work that is
     * RE-COLLECTED every tick (block entities): queueing it again behind a
     * saturated region's backlog would grow the carry-over queue without
     * bound and later burst-run stale duplicate ticks; dropping keeps the
     * vanilla "tick it if you can this tick" semantics.
     */
    public void runWorkRoundDropIfBacklogged(java.util.Map<WorldRegion, java.util.List<Runnable>> assignments) {
        if (assignments.isEmpty()) return;
        for (RegionThread t : threads) {
            if (t.getLastWorkDeferred() > 0) assignments.remove(t.region);
        }
        runWorkRound(assignments);
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

    // Crash isolation: a region whose thread keeps dying must not respawn-loop
    // forever (spamming crashes, never ticking). After MAX_CRASHES within
    // CRASH_WINDOW_MS we stop respawning it and disable the region — it no
    // longer gets a dedicated RegionThread, but NestworldRegionSystem picks its
    // entities up on the main thread every tick instead (same fallback as a
    // pinned entity type), so it degrades to unparallelized-but-alive rather
    // than fully frozen. Its blocks/scheduled ticks/block-entities still freeze
    // (their work-round dispatch is keyed off an alive RegionThread — a
    // separate follow-up). Touched only from the main thread
    // (respawnCrashedThreads runs once per tick), so a plain map is fine.
    private static final int MAX_CRASHES =
            Integer.getInteger("nestworld.maxRegionCrashes", 5);
    private static final long CRASH_WINDOW_MS = 300_000L; // 5 min
    private final java.util.Map<Integer, java.util.Deque<Long>> crashTimes =
            new java.util.HashMap<>();
    private final java.util.Map<Integer, WorldRegion> disabledRegions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Region ids that crashed too often and no longer have a RegionThread (for status). */
    public java.util.Set<Integer> getDisabledRegions() { return disabledRegions.keySet(); }

    /** Regions that crashed too often and no longer have a RegionThread — their owned
     *  entities are ticked on the main thread instead (see NestworldRegionSystem). */
    public java.util.Collection<WorldRegion> getDisabledRegionObjects() { return disabledRegions.values(); }

    private void respawnCrashedThreads() {
        for (RegionThread t : threads) {
            if (t.isAlive()) continue;
            WorldRegion region = t.region;
            int id = region.getId();
            threads.remove(t);

            long now = System.currentTimeMillis();
            java.util.Deque<Long> times = crashTimes.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > CRASH_WINDOW_MS) times.removeFirst();
            int recent = times.size();

            if (recent > MAX_CRASHES) {
                if (disabledRegions.put(id, region) == null) {
                    LOGGER.error("Region {} crashed {} times in {} min — DISABLING its RegionThread. "
                            + "Its entities now tick on the main thread (unparallelized, like a pinned "
                            + "type); its blocks/scheduled ticks/block-entities will not tick until a "
                            + "restart or you /nestworld merge it into a neighbour. The rest of the "
                            + "server keeps running. (Enable -Dnestworld.autoPin=true to pin the "
                            + "offending entity type instead.)", id, recent, CRASH_WINDOW_MS / 60_000);
                }
                continue;
            }

            // Exponential backoff: 5s, 10s, 20s, 40s, capped at 60s.
            long delayMs = Math.min(5_000L * (1L << (recent - 1)), 60_000L);
            LOGGER.warn("Region {} thread died (crash {}/{} in window) — respawning in {} s",
                    id, recent, MAX_CRASHES, delayMs / 1000);
            Thread respawner = new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { return; }
                if (!disabledRegions.containsKey(id)) {
                    spawn(region);
                    LOGGER.info("Respawned RegionThread for region {}", id);
                }
            }, "NestWorld-Respawner-" + id);
            respawner.setDaemon(true);
            respawner.start();
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public List<RegionThread> getThreads() { return threads; }
    public int getRegionCount() { return threads.size(); }
    public ServerLevel getLevel() { return level; }
}
