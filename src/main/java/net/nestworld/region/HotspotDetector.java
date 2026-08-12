package net.nestworld.region;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NestWorld Inspector §4/§5 (docs/NESTWORLD_INSPECTOR_SPEC.md) — first slice: on-demand, zero-idle-cost
 * hotspot ranking combining block/fluid-tick heat (already tracked continuously by {@link BlockTickHeat}
 * for the split scorer) with entity density (computed fresh from every region's already-published
 * {@link WorldRegion#nestworldGetEntitySnapshot()} map, no new continuous tracking needed). Computed
 * ONLY when {@code /nestworld hotspots} runs — true zero overhead the rest of the time, matching the
 * spec's "minimal production overhead" requirement (§1, §13).
 *
 * <p>Deliberately does NOT attempt real stack-sampled mod attribution (spec §6) or per-hotspot MSPT
 * (spec §1/§2) — those are later phases. This gives WHAT (heat score, dominant channel) and WHERE
 * (a real in-game position), the first two of the spec's three guiding questions (§15); WHY in the
 * "which mod" sense is approximated here only via entity-type breakdown (safe: {@link Entity#getType()}
 * is an immutable field, safe to read live from any thread, unlike position/velocity/health).
 *
 * <p>Main-thread only: reads {@link BlockTickHeat}'s internals (already documented main-thread-only)
 * and every region's published {@code EntitySnapshot} map (documented safe from any thread, but this
 * class is only ever invoked from the command dispatcher, which is main-thread).
 */
final class HotspotDetector {
    private static final double ENTITY_WEIGHT = 1.0; // one live entity ~= one unit of block-tick heat

    record Hotspot(int rank, WorldRegion region, double totalHeat, double blockTickHeat,
                    double entityHeat, BlockPos position, ChunkPos chunk,
                    List<Map.Entry<String, Integer>> topEntityTypes) {}

    private static List<Hotspot> lastComputed = List.of();

    private HotspotDetector() {}

    static List<Hotspot> compute(NestworldRegionSystem sys, int topN) {
        Long2DoubleOpenHashMap blockOnly = sys.getBlockTickHeat().snapshotCounts();

        Long2DoubleOpenHashMap entityOnly = new Long2DoubleOpenHashMap();
        entityOnly.defaultReturnValue(0.0);
        Map<Long, List<UUID>> entitiesByChunk = new HashMap<>();
        Map<UUID, WorldRegion> ownerOf = new HashMap<>();
        for (WorldRegion r : sys.getGrid().getAllRegions()) {
            for (Map.Entry<UUID, WorldRegion.EntitySnapshot> entry : r.nestworldGetEntitySnapshot().entrySet()) {
                WorldRegion.EntitySnapshot snap = entry.getValue();
                if (snap.removed()) continue;
                int cx = Mth.floor(snap.x()) >> 4;
                int cz = Mth.floor(snap.z()) >> 4;
                long key = ChunkPos.asLong(cx, cz);
                entityOnly.addTo(key, ENTITY_WEIGHT);
                entitiesByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
                ownerOf.put(entry.getKey(), r);
            }
        }

        Long2DoubleOpenHashMap combined = new Long2DoubleOpenHashMap(blockOnly);
        combined.defaultReturnValue(0.0);
        var eit = entityOnly.long2DoubleEntrySet().fastIterator();
        while (eit.hasNext()) {
            Long2DoubleMap.Entry e = eit.next();
            combined.addTo(e.getLongKey(), e.getDoubleValue());
        }

        List<long[]> ranked = new ArrayList<>();
        var it = combined.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            ranked.add(new long[]{e.getLongKey(), Math.round(e.getDoubleValue() * 100)});
        }
        ranked.sort((a, b) -> Long.compare(b[1], a[1]));

        List<Hotspot> out = new ArrayList<>();
        ServerLevel level = sys.getOverworld();
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            long key = ranked.get(i)[0];
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            double total = combined.get(key);
            double bHeat = blockOnly.get(key);
            double eHeat = entityOnly.get(key);
            WorldRegion owner = sys.getGrid().getRegionForChunk(cx, cz);

            List<UUID> ids = entitiesByChunk.getOrDefault(key, List.of());
            Map<String, Integer> typeCounts = new HashMap<>();
            BlockPos pos = null;
            for (UUID uuid : ids) {
                WorldRegion entOwner = ownerOf.get(uuid);
                WorldRegion.EntitySnapshot snap = entOwner != null
                        ? entOwner.nestworldGetEntitySnapshot().get(uuid) : null;
                if (snap == null || snap.removed()) continue;
                if (pos == null) {
                    pos = new BlockPos(Mth.floor(snap.x()), Mth.floor(snap.y()), Mth.floor(snap.z()));
                }
                Entity live = level.getEntity(uuid);
                if (live != null) {
                    String key2 = EntityType.getKey(live.getType()).toString();
                    typeCounts.merge(key2, 1, Integer::sum);
                }
            }
            if (pos == null) pos = chunkCenter(level, cx, cz);

            List<Map.Entry<String, Integer>> topTypes = new ArrayList<>(typeCounts.entrySet());
            topTypes.sort((a, b) -> b.getValue() - a.getValue());
            if (topTypes.size() > 3) topTypes = topTypes.subList(0, 3);

            out.add(new Hotspot(i + 1, owner, total, bHeat, eHeat, pos, new ChunkPos(cx, cz), topTypes));
        }
        lastComputed = out;
        return out;
    }

    static Hotspot get(int rank) {
        for (Hotspot h : lastComputed) {
            if (h.rank() == rank) return h;
        }
        return null;
    }

    /** Best-effort "interesting" position inside a region: the hottest chunk it owns (block-tick +
     *  entity heat combined), or the geometric center (heightmap-safe) if it has no measurable
     *  activity right now — used by {@code /nestworld goto region <id>} (spec §5). */
    static BlockPos bestPositionInRegion(NestworldRegionSystem sys, WorldRegion region) {
        Long2DoubleOpenHashMap blockOnly = sys.getBlockTickHeat().snapshotCounts();
        ServerLevel level = sys.getOverworld();

        long bestKey = Long.MIN_VALUE;
        double bestHeat = 0.0;
        var it = blockOnly.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            long key = e.getLongKey();
            if (region.containsChunk(ChunkPos.getX(key), ChunkPos.getZ(key)) && e.getDoubleValue() > bestHeat) {
                bestHeat = e.getDoubleValue();
                bestKey = key;
            }
        }
        for (Map.Entry<UUID, WorldRegion.EntitySnapshot> entry : region.nestworldGetEntitySnapshot().entrySet()) {
            WorldRegion.EntitySnapshot snap = entry.getValue();
            if (snap.removed()) continue;
            return new BlockPos(Mth.floor(snap.x()), Mth.floor(snap.y()), Mth.floor(snap.z()));
        }
        if (bestKey != Long.MIN_VALUE) {
            return chunkCenter(level, ChunkPos.getX(bestKey), ChunkPos.getZ(bestKey));
        }
        int cx = clampChunkCoord(region.getMinChunkX(), region.getMaxChunkX());
        int cz = clampChunkCoord(region.getMinChunkZ(), region.getMaxChunkZ());
        return chunkCenter(level, cx, cz);
    }

    /** Region bounds can be world-sized (±1_875_000 chunks) for catch-all edge regions — the
     *  geometric midpoint of such a region is meaningless/likely-ungenerated wilderness, so clamp
     *  toward a reasonable near-origin fallback instead of literally averaging huge bounds. */
    private static int clampChunkCoord(int min, int max) {
        long mid = ((long) min + (long) max) / 2;
        long clamped = Math.max(-3750, Math.min(3750, mid));
        return (int) clamped;
    }

    private static BlockPos chunkCenter(ServerLevel level, int cx, int cz) {
        int x = (cx << 4) + 8;
        int z = (cz << 4) + 8;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new BlockPos(x, y, z);
    }
}
