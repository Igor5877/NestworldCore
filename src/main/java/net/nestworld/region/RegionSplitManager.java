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
                    pendingSplits.add(region);
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
        WorldRegion[] children = tree.split(region);
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
