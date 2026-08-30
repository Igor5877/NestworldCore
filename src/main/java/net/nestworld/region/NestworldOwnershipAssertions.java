package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Phase 9 (docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md §13) — generic ownership assertions for
 * entities, block positions, and block entities. Same DIAGNOSTIC-vs-STRICT shape as {@link
 * NestworldTickOwnership} (that class stays LevelTicks-specific; this one is the general-purpose
 * sibling the spec asks for — deliberately NOT merged into one class, since LevelTicks' ownership
 * model is "one thread for the whole server" while this one is "one thread PER region", a
 * different check shape).
 *
 * <p>Off by default ({@link NestworldTuning#OWNERSHIP_ASSERTIONS_ENABLED}) — every call does a
 * region-grid lookup (entity: {@code findOwningRegion}, an O(1) map lookup; block: {@code
 * getRegionForChunk}, also O(1) but through one more indirection), real per-call overhead not
 * acceptable to pay unconditionally in production. Call sites should still be as narrow as
 * possible (guard the call in {@code if (NestworldTuning.OWNERSHIP_ASSERTIONS_ENABLED)} at the
 * CALLER too, where the object being asserted is expensive to describe) even though every method
 * here also early-returns on the same flag.
 *
 * <p><b>Why this catches the "stale mailbox destination" scenario</b> (the detail flagged
 * 2026-08-27 as the most important part of this design): ownership is looked up FRESH on every
 * call via {@code WorldGrid.findOwningRegion}/{@code getRegionForChunk}, never cached across the
 * assertion call. If an entity/block was queued to region B's mailbox, then region-split/merge/
 * handover moved real ownership to region C before B got around to draining and applying that
 * message, calling {@code assertEntityOwner} at the point of actually applying the mutation
 * (running on B's thread) computes the CURRENT owner (C) fresh, sees B's thread != C's thread, and
 * reports a violation — exactly the "mailbox destination != automatically the current owner"
 * bug class this is meant to catch, with no separate staleness-tracking machinery needed. This
 * only works if callers assert AT THE ACTUAL MUTATION POINT (after dequeuing, right before
 * applying), not at message-send time — asserting at send time would only prove the SENDER's
 * ownership belief at that moment, not the destination's validity by the time it actually runs.
 */
public final class NestworldOwnershipAssertions {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private static final AtomicLong violationCount = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> violationCountByOperation = new ConcurrentHashMap<>();

    private NestworldOwnershipAssertions() {}

    public static long getViolationCount() {
        return violationCount.get();
    }

    /** {@code /nestworld ownershipassertions} — one line per operation that has ever violated,
     *  plus the running total, matching {@code NestworldCommand}'s existing terse-status style. */
    public static String summary() {
        if (violationCountByOperation.isEmpty()) {
            return "violations=0 mode=" + (NestworldTuning.OWNERSHIP_ASSERTIONS_ENABLED
                    ? (NestworldTuning.OWNERSHIP_ASSERTIONS_STRICT ? "STRICT" : "DIAGNOSTIC") : "DISABLED");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("violations=").append(violationCount.get())
                .append(" mode=").append(NestworldTuning.OWNERSHIP_ASSERTIONS_STRICT ? "STRICT" : "DIAGNOSTIC");
        violationCountByOperation.forEach((op, count) -> sb.append("\n  ").append(op).append('=').append(count.get()));
        return sb.toString();
    }

    /** Assert {@code entity} is only mutated by its owning region's own thread. No-op if
     *  assertions are disabled, the region system isn't initialised/managing this level, or the
     *  entity isn't currently owned by any region (e.g. mid-spawn, nothing to compare against
     *  yet — not itself a violation). */
    public static void assertEntityOwner(Entity entity, String operation) {
        if (!NestworldTuning.OWNERSHIP_ASSERTIONS_ENABLED) return;
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(entity.level())) {
            return;
        }
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(entity.level())
                .getGrid().findOwningRegion(entity.getUUID());
        if (owner == null) return;
        check("Entity." + operation, "entity=" + entity.getType() + " uuid=" + entity.getUUID()
                + " pos=" + entity.blockPosition().toShortString(), owner);
    }

    /** Assert the block at {@code pos} is only mutated by its owning region's own thread. */
    public static void assertBlockOwner(Level level, BlockPos pos, String operation) {
        if (!NestworldTuning.OWNERSHIP_ASSERTIONS_ENABLED) return;
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
        if (owner == null) return;
        check("Block." + operation, "pos=" + pos.toShortString(), owner);
    }

    /** Assert {@code blockEntity} is only mutated by the region owning its block position —
     *  per INV-03 (spec §3), a BlockEntity's ownership boundary is always identical to its
     *  block position's, so this is a thin wrapper around {@link #assertBlockOwner}, not an
     *  independent lookup. */
    public static void assertBlockEntityOwner(BlockEntity blockEntity, String operation) {
        if (!NestworldTuning.OWNERSHIP_ASSERTIONS_ENABLED) return;
        Level level = blockEntity.getLevel();
        if (level == null) return;
        assertBlockOwner(level, blockEntity.getBlockPos(), "BlockEntity." + operation);
    }

    private static void check(String operation, String objectDescription, WorldRegion owner) {
        Thread current = Thread.currentThread();
        if (!(current instanceof RegionThread)) {
            // Scoped to region-thread-vs-region-thread ownership only, matching what Stage 3's
            // guards currently enforce -- main-thread callers are a SEPARATE, already-tracked
            // gap (docs/PHASE9_1_ENTITY_MUTATION_AUDIT.md / PHASE9_1_BLOCK_MUTATION_AUDIT.md:
            // Player.interactOn(), item/XP pickup, block break/place's main-thread direction),
            // not something this assertion should flood on every single ordinary main-thread
            // write while that gap is still open. Revisit once those are dispatcher-covered.
            return;
        }
        RegionThread expected = owner.owningThread;
        if (expected == null) return; // region has no thread right now (startup/shutdown/split) -- nothing to check against
        if (current == expected) return; // correct — the common case, cheap to fall through

        violationCount.incrementAndGet();
        violationCountByOperation.computeIfAbsent(operation, k -> new AtomicLong()).incrementAndGet();
        String report = buildViolationReport(operation, objectDescription, owner, current, expected);
        LOGGER.error(report);
        if (NestworldTuning.OWNERSHIP_ASSERTIONS_STRICT) {
            throw new RuntimeException(report);
        }
    }

    private static String buildViolationReport(String operation, String objectDescription,
            WorldRegion owner, Thread current, RegionThread expected) {
        StringBuilder sb = new StringBuilder();
        sb.append("---- NestWorld CROSS_REGION_MUTATION ----\n");
        sb.append("Operation: ").append(operation).append('\n');
        sb.append("Object: ").append(objectDescription).append('\n');
        sb.append("Owner region: ").append(owner.getId()).append('\n');
        sb.append("Expected thread: '").append(expected.getName()).append("' (id=").append(expected.getId()).append(")\n");
        sb.append("Actual caller thread: '").append(current.getName()).append("' (id=").append(current.getId()).append(")\n");
        if (current instanceof RegionThread rt) {
            WorldRegion currentRegion = rt.getRegion();
            sb.append("Current region: ").append(currentRegion.getId())
                    .append(" (free-running=").append(currentRegion.isFreeRunning()).append(")\n");
        } else {
            sb.append("Current region: n/a (not a RegionThread — likely main thread)\n");
        }
        sb.append("Total ownership violations this run: ").append(violationCount.get()).append('\n');
        sb.append("Call site:\n");
        StackTraceElement[] trace = current.getStackTrace();
        int limit = Math.min(trace.length, 20);
        for (int i = 0; i < limit; i++) {
            sb.append("    at ").append(trace[i]).append('\n');
        }
        sb.append("------------------------------------------\n");
        return sb.toString();
    }
}
