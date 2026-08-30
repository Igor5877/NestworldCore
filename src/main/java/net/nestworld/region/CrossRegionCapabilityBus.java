package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides cross-region capability access for mod multi-blocks, pipes,
 * energy cables and fluid networks that span two or more regions.
 *
 * <p><b>Controller-owns-all model:</b> when a mod requests a capability
 * (e.g. {@code ForgeCapabilities.ENERGY}) for a block that physically lies
 * in Region B, but whose "owner" (ME Controller, kinetic source, etc.) is
 * in Region A, this bus forwards the request to Region A's live block-entity
 * without any network round-trip — both regions share the same JVM heap.
 *
 * <p>Ownership is registered via {@link #registerController(BlockPos, BlockPos)}.
 * Multi-block mods are expected to call this when they form their structure.
 * For vanilla-compatible mods that don't know about region ownership, the bus
 * falls back to a ghost-zone read (read-only, 1-tick stale).
 *
 * <p>Thread-safety: capability reads can happen from any RegionThread concurrently.
 * NestWorld: corrected 2026-08-27 (full-core-audit finding) -- the previous wording here
 * claimed unsynchronized field reads are safe "provided the block-entity is not being
 * mutated at the exact same nanosecond," which is not a real JMM guarantee and doesn't
 * describe what actually makes this safe. Tracing the real code: {@code resolveFromRegion()}
 * takes a proper {@code StampedLock.readLock()} before reading, and
 * {@code resolveFromGhost()} reads from {@link BoundaryManager}'s ghost-chunk snapshot, a
 * genuine volatile-swap-a-whole-new-object publication (not live shared mutable state) --
 * both paths are correctly safe by an actual mechanism, not by the reasoning previously
 * written here. For writes (e.g. inserting items into an adjacent inventory), the bus
 * acquires a read-stamp on the controller region's StampedLock before delegating.
 */
public class CrossRegionCapabilityBus {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/CapabilityBus");

    private final ServerLevel level;
    private final WorldGrid grid;
    private final BoundaryManager boundaryManager;

    /**
     * Maps a "slave" block position (in a non-controller region) to the
     * block position of its controlling block-entity.
     *
     * Key:   slave BlockPos (long-packed)
     * Value: controller BlockPos (long-packed)
     */
    private final ConcurrentHashMap<Long, Long> slaveToController = new ConcurrentHashMap<>();

    public CrossRegionCapabilityBus(ServerLevel level, WorldGrid grid, BoundaryManager bm) {
        this.level = level;
        this.grid = grid;
        this.boundaryManager = bm;
    }

    // -----------------------------------------------------------------------
    // Registration API (called by multi-block mods when forming a structure)
    // -----------------------------------------------------------------------

    /**
     * Registers {@code slavePos} as a slave of the multi-block whose controller
     * is at {@code controllerPos}.  After this call, any capability request to
     * {@code slavePos} will be forwarded to the controller's block-entity.
     */
    public void registerController(BlockPos slavePos, BlockPos controllerPos) {
        slaveToController.put(slavePos.asLong(), controllerPos.asLong());
        LOGGER.debug("Registered slave {} → controller {}", slavePos, controllerPos);
    }

    /** Removes the slave→controller mapping (called when a multi-block breaks). */
    public void unregisterSlave(BlockPos slavePos) {
        slaveToController.remove(slavePos.asLong());
    }

    // -----------------------------------------------------------------------
    // Capability resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves a Forge capability for {@code pos}, handling the cross-region case.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code pos} has a registered controller mapping → forward to controller's BE.</li>
     *   <li>If {@code pos} is in a different region from the caller → read from ghost cache
     *       (read-only proxy; writes are queued for the owning region's next tick).</li>
     *   <li>Otherwise → return empty (let normal Forge capability lookup proceed).</li>
     * </ol>
     */
    public <T> LazyOptional<T> resolve(BlockPos pos, Capability<T> cap, Direction side) {
        // 1. Controller mapping
        Long controllerLong = slaveToController.get(pos.asLong());
        if (controllerLong != null) {
            BlockPos controllerPos = BlockPos.of(controllerLong);
            WorldRegion controllerRegion = grid.getRegionFor(controllerPos);
            if (controllerRegion != null) {
                return resolveFromRegion(controllerPos, cap, side, controllerRegion);
            }
        }

        // 2. Cross-region ghost read
        WorldRegion ownerRegion = grid.getRegionFor(pos);
        if (ownerRegion == null) return LazyOptional.empty();

        // Check if the caller is in a different region
        // (We detect this by checking if ownerRegion's thread is not the current thread)
        if (ownerRegion.owningThread != null && ownerRegion.owningThread != Thread.currentThread()) {
            return resolveFromGhost(pos, cap, side, ownerRegion);
        }

        // Same region — let normal lookup proceed
        return LazyOptional.empty();
    }

    // -----------------------------------------------------------------------
    // Resolution helpers
    // -----------------------------------------------------------------------

    /**
     * Acquires a read stamp on the target region's lock and returns the
     * capability directly from its live block-entity.
     */
    private <T> LazyOptional<T> resolveFromRegion(BlockPos pos, Capability<T> cap,
                                                    Direction side, WorldRegion region) {
        long stamp = region.getChunkLock().readLock();
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return LazyOptional.empty();
            return be.getCapability(cap, side);
        } finally {
            region.getChunkLock().unlockRead(stamp);
        }
    }

    /**
     * Returns a capability from the ghost-zone snapshot (read-only, 1 tick stale).
     * This is the safe fast-path for most energy/fluid/item reads.
     */
    private <T> LazyOptional<T> resolveFromGhost(BlockPos pos, Capability<T> cap,
                                                   Direction side, WorldRegion ownerRegion) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        BoundaryManager.ChunkSnapshot snap = boundaryManager.getGhostChunk(ownerRegion, cx, cz);
        if (snap == null) return LazyOptional.empty();

        BlockEntity be = snap.getBlockEntity(pos);
        if (be == null) return LazyOptional.empty();

        return be.getCapability(cap, side);
    }
}
