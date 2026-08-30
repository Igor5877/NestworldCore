package net.nestworld.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structured split/merge event log (2026-08-28) -- the user's explicit follow-up to a paired
 * n=149/n=150 scaling test that showed n=150's region count oscillating 18-&gt;22-&gt;26-&gt;22-&gt;19-&gt;20-
 * &gt;21-&gt;20-&gt;19-&gt;20 (an 8-region swing) versus n=149's calm, mostly-monotonic 12-&gt;17 growth
 * that settled onto a plateau -- suggestive of split/merge THRASHING rather than a simple
 * "150 players is harder" load story. See {@code kfirstslots-offload-win-but-n150-crashes.md}
 * project memory for the full incident history this instrumentation exists to investigate.
 *
 * <p>Records every actual {@link RegionSplitManager#doSplit} / {@link
 * RegionSplitManager#doMerge} attempt (both automatic, from {@code onTick()}'s heuristic, and
 * manual, from the {@code /nestworld split}/{@code merge} admin commands) with: timestamp,
 * region id(s) involved, the reason the automatic heuristic fired (or "manual"), region size
 * (entity count as a load proxy -- chunk-area splitting is entity-median-based, not
 * fixed-size), the region's average tick cost at decision time, how long the whole operation
 * took (lock acquisition + tree mutation + entity reassignment, not further decomposed), whether
 * the lock guard timed out, and whether the operation actually succeeded.
 *
 * <p>Also tracks THRASH detection: a region created via a split that gets merged again within a
 * short window is flagged as rapid split-then-merge -- the specific pattern the user asked to
 * measure ("скільки split→merge відбулося менш ніж за 1-2 секунди").
 */
public final class SplitMergeEventLog {
    private SplitMergeEventLog() {}

    public enum Op { SPLIT, MERGE }

    public record Event(
            long timestampMillis, Op op, String description, String reason,
            double avgTickMs, int entityCount, long operationNanos, boolean lockTimedOut,
            boolean success, double rapidThrashSeconds // -1 if not a rapid split-then-merge
    ) {}

    private static final int CAPACITY = 4096;
    private static final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger eventCount = new AtomicInteger();

    // Birth-time tracking (by region id) for rapid-thrash detection -- a region created via a
    // successful split that gets merged again within RAPID_THRASH_THRESHOLD_NANOS of its own
    // creation is flagged. Cleared on reset(); naturally bounded since region ids are recycled
    // only up to whatever the tree currently has active (no unbounded growth in practice).
    private static final ConcurrentHashMap<Integer, Long> regionBirthNanos = new ConcurrentHashMap<>();
    private static final long RAPID_THRASH_THRESHOLD_NANOS = 2_000_000_000L; // 2s, per the user's ask

    private static final AtomicInteger splitCount = new AtomicInteger();
    private static final AtomicInteger mergeCount = new AtomicInteger();
    private static final AtomicInteger splitFailCount = new AtomicInteger();
    private static final AtomicInteger mergeFailCount = new AtomicInteger();
    private static final AtomicInteger lockTimeoutCount = new AtomicInteger();
    private static final AtomicInteger rapidThrashCount = new AtomicInteger();

    // NestWorld: 2026-08-28 O(n²) merge-candidate fix instrumentation (ТЗ "Stabilization of
    // RegionSplitManager") -- candidatesGenerated is the sum of mergeCandidates.size() observed
    // across all applyPending() cycles; rejectedBeforeLock counts candidates whose tree-sibling
    // lookup failed (stale, root, or sibling not itself a candidate) -- these NEVER touch a
    // chunkLock, unlike the old O(n²) code's doomed pair attempts. Expected post-fix:
    // rejectedBeforeLock should still occur often (most candidates simply don't have a
    // ready sibling yet), but mergeFailCount (a real lock-acquisition attempt that failed) should
    // now only fire for genuinely-stale-between-decision-and-lock cases, not blind pairing noise.
    private static final AtomicLong candidatesGeneratedTotal = new AtomicLong();
    private static final AtomicLong rejectedBeforeLockCount = new AtomicLong();

    public static void recordCandidateGeneration(int candidateCount) {
        candidatesGeneratedTotal.addAndGet(candidateCount);
    }

    public static void recordRejectedBeforeLock() {
        rejectedBeforeLockCount.incrementAndGet();
    }

    // NestWorld: 2026-08-28 region-count telemetry (ТЗ "Stabilization of RegionSplitManager"
    // §13) -- sampled once per RegionSplitManager.onTick() evaluation (already gated to ~1/real
    // second by EVAL_INTERVAL_NANOS, so this is cheap and not a hot-path concern). Gives real
    // min/p50/p95/p99/max region-count distribution over a run, not just an eyeballed start/end
    // snapshot -- needed to quantify oscillation amplitude (the 18->22->26->22->19->... pattern)
    // objectively instead of just eyeballing a printed time series.
    private static final int REGION_COUNT_RING_CAPACITY = 4096;
    private static final int[] regionCountRing = new int[REGION_COUNT_RING_CAPACITY];
    private static final AtomicInteger regionCountIdx = new AtomicInteger();
    private static final AtomicInteger regionCountFill = new AtomicInteger();

    public static void recordRegionCount(int count) {
        int idx = regionCountIdx.getAndUpdate(i -> (i + 1) % REGION_COUNT_RING_CAPACITY);
        regionCountRing[idx] = count;
        regionCountFill.updateAndGet(f -> Math.min(f + 1, REGION_COUNT_RING_CAPACITY));
    }

    /** Region-count percentile summary + a simple oscillation-amplitude proxy (max-min over the
     *  window) -- objective numbers for what used to be an eyeballed time-series printout. */
    public static String regionCountSummary() {
        int n = Math.min(regionCountFill.get(), regionCountRing.length);
        if (n == 0) return "regionCount[n=0] (no samples yet)";
        int[] copy = java.util.Arrays.copyOf(regionCountRing, n);
        java.util.Arrays.sort(copy);
        int min = copy[0], max = copy[n - 1];
        int p50 = copy[n / 2];
        int p95 = copy[Math.min(n - 1, (int) (n * 0.95))];
        int p99 = copy[Math.min(n - 1, (int) (n * 0.99))];
        double mean = 0;
        for (int c : copy) mean += c;
        mean /= n;
        double variance = 0;
        for (int c : copy) variance += (c - mean) * (c - mean);
        double stddev = Math.sqrt(variance / n);
        return String.format("regionCount[n=%d] min=%d p50=%d p95=%d p99=%d max=%d mean=%.1f stddev=%.2f oscillationRange=%d",
                n, min, p50, p95, p99, max, mean, stddev, max - min);
    }

    private static final AtomicLong firstEventNanos = new AtomicLong(0L);
    private static final AtomicLong lastEventNanos = new AtomicLong(0L);

    private static void record(Event e) {
        events.offer(e);
        if (eventCount.incrementAndGet() > CAPACITY) {
            events.poll();
            eventCount.decrementAndGet();
        }
        long now = System.nanoTime();
        firstEventNanos.compareAndSet(0L, now);
        lastEventNanos.set(now);
    }

    public static void recordSplit(int parentId, int[] childIds, String reason, double avgTickMs,
            int entityCount, long operationNanos, boolean lockTimedOut, boolean success) {
        splitCount.incrementAndGet();
        if (!success) splitFailCount.incrementAndGet();
        if (lockTimedOut) lockTimeoutCount.incrementAndGet();
        String desc = success
                ? String.format("split R%d -> [R%d, R%d]", parentId, childIds[0], childIds[1])
                : String.format("split R%d FAILED", parentId);
        record(new Event(System.currentTimeMillis(), Op.SPLIT, desc, reason, avgTickMs, entityCount,
                operationNanos, lockTimedOut, success, -1));
        if (success) {
            long now = System.nanoTime();
            regionBirthNanos.put(childIds[0], now);
            regionBirthNanos.put(childIds[1], now);
        }
    }

    public static void recordMerge(int aId, int bId, int resultId, String reason,
            double avgTickMsA, double avgTickMsB, int entityCount, long operationNanos,
            boolean lockTimedOut, boolean success) {
        mergeCount.incrementAndGet();
        if (!success) mergeFailCount.incrementAndGet();
        if (lockTimedOut) lockTimeoutCount.incrementAndGet();
        long now = System.nanoTime();
        double rapidSeconds = -1;
        for (int id : new int[] {aId, bId}) {
            Long birth = regionBirthNanos.get(id);
            if (birth != null) {
                double ageSeconds = (now - birth) / 1_000_000_000.0;
                if ((now - birth) < RAPID_THRASH_THRESHOLD_NANOS && (rapidSeconds < 0 || ageSeconds < rapidSeconds)) {
                    rapidSeconds = ageSeconds;
                }
            }
        }
        if (rapidSeconds >= 0) rapidThrashCount.incrementAndGet();
        String desc = success
                ? String.format("merge [R%d, R%d] -> R%d", aId, bId, resultId)
                : String.format("merge [R%d, R%d] FAILED", aId, bId);
        record(new Event(System.currentTimeMillis(), Op.MERGE, desc, reason,
                (avgTickMsA + avgTickMsB) / 2.0, entityCount, operationNanos, lockTimedOut, success, rapidSeconds));
        if (success) {
            regionBirthNanos.put(resultId, now);
        }
    }

    public static List<Event> snapshot() {
        return new ArrayList<>(events);
    }

    public static void reset() {
        events.clear();
        eventCount.set(0);
        regionBirthNanos.clear();
        splitCount.set(0);
        mergeCount.set(0);
        splitFailCount.set(0);
        mergeFailCount.set(0);
        lockTimeoutCount.set(0);
        rapidThrashCount.set(0);
        candidatesGeneratedTotal.set(0);
        rejectedBeforeLockCount.set(0);
        regionCountIdx.set(0);
        regionCountFill.set(0);
        firstEventNanos.set(0L);
        lastEventNanos.set(0L);
    }

    /** Topology churn summary -- the aggregate metrics the user asked for (splits/min,
     *  merges/min, rapid split-&gt;merge count, region-count peak/stddev computed separately
     *  from the region/entity time-series sampler already used by the test harness), plus the
     *  2026-08-28 O(n²)-fix candidate-generation counters -- see their field javadoc above.
     *  Post-fix expectation: {@code lockAttempted} (= mergeCount, real doMerge calls) should be
     *  roughly O(candidatesGenerated), not O(candidatesGenerated²). */
    public static String summary() {
        long spanNanos = lastEventNanos.get() - firstEventNanos.get();
        double spanMinutes = spanNanos > 0 ? spanNanos / 60_000_000_000.0 : 0;
        double splitsPerMin = spanMinutes > 0 ? splitCount.get() / spanMinutes : 0;
        double mergesPerMin = spanMinutes > 0 ? mergeCount.get() / spanMinutes : 0;
        return String.format(
                "splits=%d (failed=%d) merges=%d (failed=%d) lockTimeouts=%d rapidSplitThenMerge(<2s)=%d "
                        + "splitsPerMin=%.1f mergesPerMin=%.1f spanMin=%.2f eventLogSize=%d | "
                        + "mergeCandidatesGenerated=%d mergeCandidatesRejectedBeforeLock=%d "
                        + "mergeCandidatesLockAttempted=%d mergeCandidatesSucceeded=%d mergeCandidatesFailedAfterLock=%d",
                splitCount.get(), splitFailCount.get(), mergeCount.get(), mergeFailCount.get(),
                lockTimeoutCount.get(), rapidThrashCount.get(), splitsPerMin, mergesPerMin, spanMinutes,
                eventCount.get(), candidatesGeneratedTotal.get(), rejectedBeforeLockCount.get(),
                mergeCount.get(), mergeCount.get() - mergeFailCount.get(), mergeFailCount.get());
    }

    /** Full event dump, newest last -- for RCON inspection of the actual split/merge sequence
     *  (the "tick 100: SPLIT R4 / tick 101: MERGE R4a/R4b" pattern the user described). */
    public static String dump(int maxLines) {
        List<Event> snap = snapshot();
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, snap.size() - maxLines);
        for (int i = start; i < snap.size(); i++) {
            Event e = snap.get(i);
            sb.append(String.format("[%tT.%<tL] %-6s %-40s reason=%-24s avgTickMs=%.1f entities=%d opMs=%.2f%s%s%s%n",
                    e.timestampMillis(), e.op(), e.description(), e.reason(), e.avgTickMs(), e.entityCount(),
                    e.operationNanos() / 1_000_000.0,
                    e.lockTimedOut() ? " LOCK-TIMEOUT" : "",
                    e.success() ? "" : " FAILED",
                    e.rapidThrashSeconds() >= 0 ? String.format(" RAPID-THRASH(%.2fs)", e.rapidThrashSeconds()) : ""));
        }
        return sb.toString();
    }
}
