package net.nestworld.region;

import java.util.Comparator;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * P0.3 ТЗ (docs/P0_3_AUTONOMOUS_BASELINE_SPEC.md): no-player, no-client autonomous chunk-gen
 * benchmark harness. Requests full generation for a batch of chunks via a dedicated,
 * non-PLAYER, self-expiring ticket type (same pattern as {@link PredictiveChunkGen}'s
 * {@code nestworld_predictive} ticket) -- {@code nestworldHasPlayerTicket()} only checks for
 * {@code TicketType.PLAYER}, so this drives the Tier-2/Bulk pipeline exactly like a real bulk
 * source (/forceload, distant teleport) would, without ever touching player-ticket code paths.
 *
 * <p>Coordinates spiral outward from a caller-given base, in wide (37-block) steps so every
 * dispatched chunk in one run is guaranteed never-before-generated (same spiral shape as
 * {@code NestworldCommand.stressChunks}); a fresh {@code baseChunkX/Z} per test run guarantees no
 * overlap between runs (repeat calls would just hit already-FULL, near-instant chunks otherwise).
 *
 * <p>Dispatch is fire-and-forget (returns immediately; commands must not block). Progress is
 * read back via {@link #status(ServerLevel)}, which diffs the existing
 * {@code DistanceManager.nestworldPromotionTotalApplied()} counter (chunk-promotion telemetry,
 * already tracks every completed FULL/ticking promotion) against a snapshot taken at dispatch
 * time -- no new completion-tracking machinery needed.
 */
public final class ChunkGenBenchmark {
    private ChunkGenBenchmark() {}

    /** Ticket expires on its own; long enough to survive a heavily backlogged Tier-2 queue
     *  (400 ticks = 20s) without needing manual cleanup or persisting across a restart. */
    private static final int TIMEOUT_TICKS = 400;
    private static final int FULL_DISTANCE = 0; // ticket level -> FULL, non-ticking

    private static final TicketType<ChunkPos> BENCH =
            TicketType.create("nestworld_bench", Comparator.comparingLong(ChunkPos::toLong), TIMEOUT_TICKS);

    private static long startNanos = 0;
    private static long startPromotionApplied = 0;
    private static int dispatchedCount = 0;
    private static int lastBaseX = 0, lastBaseZ = 0;

    /** Dispatch `count` fresh-chunk tickets spiraling out from (baseChunkX, baseChunkZ). */
    public static void dispatch(ServerLevel level, int count, int baseChunkX, int baseChunkZ) {
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        int placed = 0;
        int ring = 0;
        // Spiral outward ring by ring (matches NestworldCommand.stressChunks' shape), wide
        // enough steps (3 chunks) that a ring never revisits a previous run's footprint.
        outer:
        while (placed < count) {
            int size = ring == 0 ? 1 : ring * 8;
            for (int i = 0; i < size && placed < count; i++) {
                int cx, cz;
                if (ring == 0) {
                    cx = 0; cz = 0;
                } else {
                    int side = i / (2 * ring);
                    int pos = i % (2 * ring);
                    int off = pos - ring + 1;
                    switch (side) {
                        case 0: cx = ring; cz = off; break;
                        case 1: cx = -off; cz = ring; break;
                        case 2: cx = -ring; cz = -off; break;
                        default: cx = off; cz = -ring; break;
                    }
                }
                ChunkPos pos2 = new ChunkPos(baseChunkX + cx * 3, baseChunkZ + cz * 3);
                cache.addRegionTicket(BENCH, pos2, FULL_DISTANCE, pos2);
                placed++;
            }
            ring++;
            if (ring > 200) break outer; // safety: 200 rings is far more than any TEST E (4096) needs
        }
        startNanos = System.nanoTime();
        startPromotionApplied = cache.chunkMap.getDistanceManager().nestworldPromotionTotalApplied();
        dispatchedCount = count;
        lastBaseX = baseChunkX;
        lastBaseZ = baseChunkZ;
    }

    /**
     * Progress since the last dispatch() call. NOTE: {@code promotion_events} is the GLOBAL
     * {@code nestworldPromotionTotalApplied()} delta, not "requested chunks finished" -- fully
     * generating N chunks to FULL cascades into partial-stage promotions of their neighbours
     * too (structure/noise/surface/carvers/features each need a radius of earlier-stage
     * neighbours), so this is a genuine total-pipeline-throughput number, structurally larger
     * than the dispatched count. No "[DONE]" signal is derivable from it alone -- use the rate
     * flattening out (compare two status() calls a few seconds apart) as the completion signal.
     */
    public static String status(ServerLevel level) {
        if (dispatchedCount == 0) return "no benchmark dispatched yet -- use /nestworld gentest start <count> [baseX] [baseZ]";
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        long nowApplied = cache.chunkMap.getDistanceManager().nestworldPromotionTotalApplied();
        long promotionEvents = nowApplied - startPromotionApplied;
        double elapsedSec = (System.nanoTime() - startNanos) / 1e9;
        double eventsPerSec = elapsedSec <= 0 ? 0.0 : promotionEvents / elapsedSec;
        return String.format(
                "gentest: dispatched=%,d base=(%d,%d) promotion_events=%,d elapsed=%.1fs events/sec=%.2f",
                dispatchedCount, lastBaseX, lastBaseZ, promotionEvents, elapsedSec, eventsPerSec);
    }
}
