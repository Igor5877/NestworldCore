package net.nestworld.region;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API-1B.7 TEMP INSTRUMENTATION (2026-10-01) -- runtime evidence for setSlot's NestWorld peer.
 * Same status as the {@code [API1A7]} phase-trace logging: diagnostic only, never participates
 * in routing/arbitration, to be removed or promoted once API-1B's verification gate closes.
 *
 * <p>Fed exclusively from {@code EntityMutationDispatcher.applyDirect}'s SetInventorySlot case.
 * Global counters are always on (a few AtomicLong increments per apply). Per-intent bookkeeping
 * (which needs a map keyed by intent) only runs while {@link #PER_INTENT} is true -- a test
 * switches it on, so production memory can never grow from this class.
 *
 * <p>Invariant counters, computed INDEPENDENTLY of the trackers they audit (so a tracker bug
 * cannot hide itself):
 * <ul>
 *   <li>{@code duplicateApply} -- the same intent (player, slot, incarnation, version) mutated
 *       the inventory more than once.</li>
 *   <li>{@code staleIntentApply} -- a mutation landed whose version was not strictly greater
 *       than one already applied for the same (player, slot, incarnation).</li>
 *   <li>{@code wrongIncarnationMutation} -- the identity token of the object just mutated,
 *       re-derived AFTER setItem, differs from the request's token. Impossible by construction
 *       of PlayerIncarnationRegistry; a nonzero value means a registry bug. Fixture E
 *       additionally checks this at the test level by raw object reference.</li>
 * </ul>
 */
final class InventorySetTelemetry {
    private InventorySetTelemetry() {}

    static volatile boolean PER_INTENT = false;

    static final AtomicLong applied = new AtomicLong();
    static final AtomicLong incarnationMismatch = new AtomicLong();
    static final AtomicLong staleDropped = new AtomicLong();
    static final AtomicLong notPlayer = new AtomicLong();
    static final AtomicLong targetRemoved = new AtomicLong();
    static final AtomicLong targetNotFound = new AtomicLong();      // applyQueued: UUID no longer resolves
    static final AtomicLong rerouteLimitDropped = new AtomicLong(); // applyQueued: hop limit reached
    static final AtomicLong redeliveryLimitDropped = new AtomicLong(); // CORE-MAILBOX-MERGE-001: orphan redelivery cap
    static final AtomicLong maxRerouteHop = new AtomicLong();       // highest hop count any SetInventorySlot reached
    static volatile String lastApplyThread = "none";
    static final AtomicLong backendTargetNotFound = new AtomicLong(); // Core peer: player offline before dispatch
    static final AtomicLong wrongOwnerMutation = new AtomicLong();
    static final AtomicLong callerObjectLeak = new AtomicLong();
    /** Stress only: token -> the object it was stamped against at submission (weak), recorded by the
     *  harness itself so the identity check does not trust PlayerIncarnationRegistry. */
    static final ConcurrentHashMap<Long, java.lang.ref.WeakReference<Object>> TOKEN_OBJECT = new ConcurrentHashMap<>();
    /** Stress only: identity set of the caller-owned ItemStacks passed to setSlot. */
    static volatile java.util.Set<Object> CALLER_ORIGINALS = null;
    /** Stress only: per (player, slot, incarnation) the "nwseq" tag of the highest version applied. */
    private static final ConcurrentHashMap<SlotIncKey, long[]> MAX_APPLIED_SEQ = new ConcurrentHashMap<>();
    static final AtomicLong tryAcceptCalls = new AtomicLong();
    static final AtomicLong duplicateApply = new AtomicLong();
    static final AtomicLong staleIntentApply = new AtomicLong();
    static final AtomicLong wrongIncarnationMutation = new AtomicLong();

    record IntentKey(UUID playerId, int slot, long incarnation, long version) {}
    record SlotIncKey(UUID playerId, int slot, long incarnation) {}

    private static final ConcurrentHashMap<IntentKey, AtomicLong> APPLY_COUNT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IntentKey, AtomicLong> TRY_ACCEPT_COUNT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SlotIncKey, Long> MAX_APPLIED = new ConcurrentHashMap<>();

    static void onTryAccept(UUID playerId, int slot, long incarnation, long version) {
        tryAcceptCalls.incrementAndGet();
        if (PER_INTENT) {
            TRY_ACCEPT_COUNT.computeIfAbsent(new IntentKey(playerId, slot, incarnation, version), k -> new AtomicLong()).incrementAndGet();
        }
    }

    static void onApplied(UUID playerId, int slot, long incarnation, long version, long incarnationAfter,
                          Object mutatedPlayer, net.minecraft.world.item.ItemStack stored) {
        applied.incrementAndGet();
        lastApplyThread = Thread.currentThread().getName();
        java.lang.ref.WeakReference<Object> stamped = TOKEN_OBJECT.get(incarnation);
        if (incarnationAfter != incarnation || (stamped != null && stamped.get() != mutatedPlayer)) {
            wrongIncarnationMutation.incrementAndGet();
        }
        java.util.Set<Object> originals = CALLER_ORIGINALS;
        if (originals != null && originals.contains(stored)) callerObjectLeak.incrementAndGet();
        if (!PER_INTENT) return;
        IntentKey k = new IntentKey(playerId, slot, incarnation, version);
        if (APPLY_COUNT.computeIfAbsent(k, x -> new AtomicLong()).incrementAndGet() > 1) {
            duplicateApply.incrementAndGet();
        }
        SlotIncKey sk = new SlotIncKey(playerId, slot, incarnation);
        Long prev = MAX_APPLIED.get(sk);
        if (prev != null && version <= prev) staleIntentApply.incrementAndGet();
        MAX_APPLIED.merge(sk, version, Math::max);
        long seq = stored.hasTag() ? stored.getTag().getLong("nwseq") : -1;
        MAX_APPLIED_SEQ.merge(sk, new long[]{version, seq}, (a, b) -> a[0] >= b[0] ? a : b);
    }

    static long applyCount(UUID playerId, int slot, long incarnation, long version) {
        AtomicLong c = APPLY_COUNT.get(new IntentKey(playerId, slot, incarnation, version));
        return c == null ? 0 : c.get();
    }

    static long tryAcceptCount(UUID playerId, int slot, long incarnation, long version) {
        AtomicLong c = TRY_ACCEPT_COUNT.get(new IntentKey(playerId, slot, incarnation, version));
        return c == null ? 0 : c.get();
    }

    /** Stress only: nwseq of the highest version applied in this domain, or null. */
    static Long maxAppliedSeq(UUID playerId, int slot, long incarnation) {
        long[] v = MAX_APPLIED_SEQ.get(new SlotIncKey(playerId, slot, incarnation));
        return v == null ? null : v[1];
    }

    static String invariantLine() {
        return "applied=" + applied.get() + " incarnationMismatch=" + incarnationMismatch.get()
                + " staleDropped=" + staleDropped.get() + " targetRemoved=" + targetRemoved.get() + " targetNotFound=" + targetNotFound.get()
                + " rerouteLimitDropped=" + rerouteLimitDropped.get() + " maxRerouteHop=" + maxRerouteHop.get() + " notPlayer=" + notPlayer.get()
                + " tryAcceptCalls=" + tryAcceptCalls.get()
                + " backendTargetNotFound=" + backendTargetNotFound.get()
                + " | wrongIncarnationMutation=" + wrongIncarnationMutation.get() + " wrongOwnerMutation=" + wrongOwnerMutation.get()
                + " callerObjectLeak=" + callerObjectLeak.get()
                + " duplicateApply=" + duplicateApply.get()
                + " staleIntentApply=" + staleIntentApply.get();
    }

    /** Test-only: start a fixture from a clean slate. */
    static void reset() {
        applied.set(0); incarnationMismatch.set(0); staleDropped.set(0); notPlayer.set(0); targetRemoved.set(0);
        targetNotFound.set(0); rerouteLimitDropped.set(0); redeliveryLimitDropped.set(0); maxRerouteHop.set(0); lastApplyThread = "none";
        backendTargetNotFound.set(0); wrongOwnerMutation.set(0); callerObjectLeak.set(0);
        TOKEN_OBJECT.clear(); CALLER_ORIGINALS = null; MAX_APPLIED_SEQ.clear();
        tryAcceptCalls.set(0); duplicateApply.set(0); staleIntentApply.set(0); wrongIncarnationMutation.set(0);
        APPLY_COUNT.clear(); TRY_ACCEPT_COUNT.clear(); MAX_APPLIED.clear();
    }
}
