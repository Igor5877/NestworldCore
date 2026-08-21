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
    // Phase 2 feasibility probe (2026-08-22): does a batch's own combinedPositions list
    // ever contain the SAME BlockPos more than once? If duplicate-position writes within
    // one batch are rare, payload-level coalescing (dedup same-position writes, keep only
    // the final state) has near-zero payoff regardless of implementation quality — measure
    // BEFORE building the side-effect-preserving dedup logic, not after. Pure counters,
    // no behavior change; safe to leave on.
    private static final AtomicLong positionsTotal = new AtomicLong();
    private static final AtomicLong positionsDuplicate = new AtomicLong();
    private static final AtomicLong batchesWithAnyDuplicate = new AtomicLong();

    private BatchApplyStats() {}

    /** Called once per batch, right after its combined lock set is computed but before
     *  any lock is attempted — records the "before" side (what a batch cost to FORM)
     *  regardless of whether the lock acquisition below succeeds. */
    static void recordBatchFormed(int batchSize, int lockSetSize) {
        batchesFormed.incrementAndGet();
        messagesInBatches.addAndGet(batchSize);
        lockSetSizeSum.addAndGet(lockSetSize);
    }

    /** Called once per batch with its raw (pre-dedup) position list — counts how many of
     *  the batch's positions are repeats of an earlier position in the SAME batch. */
    static void recordPositionDuplicates(java.util.Collection<net.minecraft.core.BlockPos> positions) {
        int total = positions.size();
        long distinct = positions.stream().distinct().count();
        int dup = (int) (total - distinct);
        positionsTotal.addAndGet(total);
        if (dup > 0) {
            positionsDuplicate.addAndGet(dup);
            batchesWithAnyDuplicate.incrementAndGet();
        }
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
        long posTotal = positionsTotal.get();
        long posDup = positionsDuplicate.get();
        double dupRate = posTotal == 0 ? 0.0 : 100.0 * posDup / posTotal;
        return String.format(
                "batchesFormed=%d applied=%d timedOut=%d messages=%d avgBatchSize=%.1f "
                        + "avgLockSetPerBatch=%.2f locksPerMessage=%.3f messagesInTimedOutBatches=%d "
                        + "| dupProbe: positions=%d duplicates=%d dupRate=%.2f%% batchesWithDup=%d",
                formed, batchesApplied.get(), batchesTimedOut.get(), messages,
                avgBatchSize, avgLockSetPerBatch, locksPerMessage, messagesInTimedOutBatches.get(),
                posTotal, posDup, dupRate, batchesWithAnyDuplicate.get());
    }
}
