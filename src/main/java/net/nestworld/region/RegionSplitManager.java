package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Monitors per-region tick cost and decides when to split or merge regions.
 *
 * <p>Called by NestworldRegionSystem every tick; evaluates cost every 20 ticks
 * (1 second of game time). Split/merge operations are applied while all
 * region threads are parked between ticks, so the BSP tree mutates atomically.
 *
 * <p>Thresholds are expressed as the region's own tick cost in milliseconds
 * rather than per-region TPS: region TPS is capped at 20, so a region eating
 * 45 of the 50 ms budget still reported a "healthy" 20 TPS while the server
 * as a whole fell behind. Comparing raw cost against a share of the budget
 * makes the heuristic see overload the way the server does.
 *
 * <p>Hysteresis prevents thrashing:
 * <ul>
 *   <li>Split: cost &gt; {@value #SPLIT_MS_THRESHOLD} ms for {@value #SPLIT_CHECKS_REQUIRED} consecutive evaluations (100 game ticks)</li>
 *   <li>Merge: cost &lt; {@value #MERGE_MS_THRESHOLD} ms for {@value #MERGE_CHECKS_REQUIRED} consecutive evaluations (500 game ticks);
 *       two 10 ms siblings merge into ≤ ~20 ms, comfortably under the split threshold</li>
 * </ul>
 *
 * <p>A region at minimum size (1×1 chunk) is never split further.
 */
public class RegionSplitManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SplitManager");

    // --- Thresholds ---
    // Counters advance once per evaluation (every 20 game ticks), so the design
    // targets "100 ticks above 25 ms" = 5 evaluations, "500 ticks under 10 ms" = 25.
    private static final double SPLIT_MS_THRESHOLD = 25.0; // half the 50 ms tick budget
    private static final double MERGE_MS_THRESHOLD = 10.0;
    private static final int    SPLIT_CHECKS_REQUIRED = 5;  // 100 game ticks
    private static final int    MERGE_CHECKS_REQUIRED = 25; // 500 game ticks

    // --- Point-hotspot split guard ---
    // A split only helps when each child receives a meaningful share of the
    // entity load; below this share the cut just peels off a near-idle region.
    private static final double MIN_SPLIT_SEPARATION_SHARE = 0.10;
    // Regions with fewer entities than this are exempt from the guard: their
    // cost comes from block ticks, which a spatial split can still separate.
    private static final int    GUARD_MIN_ENTITIES = 50;

    private final RegionTree tree;
    private final RegionThreadPool pool;

    // Pending splits applied at the next evaluation
    private final List<WorldRegion> pendingSplits = new ArrayList<>();
    /**
     * Merge candidates persist across evaluations (insertion-ordered) because
     * sibling regions rarely cross the threshold in the same second. A candidate
     * is dropped when its tick cost rises back above the merge threshold or it
     * is no longer an active region.
     */
    private final Set<WorldRegion> mergeCandidates = new LinkedHashSet<>();

    private int checkInterval = 0;

    public RegionSplitManager(RegionTree tree, RegionThreadPool pool) {
        this.tree = tree;
        this.pool = pool;
    }

    // -----------------------------------------------------------------------
    // Called every tick from main thread (cheap path most of the time)
    // -----------------------------------------------------------------------

    /**
     * Must be called from the main thread each tick, after all region threads
     * have finished their tick. Every 20 ticks it evaluates TPS per region.
     */
    public void onTick() {
        if (++checkInterval < 20) return;
        checkInterval = 0;

        List<WorldRegion> active = tree.getActiveRegions();
        for (WorldRegion region : active) {
            double costMs = region.getAvgTickMs();

            if (costMs > SPLIT_MS_THRESHOLD) {
                region.mergePressureChecks = 0;
                mergeCandidates.remove(region);
                if (++region.splitPressureChecks >= SPLIT_CHECKS_REQUIRED && region.canSplit()) {
                    if (splitWouldSeparateLoad(region)) {
                        pendingSplits.add(region);
                    } else {
                        logSkippedSplit(region);
                    }
                    region.resetThresholdCounters();
                }
            } else if (costMs < MERGE_MS_THRESHOLD) {
                region.splitPressureChecks = 0;
                if (++region.mergePressureChecks >= MERGE_CHECKS_REQUIRED && !region.pinned) {
                    mergeCandidates.add(region); // persists until merged or cost rises
                }
            } else {
                region.splitPressureChecks = 0;
                region.mergePressureChecks = 0;
                mergeCandidates.remove(region);
            }
        }
        // Drop candidates that are no longer active leaves (already merged/split)
        mergeCandidates.retainAll(active);

        applyPending();
    }

    // -----------------------------------------------------------------------
    // Apply queued operations between ticks (region threads are parked)
    // -----------------------------------------------------------------------

    private void applyPending() {
        for (WorldRegion region : pendingSplits) {
            doSplit(region);
        }
        pendingSplits.clear();

        // Try to pair up merge candidates that are tree siblings
        List<WorldRegion> candidates = new ArrayList<>(mergeCandidates);
        for (int i = 0; i < candidates.size(); i++) {
            WorldRegion a = candidates.get(i);
            if (!mergeCandidates.contains(a)) continue;
            for (int j = i + 1; j < candidates.size(); j++) {
                WorldRegion b = candidates.get(j);
                if (!mergeCandidates.contains(b)) continue;
                if (doMerge(a, b) != null) {
                    mergeCandidates.remove(a);
                    mergeCandidates.remove(b);
                    break;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Core operations (also used by the /nestworld admin command)
    // -----------------------------------------------------------------------

    /**
     * Splits a region into two children, migrating threads and entity ownership.
     * Returns the children, or null if the region cannot be split.
     */
    public WorldRegion[] doSplit(WorldRegion region) {
        Integer cut = loadAwareCut(region);
        // Manual /nestworld split bypasses the hotspot veto: fall back to the
        // raw median so an operator can still force any cut.
        WorldRegion[] children = tree.split(region, cut != null ? cut : entityMedianCut(region));
        if (children == null) return null; // at min size or not in tree

        pool.remove(region);
        pool.spawn(children[0]);
        pool.spawn(children[1]);

        // Hand all entities to child A; BoundaryEntityTransfer reassigns any
        // that actually live in child B on the next tick via the grid lookup.
        for (java.util.UUID uuid : region.getOwnedEntityIds()) {
            children[0].addEntity(uuid);
        }

        LOGGER.info("Split {} -> [{}, {}]  (cost was {} ms)",
                region, children[0], children[1], String.format("%.1f", region.getAvgTickMs()));
        return children;
    }

    /**
     * Chunk coordinate (on the region's preferred split axis) of the median
     * owned entity — the cut that actually halves the region's entity load.
     * Falls back to the spatial midpoint when the region owns no entities.
     */
    private int entityMedianCut(WorldRegion region) {
        java.util.List<Integer> coords = ownedEntityChunkCoords(region, region.preferredSplitAxis());
        if (coords.isEmpty()) return Integer.MIN_VALUE; // spatial midpoint
        return coords.get(coords.size() / 2);
    }

    /** Sorted chunk coordinates (on the split axis) of the region's owned entities. */
    private java.util.List<Integer> ownedEntityChunkCoords(WorldRegion region, SplitAxis axis) {
        net.minecraft.server.level.ServerLevel level = pool.getLevel();
        java.util.ArrayList<Integer> coords = new java.util.ArrayList<>();
        for (java.util.UUID id : region.getOwnedEntityIds()) {
            net.minecraft.world.entity.Entity entity = level.getEntity(id);
            if (entity == null) continue;
            net.minecraft.core.BlockPos pos = entity.blockPosition();
            coords.add((axis == SplitAxis.X ? pos.getX() : pos.getZ()) >> 4);
        }
        java.util.Collections.sort(coords);
        return coords;
    }

    /** Share of region entities tolerated inside the cut line's border band. */
    private static final double MAX_CUT_BAND_SHARE = 0.25;
    /** Must mirror NestworldRegionSystem.BORDER_BAND_CHUNKS. */
    private static final int BORDER_BAND_CHUNKS = 2;

    /**
     * Picks the split line. A pure median cut lands exactly on the densest
     * entity cluster — a lag machine IS its region's median — and everything
     * within the border band of a region edge ticks serially on main, so a
     * median split drags the very hotspot it targets OUT of parallel
     * execution (and the next split re-cuts it wherever it is moved).
     * Instead, candidate cuts across the middle half of the entity
     * distribution (entity columns and the gaps between them) are scored by
     * how many entities their 2×band would swallow; the quietest fair line
     * wins, ties broken toward the median.
     *
     * @return cut chunk coordinate; Integer.MIN_VALUE = spatial midpoint
     *         (region owns no entities); null = no line gives both children
     *         a fair share without running through a cluster — the region
     *         must stay whole (the per-tick budget contains it instead).
     */
    private Integer loadAwareCut(WorldRegion region) {
        SplitAxis axis = region.preferredSplitAxis();
        java.util.List<Integer> coords = ownedEntityChunkCoords(region, axis);
        if (coords.isEmpty()) return Integer.MIN_VALUE;

        int min = axis == SplitAxis.X ? region.getMinChunkX() : region.getMinChunkZ();
        int max = axis == SplitAxis.X ? region.getMaxChunkX() : region.getMaxChunkZ();
        int n = coords.size();
        int median = Math.max(min, Math.min(coords.get(n / 2), max - 1));
        // Too few entities to profile: cost is from block ticks, keep the
        // median cut (pre-existing behaviour below GUARD_MIN_ENTITIES).
        if (n < GUARD_MIN_ENTITIES) return median;

        java.util.TreeSet<Integer> candidates = new java.util.TreeSet<>();
        for (int i = n / 4; i < (3 * n) / 4; i++) {
            int a = coords.get(i);
            candidates.add(a);
            if (i + 1 < n) {
                int b = coords.get(i + 1);
                if (b - a > 1) candidates.add(a + (b - a) / 2); // gap midpoint
            }
        }

        Integer best = null;
        int bestBand = Integer.MAX_VALUE;
        for (int raw : candidates) {
            int cut = Math.max(min, Math.min(raw, max - 1));
            int toA = countAtMost(coords, cut);
            if (Math.min(toA, n - toA) < n * MIN_SPLIT_SEPARATION_SHARE) continue;
            // Chunk columns [cut-1, cut+2] land in one of the children's bands.
            int band = countAtMost(coords, cut + BORDER_BAND_CHUNKS)
                     - countAtMost(coords, cut - BORDER_BAND_CHUNKS);
            if (band < bestBand
                    || (band == bestBand && best != null && Math.abs(cut - median) < Math.abs(best - median))) {
                bestBand = band;
                best = cut;
            }
        }
        if (best == null || bestBand > n * MAX_CUT_BAND_SHARE) return null;
        return best;
    }

    /** Entities with chunk coordinate <= c (coords sorted ascending). */
    private static int countAtMost(java.util.List<Integer> coords, int c) {
        int lo = 0, hi = coords.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (coords.get(mid) <= c) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * True when some cut line gives each child a meaningful share of the
     * entity load without slicing through a cluster (see loadAwareCut).
     */
    private boolean splitWouldSeparateLoad(WorldRegion region) {
        return loadAwareCut(region) != null;
    }

    private long lastGuardLogNanos = 0;

    private void logSkippedSplit(WorldRegion region) {
        long now = System.nanoTime();
        if (now - lastGuardLogNanos < 30_000_000_000L) return; // at most every 30 s
        lastGuardLogNanos = now;
        LOGGER.info("Split of {} skipped: no cut line separates the entity load without slicing a cluster ({} entities)",
                region, region.getOwnedEntityIds().size());
    }

    /**
     * Merges two sibling regions into one, migrating threads and entity ownership.
     * Returns the merged region, or null if the two are not tree siblings.
     */
    public WorldRegion doMerge(WorldRegion a, WorldRegion b) {
        WorldRegion merged = tree.merge(a, b);
        if (merged == null) return null;

        pool.remove(a);
        pool.remove(b);
        pool.spawn(merged);

        for (java.util.UUID uuid : a.getOwnedEntityIds()) merged.addEntity(uuid);
        for (java.util.UUID uuid : b.getOwnedEntityIds()) merged.addEntity(uuid);

        LOGGER.info("Merged [{}, {}] -> {}  (costs were {} ms, {} ms)",
                a, b, merged, String.format("%.1f", a.getAvgTickMs()), String.format("%.1f", b.getAvgTickMs()));
        return merged;
    }
}
