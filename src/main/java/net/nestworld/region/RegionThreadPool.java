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

    // NestWorld: breaks the regionPool timing-log bucket (NestworldRegionSystem's
    // "Tick phases avg ms" line) down into dispatch (the sequential
    // synchronized+notifyAll wakeup loop) vs wait (awaitLatch — time actually spent
    // waiting for the slowest region thread, including OS scheduling latency once
    // woken). Added to test the hypothesis in the Folia-barrier investigation notes:
    // that OS thread-scheduling contention (when non-empty region count exceeds
    // available cores), not the dispatch mechanism itself, dominates regionPool's
    // ~15-25ms/tick baseline cost. Same on/off property as the existing phase-timing
    // log for consistency; diagnostic only, no behavioural change.
    private static final boolean TIMING_LOG =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.timingLog", "true"));
    private static final int TIMING_LOG_INTERVAL = 200;
    private long nestworldDispatchNanos = 0;
    private long nestworldWaitNanos = 0;
    private int nestworldWorkerCountAccum = 0;
    private int nestworldTimedTicks = 0;

    // P0 RegionThreadPool redesign, Phase 1 (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md): baseline
    // telemetry BEFORE any behavior change. One PhaseStats per RegionPhase, indexed by ordinal
    // (avoids a map lookup on this hot path). Diagnostic-only: no phase currently gets its own
    // wait budget yet (that's Phase 2) -- all 5 still share TICK_WAIT_LIMIT_NANOS for now.
    private final PhaseStats[] nestworldPhaseStats = initPhaseStats();
    private static PhaseStats[] initPhaseStats() {
        RegionPhase[] phases = RegionPhase.values();
        PhaseStats[] arr = new PhaseStats[phases.length];
        for (int i = 0; i < arr.length; i++) arr[i] = new PhaseStats();
        return arr;
    }

    /** Per-RegionPhase accumulators. Only ever touched from the main thread (all call sites into
     *  awaitLatch() are main-thread-only), so plain longs/ints are fine -- no synchronization. */
    static final class PhaseStats {
        long dispatchNanos = 0;          // time spent in the wakeup loop (requestTick/requestWork)
        long waitNanos = 0;              // total awaitLatch() wall time (park + pollTask combined)
        long pollTaskNanos = 0;          // the SUBSET of waitNanos actually spent inside pollTask()
        long regionTickNanosSum = 0;     // sum of every dispatched worker's own getLastDispatchNanos()
        long regionTickNanosMax = 0;     // slowest single region's dispatch duration seen
        long workerCountAccum = 0;
        long timeoutCount = 0;
        long timeoutOverrunNanosSum = 0; // how far past the budget the abandoned wait had run
        long regionsPendingAtTimeoutSum = 0;
        int rounds = 0;                  // how many awaitLatch() calls this phase has seen

        // P0.3 ТЗ section 6: p95 for region_tick and poll, not just avg/max. Bounded ring buffer
        // (last SAMPLE_RING_SIZE rounds), same pattern as vanilla's MinecraftServer.tickTimes --
        // O(1) per round to record, one sort of a small fixed-size array to read.
        static final int SAMPLE_RING_SIZE = 200;
        final long[] regionTickMaxSamples = new long[SAMPLE_RING_SIZE];
        final long[] pollNanosSamples = new long[SAMPLE_RING_SIZE];
        int sampleIdx = 0;
        int sampleCount = 0;

        void recordSample(long regionTickMaxThisRound, long pollNanosThisRound) {
            regionTickMaxSamples[sampleIdx] = regionTickMaxThisRound;
            pollNanosSamples[sampleIdx] = pollNanosThisRound;
            sampleIdx = (sampleIdx + 1) % SAMPLE_RING_SIZE;
            if (sampleCount < SAMPLE_RING_SIZE) sampleCount++;
        }

        static double p95(long[] samples, int count) {
            if (count == 0) return 0.0;
            long[] copy = java.util.Arrays.copyOf(samples, count);
            java.util.Arrays.sort(copy);
            int idx = Math.min(count - 1, (int) Math.ceil(count * 0.95) - 1);
            return copy[Math.max(0, idx)] / 1e6;
        }
    }

    /** Read-only snapshot for /nestworld regionpool -- see NestworldCommand. */
    public String nestworldPhaseTelemetry() {
        StringBuilder sb = new StringBuilder();
        for (RegionPhase phase : RegionPhase.values()) {
            PhaseStats s = nestworldPhaseStats[phase.ordinal()];
            if (s.rounds == 0) {
                sb.append(String.format("  %s: no rounds yet%n", phase));
                continue;
            }
            sb.append(String.format(
                    "  %s: rounds=%,d avgWorkers=%.1f dispatch_avg=%.3fms wait_avg=%.3fms poll_avg=%.3fms poll_p95=%.3fms "
                            + "region_tick_avg=%.3fms region_tick_p95=%.3fms region_tick_max=%.3fms timeouts=%,d avg_pending_at_timeout=%.1f%n",
                    phase, s.rounds,
                    (double) s.workerCountAccum / s.rounds,
                    s.dispatchNanos / 1e6 / s.rounds,
                    s.waitNanos / 1e6 / s.rounds,
                    s.pollTaskNanos / 1e6 / s.rounds,
                    PhaseStats.p95(s.pollNanosSamples, s.sampleCount),
                    s.workerCountAccum == 0 ? 0.0 : s.regionTickNanosSum / 1e6 / s.workerCountAccum,
                    PhaseStats.p95(s.regionTickMaxSamples, s.sampleCount),
                    s.regionTickNanosMax / 1e6,
                    s.timeoutCount,
                    s.timeoutCount == 0 ? 0.0 : (double) s.regionsPendingAtTimeoutSum / s.timeoutCount));
        }
        return sb.toString();
    }

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
        // Step 3 (docs/LOCAL_TICK_STAGE4.md): a free-running region ticks itself on its
        // own pace (RegionThread.runFreeRunningTick()) — never dispatched or waited on
        // here. No-op unless NestworldTuning.FREE_RUNNING_REGIONS_ENABLED.
        java.util.List<RegionThread> workers = new java.util.ArrayList<>();
        for (RegionThread t : threads) {
            if (NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && t.region.isFreeRunning()) continue;
            if (!t.region.getOwnedEntityIds().isEmpty() || t.getLastWorkDeferred() > 0) workers.add(t);
        }
        if (workers.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(workers.size());

        long nestworldT0 = TIMING_LOG ? System.nanoTime() : 0L;
        // Signal the working threads to start their tick
        for (RegionThread t : workers) t.requestTick(latch);
        long nestworldT1 = TIMING_LOG ? System.nanoTime() : 0L;

        awaitLatch(latch, RegionPhase.ENTITY, workers, nestworldT1 - nestworldT0);

        if (TIMING_LOG) {
            long nestworldT2 = System.nanoTime();
            nestworldDispatchNanos += nestworldT1 - nestworldT0;
            nestworldWaitNanos += nestworldT2 - nestworldT1;
            nestworldWorkerCountAccum += workers.size();
            if (++nestworldTimedTicks >= TIMING_LOG_INTERVAL) {
                LOGGER.info("regionPool breakdown avg ms over {} ticks: dispatch={} wait={} avgWorkers={}",
                        nestworldTimedTicks,
                        String.format("%.3f", nestworldDispatchNanos / 1e6 / nestworldTimedTicks),
                        String.format("%.3f", nestworldWaitNanos / 1e6 / nestworldTimedTicks),
                        String.format("%.1f", (double) nestworldWorkerCountAccum / nestworldTimedTicks));
                nestworldDispatchNanos = 0;
                nestworldWaitNanos = 0;
                nestworldWorkerCountAccum = 0;
                nestworldTimedTicks = 0;
            }
        }
    }

    /**
     * Runs each region's work list (e.g. its bucket of due scheduled block
     * ticks) on that region's thread, in parallel, and blocks until all are
     * done. Called from the main thread mid-tick (inside ServerLevel.tick).
     */
    public void runWorkRound(java.util.Map<WorldRegion, java.util.List<Runnable>> assignments, RegionPhase phase) {
        if (assignments.isEmpty()) return;

        java.util.List<RegionThread> workers = new java.util.ArrayList<>();
        for (RegionThread t : threads) {
            java.util.List<Runnable> work = assignments.get(t.region);
            if (work == null || work.isEmpty()) continue;
            // Step 3 (docs/LOCAL_TICK_STAGE4.md, design point 2): a free-running region
            // never blocks on this latch-dispatch — its own loop drains this bucket
            // non-blockingly at the start of each of its own local ticks. NOT waited on.
            if (NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && t.region.isFreeRunning()) {
                t.region.nestworldPostFreeRunningWork(work);
                continue;
            }
            workers.add(t);
        }
        if (workers.isEmpty()) return;

        long nestworldT0 = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(workers.size());
        for (RegionThread t : workers) t.requestWork(assignments.get(t.region), latch);
        long nestworldDispatchNanosLocal = System.nanoTime() - nestworldT0;

        awaitLatch(latch, phase, workers, nestworldDispatchNanosLocal);
    }

    /**
     * Like runWorkRound, but silently drops a region's list when its thread
     * still carries unfinished work from a previous round. For work that is
     * RE-COLLECTED every tick (block entities): queueing it again behind a
     * saturated region's backlog would grow the carry-over queue without
     * bound and later burst-run stale duplicate ticks; dropping keeps the
     * vanilla "tick it if you can this tick" semantics.
     */
    public void runWorkRoundDropIfBacklogged(java.util.Map<WorldRegion, java.util.List<Runnable>> assignments, RegionPhase phase) {
        if (assignments.isEmpty()) return;
        for (RegionThread t : threads) {
            if (t.getLastWorkDeferred() > 0) assignments.remove(t.region);
        }
        runWorkRound(assignments, phase);
    }

    /**
     * Waits for a round to finish while servicing the chunk task queue —
     * this is what lets region threads safely call getChunk().join().
     *
     * <p>P0 RegionThreadPool redesign, Phase 1: now records per-{@link RegionPhase} telemetry --
     * total wait wall-time, the SUBSET of that spent actually doing pollTask() work (as opposed to
     * parked/idle), and each dispatched worker's own {@link RegionThread#getLastDispatchNanos()}
     * once the round completes (or, on timeout, whatever workers HAD finished by then). Diagnostic
     * only -- behavior (the shared TICK_WAIT_LIMIT_NANOS, the busy-wait mechanism itself) is
     * unchanged; that's Phase 2/3.
     */
    private void awaitLatch(CountDownLatch latch, RegionPhase phase, java.util.List<RegionThread> workers,
                             long dispatchNanos) {
        PhaseStats stats = nestworldPhaseStats[phase.ordinal()];
        long start = System.nanoTime();
        long pollTaskNanos = 0;
        boolean timedOut = false;
        while (latch.getCount() > 0) {
            long pollStart = System.nanoTime();
            boolean didWork = level.getChunkSource().pollTask();
            pollTaskNanos += System.nanoTime() - pollStart;
            if (!didWork) {
                LockSupport.parkNanos(50_000); // 50 µs
            }
            if (System.nanoTime() - start > TICK_WAIT_LIMIT_NANOS) {
                LOGGER.error("Region round exceeded {} s — abandoning wait ({} threads still running, phase={})",
                        TimeUnit.NANOSECONDS.toSeconds(TICK_WAIT_LIMIT_NANOS), latch.getCount(), phase);
                timedOut = true;
                break;
            }
        }
        long waitNanos = System.nanoTime() - start;

        stats.rounds++;
        stats.dispatchNanos += dispatchNanos;
        stats.waitNanos += waitNanos;
        stats.pollTaskNanos += pollTaskNanos;
        stats.workerCountAccum += workers.size();
        long roundMax = 0;
        for (RegionThread t : workers) {
            long d = t.getLastDispatchNanos();
            stats.regionTickNanosSum += d;
            if (d > stats.regionTickNanosMax) stats.regionTickNanosMax = d;
            if (d > roundMax) roundMax = d;
        }
        if (timedOut) {
            stats.timeoutCount++;
            stats.timeoutOverrunNanosSum += waitNanos - TICK_WAIT_LIMIT_NANOS;
            stats.regionsPendingAtTimeoutSum += latch.getCount();
        }
        stats.recordSample(roundMax, pollTaskNanos);

        // P0.1 per-tick correlation (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md follow-up): a
        // tick-scoped accumulator reset by NestworldRegionSystem at the top of tickAllRegions()
        // and drained at the bottom, so the correlation view reflects THIS tick's 5 rounds only,
        // separate from PhaseStats' whole-session cumulative numbers used by /nestworld regionpool.
        nestworldTickDispatchNanos += dispatchNanos;
        nestworldTickWaitNanos += waitNanos;
        nestworldTickPollNanos += pollTaskNanos;
        nestworldTickRegionMaxNanos += roundMax;
    }

    private long nestworldTickDispatchNanos = 0;
    private long nestworldTickWaitNanos = 0;
    private long nestworldTickPollNanos = 0;
    private long nestworldTickRegionMaxNanos = 0;

    /** Call once at the very start of a game tick, before any awaitLatch() round runs. */
    public void nestworldResetTickAccumulators() {
        nestworldTickDispatchNanos = 0;
        nestworldTickWaitNanos = 0;
        nestworldTickPollNanos = 0;
        nestworldTickRegionMaxNanos = 0;
    }

    /** {dispatchNanos, waitNanos, pollTaskNanos, regionMaxNanosSum} accumulated since the last reset. */
    public long[] nestworldDrainTickAccumulators() {
        return new long[] { nestworldTickDispatchNanos, nestworldTickWaitNanos,
                nestworldTickPollNanos, nestworldTickRegionMaxNanos };
    }

    // -----------------------------------------------------------------------
    // Crash recovery
    // -----------------------------------------------------------------------

    // Crash isolation: a region whose thread keeps dying must not respawn-loop
    // forever (spamming crashes, never ticking). After MAX_CRASHES within
    // CRASH_WINDOW_MS we stop respawning it and disable the region — its
    // entities/blocks freeze, but the rest of the server keeps running instead
    // of being dragged into an endless crash cycle. Touched only from the main
    // thread (respawnCrashedThreads runs once per tick), so plain maps are fine.
    private static final int MAX_CRASHES =
            Integer.getInteger("nestworld.maxRegionCrashes", 5);
    private static final long CRASH_WINDOW_MS = 300_000L; // 5 min
    private final java.util.Map<Integer, java.util.Deque<Long>> crashTimes =
            new java.util.HashMap<>();
    private final java.util.Set<Integer> disabledRegions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Region ids that crashed too often and are no longer ticked (for status). */
    public java.util.Set<Integer> getDisabledRegions() { return disabledRegions; }

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
                if (disabledRegions.add(id)) {
                    LOGGER.error("Region {} crashed {} times in {} min — DISABLING it. Its "
                            + "entities/blocks will not tick until a restart or you "
                            + "/nestworld merge it into a neighbour. The rest of the server keeps "
                            + "running. (Enable -Dnestworld.autoPin=true to pin the offending "
                            + "entity type instead.)", id, recent, CRASH_WINDOW_MS / 60_000);
                }
                continue;
            }

            // Exponential backoff: 5s, 10s, 20s, 40s, capped at 60s.
            long delayMs = Math.min(5_000L * (1L << (recent - 1)), 60_000L);
            LOGGER.warn("Region {} thread died (crash {}/{} in window) — respawning in {} s",
                    id, recent, MAX_CRASHES, delayMs / 1000);
            Thread respawner = new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { return; }
                if (!disabledRegions.contains(id)) {
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
