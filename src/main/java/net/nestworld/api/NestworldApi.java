package net.nestworld.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.region.NestworldDimensionRegion;
import net.nestworld.region.NestworldRegionSystem;
import net.nestworld.region.WorldRegion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable, read-only facade over NestworldCore's live state, for external consumers —
 * the in-JVM Prometheus metrics exporter this was built for (2026-08-21, live ATM9
 * incident night), and any future third-party mod that wants to observe region/chunk-
 * gen/tick health without depending on {@code net.nestworld.region}'s internal classes
 * directly (those classes stay free to change shape; this facade is the contract).
 *
 * <p>Every method here is a cheap main-thread snapshot read (no locks beyond what the
 * underlying counters already use, no allocation beyond the returned records/list) —
 * safe to call every tick from a metrics scrape loop. Nothing here mutates state; this
 * is observation-only by design, same reasoning as this project's existing "shadow
 * mode / orchestration not state" standing preference for auxiliary tooling.
 */
public final class NestworldApi {

    private NestworldApi() {
    }

    /** Whether the region-sharding system has finished initialising. Every other method
     *  on this class returns empty/zeroed data (never throws) when this is false. */
    public static boolean isActive() {
        return NestworldRegionSystem.isInitialised();
    }

    /**
     * Every loaded dimension's key (e.g. {@code "minecraft:overworld"}), so a caller
     * (e.g. a metrics exporter running as a plain Forge mod, compiled against MAPPED
     * method names that do NOT survive this project's SRG reobfuscation step the way
     * this facade's own implementation does) never needs to call {@code
     * MinecraftServer.getAllLevels()}/{@code ServerLevel} methods directly itself —
     * route dimension iteration and lookup through this facade instead of vanilla
     * server/level methods, and every method on this class stays safe to call from
     * code that was never reobfuscated.
     */
    public static List<String> dimensionKeys(MinecraftServer server) {
        if (server == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.add(level.dimension().location().toString());
        }
        return out;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionKey) {
        if (server == null || dimensionKey == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionKey)) {
                return level;
            }
        }
        return null;
    }

    /** Same as {@link #regions(ServerLevel)}, resolved by dimension key string instead
     *  of a {@code ServerLevel} reference -- the reobf-safe entry point for external
     *  callers (see {@link #dimensionKeys}'s javadoc for why this indirection exists). */
    public static List<RegionSnapshot> regions(MinecraftServer server, String dimensionKey) {
        return regions(resolveLevel(server, dimensionKey));
    }

    /** Snapshot of every active region in {@code level}'s dimension, unsorted. Empty
     *  list (not null) if the region system isn't active or the dimension isn't managed. */
    public static List<RegionSnapshot> regions(ServerLevel level) {
        if (!isActive() || level == null) {
            return Collections.emptyList();
        }
        NestworldDimensionRegion dr = NestworldRegionSystem.get().getDimensionRegion(level);
        if (dr == null) {
            return Collections.emptyList();
        }
        var active = dr.getTree().getActiveRegions();
        List<RegionSnapshot> out = new ArrayList<>(active.size());
        for (WorldRegion r : active) {
            out.add(new RegionSnapshot(
                    r.getId(), r.getMinChunkX(), r.getMinChunkZ(), r.getMaxChunkX(), r.getMaxChunkZ(),
                    r.getAvgTickMs(), r.getOwnedEntityIds().size(), r.isFreeRunning()));
        }
        return out;
    }

    /** Same as {@link #chunkGen(ServerLevel)}, resolved by dimension key string --
     *  see {@link #dimensionKeys}'s javadoc for why this indirection exists. */
    public static ChunkGenSnapshot chunkGen(MinecraftServer server, String dimensionKey) {
        return chunkGen(resolveLevel(server, dimensionKey));
    }

    /** Chunk-generation/promotion pipeline snapshot for {@code level}'s dimension.
     *  Zeroed (not null) if the region system isn't active. */
    public static ChunkGenSnapshot chunkGen(ServerLevel level) {
        if (level == null) {
            return new ChunkGenSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        ServerChunkCache cache = level.getChunkSource();
        var dm = cache.chunkMap.getDistanceManager();
        long predictiveRequested = 0L;
        if (isActive()) {
            NestworldDimensionRegion dr = NestworldRegionSystem.get().getDimensionRegionByKey(level.dimension());
            if (dr != null) {
                predictiveRequested = dr.predictiveGen().issuedTotal();
            }
        }
        return new ChunkGenSnapshot(
                dm.nestworldPromotionPendingPlayer(),
                dm.nestworldPromotionPendingBulk(),
                dm.nestworldPromotionTotalApplied(),
                dm.nestworldPromotionAvgMs(),
                dm.nestworldPromotionPercentileMs(0.99),
                dm.nestworldPromotionTotalAdmitted(),
                dm.nestworldPumpDeadlineHits(),
                predictiveRequested,
                cache.chunkMap.nestworldGenConcurrency(),
                cache.chunkMap.nestworldGenPoolActive(),
                cache.chunkMap.nestworldGenPoolPending(),
                net.nestworld.region.GenerationAttribution.completedTotal(),
                net.nestworld.region.GenerationAttribution.failedTotal(),
                net.nestworld.region.GenerationAttribution.deferredTotal(),
                net.nestworld.region.GenerationAttribution.avgMs(),
                net.nestworld.region.GenerationAttribution.p99Ms(),
                net.nestworld.region.GenerationAttribution.maxMs(),
                net.nestworld.region.WorldgenGlueAttribution.chunkFinalizeCompletedTotal(),
                net.nestworld.region.WorldgenGlueAttribution.chunkFinalizeAvgMs(),
                net.nestworld.region.WorldgenGlueAttribution.chunkFinalizeP99Ms());
    }

    /** Server-wide tick-time snapshot (vanilla {@code tickTimes} ring buffer — same
     *  source {@code /nestworld mspt} reads), independent of any one dimension. */
    public static TickSnapshot ticks(MinecraftServer server) {
        if (server == null) {
            return new TickSnapshot(0, 0, 0, 0, 0, 0);
        }
        long[] raw = server.tickTimes.clone();
        double[] ms = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            ms[i] = raw[i] / 1_000_000.0;
        }
        java.util.Arrays.sort(ms);
        double avg = java.util.Arrays.stream(ms).average().orElse(0.0);
        double p50 = ms[(int) (ms.length * 0.50)];
        double p95 = ms[(int) Math.min(ms.length - 1, ms.length * 0.95)];
        double p99 = ms[(int) Math.min(ms.length - 1, ms.length * 0.99)];
        double max = ms[ms.length - 1];
        double tps = Math.min(20.0, 1000.0 / Math.max(avg, 0.001));
        return new TickSnapshot(avg, p50, p95, p99, max, tps);
    }

    /** "Хто винен": top-N mods by cumulative measured tick time (exact instrumentation,
     *  not sampled) for one kind ("entity" or "blockentity"), since server boot.
     *  See {@code net.nestworld.region.TickAttribution}. Empty (not null) if inactive. */
    public static List<ModTickCost> modLoad(String kind, int limit) {
        List<net.nestworld.region.TickAttribution.ModCost> src =
                net.nestworld.region.TickAttribution.topMods(kind, limit);
        List<ModTickCost> out = new ArrayList<>(src.size());
        for (var m : src) {
            out.add(new ModTickCost(m.modId(), kind, m.ms(), m.count()));
        }
        return out;
    }

    /** "В яких чанках": top-N chunks by measured tick cost (entity+block-entity combined)
     *  over the current decayed window, across all dimensions. See
     *  {@code net.nestworld.region.TickAttribution}. Empty (not null) if inactive. */
    public static List<ChunkTickCost> tickHotspots(int limit) {
        List<net.nestworld.region.TickAttribution.ChunkCost> src =
                net.nestworld.region.TickAttribution.topChunks(limit);
        List<ChunkTickCost> out = new ArrayList<>(src.size());
        for (var c : src) {
            out.add(new ChunkTickCost(c.dimensionKey(), c.chunkX(), c.chunkZ(), c.ms()));
        }
        return out;
    }
}
