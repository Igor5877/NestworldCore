package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fix for the free-running-vs-neighbor boundary TOCTOU race (project memory:
 * freerunning-neighbor-boundary-race.md): {@link RegionThread#tickEntities()}
 * snapshots {@code ownedEntityIds} once per pass, but a free-running region's
 * pass can take long enough (60-900ms observed under load) for {@code
 * BoundaryEntityTransfer}'s main-thread reassignment scan -- which assumes "no
 * region thread is ticking while reassignments happen", a guarantee only
 * barrier-synced regions actually provide -- to hand an entity to a neighbor
 * WHILE the old owner's already-in-flight pass still holds it in its snapshot.
 *
 * <p>The snapshot decides who is a CANDIDATE for this pass; this class's live
 * check, called immediately before the entity is actually ticked, decides who
 * may actually do the work. {@link EntityOwnershipGuard} remains the last-line
 * assertion for any mutation that slips past this anyway (e.g. a future call
 * site that forgets the re-check) -- this class is the fix, not a replacement
 * for that guard.
 *
 * <p>Always active (not diagnostic-gated): unlike {@link EntityOwnershipGuard},
 * this changes behavior (skips a stale-owned entity rather than ticking it),
 * so it isn't behind an opt-in flag. The skip counter/log line exist purely to
 * measure how often the race window is actually hit in practice.
 */
public final class EntityOwnershipRecheck {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/EntityOwnershipRecheck");
    private static final AtomicLong skipTotal = new AtomicLong();
    private static final int MAX_LOGGED = 20;

    private EntityOwnershipRecheck() {}

    /**
     * Call immediately before ticking a snapshotted entity. Returns true if
     * {@code region} still owns {@code uuid} right now; records and logs a skip
     * (bounded verbosity) otherwise.
     */
    public static boolean stillOwns(WorldRegion region, UUID uuid) {
        if (region.ownsEntity(uuid)) return true;
        recordSkip(region, uuid);
        return false;
    }

    private static void recordSkip(WorldRegion region, UUID uuid) {
        long n = skipTotal.incrementAndGet();
        WorldRegion currentOwner = NestworldRegionSystem.isInitialised()
                ? NestworldRegionSystem.get().getGrid().findOwningRegion(uuid) : null;
        String msg = String.format(
                "ENTITY_OWNERSHIP_RECHECK_SKIP region=%d entity=%s previousOwner=%d currentOwner=%s localTick=%d (skip #%d)",
                region.getId(), uuid, region.getId(),
                currentOwner != null ? String.valueOf(currentOwner.getId()) : "none",
                region.getLocalTickCount(), n);
        if (n <= MAX_LOGGED || n % 1000 == 0) {
            LOGGER.warn(msg);
        }
    }

    public static long skips() { return skipTotal.get(); }
    public static void reset() { skipTotal.set(0); }
}
