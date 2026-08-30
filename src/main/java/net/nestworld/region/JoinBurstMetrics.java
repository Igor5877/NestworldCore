package net.nestworld.region;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Setup-Burst / Player Join Pipeline (ТЗ, 2026-08-29) -- MEASUREMENT ONLY, no batching/async
 * change (explicit instruction, twice: once for the original chunk-load measurement, again for
 * this AddPairing Burst Attribution follow-up). Counterpart to {@link ChunkOutboundQueue} (which
 * fixed the CHUNK-loading component of the join burst): this class attributes the SEPARATE
 * {@code ServerEntity.addPairing()} entity-pairing burst, left deliberately untouched by that
 * milestone and now the leading suspect for why n=200 still crashes (with a NEW, different
 * watchdog signature than the earlier Netty one -- see project memory
 * chunk-outbound-pipeline-shipped-2026-08-29.md for why {@code Arrays.sort()} in
 * {@code WorldRegion.getPercentileTickMs()} is almost certainly just the sampled frame of a
 * starved thread, not a real bottleneck -- that instrumentation stays, per explicit instruction,
 * but is not treated as root cause).
 *
 * <p>Cost note: unlike {@code TrackingMetrics}' entity-tracking classification (which is O(fanout)
 * per broadcast() call and needs sampling to stay cheap), {@code addPairing()} has exactly ONE
 * recipient per call by construction -- classification here is O(1) per call, safe to do
 * EXHAUSTIVELY without the sampling discipline that broadcast() classification needed.
 */
public final class JoinBurstMetrics {
    private JoinBurstMetrics() {}

    public enum EntityCategory { PLAYER, ARMOR_STAND, MOB, ITEM, PROJECTILE, OTHER }

    private static final LongAdder chunkPacketsSent = new LongAdder();
    private static final LongAdder addPairingCalls = new LongAdder();
    private static final LongAdder pairingPacketsSent = new LongAdder();

    private static final AtomicLongArr categoryCalls = new AtomicLongArr(EntityCategory.values().length);
    private static final AtomicLongArr categoryPackets = new AtomicLongArr(EntityCategory.values().length);
    private static final ConcurrentHashMap<String, LongAdder> packetTypeCalls = new ConcurrentHashMap<>();
    private static final LongAdder sameRegionPairings = new LongAdder();
    private static final LongAdder crossRegionPairings = new LongAdder();

    /** Tiny fixed-size long[] wrapper -- avoids importing AtomicLong[] boilerplate twice in this
     *  file; increments are done via synchronized-free simple add since JoinBurstMetrics callers
     *  are inherently low-frequency (one call per newly-visible entity per player, not a hot
     *  per-tick loop) but still concurrent (region threads can call addPairing too) -- uses
     *  LongAdder per slot for safety without the earlier AtomicLong-contention mistake. */
    private static final class AtomicLongArr {
        final LongAdder[] a;
        AtomicLongArr(int n) { a = new LongAdder[n]; for (int i = 0; i < n; i++) a[i] = new LongAdder(); }
        void add(int i, long v) { a[i].add(v); }
        long sum(int i) { return a[i].sum(); }
        void reset() { for (LongAdder x : a) x.reset(); }
    }

    /** Called from {@code ChunkMap.playerLoadedChunk()} -- one call per chunk sent to a player. */
    public static void recordChunkPacket() {
        chunkPacketsSent.increment();
    }

    /** Called from {@code ServerEntity.addPairing()} -- one call per (entity, newly-seeing-player)
     *  pair, {@code packets} is the bundled spawn-data list for that one pairing. */
    public static void recordPairingDetailed(Entity source, ServerPlayer recipient, List<Packet<ClientGamePacketListener>> packets) {
        int n = packets.size();
        addPairingCalls.increment();
        pairingPacketsSent.add(n);

        EntityCategory cat = classify(source);
        categoryCalls.add(cat.ordinal(), 1);
        categoryPackets.add(cat.ordinal(), n);

        for (Packet<ClientGamePacketListener> p : packets) {
            packetTypeCalls.computeIfAbsent(p.getClass().getSimpleName(), k -> new LongAdder()).increment();
        }

        // O(1) same/cross-region -- exhaustive, safe (see class javadoc).
        if (NestworldRegionSystem.isInitialised() && source.level() instanceof ServerLevel level
                && NestworldRegionSystem.get().isManagedLevel(level)) {
            var grid = NestworldRegionSystem.get().getDimensionRegion(level).getGrid();
            WorldRegion sourceTerritory = grid.getRegionFor(source.blockPosition());
            WorldRegion recipientTerritory = grid.getRegionFor(recipient.blockPosition());
            if (sourceTerritory != null && sourceTerritory == recipientTerritory) {
                sameRegionPairings.increment();
            } else {
                crossRegionPairings.increment();
            }
        }
    }

    private static EntityCategory classify(Entity e) {
        if (e instanceof ServerPlayer) return EntityCategory.PLAYER;
        if (e instanceof ArmorStand) return EntityCategory.ARMOR_STAND;
        if (e instanceof Mob) return EntityCategory.MOB;
        if (e instanceof ItemEntity) return EntityCategory.ITEM;
        if (e instanceof Projectile) return EntityCategory.PROJECTILE;
        return EntityCategory.OTHER;
    }

    public static void reset() {
        chunkPacketsSent.reset();
        addPairingCalls.reset();
        pairingPacketsSent.reset();
        categoryCalls.reset();
        categoryPackets.reset();
        packetTypeCalls.clear();
        sameRegionPairings.reset();
        crossRegionPairings.reset();
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "NW join-burst: chunkPacketsSent=%,d | addPairingCalls=%,d pairingPacketsSent=%,d avgPacketsPerPairing=%.2f%n",
                chunkPacketsSent.sum(), addPairingCalls.sum(), pairingPacketsSent.sum(),
                addPairingCalls.sum() > 0 ? (double) pairingPacketsSent.sum() / addPairingCalls.sum() : 0));

        sb.append("  PAIRING BY SOURCE ENTITY (calls / packets)\n");
        EntityCategory[] cats = EntityCategory.values();
        for (int i = 0; i < cats.length; i++) {
            long c = categoryCalls.sum(i), p = categoryPackets.sum(i);
            if (c == 0) continue;
            sb.append(String.format("    %-12s calls=%,10d packets=%,10d%n", cats[i], c, p));
        }

        sb.append("  PAIRING PACKET TYPES (calls)\n");
        packetTypeCalls.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(en -> sb.append(String.format("    %-32s calls=%,d%n", en.getKey(), en.getValue().sum())));

        long same = sameRegionPairings.sum(), cross = crossRegionPairings.sum(), total = same + cross;
        double samePct = total > 0 ? 100.0 * same / total : 0;
        sb.append(String.format("  PAIRING REGION: same-region=%,d (%.1f%%) cross-region=%,d (%.1f%%)",
                same, samePct, cross, 100.0 - samePct));
        return sb.toString();
    }
}
