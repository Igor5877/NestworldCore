package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11 #31.3/#31.4 player-action-ownership hardening: measurement-only visibility for {@link
 * RegionMessage.Type#PLAYER_USE_CONTROL}. Unlike {@link PlayerAttackTiming} (main-vs-region
 * EXECUTION cost), this message type has no main-thread side to compare against by design (see
 * that type's javadoc -- fire-and-forget, no fallback) -- what matters here instead is MAILBOX
 * LATENCY (enqueue-to-apply), since the packet handler no longer waits for a result and therefore
 * has no other way to observe how long a release/stop took to actually land.
 */
public final class PlayerUseControlTiming {
    private static final AtomicLong enqueuedCount = new AtomicLong();
    private static final AtomicLong executedCount = new AtomicLong();
    private static final AtomicLong latencyNanosTotal = new AtomicLong();
    private static final AtomicLong latencyNanosMax = new AtomicLong();
    private static final AtomicLong reroutedCount = new AtomicLong();
    private static final AtomicLong droppedCount = new AtomicLong();

    private PlayerUseControlTiming() {}

    public static void recordEnqueued() {
        enqueuedCount.incrementAndGet();
    }

    public static void recordExecuted(long queueLatencyNanos) {
        executedCount.incrementAndGet();
        latencyNanosTotal.addAndGet(queueLatencyNanos);
        latencyNanosMax.accumulateAndGet(queueLatencyNanos, Math::max);
    }

    public static void recordRerouted() {
        reroutedCount.incrementAndGet();
    }

    public static void recordDropped() {
        droppedCount.incrementAndGet();
    }

    public static void reset() {
        enqueuedCount.set(0);
        executedCount.set(0);
        latencyNanosTotal.set(0);
        latencyNanosMax.set(0);
        reroutedCount.set(0);
        droppedCount.set(0);
    }

    public static String summary() {
        long ec = executedCount.get();
        double avgUs = ec == 0 ? 0.0 : (latencyNanosTotal.get() / 1000.0) / ec;
        double maxUs = latencyNanosMax.get() / 1000.0;
        return String.format(
                "enqueued=%,d executed=%,d queueLatencyAvgUs=%.1f queueLatencyMaxUs=%.1f rerouted=%,d dropped=%,d",
                enqueuedCount.get(), ec, avgUs, maxUs, reroutedCount.get(), droppedCount.get());
    }
}
