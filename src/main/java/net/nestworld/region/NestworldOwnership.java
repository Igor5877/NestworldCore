package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Narrow, read-only ownership contract for compatibility code (AE2-COMPAT P2P-FORWARDING-FAIL-CLOSED-001,
 * owner-approved 2026-10-15). It ANSWERS one question and does nothing else: no callback execution, no
 * scheduling, no mailbox submit, no mutation.
 *
 * <p>Ownership is the same definition {@link NestworldOwnershipAssertions} enforces: the chunk of
 * {@code pos} belongs to the region whose {@code owningThread} executes it; a chunk outside every region
 * (or a level the region system does not manage) is executed by the server thread.
 *
 * <p>Fail-closed: a region with no thread right now (startup / split / merge transient) has no owner,
 * so the answer is {@code false}.
 */
public final class NestworldOwnership {
    /** Contract version of this query, for compatibility code that looks it up reflectively. */
    public static final int CONTRACT = 1;

    /** True iff the current thread is the thread that owns {@code pos}'s chunk in {@code level}. */
    public static boolean isCurrentThreadOwner(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (level.isClientSide()) return true;                       // no region execution on the client
        if (!(level instanceof ServerLevel server)) return false;
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return server.getServer().isSameThread();
        }
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(level).getGrid()
                .getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (owner == null) return server.getServer().isSameThread();
        RegionThread t = owner.owningThread;
        return t != null && Thread.currentThread() == t;
    }

    private NestworldOwnership() {
    }
}
