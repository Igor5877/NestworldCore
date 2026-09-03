package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Phase 2.2 (docs/SCHEDULED_TICK_COMPATIBILITY_CONTRACT.md #2/#3) -- the "single source of
 * truth" lookup called from patched {@code LevelTicks#hasScheduledTick}/
 * {@code #willTickThisTick}. A tick already routed into a free-running region's own
 * {@link RegionScheduledTicks} has already left vanilla's own bookkeeping (Finding A in the
 * contract doc) -- this closes Finding B (duplicate scheduling) by making that region-owned
 * pending state visible to the same two vanilla methods every dedup check (8 vanilla block
 * classes, and any mod's own diode-like block using the same API) already relies on.
 *
 * <p>Deliberately narrow for Phase 2.2: only checks {@link RegionScheduledTicks#isPending} for
 * a chunk's <em>current</em> owning region. Does not touch {@link RegionPhase#RANDOM_TICK}/
 * {@link RegionPhase#BLOCK_EVENT} (still on the accepted "may skip a round" mailbox), split/merge
 * migration (Phase 2.3, separate), or non-free-running regions (their scheduled-tick work-round
 * is barrier-synced -- by the time any caller could ask "is this still pending," it has already
 * executed, so there is no window to be blind to).
 */
public final class NestworldScheduledTickLookup {

    private NestworldScheduledTickLookup() {}

    /** 2026-08-31: counts how often this lookup is what makes {@code hasScheduledTick}/
     *  {@code willTickThisTick} return true -- i.e. vanilla asked "is anything scheduled here"
     *  about a position whose entry had ALREADY left vanilla's own bookkeeping (Finding A) and
     *  would, pre-Phase-2.2, have answered "no" and let a caller like DiodeBlock.checkTickOnNeighbor
     *  create a duplicate. A nonzero count under real load is the regression proof that this
     *  isn't just a theoretical gap -- exposed via {@code /nestworld scheduledtickloss}. */
    private static final java.util.concurrent.atomic.AtomicLong dedupHits = new java.util.concurrent.atomic.AtomicLong();

    public static long dedupHitCount() { return dedupHits.get(); }

    /** {@code level} may be null (a {@link net.minecraft.world.ticks.LevelTicks} not yet wired
     *  via {@code nestworldWithLevel}, or one belonging to an unmanaged dimension) -- always a
     *  safe "not pending here" in that case, never a false positive. */
    public static boolean isPendingInRegion(ServerLevel level, BlockPos pos, Object type) {
        if (level == null || !NestworldRegionSystem.isInitialised()) {
            return false;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = sys.getDimensionRegion(level);
        if (dr == null) {
            return false;
        }
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        WorldRegion region = dr.interiorRegionFor(cx, cz);
        if (region == null || !region.isFreeRunning()) {
            // Non-interior (border-band/main-bucket) or non-free-running: scheduled ticks for
            // these paths never enter RegionScheduledTicks in the first place (see
            // RegionThreadPool#runWorkRound) -- nothing to be blind to.
            return false;
        }
        int kind = type instanceof Block ? ScheduledTickHandoffMetrics.Tagged.BLOCK
                : type instanceof Fluid ? ScheduledTickHandoffMetrics.Tagged.FLUID
                : ScheduledTickHandoffMetrics.Tagged.OTHER;
        boolean pending = region.scheduledTicks.isPending(pos.asLong(), kind);
        if (pending) {
            dedupHits.incrementAndGet();
        }
        return pending;
    }
}
