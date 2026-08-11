package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps chunk positions to their owning WorldRegion.
 *
 * <p>Lookup scans the active region list and tests rectangular bounds.
 * Regions can span the whole world (millions of chunks), so a per-chunk map
 * is not an option; with the BSP keeping the active set small (a handful to
 * a few dozen leaves) a bounds scan is faster than hashing anyway.
 * Thread-safe: the list is copy-on-write, mutated only between ticks.
 */
public class WorldGrid {

    private final CopyOnWriteArrayList<WorldRegion> regions = new CopyOnWriteArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    /** Bumped on every register/unregister; lets per-tick scans detect layout changes cheaply. */
    private final AtomicInteger layoutVersion = new AtomicInteger(0);

    // --- Lookup ---

    public WorldRegion getRegionFor(ChunkPos pos) {
        return getRegionForChunk(pos.x, pos.z);
    }

    public WorldRegion getRegionFor(BlockPos pos) {
        return getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public WorldRegion getRegionForChunk(int cx, int cz) {
        for (WorldRegion r : regions) {
            if (r.containsChunk(cx, cz)) return r;
        }
        return null;
    }

    /** Returns true when the given block position is covered by any registered region. */
    public boolean isManaged(BlockPos pos) {
        return getRegionFor(pos) != null;
    }

    /**
     * Stage 3 EXTENSION (docs/LOCAL_TICK_STAGE4.md, "Entity Safety Layer" — the
     * Explosion.explode() entity-damage race, found during the Stage 5 audit): the
     * region that currently OWNS the given entity (by ownedEntityIds membership, not
     * by position — an entity mid-transfer or briefly stale-positioned still belongs
     * to whichever region's set actually contains it), or null if none does (not yet
     * assigned, or a type never region-owned, e.g. players). O(active regions) scan
     * — cheap, region counts are tens not thousands (same complexity class as {@link
     * #getRegionsWithinMargin}/{@link #getAdjacentRegions}); call only from
     * low-frequency cross-region classification paths, never a hot per-tick loop.
     */
    public WorldRegion findOwningRegion(java.util.UUID entityId) {
        for (WorldRegion r : regions) {
            if (r.ownsEntity(entityId)) return r;
        }
        return null;
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 2 (docs/LOCAL_TICK_STAGE4.md, "Stage 5
     * tick-scheduler architecture — DECIDED"): every region whose territory falls within
     * {@code marginBlocks} of the given block position — i.e. the region's own rectangle,
     * expanded outward by {@code marginBlocks} on every side, contains the position. Used
     * by the border-band cascade-safety guard (Part 3) to find every region that must be
     * lock-excluded before applying a write whose cascade could reach that far (the
     * quantified 48-block "cascade safety margin" from the Blocker 3 read-side scoping).
     *
     * <p>A cheap O(active regions) bounds scan — regions number in the tens, not
     * thousands, per this class's own javadoc, so this is not a hot-path concern; callers
     * should still avoid it from a genuinely per-tick path (matches every other
     * low-frequency-only scan in this codebase, e.g. {@link WorldRegion#getPercentileTickMs}).
     */
    public java.util.List<WorldRegion> getRegionsWithinMargin(BlockPos pos, int marginBlocks) {
        int x = pos.getX();
        int z = pos.getZ();
        java.util.List<WorldRegion> result = new java.util.ArrayList<>();
        for (WorldRegion r : regions) {
            int rMinX = r.getMinChunkX() * 16;
            int rMaxX = (r.getMaxChunkX() + 1) * 16 - 1;
            int rMinZ = r.getMinChunkZ() * 16;
            int rMaxZ = (r.getMaxChunkZ() + 1) * 16 - 1;
            if (x >= rMinX - marginBlocks && x <= rMaxX + marginBlocks
                    && z >= rMinZ - marginBlocks && z <= rMaxZ + marginBlocks) {
                result.add(r);
            }
        }
        return result;
    }

    // --- Registration ---

    public void register(WorldRegion region) {
        if (regions.addIfAbsent(region)) layoutVersion.incrementAndGet();
    }

    public void unregister(WorldRegion region) {
        if (regions.remove(region)) layoutVersion.incrementAndGet();
    }

    /** Changes whenever the set of active regions changes (split/merge). */
    public int getLayoutVersion() {
        return layoutVersion.get();
    }

    public Collection<WorldRegion> getAllRegions() {
        return Collections.unmodifiableList(regions);
    }

    /** Allocates the next unique region ID. */
    public int nextId() {
        return idCounter.getAndIncrement();
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 4 (docs/LOCAL_TICK_STAGE4.md, "Stage 5
     * tick-scheduler architecture — DECIDED"): every OTHER registered region whose
     * rectangle directly touches or overlaps {@code region}'s — i.e. shares an edge or
     * corner. A valid BSP layout never has truly overlapping leaves, so in practice this
     * finds edge/corner-adjacent neighbours only. Used by split/merge to find every
     * region whose adjacency bookkeeping could be affected by a BSP tree mutation, so
     * their {@code chunkLock}s can be acquired alongside the mutating region's own before
     * the tree changes — same "lock every region something reaches" pattern {@link
     * #getRegionsWithinMargin} already uses for the border-band cascade guard, just with
     * a fixed 1-block adjacency test instead of a configurable margin (split/merge cares
     * about grid-consistency neighbours, not cascade reach).
     */
    public java.util.List<WorldRegion> getAdjacentRegions(WorldRegion region) {
        int aMinX = region.getMinChunkX() * 16 - 1;
        int aMaxX = (region.getMaxChunkX() + 1) * 16;
        int aMinZ = region.getMinChunkZ() * 16 - 1;
        int aMaxZ = (region.getMaxChunkZ() + 1) * 16;
        java.util.List<WorldRegion> result = new java.util.ArrayList<>();
        for (WorldRegion r : regions) {
            if (r == region) continue;
            int rMinX = r.getMinChunkX() * 16;
            int rMaxX = (r.getMaxChunkX() + 1) * 16 - 1;
            int rMinZ = r.getMinChunkZ() * 16;
            int rMaxZ = (r.getMaxChunkZ() + 1) * 16 - 1;
            if (rMinX <= aMaxX && rMaxX >= aMinX && rMinZ <= aMaxZ && rMaxZ >= aMinZ) {
                result.add(r);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Deterministic self-test of getRegionsWithinMargin (NESTWORLD_MARGIN_TEST=1).
    // Same pattern as RegionSplitManager.selfTest()/RegionTree.selfTest()/etc —
    // runs headless at startup with a synthetic grid, no live world needed.
    // -----------------------------------------------------------------------

    public static void selfTest() {
        org.apache.logging.log4j.Logger log =
                org.apache.logging.log4j.LogManager.getLogger("NestWorld/WorldGrid");

        // Case 1: deep interior, small margin — only the containing region matches.
        WorldGrid g1 = new WorldGrid();
        WorldRegion r1 = new WorldRegion(g1.nextId(), -100, -100, 100, 100);
        g1.register(r1);
        boolean interiorOnly = g1.getRegionsWithinMargin(new BlockPos(0, 100, 0), 48)
                .equals(java.util.List.of(r1));

        // Case 2: two regions sharing an edge at chunk x=0/x=1 (block x=15/16). A
        // position near that edge, well within the margin, must return BOTH regions;
        // a position deep inside one region (margin doesn't reach the edge) must
        // return only that one.
        WorldGrid g2 = new WorldGrid();
        WorldRegion left = new WorldRegion(g2.nextId(), -10, -10, 0, 10);   // blocks x -160..15
        WorldRegion right = new WorldRegion(g2.nextId(), 1, -10, 10, 10);   // blocks x 16..175
        g2.register(left);
        g2.register(right);
        // 10 blocks from the edge on the left side — within a 48-block margin of BOTH.
        java.util.List<WorldRegion> nearEdge = g2.getRegionsWithinMargin(new BlockPos(5, 100, 0), 48);
        boolean bothNearEdge = nearEdge.size() == 2
                && nearEdge.contains(left) && nearEdge.contains(right);
        // 100 blocks from the edge — outside a 48-block margin of the far region.
        java.util.List<WorldRegion> deepInLeft = g2.getRegionsWithinMargin(new BlockPos(-100, 100, 0), 48);
        boolean onlyLeftDeep = deepInLeft.equals(java.util.List.of(left));

        // Case 3: a 4-region corner (like a real BSP quad-split) — a position exactly
        // at the corner, with a margin, must return all 4; margin=0 (touching only,
        // no cascade reach) must still return all 4 since block bounds are inclusive
        // and the corner position is simultaneously inside all 4 regions' 1-block
        // corner overlap... actually a BSP split never leaves overlapping rectangles,
        // so the corner block itself belongs to exactly ONE region at margin 0 — the
        // margin is what pulls the other 3 in.
        WorldGrid g3 = new WorldGrid();
        WorldRegion nw = new WorldRegion(g3.nextId(), -10, -10, -1, -1);
        WorldRegion ne = new WorldRegion(g3.nextId(), 0, -10, 10, -1);
        WorldRegion sw = new WorldRegion(g3.nextId(), -10, 0, -1, 10);
        WorldRegion se = new WorldRegion(g3.nextId(), 0, 0, 10, 10);
        for (WorldRegion r : new WorldRegion[]{nw, ne, sw, se}) g3.register(r);
        boolean cornerMargin0 = g3.getRegionsWithinMargin(new BlockPos(0, 100, 0), 0).size() == 1;
        java.util.List<WorldRegion> cornerMargin48 = g3.getRegionsWithinMargin(new BlockPos(0, 100, 0), 48);
        boolean cornerAllFour = cornerMargin48.size() == 4;

        boolean ok = interiorOnly && bothNearEdge && onlyLeftDeep && cornerMargin0 && cornerAllFour;
        log.info("[MARGIN SELF-TEST] {} (interiorOnly={}, bothNearEdge={}, onlyLeftDeep={}, "
                        + "cornerMargin0={}, cornerAllFour={})",
                ok ? "PASS" : "FAIL", interiorOnly, bothNearEdge, onlyLeftDeep,
                cornerMargin0, cornerAllFour);
    }
}
