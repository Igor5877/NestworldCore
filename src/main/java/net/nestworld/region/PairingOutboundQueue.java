package net.nestworld.region;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pairing Outbound Queue (ТЗ, 2026-08-29) -- budgets and batches {@code ServerEntity.addPairing()}
 * / {@code removePairing()}'s network delivery, the confirmed O(N^2) remaining contributor to the
 * join/teleport burst (see project memory addpairing-burst-attribution-2026-08-29.md: PLAYER-
 * sourced pairing calls measured EXACTLY N*(N-1) for n=50-150). Sibling to
 * {@link ChunkOutboundQueue} (same philosophy: main-thread DECIDES who-sees-whom unchanged, this
 * layer only controls WHEN the resulting packets physically reach the network) and
 * {@link OutboundBatchQueue} (pairing packets are injected into ITS per-connection queue, not
 * sent directly -- so a login/teleport burst's pairing packets bundle together with same-tick
 * entity-tracking updates into as few physical sends as possible, per the ТЗ's §15/16).
 *
 * <p><b>Critical ordering invariant</b> (why {@code seenBy.add()} is deferred too, not just the
 * packet): {@code ChunkMap$TrackedEntity.broadcast()} sends steady-state updates to every
 * recipient in {@code seenBy}. If {@code seenBy.add(player)} happened immediately (vanilla
 * timing) while the ACTUAL spawn packet sits in this budget-throttled queue for several ticks,
 * {@code broadcast()} would start sending "update this entity" packets to a player who was never
 * told the entity exists yet -- a protocol-breaking reorder. So this queue defers
 * {@code seenBy.add()}/{@code startSeenByPlayer()}/the Forge tracking-start event together WITH
 * the packet, executing all of them atomically at drain time (see {@link ChunkMap#nestworldDeferredAddPairing}).
 * REMOVE is the mirror but with a relaxed invariant: {@code seenBy.remove()} still happens
 * IMMEDIATELY (stops future broadcasts right away, matching vanilla), only the actual "forget this
 * entity" packet is deferred -- a removed-but-not-yet-told-client entity is at worst a harmless,
 * briefly-stale render, not a protocol violation, and vanilla network latency already produces
 * the same class of staleness today.
 *
 * <p><b>ADD-then-REMOVE-before-drain cancellation</b> (§5/§6): a single-slot-per-key map
 * ({@code byPlayerEntityKey}) always points at the CURRENT pending entry for a (player, entity)
 * pair -- enqueuing a new entry for an existing key marks the old one cancelled (lazy-skipped at
 * drain, matching {@link ChunkOutboundQueue}'s pattern), which is how this implements the ТЗ's
 * "generation/version protection" (§7) without a separate integer counter: the map can only ever
 * point at the latest decision for that key, so a stale queued entry is unreachable from any live
 * lookup and gets skipped when the drain loop eventually polls it.
 *
 * <p><b>Bulk cancellation</b> (§10 disconnect, §11 entity death): two secondary indices,
 * {@code byEntityId} and {@code byPlayerId}, hold the CURRENT pending set for a given entity or
 * player respectively, so "cancel everything for this player" (disconnect) or "cancel everything
 * for this entity" (death/despawn) is O(pending-for-that-key), not a scan of the whole queue.
 *
 * <p>Feature-flagged, default OFF (highest correctness surface of the three outbound queues so
 * far -- entity pairing interacts with {@code seenBy}, unlike chunk tracking). RCON:
 * {@code /nestworld pairingbatch on|off|status}.
 */
public final class PairingOutboundQueue {
    private PairingOutboundQueue() {}

    public static volatile boolean ENABLED = false;
    /** Per the ТЗ's explicit "не вигадувати N" instruction -- a measurement-sweep starting
     *  point, not a chosen final value. Limits OPERATION COUNT per tick, never a time budget
     *  (§14) -- a naive "drain while queue non-empty" loop would reproduce the exact 39,800-
     *  pairings-in-one-tick problem this class exists to fix. */
    public static volatile int BUDGET_PER_TICK = 200;

    public enum Op { ADD, REMOVE }

    private static final class PendingPairing {
        final ChunkMap chunkMap;
        final ServerPlayer player;
        final Entity entity;
        final Op op;
        final String key;
        volatile boolean cancelled;

        PendingPairing(ChunkMap chunkMap, ServerPlayer player, Entity entity, Op op) {
            this.chunkMap = chunkMap;
            this.player = player;
            this.entity = entity;
            this.op = op;
            this.key = key(player.getUUID(), entity.getId());
        }
    }

    private static String key(UUID playerId, int entityId) {
        return playerId + ":" + entityId;
    }

    private static final Queue<PendingPairing> drainQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, PendingPairing> byPlayerEntityKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Set<PendingPairing>> byEntityId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<PendingPairing>> byPlayerId = new ConcurrentHashMap<>();

    private static final LongAdder addEnqueued = new LongAdder();
    private static final LongAdder removeEnqueued = new LongAdder();
    private static final LongAdder addDrained = new LongAdder();
    private static final LongAdder removeDrained = new LongAdder();
    private static final LongAdder cancelledCount = new LongAdder();
    private static final LongAdder staleAtDrain = new LongAdder();
    private static final LongAdder sendFailures = new LongAdder();
    private static final LongAdder drainTimeNanos = new LongAdder();
    private static volatile int maxObservedQueueSize = 0;

    /** Called from {@code ChunkMap$TrackedEntity.updatePlayer()}'s ADD branch (and
     *  {@code playerLoadedChunk()}'s equivalent) INSTEAD of {@code seenBy.add()} +
     *  {@code addPairing()} directly. */
    public static void enqueueAdd(ChunkMap chunkMap, ServerPlayer player, Entity entity) {
        PendingPairing p = new PendingPairing(chunkMap, player, entity, Op.ADD);
        install(p);
        addEnqueued.increment();
    }

    /** The Op of the CURRENT pending entry for this (player, entity) key, or null if nothing
     *  is pending -- lets callers distinguish "nothing queued" / "ADD queued" / "REMOVE queued"
     *  before deciding how to react to a fresh vanilla visibility decision, instead of blindly
     *  cancelling whatever happens to be there (which could wrongly cancel a pending REMOVE
     *  when the caller only meant to cancel a stale ADD, or vice versa). */
    public static Op currentOp(ServerPlayer player, Entity entity) {
        if (!ENABLED) return null;
        PendingPairing p = byPlayerEntityKey.get(key(player.getUUID(), entity.getId()));
        return p == null ? null : p.op;
    }

    /** Cancels the pending entry for this (player, entity) key ONLY if its Op matches
     *  {@code expectedOp} -- defensive against wrongly cancelling the "other" pending op
     *  (see {@link #currentOp}'s javadoc). Returns true if something was actually cancelled. */
    public static boolean cancelIfOp(ServerPlayer player, Entity entity, Op expectedOp) {
        if (!ENABLED) return false;
        String k = key(player.getUUID(), entity.getId());
        PendingPairing p = byPlayerEntityKey.get(k);
        if (p == null || p.op != expectedOp) return false;
        if (byPlayerEntityKey.remove(k, p)) {
            p.cancelled = true;
            cancelledCount.increment();
            removeFromSecondary(p);
            return true;
        }
        return false;
    }

    /** Called when a REMOVE arrives for a (player, entity) whose ADD ALREADY drained (i.e.
     *  {@code seenBy.remove()} just succeeded) -- the caller already removed from seenBy
     *  immediately; this only defers the actual "forget entity" packet. */
    public static void enqueueRemove(ChunkMap chunkMap, ServerPlayer player, Entity entity) {
        PendingPairing p = new PendingPairing(chunkMap, player, entity, Op.REMOVE);
        install(p);
        removeEnqueued.increment();
    }

    private static void install(PendingPairing p) {
        PendingPairing prior = byPlayerEntityKey.put(p.key, p);
        if (prior != null) {
            prior.cancelled = true;
            cancelledCount.increment();
            removeFromSecondary(prior);
        }
        drainQueue.add(p);
        byEntityId.computeIfAbsent(p.entity.getId(), k -> ConcurrentHashMap.newKeySet()).add(p);
        byPlayerId.computeIfAbsent(p.player.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(p);
        int sz = byPlayerEntityKey.size();
        if (sz > maxObservedQueueSize) maxObservedQueueSize = sz;
    }

    private static void removeFromSecondary(PendingPairing p) {
        Set<PendingPairing> byE = byEntityId.get(p.entity.getId());
        if (byE != null) byE.remove(p);
        Set<PendingPairing> byP = byPlayerId.get(p.player.getUUID());
        if (byP != null) byP.remove(p);
    }

    /** §11: cancel every pending pairing (any recipient) for an entity that just died/despawned. */
    public static void cancelAllForEntity(int entityId) {
        if (!ENABLED) return;
        Set<PendingPairing> set = byEntityId.remove(entityId);
        if (set == null) return;
        for (PendingPairing p : set) {
            if (p.cancelled) continue;
            p.cancelled = true;
            cancelledCount.increment();
            byPlayerEntityKey.remove(p.key, p);
            Set<PendingPairing> byP = byPlayerId.get(p.player.getUUID());
            if (byP != null) byP.remove(p);
        }
    }

    /** §10: cancel every pending pairing (any entity) for a player who just disconnected. */
    public static void cancelAllForPlayer(UUID playerId) {
        if (!ENABLED) return;
        Set<PendingPairing> set = byPlayerId.remove(playerId);
        if (set == null) return;
        for (PendingPairing p : set) {
            if (p.cancelled) continue;
            p.cancelled = true;
            cancelledCount.increment();
            byPlayerEntityKey.remove(p.key, p);
            Set<PendingPairing> byE = byEntityId.get(p.entity.getId());
            if (byE != null) byE.remove(p);
        }
    }

    /** Called once per tick, BEFORE {@code OutboundBatchQueue.flushAll()} (so pairing packets
     *  enqueued here this tick still go out with the rest of this tick's traffic) and AFTER
     *  {@code tickChildren()} (so newly-drained seenBy membership doesn't affect broadcasts
     *  already computed this tick -- takes effect starting next tick, never early). */
    public static void drainBudget() {
        if (!ENABLED) return;
        long t0 = System.nanoTime();
        int budget = BUDGET_PER_TICK;
        for (int i = 0; i < budget; i++) {
            PendingPairing p = drainQueue.poll();
            if (p == null) break;
            if (p.cancelled) continue;
            byPlayerEntityKey.remove(p.key, p);
            removeFromSecondary(p);
            if (!p.player.isAlive() || p.player.connection == null || p.entity.isRemoved()) {
                staleAtDrain.increment();
                continue;
            }
            boolean ok;
            try {
                ok = p.op == Op.ADD
                        ? p.chunkMap.nestworldDeferredAddPairing(p.entity, p.player)
                        : p.chunkMap.nestworldDeferredRemovePairing(p.entity, p.player);
            } catch (Exception e) {
                sendFailures.increment();
                continue;
            }
            if (!ok) {
                staleAtDrain.increment();
                continue;
            }
            if (p.op == Op.ADD) addDrained.increment(); else removeDrained.increment();
        }
        drainTimeNanos.add(System.nanoTime() - t0);
    }

    public static void reset() {
        addEnqueued.reset();
        removeEnqueued.reset();
        addDrained.reset();
        removeDrained.reset();
        cancelledCount.reset();
        staleAtDrain.reset();
        sendFailures.reset();
        drainTimeNanos.reset();
        maxObservedQueueSize = 0;
    }

    public static String status() {
        long ae = addEnqueued.sum(), re = removeEnqueued.sum(), ad = addDrained.sum(), rd = removeDrained.sum(),
                c = cancelledCount.sum(), stale = staleAtDrain.sum(), fail = sendFailures.sum();
        long processed = ad + rd + stale + fail;
        double avgDrainUsPerItem = processed > 0 ? (drainTimeNanos.sum() / 1000.0) / processed : 0;
        return String.format(
                "NW pairing-batch: enabled=%s budgetPerTick=%d | addEnqueued=%,d removeEnqueued=%,d | addDrained=%,d removeDrained=%,d | cancelled=%,d stale=%,d sendFailures=%,d | maxObservedQueueSize=%,d pendingNow=%,d | drainTimeTotalMs=%.1f avgDrainUsPerItem=%.2f",
                ENABLED, BUDGET_PER_TICK, ae, re, ad, rd, c, stale, fail, maxObservedQueueSize,
                byPlayerEntityKey.size(), drainTimeNanos.sum() / 1_000_000.0, avgDrainUsPerItem);
    }
}
