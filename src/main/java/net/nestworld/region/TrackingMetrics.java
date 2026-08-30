package net.nestworld.region;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entity Tracking Fan-out Attribution (ТЗ, 2026-08-28) -- MEASUREMENT ONLY, per the ТЗ's explicit
 * "не змінювати tracking algorithm/range/Connection.send()/Netty/region ownership" constraint.
 * Counterpart to a one-line patch on vanilla {@code ChunkMap$TrackedEntity.broadcast()} (the exact
 * fan-out point: one call per outbound packet, N recipients in {@code seenBy}) that classifies
 * every broadcast by entity category, packet type, and "reason" (what changed), plus a SAMPLED
 * (not exhaustive) per-recipient breakdown for same/cross-region and distance buckets -- the
 * cross-region/distance axes need O(fanout) work per broadcast, so are only computed on 1-in-
 * {@link #SAMPLE_RATE} calls to stay within the ТЗ's overhead budget (&lt;=2% MSPT @ n=50, &lt;=5%
 * @ n=125). All counters are plain array-indexed {@code AtomicLong}s or small bounded
 * {@code ConcurrentHashMap}s keyed by a FIXED, small universe (packet class, region id) --
 * explicitly NOT the per-call-unbounded-key pattern that caused the earlier self-critical
 * "observer effect" finding in {@link ConnectionDiagnostics}'s first version.
 */
public final class TrackingMetrics {
    private TrackingMetrics() {}

    public enum EntityCategory { PLAYER, ARMOR_STAND, MOB, ITEM, PROJECTILE, OTHER }
    public enum Reason { SPAWN, REMOVE, MOVE, ROTATION, METADATA, EQUIPMENT, VELOCITY, EFFECT, PASSENGER, OTHER }

    private static final int SAMPLE_RATE = 200; // 1-in-200 broadcast() calls get the expensive per-recipient breakdown
    private static final AtomicLong sampleCounter = new AtomicLong();

    private static final AtomicLong[] entityCategoryCalls = newArr(EntityCategory.values().length);
    private static final AtomicLong[] entityCategoryFanOut = newArr(EntityCategory.values().length);
    private static final AtomicLong[] reasonCalls = newArr(Reason.values().length);
    private static final AtomicLong[] reasonFanOut = newArr(Reason.values().length);

    private static final ConcurrentHashMap<String, LongAdder> packetTypeCalls = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> packetTypeFanOut = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, LongAdder> sourceRegionCalls = new ConcurrentHashMap<>();

    private static final AtomicLong sameRegionCount = new AtomicLong();
    private static final AtomicLong crossRegionCount = new AtomicLong();
    // Distance buckets: 0-16, 16-32, 32-64, 64-128, 128-256, 256+
    private static final String[] DISTANCE_LABELS = {"0-16", "16-32", "32-64", "64-128", "128-256", "256+"};
    private static final AtomicLong[] distanceBucketCounts = newArr(DISTANCE_LABELS.length);
    private static final AtomicLong sampledRecipientCount = new AtomicLong();

    // Per-recipient outbound fan-out (2026-08-28 Outbound Tracking Optimization ТЗ): EXHAUSTIVE,
    // not sampled -- percentile fidelity (p95/p99/max) needs real per-recipient totals, not a
    // 1-in-200 estimate. Bounded key space (online player UUIDs, typically <=250) means this is
    // NOT the unbounded-key-churn pattern that caused the earlier ConnectionDiagnostics observer
    // effect -- one entry per player, created once, incremented in place for the rest of the
    // window. Sizes the theoretical win of ClientboundBundlePacket-based per-recipient batching
    // (how many Connection.send() calls could collapse into one bundle per recipient per tick).
    //
    // FIRST version used AtomicLong here and a live n=75 A/B caught a NEW observer-effect: MSPT
    // roughly doubled (39ms -> 76ms) vs. the pre-this-counter baseline. Root cause: unlike the
    // other counters in this class (keyed by region id / packet type -- a wide key space spread
    // across many cells), per-recipient counters are keyed by a NARROW space (~75-250 players),
    // and MANY concurrent region threads broadcast to the SAME popular recipients every tick --
    // real CAS contention on a small number of hot AtomicLong cells, not just per-call cost.
    // LongAdder is built exactly for this (striped counters, no CAS convoy under contention);
    // switched below and reverified live before trusting any numbers from this instrument.
    private static final ConcurrentHashMap<UUID, RecipientStats> recipientStats = new ConcurrentHashMap<>();

    private static final int RB_MOVE = 0, RB_VELOCITY = 1, RB_ROTATION = 2, RB_OTHER = 3;

    private static final class RecipientStats {
        final LongAdder total = new LongAdder();
        final LongAdder[] reasonBucket = newLongAdderArr(4); // MOVE / VELOCITY / ROTATION / OTHER
        final LongAdder playerSourced = new LongAdder();
        final LongAdder otherEntitySourced = new LongAdder();
    }

    private static LongAdder[] newLongAdderArr(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new LongAdder();
        return a;
    }

    private static int reasonBucketOf(Reason r) {
        switch (r) {
            case MOVE: return RB_MOVE;
            case VELOCITY: return RB_VELOCITY;
            case ROTATION: return RB_ROTATION;
            default: return RB_OTHER;
        }
    }

    private static AtomicLong[] newArr(int n) {
        AtomicLong[] a = new AtomicLong[n];
        for (int i = 0; i < n; i++) a[i] = new AtomicLong();
        return a;
    }

    /** Called from {@code ChunkMap$TrackedEntity.broadcast(Packet)} -- one call per outbound
     *  fan-out event (N recipients), not per individual packet-send. */
    public static void recordBroadcast(Entity entity, Packet<?> packet, Set<ServerPlayerConnection> recipients) {
        int fanOut = recipients.size();
        EntityCategory cat = classifyEntity(entity);
        entityCategoryCalls[cat.ordinal()].incrementAndGet();
        entityCategoryFanOut[cat.ordinal()].addAndGet(fanOut);

        Reason reason = classifyReason(packet);
        reasonCalls[reason.ordinal()].incrementAndGet();
        reasonFanOut[reason.ordinal()].addAndGet(fanOut);

        String packetType = packet.getClass().getSimpleName();
        packetTypeCalls.computeIfAbsent(packetType, k -> new LongAdder()).increment();
        packetTypeFanOut.computeIfAbsent(packetType, k -> new LongAdder()).add(fanOut);

        WorldRegion sourceRegion = resolveRegion(entity);
        int sourceRegionId = sourceRegion != null ? sourceRegion.getId() : -1;
        sourceRegionCalls.computeIfAbsent(sourceRegionId, k -> new LongAdder()).increment();

        // EXHAUSTIVE: cheap counter increments only (no region resolution, no distance calc) --
        // affordable on every broadcast, unlike recordSampledDetail below.
        recordRecipientFanout(cat, reason, recipients);

        // SAMPLED: cross-region + distance need O(fanOut) per-recipient work -- bounded via sampling.
        if (sampleCounter.incrementAndGet() % SAMPLE_RATE == 0) {
            recordSampledDetail(entity, sourceRegion, recipients);
        }
    }

    private static void recordRecipientFanout(EntityCategory sourceCat, Reason reason, Set<ServerPlayerConnection> recipients) {
        int bucket = reasonBucketOf(reason);
        boolean isPlayerSourced = sourceCat == EntityCategory.PLAYER;
        for (ServerPlayerConnection conn : recipients) {
            ServerPlayer recipient = conn.getPlayer();
            if (recipient == null) continue;
            RecipientStats stats = recipientStats.computeIfAbsent(recipient.getUUID(), k -> new RecipientStats());
            stats.total.increment();
            stats.reasonBucket[bucket].increment();
            if (isPlayerSourced) stats.playerSourced.increment();
            else stats.otherEntitySourced.increment();
        }
    }

    private static void recordSampledDetail(Entity entity, WorldRegion sourceRegion, Set<ServerPlayerConnection> recipients) {
        net.minecraft.world.phys.Vec3 pos = entity.position();
        // findOwningRegion() (ownership-set based, used for sourceRegion / the SOURCE REGION
        // top-10 stat) never has players in it -- "never region-owned, e.g. players" per
        // WorldGrid#findOwningRegion's own javadoc. Recipients here are ALWAYS ServerPlayer, so
        // comparing sourceRegion against resolveRegion(recipient) made same-region structurally
        // unreachable (always 0%, for every tier ever measured) -- not a real same/cross split,
        // an artifact of using ownership resolution on an entity type that's never owned.
        // Territory (position-based) lookup is the correct comparison for "same region" here.
        WorldRegion sourceTerritory = territoryRegion(entity);
        for (ServerPlayerConnection conn : recipients) {
            ServerPlayer recipient = conn.getPlayer();
            if (recipient == null) continue;
            sampledRecipientCount.incrementAndGet();

            WorldRegion targetTerritory = territoryRegion(recipient);
            if (sourceTerritory != null && sourceTerritory == targetTerritory) {
                sameRegionCount.incrementAndGet();
            } else {
                crossRegionCount.incrementAndGet();
            }

            double dist = pos.distanceTo(recipient.position());
            int bucket = dist < 16 ? 0 : dist < 32 ? 1 : dist < 64 ? 2 : dist < 128 ? 3 : dist < 256 ? 4 : 5;
            distanceBucketCounts[bucket].incrementAndGet();
        }
    }

    /** Ownership-set based (only tracks region-executed, non-player entities); used for the
     *  SOURCE REGION top-10 attribution stat, where "who generated this traffic" is the question. */
    private static WorldRegion resolveRegion(Entity e) {
        if (!NestworldRegionSystem.isInitialised()) return null;
        if (!(e.level() instanceof ServerLevel level)) return null;
        if (!NestworldRegionSystem.get().isManagedLevel(level)) return null;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().findOwningRegion(e.getUUID());
    }

    /** Position-based (works for any entity, players included); used for the same/cross-region
     *  SAMPLED comparison, where "whose territory is this in right now" is the question. */
    private static WorldRegion territoryRegion(Entity e) {
        if (!NestworldRegionSystem.isInitialised()) return null;
        if (!(e.level() instanceof ServerLevel level)) return null;
        if (!NestworldRegionSystem.get().isManagedLevel(level)) return null;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionFor(e.blockPosition());
    }

    private static EntityCategory classifyEntity(Entity e) {
        if (e instanceof ServerPlayer) return EntityCategory.PLAYER;
        if (e instanceof ArmorStand) return EntityCategory.ARMOR_STAND;
        if (e instanceof Mob) return EntityCategory.MOB;
        if (e instanceof ItemEntity) return EntityCategory.ITEM;
        if (e instanceof Projectile) return EntityCategory.PROJECTILE;
        return EntityCategory.OTHER;
    }

    private static Reason classifyReason(Packet<?> p) {
        if (p instanceof ClientboundAddEntityPacket) return Reason.SPAWN;
        if (p instanceof ClientboundRemoveEntitiesPacket) return Reason.REMOVE;
        if (p instanceof ClientboundMoveEntityPacket) return Reason.MOVE; // covers Pos/Rot/PosRot subclasses
        if (p instanceof ClientboundTeleportEntityPacket) return Reason.MOVE;
        if (p instanceof ClientboundSetEntityDataPacket) return Reason.METADATA;
        if (p instanceof ClientboundSetEquipmentPacket) return Reason.EQUIPMENT;
        if (p instanceof ClientboundSetEntityMotionPacket) return Reason.VELOCITY;
        if (p instanceof ClientboundRotateHeadPacket) return Reason.ROTATION;
        if (p instanceof ClientboundSetPassengersPacket) return Reason.PASSENGER;
        if (p instanceof ClientboundUpdateMobEffectPacket) return Reason.EFFECT;
        if (p instanceof ClientboundRemoveMobEffectPacket) return Reason.EFFECT;
        return Reason.OTHER;
    }

    public static void reset() {
        sampleCounter.set(0);
        for (AtomicLong a : entityCategoryCalls) a.set(0);
        for (AtomicLong a : entityCategoryFanOut) a.set(0);
        for (AtomicLong a : reasonCalls) a.set(0);
        for (AtomicLong a : reasonFanOut) a.set(0);
        packetTypeCalls.clear();
        packetTypeFanOut.clear();
        sourceRegionCalls.clear();
        sameRegionCount.set(0);
        crossRegionCount.set(0);
        for (AtomicLong a : distanceBucketCounts) a.set(0);
        sampledRecipientCount.set(0);
        recipientStats.clear();
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    /** Per-recipient outbound fan-out report -- sizes the theoretical win of collapsing each
     *  recipient's per-tick packets into one {@code ClientboundBundlePacket} (2026-08-28 Outbound
     *  Tracking Optimization ТЗ). {@code ticksInWindow} converts accumulated totals to per-tick
     *  rates (measured window ticks = tps * durationSec, passed in by the RCON caller). */
    public static String recipientFanoutReport(double ticksInWindow) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RECIPIENT FAN-OUT REPORT ===\n");
        int n = recipientStats.size();
        if (n == 0 || ticksInWindow <= 0) {
            sb.append("  no data\n");
            return sb.toString();
        }
        long[] totals = new long[n];
        long[] move = new long[n], velocity = new long[n], rotation = new long[n], other = new long[n];
        long[] playerSourced = new long[n], otherSourced = new long[n];
        int i = 0;
        for (RecipientStats s : recipientStats.values()) {
            totals[i] = s.total.sum();
            move[i] = s.reasonBucket[RB_MOVE].sum();
            velocity[i] = s.reasonBucket[RB_VELOCITY].sum();
            rotation[i] = s.reasonBucket[RB_ROTATION].sum();
            other[i] = s.reasonBucket[RB_OTHER].sum();
            playerSourced[i] = s.playerSourced.sum();
            otherSourced[i] = s.otherEntitySourced.sum();
            i++;
        }
        Arrays.sort(totals);
        Arrays.sort(move);
        Arrays.sort(velocity);
        Arrays.sort(rotation);
        Arrays.sort(other);
        Arrays.sort(playerSourced);
        Arrays.sort(otherSourced);

        long sumTotal = Arrays.stream(totals).sum();
        sb.append(String.format("recipients=%d  ticksInWindow=%.1f%n", n, ticksInWindow));
        sb.append(String.format("TOTAL packets/tick per recipient: avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                (sumTotal / (double) n) / ticksInWindow,
                percentile(totals, 0.50) / ticksInWindow, percentile(totals, 0.95) / ticksInWindow,
                percentile(totals, 0.99) / ticksInWindow, totals[n - 1] / ticksInWindow));
        sb.append(String.format("  MOVE       avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                (Arrays.stream(move).sum() / (double) n) / ticksInWindow,
                percentile(move, 0.50) / ticksInWindow, percentile(move, 0.95) / ticksInWindow,
                percentile(move, 0.99) / ticksInWindow, move[n - 1] / ticksInWindow));
        sb.append(String.format("  VELOCITY   avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                (Arrays.stream(velocity).sum() / (double) n) / ticksInWindow,
                percentile(velocity, 0.50) / ticksInWindow, percentile(velocity, 0.95) / ticksInWindow,
                percentile(velocity, 0.99) / ticksInWindow, velocity[n - 1] / ticksInWindow));
        sb.append(String.format("  ROTATION   avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                (Arrays.stream(rotation).sum() / (double) n) / ticksInWindow,
                percentile(rotation, 0.50) / ticksInWindow, percentile(rotation, 0.95) / ticksInWindow,
                percentile(rotation, 0.99) / ticksInWindow, rotation[n - 1] / ticksInWindow));
        sb.append(String.format("  OTHER      avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                (Arrays.stream(other).sum() / (double) n) / ticksInWindow,
                percentile(other, 0.50) / ticksInWindow, percentile(other, 0.95) / ticksInWindow,
                percentile(other, 0.99) / ticksInWindow, other[n - 1] / ticksInWindow));
        sb.append(String.format("  OTHER-ENTITY-sourced avg=%.1f p95=%.1f max=%.1f%n",
                (Arrays.stream(otherSourced).sum() / (double) n) / ticksInWindow,
                percentile(otherSourced, 0.95) / ticksInWindow, otherSourced[n - 1] / ticksInWindow));
        sb.append(String.format("  PLAYER-sourced       avg=%.1f p95=%.1f max=%.1f%n",
                (Arrays.stream(playerSourced).sum() / (double) n) / ticksInWindow,
                percentile(playerSourced, 0.95) / ticksInWindow, playerSourced[n - 1] / ticksInWindow));
        return sb.toString();
    }

    /** Full report matching the ТЗ §18 output shape. */
    public static String report(int players, String tps, String mspt) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ENTITY TRACKING REPORT ===\n");
        sb.append(String.format("players: %d  TPS: %s  MSPT: %s%n", players, tps, mspt));

        long totalCalls = 0, totalFanOut = 0;
        for (AtomicLong a : entityCategoryCalls) totalCalls += a.get();
        for (AtomicLong a : entityCategoryFanOut) totalFanOut += a.get();
        sb.append(String.format("totalBroadcastCalls: %,d  totalFanOutSends: %,d%n", totalCalls, totalFanOut));

        sb.append("ENTITY (calls / fanOut / % of fanOut)\n");
        EntityCategory[] cats = EntityCategory.values();
        for (int i = 0; i < cats.length; i++) {
            long c = entityCategoryCalls[i].get(), f = entityCategoryFanOut[i].get();
            double pct = totalFanOut > 0 ? 100.0 * f / totalFanOut : 0;
            sb.append(String.format("  %-12s calls=%,10d fanOut=%,10d (%.1f%%)%n", cats[i], c, f, pct));
        }

        sb.append("REASON (calls / fanOut / % of fanOut)\n");
        Reason[] reasons = Reason.values();
        for (int i = 0; i < reasons.length; i++) {
            long c = reasonCalls[i].get(), f = reasonFanOut[i].get();
            double pct = totalFanOut > 0 ? 100.0 * f / totalFanOut : 0;
            sb.append(String.format("  %-12s calls=%,10d fanOut=%,10d (%.1f%%)%n", reasons[i], c, f, pct));
        }

        sb.append("TOP PACKET TYPES (by fanOut)\n");
        final long totalFanOutFinal = totalFanOut;
        packetTypeFanOut.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(10)
                .forEach(en -> {
                    long calls = packetTypeCalls.getOrDefault(en.getKey(), new LongAdder()).sum();
                    double pct = totalFanOutFinal > 0 ? 100.0 * en.getValue().sum() / totalFanOutFinal : 0;
                    sb.append(String.format("  %-32s calls=%,10d fanOut=%,10d (%.1f%%)%n",
                            en.getKey(), calls, en.getValue().sum(), pct));
                });

        sb.append("SOURCE REGION (broadcast calls originating there)\n");
        sourceRegionCalls.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(10)
                .forEach(en -> sb.append(String.format("  region#%-4d calls=%,d%n", en.getKey(), en.getValue().sum())));

        long same = sameRegionCount.get(), cross = crossRegionCount.get(), sampledTotal = same + cross;
        double samePct = sampledTotal > 0 ? 100.0 * same / sampledTotal : 0;
        sb.append(String.format("REGION (SAMPLED, n=%,d recipient-checks, 1-in-%d broadcast calls)%n", sampledTotal, SAMPLE_RATE));
        sb.append(String.format("  same-region=%,d (%.1f%%)  cross-region=%,d (%.1f%%)%n", same, samePct, cross, 100.0 - samePct));

        sb.append("DISTANCE (SAMPLED, entity-to-recipient)\n");
        for (int i = 0; i < DISTANCE_LABELS.length; i++) {
            long c = distanceBucketCounts[i].get();
            double pct = sampledTotal > 0 ? 100.0 * c / sampledTotal : 0;
            sb.append(String.format("  %-8s count=%,10d (%.1f%%)%n", DISTANCE_LABELS[i], c, pct));
        }
        return sb.toString();
    }
}
