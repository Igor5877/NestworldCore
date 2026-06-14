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
    // load; below this share the cut just peels off a near-idle region.
    private static final double MIN_SPLIT_SEPARATION_SHARE = 0.10;
    // Combined load (owned entities at weight 1 + block-tick heat scaled by
    // CUT_HEAT_WEIGHT) below which a region is too quiet to profile: keep the
    // weighted-median cut and let the per-tick budget contain whatever cost
    // there is. Above it, candidate lines are scored and a band-slicing cut is
    // vetoed.
    private static final double GUARD_MIN_LOAD = 50.0;

    private final RegionTree tree;
    private final RegionThreadPool pool;
    private final BlockTickHeat blockTickHeat;

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

    public RegionSplitManager(RegionTree tree, RegionThreadPool pool, BlockTickHeat blockTickHeat) {
        this.tree = tree;
        this.pool = pool;
        this.blockTickHeat = blockTickHeat;
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

        // Once per second: age the block-tick heat window so the scorer reacts
        // to where load is now, not where it was minutes ago.
        blockTickHeat.decay();

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
        CutChoice choice = loadAwareCut(region);
        // Manual /nestworld split bypasses the hotspot veto: fall back to the
        // raw median on the preferred axis so an operator can still force a cut.
        WorldRegion[] children = (choice != null)
                ? tree.split(region, choice.axis, choice.cut)
                : tree.split(region, entityMedianCut(region));
        if (children == null) return null; // at min size or not in tree

        pool.remove(region);
        pool.spawn(children[0]);
        pool.spawn(children[1]);

        // Hand all entities to child A; BoundaryEntityTransfer reassigns any
        // that actually live in child B on the next tick via the grid lookup.
        for (java.util.UUID uuid : region.getOwnedEntityIds()) {
            children[0].addEntity(uuid);
        }

        LOGGER.info("Split {} -> [{}, {}]  (cost was {} ms; {} entities, heat {}; cut {} axis; hottest {})",
                region, children[0], children[1], String.format("%.1f", region.getAvgTickMs()),
                region.getOwnedEntityIds().size(), String.format("%.1f", blockTickHeat.totalInRegion(region)),
                choice != null ? choice.axis : region.preferredSplitAxis(),
                blockTickHeat.hotspotSummary(region, 3));
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

    /** Share of region load tolerated inside the cut line's border band. */
    private static final double MAX_CUT_BAND_SHARE = 0.25;
    /** Must mirror NestworldRegionSystem.BORDER_BAND_CHUNKS. */
    private static final int BORDER_BAND_CHUNKS = 2;
    /** Sentinel: region has no profileable load, cut at the spatial midpoint. */
    static final int SPATIAL_MIDPOINT = Integer.MIN_VALUE;

    /**
     * Builds the region's weighted load histogram on its preferred split axis
     * and asks {@link #scoreCut} for a line. The histogram combines two load
     * sources so the scorer sees the whole picture, not just mobs:
     * <ul>
     *   <li>each owned entity contributes weight 1 at its chunk column;</li>
     *   <li>block-tick heat (scheduled/fluid/BE ticks — redstone and fluid
     *       machines that carry no entities) contributes
     *       {@link NestworldTuning#CUT_HEAT_WEIGHT} per unit.</li>
     * </ul>
     * Without the heat term a redstone machine is invisible and the scorer
     * leaves its hot column in a border band (serial on main).
     *
     * @return cut chunk coordinate; {@link #SPATIAL_MIDPOINT} when the region
     *         has no profileable load; null when no line gives both children a
     *         fair share without slicing a cluster or a hot block-tick column
     *         through a band — the region stays whole (the per-tick budget
     *         contains it instead).
     */
    private CutChoice loadAwareCut(WorldRegion region) {
        SplitAxis pref = region.preferredSplitAxis();
        SplitAxis perp = pref == SplitAxis.X ? SplitAxis.Z : SplitAxis.X;

        ScoredCut sp = scoreAxis(region, pref);
        // Unprofiled (empty region, or load below GUARD_MIN_LOAD): keep the
        // weighted-median cut on the preferred axis and let the per-tick budget
        // contain whatever little cost there is. Total load is axis-independent,
        // so the perpendicular axis is unprofiled too — no point scoring it.
        if (sp != null && !sp.profiled) return new CutChoice(pref, sp.cut);

        // Both axes are profiled (or vetoed). Score the perpendicular axis too
        // when it has room to cut, then take whichever valid line swallows the
        // least load into its border band. A redstone/fluid machine elongated
        // along the long axis often has no clean line there but a quiet gap
        // across its short axis — single-axis scoring left its edge column in a
        // band (serial on main); cross-axis scoring routes it into an interior.
        ScoredCut se = axisSplittable(region, perp) ? scoreAxis(region, perp) : null;

        CutChoice best = null;
        double bestBand = Double.MAX_VALUE;
        if (sp != null && sp.profiled)            { best = new CutChoice(pref, sp.cut); bestBand = sp.band; }
        if (se != null && se.profiled && se.band < bestBand) {
            best = new CutChoice(perp, se.cut);   bestBand = se.band;
        }
        return best; // null → no axis separates the load; region stays whole
    }

    /** True when {@code axis} spans more than one chunk in this region (room to cut). */
    private static boolean axisSplittable(WorldRegion r, SplitAxis axis) {
        return axis == SplitAxis.X ? r.getMaxChunkX() > r.getMinChunkX()
                                   : r.getMaxChunkZ() > r.getMinChunkZ();
    }

    /** Builds {@code region}'s weighted load histogram on {@code axis} and scores it. */
    private ScoredCut scoreAxis(WorldRegion region, SplitAxis axis) {
        int min = axis == SplitAxis.X ? region.getMinChunkX() : region.getMinChunkZ();
        int max = axis == SplitAxis.X ? region.getMaxChunkX() : region.getMaxChunkZ();

        java.util.TreeMap<Integer, Double> hist = new java.util.TreeMap<>();
        for (int c : ownedEntityChunkCoords(region, axis)) {
            hist.merge(c, 1.0, Double::sum);
        }
        blockTickHeat.addAxisHeat(region, axis, NestworldTuning.CUT_HEAT_WEIGHT, hist);

        return scoreCut(min, max, hist);
    }

    /** Which axis to cut on, and the last chunk coordinate kept by child A. */
    static final class CutChoice {
        final SplitAxis axis;
        final int cut;
        CutChoice(SplitAxis axis, int cut) { this.axis = axis; this.cut = cut; }
    }

    /**
     * The outcome of scoring one axis: the chosen cut coordinate and how much
     * load its 2×band would swallow, so {@link #loadAwareCut} can compare the
     * two axes. {@code profiled} is false for the empty/below-guard cases where
     * {@code band} is not meaningful (and is the same on both axes anyway).
     */
    static final class ScoredCut {
        final int cut;          // chunk coord on the scored axis, or SPATIAL_MIDPOINT
        final double band;      // load inside the cut's 2×band; NaN when unprofiled
        final boolean profiled;
        ScoredCut(int cut, double band, boolean profiled) {
            this.cut = cut; this.band = band; this.profiled = profiled;
        }
    }

    /**
     * Pure cut scorer over a coord→weight load histogram on one axis,
     * bounded to chunk coordinates [{@code min}, {@code max}] (max inclusive).
     * Extracted from {@link #loadAwareCut} so it can be exercised in isolation
     * by {@link #selfTest()} without a live world.
     *
     * <p>A pure median cut lands on the densest cluster (a lag machine IS its
     * region's median) and everything within a region edge's border band ticks
     * serially on main — so a median split drags the very hotspot it targets
     * out of parallel execution. Instead, candidate lines (populated columns
     * and the gaps between them) are scored by how much load their 2×band would
     * swallow; the quietest line that still gives both children at least
     * {@link #MIN_SPLIT_SEPARATION_SHARE} wins, ties broken toward the weighted
     * median. If even the best line would slice more than
     * {@link #MAX_CUT_BAND_SHARE} of the load into a band, the region stays
     * whole.
     */
    static ScoredCut scoreCut(int min, int max, java.util.SortedMap<Integer, Double> hist) {
        if (hist.isEmpty()) return new ScoredCut(SPATIAL_MIDPOINT, Double.NaN, false);

        int m = hist.size();
        int[] coord = new int[m];
        double[] w = new double[m];
        double total = 0;
        int i = 0;
        for (java.util.Map.Entry<Integer, Double> e : hist.entrySet()) {
            coord[i] = e.getKey();
            w[i] = e.getValue();
            total += w[i];
            i++;
        }

        int median = clampCut(weightedMedian(coord, w, total), min, max);
        // Too little load to profile: keep the weighted-median cut (a low-cost
        // region is still allowed to split, the budget contains any hotspot).
        if (total < GUARD_MIN_LOAD) return new ScoredCut(median, Double.NaN, false);

        java.util.TreeSet<Integer> candidates = new java.util.TreeSet<>();
        for (int k = 0; k < m; k++) {
            candidates.add(coord[k]);
            if (k + 1 < m) {
                int gap = coord[k + 1] - coord[k];
                if (gap > 1) candidates.add(coord[k] + gap / 2); // gap midpoint
            }
        }

        Integer best = null;
        double bestBand = Double.MAX_VALUE;
        for (int raw : candidates) {
            int cut = clampCut(raw, min, max);
            double toA = weightAtMost(coord, w, cut);
            if (Math.min(toA, total - toA) < total * MIN_SPLIT_SEPARATION_SHARE) continue;
            // Columns (cut-band, cut+band] land in one of the children's bands.
            double band = weightAtMost(coord, w, cut + BORDER_BAND_CHUNKS)
                        - weightAtMost(coord, w, cut - BORDER_BAND_CHUNKS);
            if (band < bestBand
                    || (band == bestBand && best != null && Math.abs(cut - median) < Math.abs(best - median))) {
                bestBand = band;
                best = cut;
            }
        }
        if (best == null || bestBand > total * MAX_CUT_BAND_SHARE) return null;
        return new ScoredCut(best, bestBand, true);
    }

    private static int clampCut(int c, int min, int max) {
        return Math.max(min, Math.min(c, max - 1));
    }

    /** Smallest column coordinate whose cumulative weight reaches half the total. */
    private static int weightedMedian(int[] coord, double[] w, double total) {
        double half = total / 2.0, run = 0;
        for (int i = 0; i < coord.length; i++) {
            run += w[i];
            if (run >= half) return coord[i];
        }
        return coord[coord.length - 1];
    }

    /** Sum of weights at columns with coordinate <= c (coord ascending). */
    private static double weightAtMost(int[] coord, double[] w, int c) {
        double sum = 0;
        for (int i = 0; i < coord.length && coord[i] <= c; i++) sum += w[i];
        return sum;
    }

    /**
     * True when some cut line gives each child a meaningful share of the
     * entity load without slicing through a cluster (see loadAwareCut).
     */
    private boolean splitWouldSeparateLoad(WorldRegion region) {
        return loadAwareCut(region) != null;
    }

    // -----------------------------------------------------------------------
    // Deterministic self-test of the pure cut scorer (NESTWORLD_CUT_TEST=1).
    // Runs headless at startup with no world; verifies the scoring logic that
    // is otherwise hard to provoke without live redstone near a player.
    // -----------------------------------------------------------------------

    /** Builds a column→weight histogram from {coord, weight, coord, weight, ...}. */
    private static java.util.TreeMap<Integer, Double> hist(double... cw) {
        java.util.TreeMap<Integer, Double> h = new java.util.TreeMap<>();
        for (int i = 0; i < cw.length; i += 2) h.merge((int) cw[i], cw[i + 1], Double::sum);
        return h;
    }

    public static boolean selfTest() {
        boolean ok = true;

        // Empty region → spatial midpoint sentinel.
        ScoredCut empty = scoreCut(0, 24, hist());
        ok &= check("empty→midpoint", empty != null && empty.cut == SPATIAL_MIDPOINT && !empty.profiled);

        // Two entity clusters with an empty gap 5..19: cut must land in the gap
        // (band touches neither cluster) and split the load fairly.
        java.util.TreeMap<Integer, Double> twoClusters = new java.util.TreeMap<>();
        for (int c = 0; c <= 4; c++) twoClusters.merge(c, 20.0, Double::sum);
        for (int c = 20; c <= 24; c++) twoClusters.merge(c, 20.0, Double::sum);
        ScoredCut gapCut = scoreCut(0, 24, twoClusters);
        ok &= check("gap: cut found", gapCut != null && gapCut.profiled);
        ok &= check("gap: cut inside quiet zone", gapCut != null && gapCut.cut >= 7 && gapCut.cut <= 17);
        ok &= check("gap: band is empty", gapCut != null && gapCut.band == 0.0);

        // Single dense column (point hotspot, no gap): no fair cut exists, the
        // region must stay whole (budget contains it).
        ok &= check("point-hotspot→veto", scoreCut(0, 20, hist(10, 200.0)) == null);

        // A block-tick machine (column 15, heat-only weight 100) plus entities
        // in columns 0..9: the cut must route the machine into a child interior,
        // never leaving it inside a band. Cut 12 puts col 15 (>12+2) interior.
        java.util.TreeMap<Integer, Double> machine = new java.util.TreeMap<>();
        for (int c = 0; c <= 9; c++) machine.merge(c, 5.0, Double::sum);
        machine.merge(15, 100.0, Double::sum); // heat-projected block-tick column
        ScoredCut mCut = scoreCut(0, 30, machine);
        ok &= check("machine: cut found", mCut != null);
        ok &= check("machine: machine column out of band",
                mCut != null && Math.abs(15 - mCut.cut) > BORDER_BAND_CHUNKS);

        // Cross-axis selection: a machine elongated along one axis is a single
        // inseparable hot column when projected onto that (long) axis — no fair
        // cut, vetoed — but across its short axis the same machine shows a quiet
        // gap that splits cleanly. loadAwareCut picks whichever axis yields the
        // lower-band valid cut, so the perpendicular axis must rescue the split.
        ok &= check("cross-axis: long axis vetoes", scoreCut(0, 30, hist(15, 200.0)) == null);
        java.util.TreeMap<Integer, Double> shortAxis = new java.util.TreeMap<>();
        for (int c = 0; c <= 4; c++) shortAxis.merge(c, 50.0, Double::sum);
        for (int c = 20; c <= 24; c++) shortAxis.merge(c, 50.0, Double::sum);
        ScoredCut perp = scoreCut(0, 24, shortAxis);
        ok &= check("cross-axis: short axis valid", perp != null && perp.profiled);

        LOGGER.info("Cut scorer self-test: {}", ok ? "PASS" : "FAIL");
        return ok;
    }

    private static boolean check(String name, boolean cond) {
        if (!cond) LOGGER.error("Cut scorer self-test FAILED: {}", name);
        return cond;
    }

    private long lastGuardLogNanos = 0;

    private void logSkippedSplit(WorldRegion region) {
        long now = System.nanoTime();
        if (now - lastGuardLogNanos < 30_000_000_000L) return; // at most every 30 s
        lastGuardLogNanos = now;
        LOGGER.info("Split of {} skipped: no cut line separates load without slicing a cluster "
                        + "({} entities, heat {}; hottest {})",
                region, region.getOwnedEntityIds().size(),
                String.format("%.1f", blockTickHeat.totalInRegion(region)),
                blockTickHeat.hotspotSummary(region, 3));
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
