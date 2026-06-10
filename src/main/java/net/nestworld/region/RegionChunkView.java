package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * Thread-safe accessor for block data that may belong to a different region.
 *
 * <p>When mod code running on RegionThread A calls
 * {@code level.getBlockState(pos)} or {@code level.getBlockEntity(pos)} for
 * a position inside Region B, normal execution would race against Region B's
 * thread.  RegionChunkView intercepts such cross-region accesses:
 *
 * <ul>
 *   <li>If the position is in the <em>same</em> region as the calling thread →
 *       direct read (no extra lock; the region write-lock is already held by
 *       the calling thread).</li>
 *   <li>If the position is in the ghost zone of the calling region →
 *       read from {@link BoundaryManager}'s 1-tick-stale snapshot (lock-free,
 *       read-only).</li>
 *   <li>If the position is outside the ghost zone → acquire a read stamp on
 *       the target region's {@link java.util.concurrent.locks.StampedLock}
 *       and read from live data.</li>
 * </ul>
 *
 * <p>Write operations to foreign regions are <em>not</em> supported by this
 * class; they must go through {@link CrossRegionCapabilityBus} or the
 * {@link BoundarySignalQueue}.
 */
public class RegionChunkView {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/ChunkView");

    private final WorldGrid grid;
    private final BoundaryManager boundaryManager;

    public RegionChunkView(WorldGrid grid, BoundaryManager bm) {
        this.grid = grid;
        this.boundaryManager = bm;
    }

    // -----------------------------------------------------------------------
    // Block state
    // -----------------------------------------------------------------------

    /**
     * Returns the block state at {@code pos}, reading from the ghost cache if
     * the position belongs to a different region than the calling thread.
     */
    public BlockState getBlockState(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        WorldRegion owner = grid.getRegionFor(pos);
        if (owner == null) return level.getBlockState(pos); // unmanaged area — direct

        // Same-region read: the caller holds owner's write-lock already
        if (owner.owningThread == Thread.currentThread()) {
            return level.getBlockState(pos);
        }

        // Try ghost zone first (lock-free, fast)
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        BoundaryManager.ChunkSnapshot snap = boundaryManager.getGhostChunk(owner, cx, cz);
        if (snap != null) {
            return snap.getBlockState(pos);
        }

        // Fall back: acquire read stamp on the foreign region
        long stamp = owner.getChunkLock().readLock();
        try {
            return level.getBlockState(pos);
        } finally {
            owner.getChunkLock().unlockRead(stamp);
        }
    }

    // -----------------------------------------------------------------------
    // Block entity
    // -----------------------------------------------------------------------

    /**
     * Returns the block entity at {@code pos}, routing through the ghost cache
     * or the target region's read-lock as appropriate.
     */
    @Nullable
    public BlockEntity getBlockEntity(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        WorldRegion owner = grid.getRegionFor(pos);
        if (owner == null) return level.getBlockEntity(pos);

        if (owner.owningThread == Thread.currentThread()) {
            return level.getBlockEntity(pos);
        }

        // Ghost zone: block-entities are captured per-tick by BoundaryManager
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        BoundaryManager.ChunkSnapshot snap = boundaryManager.getGhostChunk(owner, cx, cz);
        if (snap != null) {
            return snap.getBlockEntity(pos);
        }

        // Live read under read-lock
        long stamp = owner.getChunkLock().readLock();
        try {
            return level.getBlockEntity(pos);
        } finally {
            owner.getChunkLock().unlockRead(stamp);
        }
    }
}
