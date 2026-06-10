package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Monitors per-region TPS and decides when to split or merge regions.
 *
 * <p>Called by NestworldRegionSystem every 20 ticks (1 second of game time).
 * Split/merge operations are queued and applied at the next tick barrier so
 * all region threads are paused while the BSP tree is mutated.
 *
 * <p>Hysteresis prevents thrashing:
 * <ul>
 *   <li>Split: TPS &lt; {@value #SPLIT_TPS_THRESHOLD} for {@value #SPLIT_TICKS_REQUIRED} consecutive ticks</li>
 *   <li>Merge: TPS &gt; {@value #MERGE_TPS_THRESHOLD} for {@value #MERGE_TICKS_REQUIRED} consecutive ticks</li>
 * </ul>
 *
 * <p>A region at minimum size (1×1 chunk) is never split further.
 */
public class RegionSplitManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SplitManager");

    // --- Thresholds ---
    private static final double SPLIT_TPS_THRESHOLD = 15.0;
    private static final double MERGE_TPS_THRESHOLD = 18.0;
    private static final int    SPLIT_TICKS_REQUIRED = 100; // 5 s
    private static final int    MERGE_TICKS_REQUIRED = 500; // 25 s

    private final RegionTree tree;
    private final RegionThreadPool pool;

    // Pending operations applied atomically at the tick barrier
    private final List<SplitRequest> pendingSplits = new ArrayList<>();
    private final List<MergeRequest>  pendingMerges  = new ArrayList<>();

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
     * have finished (i.e. after the tick barrier).
     * Every 20 ticks it evaluates TPS for every region.
     */
    public void onTick() {
        if (++checkInterval < 20) return;
        checkInterval = 0;

        for (WorldRegion region : tree.getActiveRegions()) {
            double tps = region.getCurrentTps();

            if (tps < SPLIT_TPS_THRESHOLD) {
                region.ticksAboveThreshold = 0;
                if (++region.ticksBelowThreshold >= SPLIT_TICKS_REQUIRED && region.canSplit()) {
                    pendingSplits.add(new SplitRequest(region));
                    region.resetThresholdCounters();
                }
            } else if (tps > MERGE_TPS_THRESHOLD) {
                region.ticksBelowThreshold = 0;
                if (++region.ticksAboveThreshold >= MERGE_TICKS_REQUIRED) {
                    // Only queue a merge when both siblings of a pair are above the threshold
                    // (evaluated in applyPending where we can check sibling state)
                    region.resetThresholdCounters();
                    pendingMerges.add(new MergeRequest(region));
                }
            } else {
                region.ticksBelowThreshold = 0;
                region.ticksAboveThreshold = 0;
            }
        }

        applyPending();
    }

    // -----------------------------------------------------------------------
    // Apply queued operations between ticks (region threads are at barrier)
    // -----------------------------------------------------------------------

    private void applyPending() {
        // --- Splits ---
        for (SplitRequest req : pendingSplits) {
            WorldRegion[] children = tree.split(req.region);
            if (children == null) continue; // already at min size or not found

            pool.remove(req.region);
            pool.spawn(children[0]);
            pool.spawn(children[1]);

            // Migrate entity ownership: assign each entity to the child that covers it
            for (java.util.UUID uuid : req.region.getOwnedEntityIds()) {
                // Entity position lookup deferred to BoundaryEntityTransfer on next tick;
                // for now give both entities to child A as a safe default.
                children[0].addEntity(uuid);
            }

            LOGGER.info("Split {} -> [{}, {}]  (TPS was {})",
                    req.region, children[0], children[1], String.format("%.1f", req.region.getCurrentTps()));
        }
        pendingSplits.clear();

        // --- Merges ---
        // Collect all regions that requested a merge, then find pairs that are siblings
        List<WorldRegion> mergeQueue = new ArrayList<>();
        for (MergeRequest req : pendingMerges) mergeQueue.add(req.region);

        for (int i = 0; i < mergeQueue.size(); i++) {
            WorldRegion a = mergeQueue.get(i);
            for (int j = i + 1; j < mergeQueue.size(); j++) {
                WorldRegion b = mergeQueue.get(j);
                WorldRegion merged = tree.merge(a, b); // returns null if not siblings
                if (merged == null) continue;

                pool.remove(a);
                pool.remove(b);
                pool.spawn(merged);

                // Migrate entity ownership to the new merged region
                for (java.util.UUID uuid : a.getOwnedEntityIds()) merged.addEntity(uuid);
                for (java.util.UUID uuid : b.getOwnedEntityIds()) merged.addEntity(uuid);

                LOGGER.info("Merged [{}, {}] -> {}  (TPS were {}, {})",
                        a, b, merged, String.format("%.1f", a.getCurrentTps()), String.format("%.1f", b.getCurrentTps()));

                mergeQueue.remove(j);
                mergeQueue.remove(i);
                i--;
                break;
            }
        }
        pendingMerges.clear();
    }

    // -----------------------------------------------------------------------
    // Request records
    // -----------------------------------------------------------------------

    private record SplitRequest(WorldRegion region) {}
    private record MergeRequest(WorldRegion region) {}
}
