package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains read-only "ghost zones" at the edges of every active region.
 *
 * <p>After each tick (while all region threads are paused at the barrier),
 * {@link #syncGhostZones()} copies a {@value #GHOST_DEPTH}-chunk-wide strip
 * from each region's edge into the ghost cache of each adjacent region.
 *
 * <p>When mod code in Region B reads a block position that belongs to Region A,
 * {@link RegionChunkView} returns the ghost snapshot instead of touching
 * Region A's live data — no lock contention, no cross-thread writes.
 *
 * <p>The ghost cache is intentionally stale by 1 tick.  For most mod
 * interactions (energy cables, pipe networks, multi-block state checks) this
 * is perfectly acceptable.  Redstone signals that cross a boundary are handled
 * separately by {@link BoundarySignalQueue}.
 */
public class BoundaryManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/BoundaryManager");

    /** How many chunks deep the ghost zone extends into each region. */
    public static final int GHOST_DEPTH = 2;

    private final ServerLevel level;
    private final WorldGrid grid;

    /**
     * ghostData.get(regionId).get(chunkLong) → snapshot of that chunk's block/BE data.
     * Indexed by the owning-region's ID, not the ghost-zone region.
     */
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, ChunkSnapshot>> ghostData =
            new ConcurrentHashMap<>();

    public BoundaryManager(ServerLevel level, WorldGrid grid) {
        this.level = level;
        this.grid = grid;
    }

    // -----------------------------------------------------------------------
    // Called from main thread after every tick barrier
    // -----------------------------------------------------------------------

    /**
     * Copies boundary chunk data from each region into its neighbours' ghost caches.
     * Runs while all RegionThreads are paused → no concurrent writes to live chunks.
     */
    public void syncGhostZones() {
        for (WorldRegion region : grid.getAllRegions()) {
            ConcurrentHashMap<Long, ChunkSnapshot> cache =
                    ghostData.computeIfAbsent(region.getId(), k -> new ConcurrentHashMap<>());

            snapshotEdge(region, cache, EdgeSide.NORTH);
            snapshotEdge(region, cache, EdgeSide.SOUTH);
            snapshotEdge(region, cache, EdgeSide.WEST);
            snapshotEdge(region, cache, EdgeSide.EAST);
        }
    }

    // -----------------------------------------------------------------------
    // Read API — used by RegionChunkView
    // -----------------------------------------------------------------------

    /**
     * Returns the ghost-zone snapshot of a chunk that belongs to {@code ownerRegion}.
     * Returns null if no snapshot exists (chunk is outside any ghost zone).
     */
    public ChunkSnapshot getGhostChunk(WorldRegion ownerRegion, int cx, int cz) {
        var cache = ghostData.get(ownerRegion.getId());
        if (cache == null) return null;
        return cache.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
    }

    // -----------------------------------------------------------------------
    // Snapshot helpers
    // -----------------------------------------------------------------------

    private void snapshotEdge(WorldRegion region, Map<Long, ChunkSnapshot> cache, EdgeSide side) {
        int minCx, maxCx, minCz, maxCz;
        switch (side) {
            case NORTH -> { minCx = region.getMinChunkX(); maxCx = region.getMaxChunkX();
                            minCz = region.getMinChunkZ(); maxCz = region.getMinChunkZ() + GHOST_DEPTH - 1; }
            case SOUTH -> { minCx = region.getMinChunkX(); maxCx = region.getMaxChunkX();
                            minCz = region.getMaxChunkZ() - GHOST_DEPTH + 1; maxCz = region.getMaxChunkZ(); }
            case WEST  -> { minCx = region.getMinChunkX(); maxCx = region.getMinChunkX() + GHOST_DEPTH - 1;
                            minCz = region.getMinChunkZ(); maxCz = region.getMaxChunkZ(); }
            case EAST  -> { minCx = region.getMaxChunkX() - GHOST_DEPTH + 1; maxCx = region.getMaxChunkX();
                            minCz = region.getMinChunkZ(); maxCz = region.getMaxChunkZ(); }
            default -> { return; }
        }

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                cache.put(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                        ChunkSnapshot.capture(chunk));
            }
        }
    }

    private enum EdgeSide { NORTH, SOUTH, WEST, EAST }

    // -----------------------------------------------------------------------
    // Snapshot value type
    // -----------------------------------------------------------------------

    /**
     * Immutable snapshot of a chunk's block states and block-entity positions.
     * Created while region threads are paused so no synchronisation is needed.
     */
    public static final class ChunkSnapshot {
        // Section-indexed array of block states (simplified: store per-section palette)
        // Full implementation would mirror LevelChunk's section array.
        private final Map<BlockPos, BlockState> blockStates = new HashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

        private ChunkSnapshot() {}

        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return blockEntities.get(pos);
        }

        static ChunkSnapshot capture(LevelChunk chunk) {
            ChunkSnapshot snap = new ChunkSnapshot();
            // Capture block entities (lightweight — mostly important for capability reads)
            snap.blockEntities.putAll(chunk.getBlockEntities());
            // Block states are read on-demand from chunk sections;
            // full snapshotting of all block states is omitted here for clarity —
            // a production build would walk LevelChunkSection[] and copy palettes.
            return snap;
        }
    }
}
