package net.nestworld.region;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Visibility/Pairing Admission Gate -- Step 3 (ТЗ, 2026-08-29), responding to Step 2's finding
 * (see project memory burst-admission-step2-budget-sweep-2026-08-29.md): join-side admission
 * works correctly but is NOT sufficient alone -- the real untouched bottleneck is the O(N^2)
 * addPairing/mutual-visibility burst (confirmed ~39,800 = 200x199 pending pairing operations),
 * which overwhelms {@link PairingOutboundQueue}'s fixed 200/tick DRAIN budget regardless of how
 * gently arrivals are paced. This class adds a SECOND, upstream gate: it controls how many NEW
 * visibility relationships (ADD transitions) are allowed to even be CREATED per tick, not just
 * how fast already-created ones are delivered.
 *
 * <p>Deliberately reuses {@link PairingOutboundQueue}'s existing three-state model (nothing
 * pending / ADD pending / REMOVE pending, via {@code currentOp()}) instead of inventing a
 * parallel NOT_VISIBLE/ADD_PENDING/VISIBLE/REMOVE_PENDING state machine of its own -- that state
 * already exists at the call site (see {@code ChunkMap.TrackedEntity.updatePlayer()}); this gate
 * is a single extra precondition on the NOT_VISIBLE -&gt; ADD_PENDING transition specifically
 * (the {@code enqueueAdd()} call), never on REMOVE (removals must never be delayed -- that would
 * risk stale/ghost entity visibility, a correctness regression, not just a performance one).
 *
 * <p>No separate backlog/queue is needed for admission itself: vanilla's {@code
 * ChunkMap.move()} tracking scan already re-evaluates every (entity, nearby player) pair EVERY
 * tick unconditionally (confirmed by reading {@code updatePlayer()} -- {@code flag} is recomputed
 * fresh on every call, and {@code PairingOutboundQueue}'s own comments confirm the same pair gets
 * re-visited tick after tick while in range). A denied admission this tick is retried for free by
 * that same natural loop next tick -- no new pending-list bookkeeping, no new correctness surface
 * beyond the budget check itself.
 *
 * <p>Two-tier budget per the user's explicit requirement: a GLOBAL per-tick cap (total new
 * visibility relationships admitted, across all players) and a PER-PLAYER per-tick cap (so one
 * player entering a dense crowd can't consume the entire global budget and starve everyone else's
 * admissions that tick). Both self-reset lazily based on the server tick counter read at the call
 * site -- no separate {@code onTick()} hook needed in {@code MinecraftServer}, keeping the vanilla
 * edit surface to a single wrapped call in {@code ChunkMap.java}.
 *
 * <p>Main-thread-only (the {@code ChunkMap} tracking scan is main-thread-authoritative, same
 * assumption as {@link PairingOutboundQueue}) -- plain fields, no atomics/locks.
 */
public final class PairingAdmissionGate {
    private PairingAdmissionGate() {}

    public static volatile boolean ENABLED = false;
    public static volatile int GLOBAL_BUDGET_PER_TICK = 64;
    public static volatile int PER_PLAYER_BUDGET_PER_TICK = 8;

    private static long currentTick = -1;
    private static int globalAdmittedThisTick = 0;
    private static final Map<UUID, Integer> perPlayerAdmittedThisTick = new HashMap<>();

    private static long admittedTotal = 0;
    private static long deniedTotal = 0;
    private static long deniedGlobalCap = 0;
    private static long deniedPerPlayerCap = 0;
    private static int maxGlobalUsedEver = 0;

    /** Called from {@code ChunkMap.TrackedEntity.updatePlayer()} right before what would
     *  otherwise be an unconditional {@code PairingOutboundQueue.enqueueAdd()} call. Returns true
     *  if the caller should proceed with {@code enqueueAdd()} now; false if this tick's budget
     *  (global or per-player) is exhausted -- the caller does nothing further this tick, and
     *  vanilla's own per-tick re-evaluation of this same pair retries it automatically later. */
    public static boolean tryAdmit(long tick, UUID playerId) {
        if (!ENABLED) return true;
        if (tick != currentTick) {
            currentTick = tick;
            globalAdmittedThisTick = 0;
            perPlayerAdmittedThisTick.clear();
        }
        if (globalAdmittedThisTick >= GLOBAL_BUDGET_PER_TICK) {
            deniedTotal++;
            deniedGlobalCap++;
            return false;
        }
        int playerCount = perPlayerAdmittedThisTick.getOrDefault(playerId, 0);
        if (playerCount >= PER_PLAYER_BUDGET_PER_TICK) {
            deniedTotal++;
            deniedPerPlayerCap++;
            return false;
        }
        globalAdmittedThisTick++;
        perPlayerAdmittedThisTick.put(playerId, playerCount + 1);
        admittedTotal++;
        if (globalAdmittedThisTick > maxGlobalUsedEver) maxGlobalUsedEver = globalAdmittedThisTick;
        return true;
    }

    public static void reset() {
        currentTick = -1;
        globalAdmittedThisTick = 0;
        perPlayerAdmittedThisTick.clear();
        admittedTotal = 0;
        deniedTotal = 0;
        deniedGlobalCap = 0;
        deniedPerPlayerCap = 0;
        maxGlobalUsedEver = 0;
    }

    public static String report() {
        return String.format(
                "NW pairing-admission: enabled=%s globalBudget=%d perPlayerBudget=%d | admitted=%,d "
                        + "denied=%,d (globalCap=%,d perPlayerCap=%,d) | maxGlobalUsedInOneTick=%d",
                ENABLED, GLOBAL_BUDGET_PER_TICK, PER_PLAYER_BUDGET_PER_TICK, admittedTotal,
                deniedTotal, deniedGlobalCap, deniedPerPlayerCap, maxGlobalUsedEver);
    }
}
