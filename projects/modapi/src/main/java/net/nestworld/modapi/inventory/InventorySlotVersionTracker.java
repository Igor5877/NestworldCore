package net.nestworld.modapi.inventory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API-1B.6 (revised 2026-10-01): per-{@code (playerId, slot, incarnation)} LATEST_INTENT_WINS
 * version tracking for {@code setSlot} -- a SEPARATE class from
 * {@code net.nestworld.modapi.entity.SetVelocityIntentTracker}, deliberately not generalized.
 *
 * <pre>
 * Target identity:   (playerId, slot)                 -- InventoryTargetKey, unchanged
 * Execution validity: request.playerIncarnation == incarnationOf(object being mutated)
 *                                                      -- checked by the CALLER, before this class
 * Temporal domain:   (playerId, slot, incarnation)    -- this class's key
 * </pre>
 *
 * <p><b>Why the incarnation is in the temporal domain</b> (and why there is no lifecycle reset
 * any more): with versions scoped per incarnation, an old object's V1000 and a new object's V1
 * live in different domains and can never be compared. Isolation follows from the key alone --
 * no {@code bump}/{@code resetForPlayer} step has to happen at the right moment. The previous
 * design needed exactly that reset to be correct, and its trigger (a Forge event) fired after the
 * new object was already reachable (API-1B.7 fixture E).
 *
 * <p>Incarnation is still NOT part of target identity: callers address (player, slot); the
 * incarnation is a property of the request, captured at submission.
 *
 * <p>The caller must still reject a mismatched incarnation BEFORE calling {@link #tryAccept}.
 * With per-incarnation domains that ordering is no longer load-bearing for isolation (a
 * stale-incarnation tryAccept would only touch its own dead domain), but it keeps the
 * observable contract simple: a stale-incarnation request leaves no trace in this tracker.
 *
 * <p>Threading: safe from any thread (ConcurrentHashMap-backed).
 *
 * <p>Memory: a domain is dropped by {@link #forgetIncarnation} once the registry observes that
 * incarnation's object was garbage collected (it can never validate again), and wholesale by
 * {@link #resetAll()} on server stop. Neither is a correctness mechanism.
 */
public final class InventorySlotVersionTracker {

    /** The temporal domain: one target slot of one specific incarnation. */
    record TemporalKey(UUID playerId, int slot, long incarnation) {
        static TemporalKey of(InventoryTargetKey target, long incarnation) {
            return new TemporalKey(target.playerId(), target.slot(), incarnation);
        }
    }

    private static final ConcurrentHashMap<TemporalKey, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TemporalKey, Long> LAST_APPLIED = new ConcurrentHashMap<>();

    private InventorySlotVersionTracker() {}

    /** Submission-time linearization point within one (player, slot, incarnation) domain.
     *  Public for the deterministic test harness, which needs to control submission order. */
    public static long assignVersion(InventoryTargetKey target, long incarnation) {
        return COUNTERS.computeIfAbsent(TemporalKey.of(target, incarnation), k -> new AtomicLong()).incrementAndGet();
    }

    /** Immediately before the mutation, after the caller confirmed the incarnation. Public
     *  because the NestWorld peer ({@code EntityMutationDispatcher}) lives in another package. */
    public static boolean tryAccept(InventoryTargetKey target, long incarnation, long version) {
        Long updated = LAST_APPLIED.compute(TemporalKey.of(target, incarnation), (k, current) ->
                (current == null || version > current) ? version : current);
        return updated == version;
    }

    /** Read-only diagnostic: last accepted version in this domain, or null. */
    public static Long peekLastApplied(InventoryTargetKey target, long incarnation) {
        return LAST_APPLIED.get(TemporalKey.of(target, incarnation));
    }

    /** Read-only diagnostic: highest version handed out in this domain, or 0. */
    public static long peekAssigned(InventoryTargetKey target, long incarnation) {
        AtomicLong c = COUNTERS.get(TemporalKey.of(target, incarnation));
        return c == null ? 0L : c.get();
    }

    /** Read-only diagnostic: number of live temporal domains for a player (all incarnations). */
    public static int domainCount(UUID playerId) {
        int n = 0;
        for (TemporalKey k : COUNTERS.keySet()) if (k.playerId().equals(playerId)) n++;
        return n;
    }

    /** Memory cleanup only -- called by {@link PlayerIncarnationRegistry} for a token whose
     *  object is gone (the token can never validate again). */
    static void forgetIncarnation(UUID playerId, long incarnation) {
        COUNTERS.keySet().removeIf(k -> k.incarnation() == incarnation && k.playerId().equals(playerId));
        LAST_APPLIED.keySet().removeIf(k -> k.incarnation() == incarnation && k.playerId().equals(playerId));
    }

    /** Server-stop cleanup, wired next to SetVelocityIntentTracker.resetAll(). */
    public static void resetAll() {
        COUNTERS.clear();
        LAST_APPLIED.clear();
    }
}
