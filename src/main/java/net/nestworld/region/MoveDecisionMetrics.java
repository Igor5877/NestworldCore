package net.nestworld.region;

import java.util.concurrent.atomic.LongAdder;

/**
 * Player Tick / Decision Fan-out Attribution (ТЗ, 2026-08-29) -- MEASUREMENT ONLY. Targets
 * {@code ChunkMap.move()}'s tracking-DECISION cost specifically, as distinct from the
 * outbound-DELIVERY cost the three prior queues ([[OutboundBatchQueue]], [[ChunkOutboundQueue]],
 * [[PairingOutboundQueue]]) already fixed. Per the user's own hypothesis: those three queues only
 * changed WHEN/HOW a tracking decision's resulting packet reaches the network -- none of them
 * touch the DECISION-MAKING loop itself (iterating candidate entities and calling
 * {@code TrackedEntity.updatePlayer()} for each to decide ADD/REMOVE/no-change).
 *
 * <p>{@code move()} has two paths: the cheap {@code TRACKER_SPATIAL_CULL} path (bounded AABB
 * query, used for small moves) and a FULL fallback scan over the entire {@code entityMap} (used
 * whenever the move delta exceeds tracking range -- i.e. ALWAYS on a teleport, since the delta is
 * `Double.MAX_VALUE` on the first post-teleport move). This class counts calls and candidates on
 * BOTH paths, so the ratio of "decisions evaluated" to "actual transitions" (already tracked by
 * {@link PairingOutboundQueue}'s addEnqueued/removeEnqueued, or the vanilla addPairing/
 * removePairing call counts when that queue is disabled) can be computed directly, without
 * guessing which path or magnitude is responsible.
 */
public final class MoveDecisionMetrics {
    private MoveDecisionMetrics() {}

    private static final LongAdder spatialCullCalls = new LongAdder();
    private static final LongAdder spatialCullCandidates = new LongAdder();
    private static final LongAdder fullScanCalls = new LongAdder();
    private static final LongAdder fullScanCandidates = new LongAdder();
    private static final LongAdder throttledCalls = new LongAdder();
    private static final LongAdder moveInvocations = new LongAdder();

    public static void recordMoveInvocation() {
        moveInvocations.increment();
    }

    public static void recordThrottled() {
        throttledCalls.increment();
    }

    /** {@code candidates} = number of entities considered (AABB query result size for the cull
     *  path, {@code entityMap.size()} for the fallback) -- each candidate triggers ONE
     *  {@code updatePlayer()} call, i.e. one tracking DECISION, regardless of whether it results
     *  in a state change. */
    public static void recordSpatialCull(int candidates) {
        spatialCullCalls.increment();
        spatialCullCandidates.add(candidates);
    }

    public static void recordFullScan(int candidates) {
        fullScanCalls.increment();
        fullScanCandidates.add(candidates);
    }

    public static void reset() {
        spatialCullCalls.reset();
        spatialCullCandidates.reset();
        fullScanCalls.reset();
        fullScanCandidates.reset();
        throttledCalls.reset();
        moveInvocations.reset();
    }

    public static String report() {
        long totalCandidates = spatialCullCandidates.sum() + fullScanCandidates.sum();
        return String.format(
                "NW move-decision: moveInvocations=%,d throttled=%,d | spatialCull calls=%,d candidates=%,d | fullScan calls=%,d candidates=%,d | totalDecisions(candidates)=%,d",
                moveInvocations.sum(), throttledCalls.sum(),
                spatialCullCalls.sum(), spatialCullCandidates.sum(),
                fullScanCalls.sum(), fullScanCandidates.sum(),
                totalCandidates);
    }
}
