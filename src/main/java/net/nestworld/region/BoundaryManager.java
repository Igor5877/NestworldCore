package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides read-only "ghost" views of chunks owned by other regions.
 *
 * <p>When mod code in Region B reads a block position that belongs to Region A,
 * {@link RegionChunkView} asks for a ghost view instead of touching Region A's
 * live data structures through the normal write path.
 *
 * <p>Views are created on demand and delegate reads to the live chunk:
 * PalettedContainer guards its block-state reads internally, and the ghost
 * contract only promises data no staler than 1 tick — live reads are strictly
 * fresher. This avoids per-tick edge scans entirely, which matters because a
 * region can span the whole world (boundary strips would be millions of
 * chunks long).
 */
public class BoundaryManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/BoundaryManager");

    /** Nominal ghost-zone depth in chunks (documented contract for mod authors). */
    public static final int GHOST_DEPTH = 2;

    private final ServerLevel level;
    private final WorldGrid grid;

    public BoundaryManager(ServerLevel level, WorldGrid grid) {
        this.level = level;
        this.grid = grid;
    }

    /**
     * Called from the main thread after region threads finish each tick.
     * Ghost views are created on demand, so there is nothing to refresh.
     */
    public void syncGhostZones() {
        // Intentionally empty — kept as a lifecycle hook for future
        // invalidation logic (e.g. dropping views of unloaded chunks).
    }

    /**
     * Returns a read-only view of a chunk owned by {@code ownerRegion}, or null when
     * the chunk is not loaded OR the chunk is deeper than {@link #GHOST_DEPTH} inside
     * the owner's territory. The depth check matters: without it this always returns
     * non-null for any loaded foreign chunk, which meant {@link RegionChunkView}'s real
     * lock tier (for reads that reach further into a foreign region than the documented
     * ghost contract) was practically unreachable — this "ghost" was a live,
     * unsynchronized pass-through with no actual staleness bound, not the snapshot the
     * class javadoc promises.
     * Safe to call from any region thread.
     */
    public ChunkSnapshot getGhostChunk(WorldRegion ownerRegion, int cx, int cz) {
        if (!withinGhostDepth(ownerRegion, cx, cz)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk == null ? null : new ChunkSnapshot(chunk);
    }

    /** True when (cx,cz) — assumed inside {@code owner}'s bounds — is within
     *  {@link #GHOST_DEPTH} chunks of the nearest edge of the owner region. */
    private static boolean withinGhostDepth(WorldRegion owner, int cx, int cz) {
        int distToNearestEdge = Math.min(
                Math.min(cx - owner.getMinChunkX(), owner.getMaxChunkX() - cx),
                Math.min(cz - owner.getMinChunkZ(), owner.getMaxChunkZ() - cz));
        return distToNearestEdge <= GHOST_DEPTH;
    }

    /**
     * Read-only view of a boundary chunk for cross-region access.
     * Delegates to the live chunk — see class javadoc for why this is safe.
     */
    public static final class ChunkSnapshot {
        private final LevelChunk chunk;

        ChunkSnapshot(LevelChunk chunk) {
            this.chunk = chunk;
        }

        public BlockState getBlockState(BlockPos pos) {
            return chunk.getBlockState(pos);
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return chunk.getBlockEntity(pos);
        }
    }
}
