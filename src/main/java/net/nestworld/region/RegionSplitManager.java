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

    // --- Fill-idle-cores split (load balancing) ---
    // Spark profiling (ZombieCraft, 1580 entities) showed the main thread parked
    // ~110 s on the barrier while CPU sat at ~28 % of 8 cores: work concentrates
    // in a few regions and most cores idle. When fewer regions are active than we
    // have cores to run them on, split the single most expensive region at a
    // lower bar so its load spreads onto an idle core. Bounded by the target so
    // we never create more region threads than cores (extra threads beyond that
    // only add context-switch + barrier overhead, not parallelism). Merge is
    // suppressed while regions <= target so these balance-splits stay put instead
    // of merging back the next window (no split/merge thrash). Set
    // nestworld.splitFillCoresMs very high to disable (restores pure 25 ms rule).
    private static final int SPLIT_TARGET_PARALLELISM =
            Math.max(1, Integer.getInteger("nestworld.splitTargetParallelism",
                    Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
    private static final double SPLIT_FILL_CORES_MS =
            Double.parseDouble(System.getProperty("nestworld.splitFillCoresMs", "12.0"));

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

        // Load balancing: when fewer regions are active than we have cores, the
        // tick is gated by the most expensive region while cores idle (spark:
        // ~28 % CPU, main parked on the barrier). Pick the hottest region so it
        // can split at the lower fill-cores bar and spread onto an idle core.
        // Merge is suppressed while regions <= target so the balance-splits don't
        // immediately merge back (no thrash).
        // Only regions that actually tick (own entities) consume a core, so empty
        // regions must not count as "using up" parallelism — otherwise a handful
        // of stale empty regions would block fill-splitting a newly hot one.
        int busy = 0;
        for (WorldRegion region : active) {
            if (!region.getOwnedEntityIds().isEmpty()) busy++;
        }
        boolean spareCores = busy < SPLIT_TARGET_PARALLELISM;
        WorldRegion hottest = null;
        double hottestMs = 0.0;
        if (spareCores) {
            for (WorldRegion region : active) {
                double c = region.getAvgTickMs();
                if (c > hottestMs) { hottestMs = c; hottest = region; }
            }
        }

        for (WorldRegion region : active) {
            double costMs = region.getAvgTickMs();
            boolean fillSplit = spareCores && region == hottest && costMs > SPLIT_FILL_CORES_MS;

            if (costMs > SPLIT_MS_THRESHOLD || fillSplit) {
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
            } else if (costMs < MERGE_MS_THRESHOLD
                    && (active.size() > SPLIT_TARGET_PARALLELISM || region.getOwnedEntityIds().isEmpty())) {
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

    /** Builds {@code region}'s weighted load histograms on {@code axis} and scores it. */
    private ScoredCut scoreAxis(WorldRegion region, SplitAxis axis) {
        int min = axis == SplitAxis.X ? region.getMinChunkX() : region.getMinChunkZ();
        int max = axis == SplitAxis.X ? region.getMaxChunkX() : region.getMaxChunkZ();

        // Entity load decides WHERE to cut (balance the crowd); block-tick heat
        // decides the band VETO — only block/BE/scheduled ticks in a border band
        // run serially on main, so a hot column in a band is the thing to avoid.
        // Entities in a band still tick on the region thread, so they must NOT
        // contribute to the veto (else a uniform crowd, which has ~equal entities
        // around every line, would never split).
        java.util.TreeMap<Integer, Double> combined = new java.util.TreeMap<>();
        for (int c : ownedEntityChunkCoords(region, axis)) {
            combined.merge(c, 1.0, Double::sum);
        }
        java.util.TreeMap<Integer, Double> heat = new java.util.TreeMap<>();
        blockTickHeat.addAxisHeat(region, axis, NestworldTuning.CUT_HEAT_WEIGHT, heat);
        for (java.util.Map.Entry<Integer, Double> e : heat.entrySet()) {
            combined.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return scoreCut(min, max, combined, heat);
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
     * median. Only block-tick <em>heat</em> counts toward a band's score and the
     * stay-whole veto — entities in a band still tick on the region thread, so a
     * dense entity crowd splits at its median; only a redstone/fluid machine that
     * no line separates (more than {@link #MAX_CUT_BAND_SHARE} of the heat in the
     * best line's band) keeps the region whole.
     */
    static ScoredCut scoreCut(int min, int max,
                              java.util.SortedMap<Integer, Double> combined,
                              java.util.SortedMap<Integer, Double> heat) {
        if (combined.isEmpty()) return new ScoredCut(SPATIAL_MIDPOINT, Double.NaN, false);

        int m = combined.size();
        int[] coord = new int[m];
        double[] w = new double[m];
        double total = 0;
        int i = 0;
        for (java.util.Map.Entry<Integer, Double> e : combined.entrySet()) {
            coord[i] = e.getKey();
            w[i] = e.getValue();
            total += w[i];
            i++;
        }

        // Heat-only columns: the band veto must consider only block-tick load,
        // since that is the only work a border band forces onto the main thread.
        int mh = heat.size();
        int[] hCoord = new int[mh];
        double[] hW = new double[mh];
        double totalHeat = 0;
        int j = 0;
        for (java.util.Map.Entry<Integer, Double> e : heat.entrySet()) {
            hCoord[j] = e.getKey();
            hW[j] = e.getValue();
            totalHeat += hW[j];
            j++;
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

        // Pick the line whose 2×band swallows the least block-tick HEAT (the only
        // serial-on-main load); ties broken toward the weighted median so a pure
        // entity crowd — heat band 0 on every line — splits right at its median.
        Integer best = null;
        double bestHeatBand = Double.MAX_VALUE;
        for (int raw : candidates) {
            int cut = clampCut(raw, min, max);
            double toA = weightAtMost(coord, w, cut);
            if (Math.min(toA, total - toA) < total * MIN_SPLIT_SEPARATION_SHARE) continue;
            double heatBand = weightAtMost(hCoord, hW, cut + BORDER_BAND_CHUNKS)
                            - weightAtMost(hCoord, hW, cut - BORDER_BAND_CHUNKS);
            if (heatBand < bestHeatBand
                    || (heatBand == bestHeatBand && best != null
                        && Math.abs(cut - median) < Math.abs(best - median))) {
                bestHeatBand = heatBand;
                best = cut;
            }
        }
        // Stay whole only when a hot block-tick column is unavoidable in a band
        // (a redstone/fluid machine that no line cleanly separates). A dense
        // entity crowd has totalHeat 0 → bestHeatBand 0 → never vetoed here.
        if (best == null || bestHeatBand > totalHeat * MAX_CUT_BAND_SHARE) return null;
        return new ScoredCut(best, bestHeatBand, true);
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
        ScoredCut empty = scoreCut(0, 24, hist(), hist());
        ok &= check("empty→midpoint", empty != null && empty.cut == SPATIAL_MIDPOINT && !empty.profiled);

        // Two entity clusters (no heat) with a gap: cut separates them; the
        // heat band is 0 (no block ticks anywhere).
        java.util.TreeMap<Integer, Double> twoClusters = new java.util.TreeMap<>();
        for (int c = 0; c <= 4; c++) twoClusters.merge(c, 20.0, Double::sum);
        for (int c = 20; c <= 24; c++) twoClusters.merge(c, 20.0, Double::sum);
        ScoredCut gapCut = scoreCut(0, 24, twoClusters, hist());
        ok &= check("gap: cut found", gapCut != null && gapCut.profiled);
        // No heat anywhere → cut lands at the weighted median (inner edge of the
        // first cluster) and separates the two clusters fairly; band heat is 0.
        ok &= check("gap: separates clusters", gapCut != null && gapCut.cut >= 4 && gapCut.cut <= 20);
        ok &= check("gap: heat band 0", gapCut != null && gapCut.band == 0.0);

        // THE FIX: a dense uniform entity crowd (no heat) across 0..20 — every
        // line has ~equal entities in its band, which previously tripped the
        // band veto and left the region whole. With heat-only veto it now splits
        // near its median.
        java.util.TreeMap<Integer, Double> crowd = new java.util.TreeMap<>();
        for (int c = 0; c <= 20; c++) crowd.merge(c, 20.0, Double::sum);
        ScoredCut crowdCut = scoreCut(0, 20, crowd, hist());
        ok &= check("entity crowd: splits (heat-only veto)", crowdCut != null && crowdCut.profiled);
        ok &= check("entity crowd: cut near median",
                crowdCut != null && crowdCut.cut >= 7 && crowdCut.cut <= 13);

        // Single dense entity column: no fair cut exists (separation), the region
        // stays whole regardless of heat.
        ok &= check("point-hotspot→veto", scoreCut(0, 20, hist(10, 200.0), hist()) == null);

        // A block-tick machine (heat column 15, weight 100) plus entities in
        // columns 0..9: the cut must route the machine into a child interior,
        // never leaving it inside a band. Cut 12 puts col 15 (>12+2) interior.
        java.util.TreeMap<Integer, Double> mCombined = new java.util.TreeMap<>();
        for (int c = 0; c <= 9; c++) mCombined.merge(c, 5.0, Double::sum);
        mCombined.merge(15, 100.0, Double::sum);
        ScoredCut mCut = scoreCut(0, 30, mCombined, hist(15, 100.0));
        ok &= check("machine: cut found", mCut != null);
        ok &= check("machine: machine column out of band",
                mCut != null && Math.abs(15 - mCut.cut) > BORDER_BAND_CHUNKS);

        // A single inseparable hot heat column (a machine projected onto its long
        // axis): no fair cut, vetoed. Across the short axis the same machine
        // shows a quiet entity gap that splits cleanly (loadAwareCut picks it).
        ok &= check("heat hotspot→veto", scoreCut(0, 30, hist(15, 200.0), hist(15, 200.0)) == null);
        java.util.TreeMap<Integer, Double> shortAxis = new java.util.TreeMap<>();
        for (int c = 0; c <= 4; c++) shortAxis.merge(c, 50.0, Double::sum);
        for (int c = 20; c <= 24; c++) shortAxis.merge(c, 50.0, Double::sum);
        ScoredCut perp = scoreCut(0, 24, shortAxis, hist());
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
