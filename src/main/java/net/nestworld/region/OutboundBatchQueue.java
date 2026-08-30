package net.nestworld.region;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entity Tracking Outbound Batching (ТЗ, 2026-08-28) -- collapses the many
 * {@code ChunkMap$TrackedEntity.broadcast()} sends a single recipient accumulates over one
 * server tick into ONE {@link ClientboundBundlePacket} (vanilla primitive, already used by
 * {@code ServerEntity.addPairing()} for spawn-data bundling), instead of N separate
 * {@code Connection.send()} calls. Per the ТЗ's own closed investigation
 * (entity-tracking-attribution + outbound-recipient-fanout-measurement memory files):
 * {@code ServerEntity.sendChanges()} already emits at most one packet per type per entity per
 * tick, so there is no redundant STATE to dedup -- the only available win is the DELIVERY
 * mechanism (send()-call count / Netty event-loop-submission count), not packet content. No
 * packet is dropped, no state is deduplicated, and per-recipient order is preserved (a
 * {@link java.util.concurrent.ConcurrentLinkedQueue} drained FIFO).
 *
 * <p>Feature-flagged, RCON-toggleable (master on/off switch: {@code /nestworld outboundbatch
 * on|off}) so it can be instantly rolled back live without a restart. Default flipped to ON
 * 2026-08-29 after the full §17 correctness matrix + maxBundleSize sweep + A/B scalability sweep
 * (100-200) validated it as production-ready for the tested <=150-175 range -- see
 * outbound-batching-full-validation-2026-08-29.md. Still OFF-by-default logic is unchanged
 * (just the static field's initial value), so a config/property override remains trivial if ever
 * needed.
 *
 * <p>Route: {@code ChunkMap$TrackedEntity.broadcast()} calls {@link #route} instead of
 * {@code connection.send(packet)} directly. Flush: {@code MinecraftServer.tickServer()} calls
 * {@link #flushAll} once, AFTER {@code tickChildren()} returns -- i.e. after the region-thread
 * barrier for this tick, on the main thread only, so flush never contends with a region thread
 * still enqueueing. Packets sent through any OTHER path (spawn/destroy via
 * {@code addPairing()}/{@code removePairing()}, chat, inventory, etc.) are untouched and keep
 * going out immediately -- only the entity-tracking broadcast() fan-out is batched.
 */
public final class OutboundBatchQueue {
    private OutboundBatchQueue() {}

    public static volatile boolean ENABLED = true;
    /** Per the ТЗ's own required max-bundle-size sweep (16/32/64/128/256/512) -- RCON-mutable.
     *  Sweep result: 256/512 both hit the TPS plateau, 16-128 all degraded -- 512 kept. */
    public static volatile int MAX_BUNDLE_SIZE = 512;

    private static final LongAdder bundlesSent = new LongAdder();
    private static final LongAdder packetsBundled = new LongAdder();
    private static final LongAdder rawSinglePacketSends = new LongAdder();
    private static final LongAdder flushCalls = new LongAdder();
    private static final LongAdder bundleSendFailures = new LongAdder();
    // Flush timing + queue-depth: flushAll/flushOne/sendBatch are ALWAYS called from the main
    // thread only (see class javadoc) -- no concurrent writers, so plain (non-atomic) fields are
    // safe and cheaper than LongAdder here.
    private static volatile long flushTimeTotalNanos = 0L;
    private static volatile long flushTimeMaxNanos = 0L;
    private static volatile int maxObservedBatchSize = 0;

    /** Called from {@code ChunkMap$TrackedEntity.broadcast()}'s per-recipient loop. */
    public static void route(ServerPlayerConnection conn, Packet<?> packet) {
        if (!ENABLED || !(conn instanceof ServerGamePacketListenerImpl impl)) {
            conn.send(packet);
            return;
        }
        impl.nestworldOutboundBatch.add(packet);
    }

    /** Called once per server tick, after {@code tickChildren()} -- i.e. after every region
     *  thread for this tick has already joined back on the barrier, so no producer can still be
     *  enqueueing into any connection's queue at this point. Main thread only. */
    @SuppressWarnings("unchecked")
    public static void flushAll(List<ServerPlayer> players) {
        if (!ENABLED) return;
        long start = System.nanoTime();
        flushCalls.increment();
        for (ServerPlayer player : players) {
            ServerGamePacketListenerImpl conn = player.connection;
            if (conn == null) continue;
            Queue<Packet<?>> queue = conn.nestworldOutboundBatch;
            if (queue.isEmpty()) continue;
            flushOne(conn, queue);
        }
        long elapsed = System.nanoTime() - start;
        flushTimeTotalNanos += elapsed;
        if (elapsed > flushTimeMaxNanos) flushTimeMaxNanos = elapsed;
    }

    @SuppressWarnings("unchecked")
    private static void flushOne(ServerGamePacketListenerImpl conn, Queue<Packet<?>> queue) {
        // Note: deliberately never calls queue.size() -- O(n) on ConcurrentLinkedQueue, not O(1).
        int initialCapacity = Math.min(64, MAX_BUNDLE_SIZE);
        List<Packet<?>> batch = new ArrayList<>(initialCapacity);
        Packet<?> p;
        while ((p = queue.poll()) != null) {
            batch.add(p);
            if (batch.size() >= MAX_BUNDLE_SIZE) {
                sendBatch(conn, batch);
                batch = new ArrayList<>(initialCapacity);
            }
        }
        if (!batch.isEmpty()) {
            sendBatch(conn, batch);
        }
    }

    @SuppressWarnings("unchecked")
    private static void sendBatch(ServerGamePacketListenerImpl conn, List<Packet<?>> batch) {
        int size = batch.size();
        if (size > maxObservedBatchSize) maxObservedBatchSize = size;
        try {
            if (size == 1) {
                // Single packet this tick -- send raw, no bundle-wrapping overhead for the common
                // "nothing much changed for this recipient" case.
                conn.send(batch.get(0));
                rawSinglePacketSends.increment();
                return;
            }
            conn.send(new ClientboundBundlePacket((List<Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>>) (List<?>) batch));
            bundlesSent.increment();
            packetsBundled.add(size);
        } catch (Exception e) {
            // Defensive: a bundle/send failure for one recipient (e.g. mid-flush disconnect
            // race) must never abort flushAll()'s loop over the rest of the player list --
            // that would silently starve every OTHER player's outbound queue for the tick.
            bundleSendFailures.increment();
        }
    }

    public static void reset() {
        bundlesSent.reset();
        packetsBundled.reset();
        rawSinglePacketSends.reset();
        flushCalls.reset();
        bundleSendFailures.reset();
        flushTimeTotalNanos = 0L;
        flushTimeMaxNanos = 0L;
        maxObservedBatchSize = 0;
    }

    public static String report() {
        long bundles = bundlesSent.sum(), packets = packetsBundled.sum(), raw = rawSinglePacketSends.sum(),
                flushes = flushCalls.sum(), failures = bundleSendFailures.sum();
        double avgPerBundle = bundles > 0 ? (double) packets / bundles : 0;
        double avgFlushUs = flushes > 0 ? (flushTimeTotalNanos / 1000.0) / flushes : 0;
        double maxFlushUs = flushTimeMaxNanos / 1000.0;
        return String.format(
                "NW outbound-batch: enabled=%s maxBundleSize=%d flushCalls=%,d | bundlesSent=%,d packetsBundled=%,d avgPacketsPerBundle=%.1f | rawSinglePacketSends=%,d | totalConnectionSendCalls=%,d | maxQueueDepthAtFlush=%,d | flushTimeAvgUs=%.1f flushTimeMaxUs=%.1f | bundleSendFailures=%,d",
                ENABLED, MAX_BUNDLE_SIZE, flushes, bundles, packets, avgPerBundle, raw, bundles + raw,
                maxObservedBatchSize, avgFlushUs, maxFlushUs, failures);
    }
}
