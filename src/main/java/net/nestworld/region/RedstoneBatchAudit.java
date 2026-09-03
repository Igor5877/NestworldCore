package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only (2026-09-03, "#2 both=true" investigation -- see project memory around this
 * date). Does NOT fix or gate anything; only observes. Purpose: distinguish between the two live
 * hypotheses for the one-tick "both repeaters powered=true simultaneously" anomaly caught in
 * {@code /opt/minecraft/blockwatch-redstone.csv} (tick 7051):
 *
 * <p>(a) benign observation-timing artifact -- {@link BlockTickWatcher}'s main-thread read of
 * block state is unsynchronized against a free-running region's own thread (free-running
 * regions never barrier-sync with the main thread by design), so it can land between two
 * separate, correctly-ordered writes that are part of the same logical redstone update but not
 * the same {@code scheduledWork} batch;
 *
 * <p>(b) a real computation issue -- two entries land in the SAME {@code scheduledWork} batch
 * (see {@link RegionThread#runFreeRunningTick()}) and one computes its result from the other's
 * not-yet-applied (stale) state.
 *
 * <p>Reuses {@link BlockTickWatcher}'s existing watch-list rather than adding a second
 * configuration surface -- this class only ever looks at positions the operator already
 * registered via {@code /nestworld blockwatch addpos}.
 */
public final class RedstoneBatchAudit {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RedstoneBatchAudit");
    private static final AtomicLong nextBatchId = new AtomicLong(1);

    private RedstoneBatchAudit() {}

    /** One watched position's involvement in a single scheduledWork batch. */
    private record Hit(BlockPos pos, int kind, BlockState before) {}

    /** Opaque handle threaded from {@link #beforeBatch} to {@link #afterBatch} -- carries no
     *  shared mutable state, so this is safe to call concurrently from multiple free-running
     *  regions' own threads without any locking. Null means "nothing interesting in this batch,
     *  afterBatch is a no-op" -- checked once here instead of twice. */
    public static final class PendingAudit {
        private final long batchId;
        private final int batchSize;
        private final List<Hit> hits;
        private PendingAudit(long batchId, int batchSize, List<Hit> hits) {
            this.batchId = batchId; this.batchSize = batchSize; this.hits = hits;
        }
    }

    /** Called from {@link RegionThread#runFreeRunningTick()} right before {@code runOneTick()}
     *  executes the drained {@code scheduledWork} batch. Cheap in the common case (no watched
     *  position involved, or fewer than 2 watched positions in this batch): one list-contains
     *  check per {@code Tagged} entry, {@link BlockPos#of} is a cheap unpack, no allocation
     *  unless there's actually something to report. */
    public static PendingAudit beforeBatch(ServerLevel level, List<Runnable> batch) {
        // Exception-safety hardening (2026-09-03, "#3" investigation -- see project memory):
        // this diagnostic is now also called from RegionThread#drainScheduledTicksToCompletion(),
        // which runs BEFORE a save-rendezvous pause is honored (see WorldRegion
        // #nestworldCheckSaveRendezvous) -- an uncaught exception here would skip that pause
        // entirely for this iteration (or, since RegionThread#run()'s outer loop has no
        // surrounding try/catch, could kill the whole region thread), defeating the very fix
        // this diagnostic now sits inside of. Never let diagnostic code break gameplay or save
        // correctness -- same standing convention as every other diagnostic in this codebase.
        try {
            List<BlockPos> watched = BlockTickWatcher.positions();
            if (watched.isEmpty() || batch.size() < 2) return null;

            List<Hit> hits = null;
            for (Runnable r : batch) {
                if (!(r instanceof ScheduledTickHandoffMetrics.Tagged t)) continue;
                BlockPos p = BlockPos.of(t.pos);
                if (!watched.contains(p)) continue;
                if (hits == null) hits = new ArrayList<>(4);
                hits.add(new Hit(p, t.kind, level.getBlockState(p)));
            }
            if (hits == null || hits.size() < 2) return null; // need >=2 watched positions in ONE
            // batch to be relevant to the both=true question -- a single watched position tells
            // us nothing about same-batch ordering.
            return new PendingAudit(nextBatchId.getAndIncrement(), batch.size(), hits);
        } catch (Throwable t) {
            LOGGER.warn("beforeBatch() failed unexpectedly (diagnostic-only, no effect on "
                    + "scheduledWork's execution): {}", t.toString());
            return null;
        }
    }

    /** Called from {@link RegionThread#runFreeRunningTick()} right after {@code runOneTick()}
     *  returns. {@code audit} is whatever {@link #beforeBatch} returned for this same batch
     *  (null is the common case and a no-op). */
    public static void afterBatch(ServerLevel level, PendingAudit audit) {
        if (audit == null) return;
        // Exception-safety hardening -- see beforeBatch()'s comment; same reasoning applies here.
        try {
            StringBuilder sb = new StringBuilder();
            for (Hit h : audit.hits) {
                if (sb.length() > 0) sb.append(" | ");
                String kind = h.kind == ScheduledTickHandoffMetrics.Tagged.BLOCK ? "B"
                        : h.kind == ScheduledTickHandoffMetrics.Tagged.FLUID ? "F" : "O";
                BlockState after = level.getBlockState(h.pos);
                sb.append(h.pos.getX()).append(',').append(h.pos.getY()).append(',').append(h.pos.getZ())
                  .append('(').append(kind).append(')')
                  .append(" before=").append(h.before)
                  .append(" after=").append(after);
            }
            LOGGER.warn("BATCH#{} contained {} watched position(s) together in ONE scheduledWork batch "
                    + "(batch size={}), execution order = the order below: {}",
                    audit.batchId, audit.hits.size(), audit.batchSize, sb);
        } catch (Throwable t) {
            LOGGER.warn("afterBatch() failed unexpectedly (diagnostic-only, no effect on "
                    + "scheduledWork's execution): {}", t.toString());
        }
    }
}
