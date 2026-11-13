package net.nestworld.region;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * MM-GROUP-DOMAIN-001 (narrow, internal; NOT Mod API — see MOD-API-BE-GROUP-001): a runtime registry of block-entity
 * GROUPS (positions whose state is shared, e.g. a multiblock controller and its ports) plus the two read-only facts a
 * compatibility layer needs to keep such a group on one thread without pinning it to MAIN:
 * <ul>
 *   <li>{@link #geometricDomain}: the domain a position would have from its type and geometry only, ignoring
 *       position pins (CORE-PINZONE-001) — "could the whole group run on one region thread?";</li>
 *   <li>{@link #positionPinRefs}: how many position-pin references a position holds — lets a caller tell its own
 *       pins from pins taken by someone else (e.g. an automation host that reaches the position from MAIN).</li>
 * </ul>
 * The registry changes no execution by itself: block-entity dispatch is untouched. Callers (addons) reach this class
 * reflectively; absent ⇒ they fall back to their previous behaviour. Runtime state only, never persisted.
 * Safe from any thread.
 */
public final class BlockEntityGroups {
    private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, long[]>> GROUPS = new ConcurrentHashMap<>();
    public static final AtomicLong registered = new AtomicLong(), unregistered = new AtomicLong();
    private static volatile Field pinMapField;

    private BlockEntityGroups() {}

    /** Registers {@code members} (BlockPos longs) as one group; a member already in another group is moved. */
    /** Groups whose OWNER (the compat adapter that registered them) declared, after a fixture, that executing the whole
     *  group on one dedicated domain thread is safe (TZ AE2-GRID-DOMAIN-001 §3″.1 class 1′). Keyed by the group array. */
    private static final java.util.Set<long[]> CLAIM_SAFE = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Like {@link #registerGroup} plus the adapter's explicit {@code wholeGroupClaimSafe} declaration. */
    public static void registerGroup(ServerLevel level, long[] members, boolean wholeGroupClaimSafe) {
        registerGroup(level, members);
        long[] g = groupOf(level, BlockPos.of(members[0]));
        if (g != null && wholeGroupClaimSafe) CLAIM_SAFE.add(g);
    }

    /** Did the group containing {@code pos} get an explicit whole-group claim-safe declaration? */
    public static boolean wholeGroupClaimSafe(ServerLevel level, BlockPos pos) {
        long[] g = groupOf(level, pos);
        return g != null && CLAIM_SAFE.contains(g);
    }

    public static void registerGroup(ServerLevel level, long[] members) {
        ConcurrentHashMap<Long, long[]> m = GROUPS.computeIfAbsent(level.dimension(), d -> new ConcurrentHashMap<>());
        long[] copy = members.clone();
        for (long p : copy) m.put(p, copy);
        registered.incrementAndGet();
    }

    /** Removes the group whose member list equals {@code members}; entries re-registered to another group stay. */
    public static void unregisterGroup(ServerLevel level, long[] members) {
        ConcurrentHashMap<Long, long[]> m = GROUPS.get(level.dimension());
        if (m == null) return;
        for (long p : members) m.computeIfPresent(p, (k, g) -> {
            if (!java.util.Arrays.equals(g, members)) return g;
            CLAIM_SAFE.remove(g);
            return null;
        });
        unregistered.incrementAndGet();
    }

    /** The members of the group containing {@code pos}, or null. The returned array must not be modified. */
    @Nullable
    public static long[] groupOf(ServerLevel level, BlockPos pos) {
        ConcurrentHashMap<Long, long[]> m = GROUPS.get(level.dimension());
        return m == null ? null : m.get(pos.asLong());
    }

    /** Execution domain of {@code pos} from type pins/cascade-safety and geometry only (position pins ignored). */
    public static BlockEntityExecutionDomain.Domain geometricDomain(ServerLevel level, BlockPos pos, @Nullable String beTypeId) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return BlockEntityExecutionDomain.Domain.MAIN;
        }
        NestworldDimensionRegion dr = NestworldRegionSystem.get().getDimensionRegion(level);
        if (dr == null) return BlockEntityExecutionDomain.Domain.MAIN;
        int policy = NestworldDimensionRegion.blockEntityTypePolicy(NestworldRegionSystem.get().getPins(), beTypeId);
        WorldRegion r = dr.blockEntityRegionFor(policy, pos.getX() >> 4, pos.getZ() >> 4);
        return r == null ? BlockEntityExecutionDomain.Domain.MAIN : new BlockEntityExecutionDomain.Domain(r);
    }

    /** Number of CORE-PINZONE-001 references held on {@code pos} (0 = not position-pinned). */
    @SuppressWarnings("unchecked")
    public static int positionPinRefs(ServerLevel level, BlockPos pos) {
        if (!NestworldRegionSystem.isInitialised()) return 0;
        NestworldPins pins = NestworldRegionSystem.get().getPins();
        if (pins.isBePositionPinEmpty()) return 0;
        try {
            Field f = pinMapField;
            if (f == null) {
                f = NestworldPins.class.getDeclaredField("pinnedBePositions");
                f.setAccessible(true);
                pinMapField = f;
            }
            Map<ResourceKey<Level>, ConcurrentHashMap<Long, Integer>> all =
                    (Map<ResourceKey<Level>, ConcurrentHashMap<Long, Integer>>) f.get(pins);
            ConcurrentHashMap<Long, Integer> m = all.get(level.dimension());
            Integer v = m == null ? null : m.get(pos.asLong());
            return v == null ? 0 : v;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // unknown pin layout: report "pinned by someone" whenever the position is pinned at all (fail-closed)
            return pins.isBePositionPinned(level.dimension(), pos.asLong()) ? Integer.MAX_VALUE : 0;
        }
    }

    public static String stats() {
        int members = 0;
        for (var m : GROUPS.values()) members += m.size();
        return "beGroups registered=" + registered.get() + " unregistered=" + unregistered.get() + " members=" + members;
    }
}
