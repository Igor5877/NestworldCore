package net.nestworld.region;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Setup-Burst / Player Join Pipeline (ТЗ, 2026-08-29) -- MEASUREMENT-DRIVEN admission control for
 * initial chunk delivery. Per the closed measurement phase (see project memory
 * setup-burst-join-pipeline-measurement-2026-08-29.md): {@code ChunkMap.updatePlayerStatus()}
 * sends ~456 chunk packets per newly-joining player via raw, synchronous, unbatched
 * {@code Connection.send()} calls in a tight main-thread loop -- at n=100 alone, 84 simultaneous
 * joins collapsed TPS to 1.3 (MSPT=766ms) for at least one tick. This is a SEPARATE code path
 * from {@link OutboundBatchQueue} (which only covers steady-state entity-tracking broadcast()) --
 * deliberately not touched here, per the user's explicit instruction.
 *
 * <p>Unlike entity-tracking's fix (bundle MORE packets into ONE send), this problem is about
 * total main-thread TIME spent synchronously sending within a single tick, not send-call count
 * alone -- so the fix is temporal: spread the ~456-chunks-per-player burst across MANY ticks
 * via a budget, instead of one synchronous loop. This intentionally trades a little join latency
 * (a player takes longer to receive their full view distance) for main-thread health during a
 * mass-connect event -- see the user's own explicit acceptance framing.
 *
 * <p><b>Correctness boundary</b> (the reason this is NOT a trivial refactor): a chunk can be
 * requested for LOAD and then, before the deferred load actually executes, the SAME player can
 * move out of range and the SAME chunk gets requested for UNLOAD. Sending both (a late LOAD then
 * an UNLOAD) would be wasted work; sending only the UNLOAD without ever having sent the LOAD
 * would send the client an invalid "forget a chunk you were never told about" instruction. This
 * class uses lazy cancellation: {@link #cancelIfPending} is checked by
 * {@code ChunkMap.updateChunkTracking()}'s UNLOAD branch BEFORE sending a real unload -- if a
 * load for that exact (player, chunk) pair is still queued, it's marked cancelled (skipped by the
 * drain loop) and NEITHER packet is sent (net zero, matching what the client's actual state
 * already is: it was never told to load this chunk). Chunk readiness is re-checked FRESH at drain
 * time (not a stale snapshot from enqueue time) via {@link ChunkMap#nestworldDeferredLoadChunk},
 * mirroring exactly what the original synchronous code path already did.
 *
 * <p>Feature-flagged, default OFF (more conservative than {@link OutboundBatchQueue}'s
 * now-production-default given the higher correctness surface here per the user's own explicit
 * caution). Master RCON switch: {@code /nestworld chunkoutbound on|off}.
 */
public final class ChunkOutboundQueue {
    private ChunkOutboundQueue() {}

    public static volatile boolean ENABLED = false;
    /** Global chunk-loads drained per tick. Per the ТЗ's explicit "не вигадувати N" instruction,
     *  this is a measurement-sweep starting point, RCON-mutable, not a chosen final value. */
    public static volatile int BUDGET_PER_TICK = 200;

    private static final class PendingChunkLoad {
        final ChunkMap chunkMap;
        final ServerPlayer player;
        final ChunkPos pos;
        final long enqueuedAtNanos;
        volatile boolean cancelled;

        PendingChunkLoad(ChunkMap chunkMap, ServerPlayer player, ChunkPos pos) {
            this.chunkMap = chunkMap;
            this.player = player;
            this.pos = pos;
            this.enqueuedAtNanos = System.nanoTime();
        }
    }

    private static final Queue<PendingChunkLoad> queue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<String, PendingChunkLoad> byKey = new ConcurrentHashMap<>();

    private static final LongAdder enqueued = new LongAdder();
    private static final LongAdder drained = new LongAdder();
    private static final LongAdder cancelledCount = new LongAdder();
    private static final LongAdder droppedNotReady = new LongAdder();
    private static final LongAdder ageSumNanosAtDrain = new LongAdder();
    private static volatile int maxObservedQueueSize = 0;

    private static String key(UUID playerId, ChunkPos pos) {
        return playerId + ":" + pos.toLong();
    }

    /** Called from {@code ChunkMap.updateChunkTracking()}'s LOAD branch instead of sending
     *  immediately. Fresh readiness re-check happens at drain time, not here. */
    public static void enqueueLoad(ChunkMap chunkMap, ServerPlayer player, ChunkPos pos) {
        PendingChunkLoad pcl = new PendingChunkLoad(chunkMap, player, pos);
        String k = key(player.getUUID(), pos);
        byKey.put(k, pcl);
        queue.add(pcl);
        enqueued.increment();
        // Approximate size gauge -- CLQ.size() is O(n), so only sampled via the map's cheaper
        // (still not O(1), but bounded by in-flight pending count, not lifetime total) size.
        int sz = byKey.size();
        if (sz > maxObservedQueueSize) maxObservedQueueSize = sz;
    }

    /** Called from {@code ChunkMap.updateChunkTracking()}'s UNLOAD branch BEFORE sending a real
     *  unload. Returns true if a pending load for this exact (player, chunk) was cancelled --
     *  the caller must then skip sending the unload packet too (net zero, see class javadoc). */
    public static boolean cancelIfPending(ServerPlayer player, ChunkPos pos) {
        if (!ENABLED) return false;
        PendingChunkLoad pcl = byKey.remove(key(player.getUUID(), pos));
        if (pcl == null) return false;
        pcl.cancelled = true;
        cancelledCount.increment();
        return true;
    }

    /** Called once per server tick (main thread, after tickChildren() -- same timing contract as
     *  {@link OutboundBatchQueue#flushAll}). Drains up to {@link #BUDGET_PER_TICK} entries. */
    public static void drainBudget() {
        if (!ENABLED) return;
        int budget = BUDGET_PER_TICK;
        for (int i = 0; i < budget; i++) {
            PendingChunkLoad pcl = queue.poll();
            if (pcl == null) break;
            if (pcl.cancelled) continue; // already accounted in cancelledCount
            byKey.remove(key(pcl.player.getUUID(), pcl.pos), pcl);
            if (!pcl.player.isAlive() || pcl.player.connection == null) continue; // disconnected
            boolean sent = pcl.chunkMap.nestworldDeferredLoadChunk(pcl.player, pcl.pos);
            drained.increment();
            ageSumNanosAtDrain.add(System.nanoTime() - pcl.enqueuedAtNanos);
            if (!sent) droppedNotReady.increment();
        }
    }

    public static void reset() {
        enqueued.reset();
        drained.reset();
        cancelledCount.reset();
        droppedNotReady.reset();
        ageSumNanosAtDrain.reset();
        maxObservedQueueSize = 0;
    }

    public static String report() {
        long e = enqueued.sum(), d = drained.sum(), c = cancelledCount.sum(), nr = droppedNotReady.sum();
        double avgAgeMs = d > 0 ? (ageSumNanosAtDrain.sum() / 1_000_000.0) / d : 0;
        return String.format(
                "NW chunk-outbound: enabled=%s budgetPerTick=%d | enqueued=%,d drained=%,d cancelled=%,d droppedNotReady=%,d | avgAgeAtDrainMs=%.1f maxObservedQueueSize=%,d | pendingNow=%,d",
                ENABLED, BUDGET_PER_TICK, e, d, c, nr, avgAgeMs, maxObservedQueueSize, byKey.size());
    }
}
