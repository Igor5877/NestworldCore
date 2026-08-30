package net.nestworld.region;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-{@code Connection} packet-send fan-in/latency diagnostics (2026-08-28) -- counterpart to a
 * small patch on vanilla {@code net.minecraft.network.Connection} (see its {@code
 * nestworldConcurrentSenders} field and the calls into this class from {@code send()}/{@code
 * flushQueue()}/{@code sendPacket()}).
 *
 * <p>LEAN REWRITE (2026-08-28, same day): the first version's per-source ring buffers +
 * ConcurrentHashMap-based in-flight tracker showed up as real, non-trivial cost in its OWN JFR
 * profile at ~50-60k send()/sec (72 samples across {@code recordQueueLockWait} +
 * {@code ConcurrentHashMap.remove} in one 297s recording) -- the tool was measurably perturbing
 * the exact phenomenon it was trying to observe. The in-flight tracker already served its
 * purpose (confirmed no true multi-second deadlock -- the Server thread kept producing DIFFERENT
 * execution samples even after the watchdog's dump, ruling out a classic stuck-forever freeze;
 * see project memory connection-queue-lock-livelock-crash-n150.md). Removed entirely here. Per-
 * source tracking is now the cheapest possible shape: two `AtomicLong`s (count, total nanos) per
 * source, no ring buffer, no percentiles -- percentile-level detail is JFR's job now, not this
 * class's, and the two should not run in the same "production-like" pass (heavy JFR profiling is
 * its own separate, deliberately instrumented run).
 *
 * <p>Working diagnosis as of this rewrite (see the user's own reframing): NOT a lock livelock,
 * NOT a GC pause, NOT CPU/cgroup starvation, NOT a true multi-second deadlock -- "massive
 * synchronous outbound packet amplification -> main-thread tick starvation -> watchdog." The
 * per-source breakdown here is what determines whether #31's region-execution work is a genuine
 * contributor (region-sourced sends anomalous vs. main/forkjoin) or not (all three look similar,
 * pointing below all of them into Netty/Connection/socket itself).
 */
public final class ConnectionDiagnostics {
    private ConnectionDiagnostics() {}

    private static final AtomicInteger maxConcurrentSendersEver = new AtomicInteger();
    private static final AtomicLong sendCallCount = new AtomicLong();
    private static final AtomicLong totalSendNanos = new AtomicLong();
    private static final AtomicLong maxSendNanos = new AtomicLong();

    private enum Source { MAIN, REGION, FORKJOIN, OTHER }

    private static Source classifySource() {
        String name = Thread.currentThread().getName();
        if (name.equals("Server thread")) return Source.MAIN;
        if (name.startsWith("NestWorld-Region-")) return Source.REGION;
        if (name.startsWith("ForkJoinPool.commonPool-worker-")) return Source.FORKJOIN;
        return Source.OTHER;
    }

    // Cheapest possible per-source shape: plain AtomicLongs for the hot count/total/max path,
    // PLUS a small ring buffer populated only 1-in-SAMPLE_RATE calls (ТЗ §12: percentile latency
    // via sampling, not exhaustive per-call tracking) so p50/p90/p95/p99 stay available without
    // paying array-write cost on every one of ~50-60k calls/sec.
    private static final int LATENCY_SAMPLE_RATE = 1000;
    private static final int LATENCY_RING_CAPACITY = 2048;

    private static final class SourceStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong sampleCounter = new AtomicLong();
        final long[] ring = new long[LATENCY_RING_CAPACITY];
        final AtomicInteger ringIdx = new AtomicInteger();
        final AtomicInteger ringFill = new AtomicInteger();

        void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
            if (sampleCounter.incrementAndGet() % LATENCY_SAMPLE_RATE == 0) {
                int idx = ringIdx.getAndUpdate(i -> (i + 1) % LATENCY_RING_CAPACITY);
                ring[idx] = nanos;
                ringFill.updateAndGet(f -> Math.min(f + 1, LATENCY_RING_CAPACITY));
            }
        }

        void reset() {
            count.set(0);
            totalNanos.set(0);
            maxNanos.set(0);
            sampleCounter.set(0);
            ringIdx.set(0);
            ringFill.set(0);
        }

        String summary(String label) {
            long c = count.get();
            if (c == 0) return label + "[n=0]";
            int n = Math.min(ringFill.get(), LATENCY_RING_CAPACITY);
            String pctStr = "p50=n/a p90=n/a p95=n/a p99=n/a";
            if (n > 0) {
                long[] copy = java.util.Arrays.copyOf(ring, n);
                java.util.Arrays.sort(copy);
                pctStr = String.format("p50=%.1fus p90=%.1fus p95=%.1fus p99=%.1fus",
                        copy[n / 2] / 1000.0, copy[Math.min(n - 1, (int) (n * 0.90))] / 1000.0,
                        copy[Math.min(n - 1, (int) (n * 0.95))] / 1000.0, copy[Math.min(n - 1, (int) (n * 0.99))] / 1000.0);
            }
            return String.format("%s[n=%,d sampled=%,d] avgUs=%.1f maxUs=%.1f %s",
                    label, c, n, (totalNanos.get() / 1000.0) / c, maxNanos.get() / 1000.0, pctStr);
        }
    }

    private static final SourceStats mainStats = new SourceStats();
    private static final SourceStats regionStats = new SourceStats();
    private static final SourceStats forkjoinStats = new SourceStats();
    private static final SourceStats otherStats = new SourceStats();

    private static SourceStats statsFor(Source s) {
        return switch (s) {
            case MAIN -> mainStats;
            case REGION -> regionStats;
            case FORKJOIN -> forkjoinStats;
            case OTHER -> otherStats;
        };
    }

    /** Per-source send() call-count/latency breakdown -- main vs region vs forkjoin vs other. */
    public static String perSourceSummary() {
        return mainStats.summary("main") + " | " + regionStats.summary("region")
                + " | " + forkjoinStats.summary("forkjoin") + " | " + otherStats.summary("other");
    }

    // NestWorld: 2026-08-28 broadcast composition (player-tracked vs other-entity-tracked) -- see
    // ChunkMap$TrackedEntity.broadcast()'s patch for the call site. broadcastCalls counts how many
    // times ChunkMap fanned out a packet for an entity of this category; fanOutSum is the total
    // number of individual player sends that resulted (sum of seenBy.size() across those calls) --
    // the more informative number for "how many actual packets did this category cause."
    private static final AtomicLong playerTrackedBroadcastCalls = new AtomicLong();
    private static final AtomicLong playerTrackedFanOutSum = new AtomicLong();
    private static final AtomicLong otherEntityBroadcastCalls = new AtomicLong();
    private static final AtomicLong otherEntityFanOutSum = new AtomicLong();

    public static void recordBroadcast(boolean isPlayerTarget, int fanOutCount) {
        if (isPlayerTarget) {
            playerTrackedBroadcastCalls.incrementAndGet();
            playerTrackedFanOutSum.addAndGet(fanOutCount);
        } else {
            otherEntityBroadcastCalls.incrementAndGet();
            otherEntityFanOutSum.addAndGet(fanOutCount);
        }
    }

    public static String broadcastCompositionSummary() {
        long pc = playerTrackedBroadcastCalls.get(), pf = playerTrackedFanOutSum.get();
        long oc = otherEntityBroadcastCalls.get(), of = otherEntityFanOutSum.get();
        long totalFanOut = pf + of;
        double playerPct = totalFanOut > 0 ? 100.0 * pf / totalFanOut : 0;
        return String.format(
                "playerTracked: broadcastCalls=%,d fanOutSum=%,d (%.1f%% of total) | "
                        + "otherEntityTracked: broadcastCalls=%,d fanOutSum=%,d (%.1f%% of total)",
                pc, pf, playerPct, oc, of, 100.0 - playerPct);
    }

    private static final AtomicLong queueLockWaitCount = new AtomicLong();
    private static final AtomicLong totalQueueLockWaitNanos = new AtomicLong();
    private static final AtomicLong maxQueueLockWaitNanos = new AtomicLong();

    private static final AtomicLong eventLoopExecuteCount = new AtomicLong();
    private static final AtomicLong totalEventLoopExecuteNanos = new AtomicLong();
    private static final AtomicLong maxEventLoopExecuteNanos = new AtomicLong();

    private static volatile long windowStartNanos = System.nanoTime();

    // NestWorld: enterEventLoopExecute()/exitEventLoopExecute() are still called from the vanilla
    // Connection.java patch (kept as no-ops here rather than re-patching+re-installing vanilla
    // just to remove two call sites -- the ConcurrentHashMap-based in-flight tracker they used to
    // drive is gone; these are now free).
    public static void enterEventLoopExecute() {
    }

    public static void exitEventLoopExecute() {
    }

    public static String inFlightSummary() {
        return "inFlight tracking removed (lean rewrite -- see class javadoc)";
    }

    /** Called from {@code Connection.send()} right after incrementing that connection's own
     *  {@code nestworldConcurrentSenders} counter -- {@code concurrentNow} is the resulting count
     *  (>=1, includes the caller itself). */
    public static void recordSendEnter(int concurrentNow) {
        sendCallCount.incrementAndGet();
        maxConcurrentSendersEver.accumulateAndGet(concurrentNow, Math::max);
    }

    /** Called from {@code Connection.send()}'s finally block. */
    public static void recordSendExit(long elapsedNanos, int concurrentAtEntry) {
        totalSendNanos.addAndGet(elapsedNanos);
        maxSendNanos.accumulateAndGet(elapsedNanos, Math::max);
        statsFor(classifySource()).record(elapsedNanos);
    }

    /** Called from {@code Connection.flushQueue()}, timing only the {@code synchronized(queue)}
     *  block itself (wait + hold), not the whole {@code send()} call. */
    public static void recordQueueLockWait(long nanos) {
        queueLockWaitCount.incrementAndGet();
        totalQueueLockWaitNanos.addAndGet(nanos);
        maxQueueLockWaitNanos.accumulateAndGet(nanos, Math::max);
    }

    /** Called from {@code Connection.sendPacket()} around the {@code channel.eventLoop()
     *  .execute(...)} call. */
    public static void recordEventLoopExecuteLatency(long nanos) {
        eventLoopExecuteCount.incrementAndGet();
        totalEventLoopExecuteNanos.addAndGet(nanos);
        maxEventLoopExecuteNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void reset() {
        maxConcurrentSendersEver.set(0);
        sendCallCount.set(0);
        totalSendNanos.set(0);
        maxSendNanos.set(0);
        mainStats.reset();
        regionStats.reset();
        forkjoinStats.reset();
        otherStats.reset();
        queueLockWaitCount.set(0);
        totalQueueLockWaitNanos.set(0);
        maxQueueLockWaitNanos.set(0);
        eventLoopExecuteCount.set(0);
        totalEventLoopExecuteNanos.set(0);
        maxEventLoopExecuteNanos.set(0);
        playerTrackedBroadcastCalls.set(0);
        playerTrackedFanOutSum.set(0);
        otherEntityBroadcastCalls.set(0);
        otherEntityFanOutSum.set(0);
        windowStartNanos = System.nanoTime();
    }

    public static String summary() {
        long sc = sendCallCount.get();
        double avgSendUs = sc == 0 ? 0.0 : (totalSendNanos.get() / 1000.0) / sc;
        long qc = queueLockWaitCount.get();
        double avgQueueLockUs = qc == 0 ? 0.0 : (totalQueueLockWaitNanos.get() / 1000.0) / qc;
        long ec = eventLoopExecuteCount.get();
        double avgEventLoopUs = ec == 0 ? 0.0 : (totalEventLoopExecuteNanos.get() / 1000.0) / ec;
        double windowSeconds = (System.nanoTime() - windowStartNanos) / 1_000_000_000.0;
        double sendsPerSec = windowSeconds > 0 ? sc / windowSeconds : 0;
        // sendsPerTick: crude proxy using the current TPS-target (20) rather than a real tick
        // hook -- accurate while healthy (~20 TPS), an undercount once TPS degrades (the SAME
        // volume spread over fewer, longer ticks means the real calls/tick is higher than this).
        double sendsPerTick = sendsPerSec / 20.0;
        return String.format(
                "maxConcurrentSendersEverOnOneConnection=%d | sendCalls=%,d sendsPerSec=%.1f sendsPerTick~=%.1f "
                        + "sendAvgUs=%.1f sendMaxUs=%.1f | queueLockWaits=%,d queueLockAvgUs=%.1f "
                        + "queueLockMaxUs=%.1f | eventLoopExecuteCalls=%,d eventLoopExecuteAvgUs=%.1f "
                        + "eventLoopExecuteMaxUs=%.1f | windowSec=%.1f",
                maxConcurrentSendersEver.get(), sc, sendsPerSec, sendsPerTick, avgSendUs, maxSendNanos.get() / 1000.0,
                qc, avgQueueLockUs, maxQueueLockWaitNanos.get() / 1000.0,
                ec, avgEventLoopUs, maxEventLoopExecuteNanos.get() / 1000.0, windowSeconds);
    }

    public static String slowLog(int maxLines) {
        return "slow-call log removed (lean rewrite -- see class javadoc; use a dedicated JFR pass instead)";
    }
}
