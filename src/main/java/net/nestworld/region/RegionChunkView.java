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
    private static final Logger DIAG_LOGGER = LogManager.getLogger("NestWorld/ChunkViewDiag");

    private final WorldGrid grid;
    private final BoundaryManager boundaryManager;

    // NestWorld: real telemetry for how often cross-region getBlockEntity() reads
    // actually happen and what path they take, requested after the me_beam_former /
    // AE2-style "could this be worse than vanilla" discussion — static analysis of mod
    // bytecode can only say a network COULD span a region border, not whether it DOES
    // in a live world. These counters answer that empirically. Call-count-triggered
    // (not tick-triggered, unlike ChunkMap's tracker diagnostic) since this class has
    // no tick hook of its own — every REPORT_INTERVAL-th cross-region attempt (i.e.
    // excluding the same-region fast path) flushes and logs a snapshot.
    private static final int REPORT_INTERVAL = 20_000;
    private static final java.util.concurrent.atomic.AtomicLong sameRegionReads =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong crossRegionAttempts =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ghostZoneReads =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong deepLockAcquired =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong deepLockTimedOut =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong deepLockInterrupted =
            new java.util.concurrent.atomic.AtomicLong();

    private static void reportIfDue() {
        long n = crossRegionAttempts.incrementAndGet();
        if (n % REPORT_INTERVAL != 0) return;
        long same = sameRegionReads.getAndSet(0);
        long ghost = ghostZoneReads.getAndSet(0);
        long acquired = deepLockAcquired.getAndSet(0);
        long timedOut = deepLockTimedOut.getAndSet(0);
        long interrupted = deepLockInterrupted.getAndSet(0);
        long cross = ghost + acquired + timedOut + interrupted;
        long total = same + cross;
        DIAG_LOGGER.info(
                "cross-region getBlockEntity() over last {} attempts: same-region={} ({}%) "
                        + "ghost-zone={} deep-lock-acquired={} deep-lock-STALE-fallback={} "
                        + "interrupted={} — cross-region share={}%",
                total, same, total == 0 ? 0.0 : (100.0 * same / total),
                ghost, acquired, timedOut, interrupted,
                total == 0 ? 0.0 : (100.0 * cross / total));
        if (timedOut > 0 && cross > 0 && (100.0 * timedOut / cross) > 10.0) {
            DIAG_LOGGER.warn(
                    "{}% of cross-region reads in the last window degraded to a STALE "
                            + "best-effort fallback (lock timeout after {}ms) — a network/multiblock "
                            + "mod's live state may be reading briefly-out-of-date data across a region "
                            + "border under this load.",
                    String.format("%.1f", 100.0 * timedOut / cross),
                    NestworldTuning.CROSS_REGION_READ_LOCK_TIMEOUT_NANOS / 1_000_000L);
        }
    }

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
        // NestWorld: these must call the raw/direct accessor, not level.getBlockEntity(pos) —
        // the vanilla method redirects back into this class for any RegionThread caller, so
        // calling it here would recurse forever (confirmed live: StackOverflowError on every
        // block-entity read from a region thread).
        if (owner == null) return level.nestworldGetBlockEntityRaw(pos);

        if (owner.owningThread == Thread.currentThread()) {
            sameRegionReads.incrementAndGet();
            reportIfDue();
            return level.nestworldGetBlockEntityRaw(pos);
        }

        // Ghost zone: within BoundaryManager.GHOST_DEPTH of a border — live but
        // bounded-staleness read (see BoundaryManager.getGhostChunk javadoc).
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        BoundaryManager.ChunkSnapshot snap = boundaryManager.getGhostChunk(owner, cx, cz);
        if (snap != null) {
            ghostZoneReads.incrementAndGet();
            reportIfDue();
            return snap.getBlockEntity(pos);
        }

        // Deep foreign read (beyond ghost depth): try the real lock, bounded. A region
        // thread holds its OWN write-lock for its entire tick (up to
        // NestworldTuning.REGION_ENTITY_BUDGET_NANOS) — an unbounded readLock() here
        // risks two regions reading each other at the same moment and deadlocking
        // forever, which hangs the WHOLE server (RegionThreadPool's barrier waits on
        // every region, not just these two). On timeout, degrade to a best-effort
        // unsynchronized read instead of blocking indefinitely — no worse than the
        // pre-fix behavior, just no longer the default for every foreign read.
        long stamp;
        try {
            stamp = owner.getChunkLock().tryReadLock(
                    NestworldTuning.CROSS_REGION_READ_LOCK_TIMEOUT_NANOS,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deepLockInterrupted.incrementAndGet();
            reportIfDue();
            return level.nestworldGetBlockEntityRaw(pos);
        }
        if (stamp == 0L) {
            deepLockTimedOut.incrementAndGet();
            reportIfDue();
            return level.nestworldGetBlockEntityRaw(pos); // timed out — best-effort stale read
        }
        try {
            deepLockAcquired.incrementAndGet();
            reportIfDue();
            return level.nestworldGetBlockEntityRaw(pos);
        } finally {
            owner.getChunkLock().unlockRead(stamp);
        }
    }
}
