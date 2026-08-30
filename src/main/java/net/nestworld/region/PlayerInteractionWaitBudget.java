package net.nestworld.region;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * K-first-slots wait gate for the four synchronous "sync request-response" dispatchers ({@link
 * PlayerInteractionDispatcher}, {@link PlayerUseItemDispatcher}, {@link
 * PlayerUseItemOnBlockDispatcher}, {@link EntityInteractionDispatcher}) -- see {@link
 * NestworldTuning#MAX_INTERACTION_WAIT_PER_TICK_NANOS}'s javadoc for the ORIGINAL incident (an
 * unbounded N-way cumulative main-thread stall crashing the watchdog at 60-80 concurrent
 * players) and {@link NestworldTuning#INTERACTION_MAX_WAIT_SLOTS_PER_TICK}'s javadoc for why
 * that first fix (a tiny shared nanos budget) was replaced by THIS one: it collapsed region
 * offload under load, since a 2ms shared pool can't survive the ~25-50ms natural free-running
 * region round-trip this session measured.
 *
 * <p>Only the FIRST {@code K} ({@link NestworldTuning#INTERACTION_MAX_WAIT_SLOTS_PER_TICK})
 * dispatch calls observed in a given main tick get a real bounded wait (up to {@code X} = {@link
 * NestworldTuning#INTERACTION_MAX_WAIT_PER_CALL_NANOS} each); every call after that in the SAME
 * tick skips the wait entirely (instant fallback, same as budget-exhausted under the old model).
 * Worst-case main-thread stall per tick is therefore a CONSTANT {@code K * X}, independent of
 * how many total players dispatch that tick -- unlike the old model, where the effective
 * per-call share shrank toward zero (and offload collapsed) as N grew.
 *
 * <p>All access to {@link #reserve} happens from the main thread only (every caller is a packet
 * handler running under {@code PacketUtils.ensureRunningOnSameThread}), so the slot counter
 * itself needs no synchronization -- a plain {@code int}, not an {@code AtomicInteger}, is
 * correct and cheaper. The metric counters ARE read from a different thread (an RCON command
 * handler), so those use {@link AtomicLong}/{@link AtomicInteger}.
 */
public final class PlayerInteractionWaitBudget {
    private PlayerInteractionWaitBudget() {}

    private static int slotsRemainingThisTick = 0;
    private static long lastResetTickCount = -1L;

    // NestWorld: per-tick call-count distribution (2026-08-28 latency-measurement request) --
    // how many of the four dispatchers' reserve() calls landed in the SAME main tick, i.e. the
    // real concurrency level a K-first-slots design would actually see. Pushed into the ring at
    // the moment reserve() observes the NEXT tick (so a given tick's count is finalized once).
    private static int callsThisTick = 0;
    private static final int TICK_CALL_RING_CAPACITY = 4096;
    private static final int[] callsPerTickRing = new int[TICK_CALL_RING_CAPACITY];
    private static final AtomicInteger callsPerTickIdx = new AtomicInteger();
    private static final AtomicInteger callsPerTickFill = new AtomicInteger();

    private static final AtomicLong totalWaitNanos = new AtomicLong();
    private static final AtomicLong maxWaitNanos = new AtomicLong();
    private static final AtomicLong waitCount = new AtomicLong();
    private static final AtomicLong budgetExhaustedFallbackCount = new AtomicLong();
    private static final AtomicLong timeoutFallbackCount = new AtomicLong();

    // Real post-to-drain round-trip latency (RegionMessage.ageNanos() at the moment the region
    // thread actually applies it) -- UNBOUNDED, independent of the wait budget above: this is
    // what the budget SHOULD be sized against. A message still gets this recorded even if the
    // dispatching call gave up early (budget exhausted / timed out) and fell back to main --
    // the message stays in the mailbox and is still drained eventually, just ignored via the
    // claimed CAS once the main-thread fallback already ran it.
    private static final AtomicLong totalApplyLatencyNanos = new AtomicLong();
    private static final AtomicLong maxApplyLatencyNanos = new AtomicLong();
    private static final AtomicLong applyLatencyCount = new AtomicLong();

    // NestWorld: real percentile distributions (2026-08-28 latency-measurement request) -- the
    // avg/max counters above hide the shape of the distribution the user explicitly asked to see
    // before choosing maxPerCall/K. Lock-free fixed-size ring buffers of raw nanos samples;
    // percentiles computed on demand (copy + sort) only when a summary is requested, never on the
    // hot path. 65536 samples is far more than one 45s test tier produces (hundreds-low
    // thousands of interactions), so effectively no data loss within a single measurement run.
    private static final int RING_CAPACITY = 1 << 16;
    private static final long[] applyLatencySameRegionRing = new long[RING_CAPACITY];
    private static final AtomicInteger applyLatencySameRegionIdx = new AtomicInteger();
    private static final AtomicInteger applyLatencySameRegionFill = new AtomicInteger();
    private static final long[] applyLatencyCrossRegionRing = new long[RING_CAPACITY];
    private static final AtomicInteger applyLatencyCrossRegionIdx = new AtomicInteger();
    private static final AtomicInteger applyLatencyCrossRegionFill = new AtomicInteger();
    private static final long[] waitLatencyRing = new long[RING_CAPACITY];
    private static final AtomicInteger waitLatencyIdx = new AtomicInteger();
    private static final AtomicInteger waitLatencyFill = new AtomicInteger();

    /**
     * K-first-slots gate: lazily resets the slot counter to {@code K} the first time a new
     * {@code tickCount} is observed (avoids needing a separate main tick-loop hook -- every
     * caller already has the current tick count on hand for its {@code RegionMessage} anyway).
     * Returns the ACTUAL nanos the caller is allowed to wait -- 0 once all {@code K} slots for
     * this tick are taken, in which case the caller MUST NOT call {@code latch.await(...)} at
     * all, treating it exactly like an immediate timeout (same fallback path, just with zero
     * wall-clock cost instead of up to {@code PLAYER_INTERACTION_TIMEOUT_MS}).
     */
    public static long reserve(long requestedNanos, long currentTickCount) {
        if (currentTickCount != lastResetTickCount) {
            if (lastResetTickCount >= 0) {
                recordCallsPerTick(callsThisTick);
            }
            slotsRemainingThisTick = NestworldTuning.INTERACTION_MAX_WAIT_SLOTS_PER_TICK;
            lastResetTickCount = currentTickCount;
            callsThisTick = 0;
        }
        callsThisTick++;
        if (slotsRemainingThisTick <= 0) {
            return 0L;
        }
        slotsRemainingThisTick--;
        return Math.min(requestedNanos, NestworldTuning.INTERACTION_MAX_WAIT_PER_CALL_NANOS);
    }

    private static void recordCallsPerTick(int n) {
        int idx = callsPerTickIdx.getAndUpdate(i -> (i + 1) % TICK_CALL_RING_CAPACITY);
        callsPerTickRing[idx] = n;
        callsPerTickFill.updateAndGet(f -> Math.min(f + 1, TICK_CALL_RING_CAPACITY));
    }

    private static void ringRecord(long[] ring, AtomicInteger idxCounter, AtomicInteger fillCounter, long value) {
        int idx = idxCounter.getAndUpdate(i -> (i + 1) % RING_CAPACITY);
        ring[idx] = value;
        fillCounter.updateAndGet(f -> Math.min(f + 1, RING_CAPACITY));
    }

    /** Called after an actually-attempted wait (granted &gt; 0), regardless of whether it
     *  completed or timed out within the granted window. */
    public static void recordWait(long actualWaitNanos) {
        recordWait(actualWaitNanos, -1L, -1L);
    }

    // 2026-08-30, per the user's explicit direction after a real n=125 ftbchunks-repro watchdog
    // crash (main thread frozen 60s inside this exact latch.await() call, despite K=1/X=50ms
    // theoretically bounding the REQUESTED wait to 50ms): CountDownLatch.await(timeout,...) is a
    // hard JDK guarantee on requested wall-clock time, so a 60s stall from a 50ms request can
    // only mean the thread wasn't scheduled back promptly by the OS -- exactly what severe CPU
    // oversubscription (already flagged CRITICAL by CpuCapacityPlanner, ~2.5x on this box) would
    // cause. This does NOT change dispatcher behavior at all (still the same bounded await) --
    // pure attribution: does measured wall-clock time match what was requested (dispatcher/budget
    // logic is fine, no further action) or does it wildly exceed it (scheduler-starvation, a
    // system-wide CPU-pressure problem, not a dispatcher bug)? cpuStartNanos, if >=0, lets this
    // also report how much ACTUAL CPU time the thread consumed during the wait -- near-zero CPU
    // time despite a huge wall-clock gap is the clean signature of "parked, not given a core",
    // as opposed to some other main-thread cost masquerading as this call in a stack sample.
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final long SPIKE_ABSOLUTE_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final int SPIKE_HISTORY_CAPACITY = 200;
    private static final Deque<String> spikeHistory = new ArrayDeque<>();
    private static final AtomicLong spikeCount = new AtomicLong();

    public static long currentThreadCpuTime() {
        try {
            return THREAD_MX.isCurrentThreadCpuTimeSupported() ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** @param grantedNanos what {@link #reserve} actually granted this call (-1 if unknown/not
     *  passed by an older call site). @param cpuStartNanos {@link #currentThreadCpuTime()}
     *  sampled immediately before the await call (-1 if unsupported/not captured). */
    public static synchronized void recordWait(long actualWaitNanos, long grantedNanos, long cpuStartNanos) {
        totalWaitNanos.addAndGet(actualWaitNanos);
        maxWaitNanos.accumulateAndGet(actualWaitNanos, Math::max);
        waitCount.incrementAndGet();
        ringRecord(waitLatencyRing, waitLatencyIdx, waitLatencyFill, actualWaitNanos);
        if (grantedNanos < 0) return;
        if (actualWaitNanos <= grantedNanos + SPIKE_ABSOLUTE_THRESHOLD_NANOS) return;
        spikeCount.incrementAndGet();
        long cpuNanos = (cpuStartNanos >= 0) ? Math.max(0, currentThreadCpuTime() - cpuStartNanos) : -1L;
        String census = "";
        try {
            census = BurstAdmissionMetrics.threadCensus();
        } catch (Throwable ignored) {}
        String entry = String.format(
                "requestedMs=%.1f actualWallMs=%.1f mainCpuMs=%s ratio=%.1fx | %s",
                grantedNanos / 1e6, actualWaitNanos / 1e6,
                cpuNanos >= 0 ? String.format("%.2f", cpuNanos / 1e6) : "n/a",
                grantedNanos > 0 ? (double) actualWaitNanos / grantedNanos : Double.POSITIVE_INFINITY,
                census.replace('\n', ' '));
        if (spikeHistory.size() >= SPIKE_HISTORY_CAPACITY) spikeHistory.removeFirst();
        spikeHistory.addLast(entry);
    }

    public static synchronized String spikeSummary() {
        if (spikeHistory.isEmpty()) {
            return "NW interaction-wait spikes: count=" + spikeCount.get() + " (none recorded/none since reset)";
        }
        StringBuilder sb = new StringBuilder("NW interaction-wait spikes: count=" + spikeCount.get()
                + " (showing last " + spikeHistory.size() + ")\n");
        for (String e : spikeHistory) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }

    /** Called by {@code RegionThread}'s mailbox-drain loop for EVERY sync-dispatch message
     *  actually applied (claimed or not -- fires regardless of whether the dispatching main-
     *  thread call already gave up and fell back), using {@code RegionMessage.ageNanos()} as the
     *  real post-to-drain wall-clock time. This is the single source of truth for round-trip
     *  latency: recording only on the main-thread SUCCESS path would be blind exactly where we
     *  need data most -- under a too-small budget nearly every call falls back, yet the message
     *  is still drained and applied moments later regardless. {@code sameRegion} is whether the
     *  applying region equals the acting player's own region (always true for {@link
     *  PlayerUseItemDispatcher}, which resolves ownership BY player position -- no cross-region
     *  case exists there). */
    public static void recordApplyLatencySplit(long ageNanos, boolean sameRegion) {
        totalApplyLatencyNanos.addAndGet(ageNanos);
        maxApplyLatencyNanos.accumulateAndGet(ageNanos, Math::max);
        applyLatencyCount.incrementAndGet();
        if (sameRegion) {
            ringRecord(applyLatencySameRegionRing, applyLatencySameRegionIdx, applyLatencySameRegionFill, ageNanos);
        } else {
            ringRecord(applyLatencyCrossRegionRing, applyLatencyCrossRegionIdx, applyLatencyCrossRegionFill, ageNanos);
        }
    }

    private static long[] percentilesOf(long[] ring, int fill) {
        int n = Math.min(fill, ring.length);
        long[] copy = Arrays.copyOf(ring, n);
        Arrays.sort(copy);
        if (n == 0) {
            return new long[] {0, 0, 0, 0, 0};
        }
        return new long[] {
                copy[(int) (n * 0.50)],
                copy[Math.min(n - 1, (int) (n * 0.90))],
                copy[Math.min(n - 1, (int) (n * 0.95))],
                copy[Math.min(n - 1, (int) (n * 0.99))],
                copy[n - 1]
        };
    }

    /** Real percentile distributions -- see class javadoc note above. Computed on demand (not on
     *  the hot path), safe to call from the RCON command thread. */
    public static String distributionSummary() {
        long[] sameP = percentilesOf(applyLatencySameRegionRing, applyLatencySameRegionFill.get());
        long[] crossP = percentilesOf(applyLatencyCrossRegionRing, applyLatencyCrossRegionFill.get());
        long[] waitP = percentilesOf(waitLatencyRing, waitLatencyFill.get());
        int tickN = Math.min(callsPerTickFill.get(), callsPerTickRing.length);
        int[] ticks = Arrays.copyOf(callsPerTickRing, tickN);
        Arrays.sort(ticks);
        int tp50 = tickN == 0 ? 0 : ticks[tickN / 2];
        int tp95 = tickN == 0 ? 0 : ticks[Math.min(tickN - 1, (int) (tickN * 0.95))];
        int tmax = tickN == 0 ? 0 : ticks[tickN - 1];
        return String.format(
                "roundTripSameRegionMs[n=%d] p50=%.2f p90=%.2f p95=%.2f p99=%.2f max=%.2f | "
                        + "roundTripCrossRegionMs[n=%d] p50=%.2f p90=%.2f p95=%.2f p99=%.2f max=%.2f | "
                        + "mainWaitMs[n=%d] p50=%.3f p90=%.3f p95=%.3f p99=%.3f max=%.3f | "
                        + "callsPerTick[n=%d] p50=%d p95=%d max=%d",
                applyLatencySameRegionFill.get(), sameP[0] / 1e6, sameP[1] / 1e6, sameP[2] / 1e6, sameP[3] / 1e6, sameP[4] / 1e6,
                applyLatencyCrossRegionFill.get(), crossP[0] / 1e6, crossP[1] / 1e6, crossP[2] / 1e6, crossP[3] / 1e6, crossP[4] / 1e6,
                waitLatencyFill.get(), waitP[0] / 1e6, waitP[1] / 1e6, waitP[2] / 1e6, waitP[3] / 1e6, waitP[4] / 1e6,
                tickN, tp50, tp95, tmax);
    }

    /** Called whenever a dispatch falls back to main-thread execution, distinguishing WHY --
     *  the budget was already exhausted (zero wait attempted) vs. a real per-call timeout
     *  (waited some or all of its granted share and still didn't complete). Both are safe,
     *  already-proven fallback paths; this is purely visibility into which one is firing. */
    public static void recordFallback(boolean dueToExhaustedBudget) {
        if (dueToExhaustedBudget) {
            budgetExhaustedFallbackCount.incrementAndGet();
        } else {
            timeoutFallbackCount.incrementAndGet();
        }
    }

    public static synchronized void reset() {
        spikeHistory.clear();
        spikeCount.set(0);
        totalWaitNanos.set(0);
        maxWaitNanos.set(0);
        waitCount.set(0);
        budgetExhaustedFallbackCount.set(0);
        timeoutFallbackCount.set(0);
        totalApplyLatencyNanos.set(0);
        maxApplyLatencyNanos.set(0);
        applyLatencyCount.set(0);
        applyLatencySameRegionIdx.set(0);
        applyLatencySameRegionFill.set(0);
        applyLatencyCrossRegionIdx.set(0);
        applyLatencyCrossRegionFill.set(0);
        waitLatencyIdx.set(0);
        waitLatencyFill.set(0);
        callsPerTickIdx.set(0);
        callsPerTickFill.set(0);
    }

    public static String summary() {
        long wc = waitCount.get();
        double avgUs = wc == 0 ? 0.0 : (totalWaitNanos.get() / 1000.0) / wc;
        long alc = applyLatencyCount.get();
        double avgApplyUs = alc == 0 ? 0.0 : (totalApplyLatencyNanos.get() / 1000.0) / alc;
        return String.format(
                "mainThreadInteractionWaitTotal=%.2fms mainThreadInteractionWaitAvgUs=%.1f "
                        + "mainThreadInteractionWaitMaxUs=%.1f waitCount=%,d "
                        + "mainThreadInteractionFallbackCount=%,d (budgetExhausted=%,d timeout=%,d) "
                        + "K=%d X=%.0fms | realRoundTripAvgUs=%.1f realRoundTripMaxUs=%.1f realRoundTripCount=%,d",
                totalWaitNanos.get() / 1_000_000.0, avgUs, maxWaitNanos.get() / 1000.0, wc,
                budgetExhaustedFallbackCount.get() + timeoutFallbackCount.get(),
                budgetExhaustedFallbackCount.get(), timeoutFallbackCount.get(),
                NestworldTuning.INTERACTION_MAX_WAIT_SLOTS_PER_TICK,
                NestworldTuning.INTERACTION_MAX_WAIT_PER_CALL_NANOS / 1_000_000.0,
                avgApplyUs, maxApplyLatencyNanos.get() / 1000.0, alc);
    }
}
