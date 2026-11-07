package net.nestworld.compat.ae2.glue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * AE2-NEIGHBOR-AFFINITY-001 (owner plan 2026-11-07): AE2 automation hosts (import/export/storage buses, interfaces,
 * pattern providers and every other non-cable part) reach into the block next to them from AE2's grid tick, which runs
 * on the server thread. A non-AE2 block entity at that position would otherwise tick on its region thread at the same
 * time (CONFIRMED live: Oversize Interface ↔ Mekanism Extras pipe, Export Bus ↔ MasterfulMachinery item port). Each
 * host pins the position(s) it reaches to MAIN through the Core's generic position pin (CORE-PINZONE-001), so both
 * sides run on one thread. Reference-counted, runtime only; released when the host leaves the world.
 *
 * <p>No Core compile dependency (addon boundary): the Core facade is looked up reflectively; without it this is a
 * no-op. {@link #ENABLED} off = negative control.
 */
public final class NeighborAffinity {
    public static volatile boolean ENABLED = !Boolean.getBoolean("nestworld.compatAe2.neighborAffinityOff");

    private static final MethodHandle PIN, UNPIN;
    public static final AtomicLong pinned = new AtomicLong(), released = new AtomicLong(), unavailable = new AtomicLong();

    static {
        MethodHandle p = null, u = null;
        try {
            ClassLoader cl = NeighborAffinity.class.getClassLoader();
            Class<?> d = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", true, cl);
            MethodType t = MethodType.methodType(void.class, ServerLevel.class, BlockPos.class);
            p = MethodHandles.publicLookup().findStatic(d, "pinPositionToMain", t);
            u = MethodHandles.publicLookup().findStatic(d, "unpinPositionFromMain", t);
        } catch (Throwable ignored) {
            // Core without position pins: affinity unavailable (counted on use)
        }
        PIN = p;
        UNPIN = u;
    }

    private NeighborAffinity() {
    }

    /** Pins {@code positions} (BlockPos longs) in {@code level}; returns true when they were pinned (caller must release). */
    public static boolean pin(ServerLevel level, long[] positions) {
        if (!ENABLED || level == null || positions.length == 0) return false;
        if (PIN == null) {
            unavailable.incrementAndGet();
            return false;
        }
        try {
            for (long pos : positions) PIN.invokeExact(level, BlockPos.of(pos));
            pinned.addAndGet(positions.length);
            return true;
        } catch (Throwable t) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] neighbour affinity pin failed: {}", t.toString());
            return false;
        }
    }

    public static void release(ServerLevel level, long[] positions) {
        if (UNPIN == null || level == null) return;
        try {
            for (long pos : positions) UNPIN.invokeExact(level, BlockPos.of(pos));
            released.addAndGet(positions.length);
        } catch (Throwable t) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] neighbour affinity release failed: {}", t.toString());
        }
    }

    /** Read-only probe: the current BE execution domain of {@code pos} (with its block-entity type, if any). */
    public static String domainAt(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        String type = be == null ? null : BeDomainReady.typeOf(be);
        return level.dimension().location() + " " + pos.toShortString() + " be=" + type + " domain="
                + BeDomainReady.domainName(level, pos, type);
    }

    public static String summary() {
        return "neighborAffinity{enabled=" + ENABLED + " available=" + (PIN != null) + " pinned=" + pinned.get()
                + " released=" + released.get() + " live=" + (pinned.get() - released.get()) + " unavailable="
                + unavailable.get() + "}";
    }
}
