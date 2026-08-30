package net.nestworld.region;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11 #31.2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — diagnostics and per-player
 * sequencing/bookkeeping state for the SHADOW-MODE player-input mailbox. This class owns:
 * <ul>
 *   <li>The monotonic per-player sequence counter (main-thread-assigned, at capture time).</li>
 *   <li>Per-player pending-count (for the bounded-queue requirement — a disconnected or
 *       flooding client must not build an unbounded backlog).</li>
 *   <li>Per-player last-applied-sequence (for out-of-order/duplicate/gap detection at apply
 *       time).</li>
 *   <li>Aggregate counters (sent/applied/rerouted/dropped-stale-sequence/dropped-queue-full/
 *       gap-detected) surfaced via {@link #summary()} for {@code /nestworld playerinputstats}.</li>
 * </ul>
 * All state here is keyed by player UUID in {@link ConcurrentHashMap}s — main thread writes
 * (capture/dispatch), region threads read/write (apply-time validation) — this is exactly the
 * B-GLOBAL shape from the Phase 10 audit (docs/PHASE10_GLOBAL_STATE_AUDIT.md), correctly handled
 * with concurrent collections + atomics, not a mailbox redirect (this data isn't "owned" by any
 * one region, it's server-wide bookkeeping about a player's INPUT STREAM).
 */
public final class PlayerInputDiagnostics {
    private static final ConcurrentHashMap<UUID, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, AtomicLong> lastAppliedSequence = new ConcurrentHashMap<>();

    private static final AtomicLong totalSent = new AtomicLong();
    private static final AtomicLong totalApplied = new AtomicLong();
    private static final AtomicLong totalRerouted = new AtomicLong();
    private static final AtomicLong totalDroppedRerouteLimit = new AtomicLong();
    private static final AtomicLong totalDroppedQueueFull = new AtomicLong();
    private static final AtomicLong totalDroppedStaleOrDuplicate = new AtomicLong();
    private static final AtomicLong totalDroppedPlayerGone = new AtomicLong();
    private static final AtomicLong totalGapsDetected = new AtomicLong();

    /** Sum of all per-player {@code pendingCounts} right now -- total in-flight (sent, not yet
     *  applied/dropped) messages across every tracked player. Maintained incrementally alongside
     *  {@link #tryReserveSlot}/{@link #releaseSlot} rather than summed on read, so it's cheap to
     *  poll from {@code /nestworld playerinputstats} every tick if needed. */
    private static final AtomicInteger queueDepthCurrent = new AtomicInteger();
    /** High-water mark of {@link #queueDepthCurrent} since boot (or since the last {@code
     *  playertickexperiment}-style reset, if one is ever added) -- lets a live check distinguish
     *  "briefly touched the cap" from "sitting at the cap the whole time." */
    private static final AtomicInteger queueDepthMax = new AtomicInteger();

    private PlayerInputDiagnostics() {}

    /** Called by {@code PlayerInputDispatcher} on capture. Assigns the next sequence number for
     *  this player — always increasing, never reused, independent of apply order. */
    public static long nextSequence(UUID playerId) {
        return sequenceCounters.computeIfAbsent(playerId, k -> new AtomicLong()).incrementAndGet();
    }

    /** Returns {@code true} (and increments the pending count) if under {@link
     *  NestworldTuning#PLAYER_INPUT_QUEUE_LIMIT_PER_PLAYER}; {@code false} (and counts a
     *  queue-full drop) otherwise — caller must not enqueue the message if this returns false.
     *  Purely for bounded-queue accounting — does NOT touch {@link #recordSent}'s counter, so
     *  calling this again on a reroute (same logical input, re-sent to a new destination)
     *  doesn't inflate the "how many distinct inputs were captured" total. */
    public static boolean tryReserveSlot(UUID playerId) {
        AtomicInteger count = pendingCounts.computeIfAbsent(playerId, k -> new AtomicInteger());
        int updated = count.incrementAndGet();
        if (updated > NestworldTuning.PLAYER_INPUT_QUEUE_LIMIT_PER_PLAYER) {
            count.decrementAndGet();
            totalDroppedQueueFull.incrementAndGet();
            return false;
        }
        int depth = queueDepthCurrent.incrementAndGet();
        queueDepthMax.updateAndGet(v -> Math.max(v, depth));
        return true;
    }

    /** Called exactly once per originally-captured input, at the point of first dispatch (not
     *  on reroute re-sends — see {@link #tryReserveSlot}'s note). */
    public static void recordSent() {
        totalSent.incrementAndGet();
    }

    /** Called once a message leaves the pending state, however it resolves (applied, dropped,
     *  or being re-sent after a reroute — reroute keeps holding a slot, see the reroute path in
     *  {@code PlayerInputDispatcher.applyQueued}, which reserves again before re-sending). */
    public static void releaseSlot(UUID playerId) {
        AtomicInteger count = pendingCounts.get(playerId);
        if (count != null) {
            int before = count.getAndUpdate(v -> Math.max(0, v - 1));
            if (before > 0) queueDepthCurrent.decrementAndGet();
        }
    }

    /** Apply-time validation: records the outcome for a sequence number being applied on {@code
     *  applyingRegion} (already confirmed to be the CURRENT owner by the caller). Returns a
     *  one-word classification for logging: "OK", "DUPLICATE", "STALE", or "GAP" (GAP still
     *  applies — a gap means input was lost/dropped upstream, not that this one is invalid). */
    public static String recordApply(UUID playerId, long sequence) {
        AtomicLong last = lastAppliedSequence.computeIfAbsent(playerId, k -> new AtomicLong(0));
        long previous = last.get();
        if (sequence <= previous) {
            totalDroppedStaleOrDuplicate.incrementAndGet();
            return sequence == previous ? "DUPLICATE" : "STALE";
        }
        boolean gap = sequence != previous + 1;
        if (gap) totalGapsDetected.incrementAndGet();
        // Only advance if this is still the highest applied — concurrent applies for the same
        // player should not happen (a player belongs to one region's mailbox drain at a time
        // per tick), but compareAndSet keeps this correct even if that assumption is ever wrong.
        last.updateAndGet(v -> Math.max(v, sequence));
        totalApplied.incrementAndGet();
        return gap ? "GAP" : "OK";
    }

    public static void recordRerouted() {
        totalRerouted.incrementAndGet();
    }

    public static void recordDroppedRerouteLimit() {
        totalDroppedRerouteLimit.incrementAndGet();
    }

    public static void recordDroppedPlayerGone() {
        totalDroppedPlayerGone.incrementAndGet();
    }

    /** Called on player disconnect ({@code PlayerList.remove()}) — clears all per-player state
     *  so a reconnecting player (same UUID) starts a fresh sequence, and so a disconnected
     *  player's bookkeeping doesn't leak forever. */
    public static void cleanup(UUID playerId) {
        sequenceCounters.remove(playerId);
        // Any in-flight messages still owed a releaseSlot() call (already sent, sitting in a
        // region mailbox) would find pendingCounts.get(playerId) == null after this remove() and
        // silently fail to decrement queueDepthCurrent -- drain that player's remaining reserved
        // depth here so the aggregate gauge doesn't drift on disconnect-during-handover.
        AtomicInteger remaining = pendingCounts.remove(playerId);
        if (remaining != null && remaining.get() > 0) {
            queueDepthCurrent.addAndGet(-remaining.get());
        }
        lastAppliedSequence.remove(playerId);
    }

    public static String summary() {
        return String.format(
                "sent=%,d applied=%,d rerouted=%,d dropped(rerouteLimit=%,d queueFull=%,d staleOrDup=%,d playerGone=%,d) gaps=%,d trackedPlayers=%,d queueDepthCurrent=%,d queueDepthMax=%,d",
                totalSent.get(), totalApplied.get(), totalRerouted.get(),
                totalDroppedRerouteLimit.get(), totalDroppedQueueFull.get(),
                totalDroppedStaleOrDuplicate.get(), totalDroppedPlayerGone.get(),
                totalGapsDetected.get(), sequenceCounters.size(), queueDepthCurrent.get(), queueDepthMax.get());
    }
}
