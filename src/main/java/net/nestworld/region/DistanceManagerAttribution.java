package net.nestworld.region;

/**
 * P0.1 follow-up: DistanceManager.runAllUpdates() is 80.4% of all pollTask() time (see
 * PollTaskAttribution's first live reading, docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md). That
 * method mixes vanilla ticket-propagation machinery with NestWorld's own Tier 1a/1b/2 budget
 * bookkeeping in the SAME method body -- this class answers which one actually costs the time,
 * by timing each of runAllUpdates()'s existing, already-distinct code blocks. Same O(1)-per-call,
 * no-scan discipline as PollTaskAttribution; main-thread-only access (runAllUpdates always runs
 * from pollTask on the main/region-dispatch thread), so plain longs are safe.
 */
public final class DistanceManagerAttribution {
    private DistanceManagerAttribution() {}

    public enum Category {
        SPAWN_TRACKER,            // naturalSpawnChunkCounter.runAllUpdates()
        TICKING_TRACKER,          // tickingTicketsTracker.runAllUpdates()
        PLAYER_TICKET_TRACKER,    // playerTicketManager.runAllUpdates()
        DISTANCE_PROPAGATION,     // ticketTracker.runDistanceUpdates() -- vanilla BFS ticket-level spread
        VALIDATOR_BATCH,          // nestworldRunValidatorBatch() -- NestWorld correctness backstop
        UNLIMITED_PROMOTION,      // CHUNK_GEN_BUDGET<=0 branch: forEach over both sets, no tiering
        URGENT_SET_BUILD,         // NestWorld: players x radius -> nestworldUrgentChunks (pure bookkeeping)
        TIER1_CLASSIFY_PROMOTE,   // NestWorld: Tier1a/1b split + Tier1a immediate promotion (mixed)
        TIER1B_SELECT_PROMOTE,    // NestWorld: Tier1b top-N PriorityQueue + promotion (mixed)
        TIER2_SELECT,             // NestWorld: Tier2 (bulk) top-N heap build -- scans chunksToUpdateFuturesBulk IN FULL every call
        TIER2_PROMOTE,            // NestWorld: Tier2 -- actual nestworldTimedUpdateFutures() calls on the selected N
        TICKET_RELEASE            // vanilla: ticketsToRelease drain
    }

    private static final long[] nanosSum = new long[Category.values().length];
    private static final long[] maxNanos = new long[Category.values().length];
    private static final long[] callCount = new long[Category.values().length];

    public static void record(Category category, long elapsedNanos) {
        int i = category.ordinal();
        nanosSum[i] += elapsedNanos;
        if (elapsedNanos > maxNanos[i]) maxNanos[i] = elapsedNanos;
        callCount[i]++;
    }

    // P0.3/P0.4 ТЗ section 4: TIER2 "examined/selected" ratio. Post-P0.4 fix, `examined` is the
    // number of ready-heap polls (including discarded stale entries), NOT queueSize -- the whole
    // point of the fix is that these are no longer structurally equal. Acceptance criterion:
    // examined/selected should stay near O(1) (amortized -- see nestworldTier2Ready's doc for why
    // "amortized" is the right bar, not per-call-worst-case), not grow with queueSize like before
    // the fix (was measured 690 -> 1609.6 pre-fix). Cumulative sums + last-seen snapshot, O(1)/call.
    private static long tier2ExaminedSum = 0, tier2SelectedSum = 0, tier2Calls = 0;
    private static long tier2LastQueueSize = 0, tier2LastSelected = 0, tier2LastExamined = 0, tier2MaxQueueSize = 0;

    public static void recordTier2Selection(long queueSize, long examined, long selected) {
        tier2ExaminedSum += examined;
        tier2SelectedSum += selected;
        tier2Calls++;
        tier2LastQueueSize = queueSize;
        tier2LastSelected = selected;
        tier2LastExamined = examined;
        if (queueSize > tier2MaxQueueSize) tier2MaxQueueSize = queueSize;
    }

    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        long totalNanos = 0, totalCalls = 0;
        for (Category c : Category.values()) {
            totalNanos += nanosSum[c.ordinal()];
            totalCalls += callCount[c.ordinal()];
        }
        sb.append(String.format("runAllUpdates_total: calls=%,d total=%.3fms%n", totalCalls, totalNanos / 1e6));
        for (Category c : Category.values()) {
            int i = c.ordinal();
            if (callCount[i] == 0) continue;
            double pct = totalNanos == 0 ? 0.0 : 100.0 * nanosSum[i] / totalNanos;
            sb.append(String.format("  %-22s calls=%,-8d total=%9.3fms (%5.1f%%) avg=%.4fms max=%.3fms%n",
                    c, callCount[i], nanosSum[i] / 1e6, pct, nanosSum[i] / 1e6 / callCount[i], maxNanos[i] / 1e6));
        }
        if (tier2Calls > 0) {
            double ratio = tier2SelectedSum == 0 ? 0.0 : (double) tier2ExaminedSum / tier2SelectedSum;
            sb.append(String.format(
                "  TIER2 examine/select (amortized): calls=%,d examined_sum=%,d selected_sum=%,d ratio=%.2f last_queue=%,d last_examined=%,d last_selected=%,d max_queue_seen=%,d%n",
                tier2Calls, tier2ExaminedSum, tier2SelectedSum, ratio, tier2LastQueueSize, tier2LastExamined, tier2LastSelected, tier2MaxQueueSize));
        }
        return sb.toString();
    }
}
