package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch-apply Phase 1 telemetry (docs/BATCH_APPLY_COALESCING_DESIGN.md). Always-on,
 * cheap {@code AtomicLong} counters — same "cheap enough to leave on unconditionally"
 * choice as {@link MailboxAudit}'s id counter, since this is exactly the mechanism the
 * design doc's benchmark plan needs numbers from ({@code locks/message} before vs
 * {@code locks/batch} after, batch size, timeout/convoy rate). Surfaced via
 * {@code /nestworld pressure}.
 */
public final class BatchApplyStats {
    private static final AtomicLong batchesFormed = new AtomicLong();
    private static final AtomicLong messagesInBatches = new AtomicLong();
    private static final AtomicLong lockSetSizeSum = new AtomicLong();
    private static final AtomicLong batchesApplied = new AtomicLong();
    private static final AtomicLong batchesTimedOut = new AtomicLong();
    private static final AtomicLong messagesInTimedOutBatches = new AtomicLong();

    private BatchApplyStats() {}

    /** Called once per batch, right after its combined lock set is computed but before
     *  any lock is attempted — records the "before" side (what a batch cost to FORM)
     *  regardless of whether the lock acquisition below succeeds. */
    static void recordBatchFormed(int batchSize, int lockSetSize) {
        batchesFormed.incrementAndGet();
        messagesInBatches.addAndGet(batchSize);
        lockSetSizeSum.addAndGet(lockSetSize);
    }

    /** Called once per batch that acquired its full lock set and applied every message. */
    static void recordBatchApplied() {
        batchesApplied.incrementAndGet();
    }

    /** Called once per batch that timed out acquiring its lock set — every message in
     *  it was requeued whole, per the design doc's "whole batch, not partial" rule. */
    static void recordBatchTimeout(int batchSize) {
        batchesTimedOut.incrementAndGet();
        messagesInTimedOutBatches.addAndGet(batchSize);
    }

    public static String summary() {
        long formed = batchesFormed.get();
        long messages = messagesInBatches.get();
        long lockSum = lockSetSizeSum.get();
        double avgBatchSize = formed == 0 ? 0.0 : (double) messages / formed;
        double avgLockSetPerBatch = formed == 0 ? 0.0 : (double) lockSum / formed;
        double locksPerMessage = messages == 0 ? 0.0 : (double) lockSum / messages;
        return String.format(
                "batchesFormed=%d applied=%d timedOut=%d messages=%d avgBatchSize=%.1f "
                        + "avgLockSetPerBatch=%.2f locksPerMessage=%.3f messagesInTimedOutBatches=%d",
                formed, batchesApplied.get(), batchesTimedOut.get(), messages,
                avgBatchSize, avgLockSetPerBatch, locksPerMessage, messagesInTimedOutBatches.get());
    }
}
