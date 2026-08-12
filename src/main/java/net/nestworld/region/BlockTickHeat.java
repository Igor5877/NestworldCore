package net.nestworld.region;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Sparse per-chunk "heat" map of block-tick work: a rolling-window count of
 * scheduled block ticks, fluid ticks and block-entity ticks per chunk.
 *
 * <p>Why this exists: a pure block-tick machine (redstone clock, fluid array,
 * hopper bank) carries few or no entities. The split scorer used to weigh cut
 * lines by <em>entity</em> occupancy only, so it was blind to these machines —
 * it would happily leave a hot block-tick column inside a region's border band,
 * where those ticks run serially on the main thread (observed live: the east
 * column of the user's lag machine, stuck at 15–18 TPS). This map gives the
 * scorer a load signal for columns that have no entities, so it can route the
 * hot column into a region's interior (its own thread) instead.
 *
 * <p>It is also the answer to "show me what's actually happening": the heat per
 * region and the hottest chunks are surfaced in {@code /nestworld status} and
 * the split decision log, so cut choices can be understood and tuned from real
 * numbers.
 *
 * <p><b>Threading:</b> main-thread only. Every writer ({@code routeScheduledTick}
 * during tick bucketing) and the reader (the split scorer in
 * {@link RegionSplitManager}, the status command, the once-per-second decay) all
 * run on the server main thread between or within the tick. No synchronisation
 * is needed; this is documented rather than locked. Do not call from a
 * RegionThread.
 */
final class BlockTickHeat {

    /**
     * Multiplier applied to every cell once per second. 0.5 gives a ~2–3 s
     * memory: a column that stops ticking fades below {@link #FLOOR} within a
     * few seconds, so the scorer reacts to where load is <em>now</em>.
     */
    private static final double DECAY = 0.5;
    /** Cells below this after decay are dropped to keep the map sparse. */
    private static final double FLOOR = 0.75;

    private final Long2DoubleOpenHashMap counts = new Long2DoubleOpenHashMap();

    /**
     * Subset of {@link #counts} that still needs border-band protection: scheduled/fluid
     * ticks, block events, and ordinary (non-cascade-safe, non-pinned) block-entity ticks.
     * {@link RegionSplitManager}'s split veto is scored against this channel only, so a
     * cascade-safe machine's real load still influences WHERE to cut (via {@link #counts})
     * without ever counting toward WHETHER the cut is vetoed. Decayed in lockstep with
     * {@link #counts}; never contains more weight than {@link #counts} at the same key.
     */
    private final Long2DoubleOpenHashMap vetoCounts = new Long2DoubleOpenHashMap();

    BlockTickHeat() {
        counts.defaultReturnValue(0.0);
        vetoCounts.defaultReturnValue(0.0);
    }

    /**
     * Record one unit of block-tick work in the given chunk that does NOT count toward the
     * split veto (a cascade-safe or pinned block-entity tick — see {@link NestworldPins}).
     * Still real load, so it still informs cut position via {@link #counts}. Main thread only.
     */
    void record(int chunkX, int chunkZ) {
        counts.addTo(ChunkPos.asLong(chunkX, chunkZ), 1.0);
    }

    /**
     * Record one unit of block-tick work that DOES count toward the split veto (scheduled/fluid
     * ticks, block events, ordinary block-entity ticks — anything that still needs border-band
     * protection). Main thread only.
     */
    void recordVetoable(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        counts.addTo(key, 1.0);
        vetoCounts.addTo(key, 1.0);
    }

    /**
     * Apply the rolling-window decay. Call once per second from the main thread
     * (the split manager's 20-tick evaluation).
     */
    void decay() {
        decayMap(counts);
        decayMap(vetoCounts);
    }

    private static void decayMap(Long2DoubleOpenHashMap map) {
        if (map.isEmpty()) return;
        var it = map.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            double v = e.getDoubleValue() * DECAY;
            if (v < FLOOR) {
                it.remove();
            } else {
                e.setValue(v);
            }
        }
    }

    /** Total recent block-tick heat inside a region's chunk bounds. */
    double totalInRegion(WorldRegion r) {
        if (counts.isEmpty()) return 0.0;
        double sum = 0.0;
        var it = counts.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            long key = e.getLongKey();
            if (r.containsChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                sum += e.getDoubleValue();
            }
        }
        return sum;
    }

    /**
     * Add this region's heat, projected onto {@code axis} and scaled by
     * {@code weight}, into a coord→weight histogram the scorer is building.
     * Only chunks inside the region's bounds contribute. Full picture (all
     * sources) — used to decide WHERE to cut, never the veto.
     */
    void addAxisHeat(WorldRegion r, SplitAxis axis, double weight,
                     java.util.Map<Integer, Double> into) {
        addAxisHeatFrom(counts, r, axis, weight, into);
    }

    /**
     * Same as {@link #addAxisHeat}, but only the subset of heat that still needs
     * border-band protection ({@link #vetoCounts}) — this is what {@link RegionSplitManager}
     * must pass to {@code scoreCut}'s veto check, so cascade-safe/pinned heat never blocks
     * a split it wouldn't actually need to avoid.
     */
    void addAxisHeatVetoOnly(WorldRegion r, SplitAxis axis, double weight,
                             java.util.Map<Integer, Double> into) {
        addAxisHeatFrom(vetoCounts, r, axis, weight, into);
    }

    private static void addAxisHeatFrom(Long2DoubleOpenHashMap source, WorldRegion r, SplitAxis axis,
                                        double weight, java.util.Map<Integer, Double> into) {
        if (source.isEmpty() || weight == 0.0) return;
        var it = source.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            long key = e.getLongKey();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            if (!r.containsChunk(cx, cz)) continue;
            int coord = (axis == SplitAxis.X) ? cx : cz;
            into.merge(coord, weight * e.getDoubleValue(), Double::sum);
        }
    }

    /** Defensive copy of the raw per-chunk heat map, world-wide (not region-scoped) — for
     *  {@link HotspotDetector}'s combined ranking (NestWorld Inspector §4). Main thread only. */
    Long2DoubleOpenHashMap snapshotCounts() {
        return new Long2DoubleOpenHashMap(counts);
    }

    /** {@code "(x,z)=h"} for the hottest {@code topN} chunks in a region — for diagnostics. */
    String hotspotSummary(WorldRegion r, int topN) {
        if (counts.isEmpty()) return "none";
        List<long[]> rows = new ArrayList<>(); // {key, scaledHeatx10}
        var it = counts.long2DoubleEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2DoubleMap.Entry e = it.next();
            long key = e.getLongKey();
            if (r.containsChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                rows.add(new long[]{key, Math.round(e.getDoubleValue() * 10)});
            }
        }
        if (rows.isEmpty()) return "none";
        rows.sort((a, b) -> Long.compare(b[1], a[1]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(topN, rows.size()); i++) {
            if (i > 0) sb.append(' ');
            long key = rows.get(i)[0];
            sb.append('(').append(ChunkPos.getX(key)).append(',').append(ChunkPos.getZ(key))
              .append(")=").append(String.format("%.1f", rows.get(i)[1] / 10.0));
        }
        return sb.toString();
    }
}
