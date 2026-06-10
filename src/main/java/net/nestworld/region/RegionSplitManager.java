package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Monitors per-region TPS and decides when to split or merge regions.
 *
 * <p>Called by NestworldRegionSystem every tick; evaluates TPS every 20 ticks
 * (1 second of game time). Split/merge operations are applied while all
 * region threads are parked between ticks, so the BSP tree mutates atomically.
 *
 * <p>Hysteresis prevents thrashing:
 * <ul>
 *   <li>Split: TPS &lt; {@value #SPLIT_TPS_THRESHOLD} for {@value #SPLIT_CHECKS_REQUIRED} consecutive evaluations (100 game ticks)</li>
 *   <li>Merge: TPS &gt; {@value #MERGE_TPS_THRESHOLD} for {@value #MERGE_CHECKS_REQUIRED} consecutive evaluations (500 game ticks)</li>
 * </ul>
 *
 * <p>A region at minimum size (1×1 chunk) is never split further.
 */
public class RegionSplitManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SplitManager");

    // --- Thresholds ---
    // Counters advance once per evaluation (every 20 game ticks), so the design
    // targets "100 ticks below 15 TPS" = 5 evaluations, "500 ticks above 18" = 25.
    private static final double SPLIT_TPS_THRESHOLD = 15.0;
    private static final double MERGE_TPS_THRESHOLD = 18.0;
    private static final int    SPLIT_CHECKS_REQUIRED = 5;  // 100 game ticks
    private static final int    MERGE_CHECKS_REQUIRED = 25; // 500 game ticks

    private final RegionTree tree;
    private final RegionThreadPool pool;

    // Pending splits applied at the next evaluation
    private final List<WorldRegion> pendingSplits = new ArrayList<>();
    /**
     * Merge candidates persist across evaluations (insertion-ordered) because
     * sibling regions rarely cross the threshold in the same second. A candidate
     * is dropped when its TPS falls back under the merge threshold or it is no
     * longer an active region.
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
            double tps = region.getCurrentTps();

            if (tps < SPLIT_TPS_THRESHOLD) {
                region.ticksAboveThreshold = 0;
                mergeCandidates.remove(region);
                if (++region.ticksBelowThreshold >= SPLIT_CHECKS_REQUIRED && region.canSplit()) {
                    pendingSplits.add(region);
                    region.resetThresholdCounters();
                }
            } else if (tps > MERGE_TPS_THRESHOLD) {
                region.ticksBelowThreshold = 0;
                if (++region.ticksAboveThreshold >= MERGE_CHECKS_REQUIRED) {
                    mergeCandidates.add(region); // persists until merged or TPS drops
                }
            } else {
                region.ticksBelowThreshold = 0;
                region.ticksAboveThreshold = 0;
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

        LOGGER.info("Split {} -> [{}, {}]  (TPS was {})",
                region, children[0], children[1], String.format("%.1f", region.getCurrentTps()));
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

        LOGGER.info("Merged [{}, {}] -> {}  (TPS were {}, {})",
                a, b, merged, String.format("%.1f", a.getCurrentTps()), String.format("%.1f", b.getCurrentTps()));
        return merged;
    }
}
