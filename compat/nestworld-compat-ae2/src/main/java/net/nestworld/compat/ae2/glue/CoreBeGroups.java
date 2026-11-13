package net.nestworld.compat.ae2.glue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * MM-GROUP-DOMAIN-001: read-only view of Core's block-entity group registry (net.nestworld.region.BlockEntityGroups),
 * reached reflectively — no compile dependency on Core and none on the addon that registers groups. A group is
 * "together" when every member currently resolves to the same execution domain (position pins included), i.e. the
 * neighbour and everything that shares its state run on one thread. Absent Core class or no group ⇒ false
 * (callers keep their previous fail-closed path).
 */
final class CoreBeGroups {
    private static final MethodHandle GROUP_OF, RESOLVE;
    static final LongAdder together = new LongAdder(), apart = new LongAdder(), noGroup = new LongAdder(),
            regYes = new LongAdder(), regNo = new LongAdder(), regError = new LongAdder(), errors = new LongAdder();
    static {
        MethodHandle g = null, r = null;
        try {
            ClassLoader cl = CoreBeGroups.class.getClassLoader();
            Class<?> groups = Class.forName("net.nestworld.region.BlockEntityGroups", true, cl);
            g = MethodHandles.publicLookup().findStatic(groups, "groupOf",
                    MethodType.methodType(long[].class, ServerLevel.class, BlockPos.class));
            Class<?> d = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", true, cl);
            Class<?> domain = Class.forName("net.nestworld.region.BlockEntityExecutionDomain$Domain", true, cl);
            r = MethodHandles.publicLookup().findStatic(d, "resolveBlockEntityExecutionDomain",
                    MethodType.methodType(domain, ServerLevel.class, BlockPos.class, String.class))
                    .asType(MethodType.methodType(Object.class, ServerLevel.class, BlockPos.class, String.class));
        } catch (Throwable unavailable) {
            g = r = null;
        }
        GROUP_OF = g;
        RESOLVE = r;
    }

    private CoreBeGroups() {}

    static boolean available() { return GROUP_OF != null && RESOLVE != null; }

    /** True when {@code pos} is in a registered group (null = no Core support). */
    static Boolean registered(ServerLevel level, BlockPos pos) {
        if (!available()) return null;
        try {
            long[] g = (long[]) GROUP_OF.invokeExact(level, pos);
            (g != null ? regYes : regNo).increment();
            return g != null;
        } catch (Throwable t) {
            regError.increment();
            if (regError.sum() <= 3) Ae2Compat.LOG.warn("[nestworld-compat-ae2] Core group lookup failed", t);
            return null;
        }
    }

    /** Every member of the group containing {@code pos} currently resolves to {@code pos}'s domain. */
    static boolean together(ServerLevel level, BlockPos pos, String type) {
        if (!available()) return false;
        try {
            long[] members = (long[]) GROUP_OF.invokeExact(level, pos);
            if (members == null) { noGroup.increment(); return false; }
            Object d = RESOLVE.invokeExact(level, pos, type);
            for (long m : members) {
                if (m == pos.asLong()) continue;
                BlockPos mp = BlockPos.of(m);
                // Each member with ITS OWN type (a type-pinned controller resolves to MAIN); type from the block state,
                // never from another domain's block-entity map. Unknown/no BE ⇒ apart (fail-closed).
                String mt = BusTransit.expectedBeType(level, mp);
                if (mt == null) { apart.increment(); return false; }
                Object o = RESOLVE.invokeExact(level, mp, mt);
                if (!Objects.equals(d, o)) { apart.increment(); return false; }
            }
            together.increment();
            return true;
        } catch (Throwable t) {
            errors.increment();
            if (errors.sum() <= 3) Ae2Compat.LOG.warn("[nestworld-compat-ae2] Core group check failed", t);
            return false;
        }
    }

    static String status() {
        return "coreBeGroups available=" + available() + " together=" + together.sum() + " apart=" + apart.sum()
                + " noGroup=" + noGroup.sum() + " registered{yes=" + regYes.sum() + " no=" + regNo.sum() + " error="
                + regError.sum() + "} errors=" + errors.sum();
    }
}
