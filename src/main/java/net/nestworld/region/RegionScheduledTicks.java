package net.nestworld.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 2.1 (docs/SCHEDULED_TICK_COMPATIBILITY_CONTRACT.md #1/#2) -- lossless, append-only
 * per-region queue for BLOCK/FLUID scheduled ticks specifically, replacing the "latest batch
 * wins" {@code AtomicReference} handoff ({@link WorldRegion#pendingFreeRunningWork}) for this
 * one phase only. {@link RegionPhase#RANDOM_TICK}/{@link RegionPhase#BLOCK_EVENT} keep using the
 * existing mailbox unchanged -- that "may skip a round, self-heals next tick" tradeoff was
 * accepted deliberately for those (block entities are re-collected every tick; a skipped round
 * isn't a lost one-shot event the way a scheduled tick is).
 *
 * <p>Ordering: vanilla's own {@code LevelTicks.tick()} already sorts due entries by
 * {@code TickPriority} then {@code subTickOrder} (FIFO within a priority tier) before invoking
 * our {@code routeScheduledTick} callback once per entry, in that order -- see the callback
 * signature in {@code LevelTicks#runCollectedTicks} ({@code BiConsumer<BlockPos, T>}, no
 * priority/order info passed through). We therefore don't need our own priority field: a plain
 * FIFO queue that appends each posted batch to the tail and only ever drains from the head
 * preserves vanilla's relative ordering both within one batch (already correctly ordered when
 * handed to us) and across batches (an earlier real tick's due entries were always handed to us
 * before a later tick's, so appending preserves "earlier tick's entries drain first" too).
 *
 * <p>Single-producer (main thread, via {@link #offerAll}), single-consumer (this region's own
 * free-running thread, via {@link #drainAll}) per instance -- {@code synchronized} is simple and
 * correct for that shape; this is not a hot per-entity path (touched once per
 * {@link RegionPhase#SCHEDULED_TICK} work-round per region, not per entity).
 *
 * <p>Dedup index (contract #3/#4) is tracked here as groundwork -- {@link #isPending} -- but is
 * NOT YET consulted by vanilla's {@code hasScheduledTick}/{@code willTickThisTick} (that
 * redirect is Phase 2.2, a separate vanilla-patch change per the contract's #4 table of 8
 * affected classes). Populating and maintaining it now, rather than bolting it on later, avoids
 * a second pass through every call site that mutates {@link #pending}.
 */
public final class RegionScheduledTicks {

    private record Key(long pos, int kind) {}

    private final ArrayDeque<ScheduledTickHandoffMetrics.Tagged> pending = new ArrayDeque<>();
    private final Set<Key> dedupIndex = new HashSet<>();

    /** Called from the main thread only (RegionThreadPool.runWorkRound). Appends -- never
     *  overwrites -- so nothing already pending is ever discarded by a later post. */
    public synchronized void offerAll(List<Runnable> batch) {
        for (Runnable r : batch) {
            if (r instanceof ScheduledTickHandoffMetrics.Tagged t) {
                pending.addLast(t);
                dedupIndex.add(new Key(t.pos, t.kind));
            }
        }
    }

    /** Called from this region's own free-running thread only; non-blocking. Drains
     *  everything currently pending -- every entry here already passed vanilla's own
     *  {@code triggerTick <= gameTime} check before being routed to us, so there is no
     *  additional due-filtering to do on our side.
     *
     *  <p>Deliberately does NOT touch {@link #dedupIndex} here (see {@link #markExecuted}):
     *  {@code RegionThread#runWorkBudgeted} may not finish running everything in one local
     *  tick if the batch exceeds {@code REGION_ENTITY_BUDGET_NANOS} -- the remainder is
     *  deferred (carried over to the next local tick), NOT lost, but if we cleared the dedup
     *  index here, an entry sitting in that carry-over would look "not scheduled" to vanilla
     *  for a real window between drain and actual execution, defeating the whole point of
     *  Phase 2.2. The index must stay accurate until an entry has genuinely run. */
    public synchronized List<Runnable> drainAll() {
        if (pending.isEmpty()) return List.of();
        List<Runnable> out = new ArrayList<>(pending.size());
        ScheduledTickHandoffMetrics.Tagged t;
        while ((t = pending.pollFirst()) != null) {
            out.add(t);
        }
        return out;
    }

    /** Called once a drained entry has actually run (from any thread that executes it --
     *  today always this region's own thread, via {@code RegionThread#runWorkBudgeted}).
     *  This, not {@link #drainAll}, is what keeps the dedup index truthful across a
     *  budget-limited carry-over. */
    public synchronized void markExecuted(long pos, int kind) {
        dedupIndex.remove(new Key(pos, kind));
    }

    /** Live backlog size -- contract #8, "no silent drop; if backlog can't be drained, it must
     *  be observable." Safe to call from any thread. */
    public synchronized int size() {
        return pending.size();
    }

    /** Diagnostic only (2026-09-03, "#1 ~4s freeze" investigation): renders up to
     *  {@code maxEntries} pending positions without draining or otherwise mutating anything --
     *  for logging what, specifically, is sitting un-drained at a free-running demote decision.
     *  Safe to call from any thread (same lock as every other method here). */
    public synchronized String diagnosticSnapshot(int maxEntries) {
        if (pending.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ScheduledTickHandoffMetrics.Tagged t : pending) {
            if (n > 0) sb.append(", ");
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(t.pos);
            String kind = t.kind == ScheduledTickHandoffMetrics.Tagged.BLOCK ? "B"
                    : t.kind == ScheduledTickHandoffMetrics.Tagged.FLUID ? "F" : "O";
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('(').append(kind).append(')');
            if (++n >= maxEntries) { sb.append(", ..."); break; }
        }
        return sb.toString();
    }

    /** Groundwork for the Phase 2.2 {@code hasScheduledTick}/{@code willTickThisTick} redirect --
     *  not yet wired to those vanilla call sites. */
    public synchronized boolean isPending(long pos, int kind) {
        return dedupIndex.contains(new Key(pos, kind));
    }
}
