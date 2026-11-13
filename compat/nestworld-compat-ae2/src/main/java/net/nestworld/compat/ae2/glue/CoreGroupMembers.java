package net.nestworld.compat.ae2.glue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Reflective view of Core's BlockEntityGroups.groupOf (no compile dependency); null when absent or not a member. */
final class CoreGroupMembers {
    private static final MethodHandle GROUP_OF, CLAIM_SAFE;
    static {
        MethodHandle g = null, cs = null;
        try {
            Class<?> groups = Class.forName("net.nestworld.region.BlockEntityGroups", true, CoreGroupMembers.class.getClassLoader());
            g = MethodHandles.publicLookup().findStatic(groups, "groupOf", MethodType.methodType(long[].class, ServerLevel.class, BlockPos.class));
            try {
                cs = MethodHandles.publicLookup().findStatic(groups, "wholeGroupClaimSafe", MethodType.methodType(boolean.class, ServerLevel.class, BlockPos.class));
            } catch (Throwable older) {
                cs = null;
            }
        } catch (Throwable unavailable) {
            g = null;
            cs = null;
        }
        GROUP_OF = g;
        CLAIM_SAFE = cs;
    }

    private CoreGroupMembers() {}

    /** Explicit adapter declaration that the whole group may be owned by one domain (absent ⇒ false, fail-closed). */
    static boolean wholeGroupClaimSafe(ServerLevel level, BlockPos pos) {
        if (CLAIM_SAFE == null) return false;
        try { return (boolean) CLAIM_SAFE.invokeExact(level, pos); } catch (Throwable t) { return false; }
    }

    static long[] groupOf(ServerLevel level, BlockPos pos) {
        if (GROUP_OF == null) return null;
        try { return (long[]) GROUP_OF.invokeExact(level, pos); } catch (Throwable t) { return null; }
    }
}
