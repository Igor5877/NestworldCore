package net.nestworld.ae2detect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Observation only — AE2-PIN-LEAK-001 D2: where the AE2 work behind each approved block callback actually executes
 * (innermost call inside the hooked AE2 method, so addon deferral cannot hide the real thread), and the thread split
 * of MBCalculator calculation vs cluster destroy (MB_MODIFICATION sources).
 */
public final class D2Detect {
    static final class Site {
        final LongAdder calls = new LongAdder(), offDomain = new LongAdder(), onMain = new LongAdder(), offMain = new LongAdder(),
                offDomainRoutingOn = new LongAdder(), offDomainRoutingOff = new LongAdder();
        volatile String firstOffDomain = "-";
    }

    static final Map<String, Site> SITES = new ConcurrentHashMap<>();
    public static final LongAdder mbCalc = new LongAdder(), mbCalcOffMain = new LongAdder(),
            clusterDestroy = new LongAdder(), clusterDestroyOffMain = new LongAdder();

    /** Addon 0.5.0+ BlockCallbacks.ROUTING (by name); null when the addon has no D2 routing. */
    private static final java.lang.reflect.Field ROUTING = lookupRouting();

    private static java.lang.reflect.Field lookupRouting() {
        try {
            return Class.forName("net.nestworld.compat.ae2.glue.BlockCallbacks").getField("ROUTING");
        } catch (Throwable t) {
            return null;
        }
    }

    static String routing() {
        try {
            return ROUTING == null ? "n/a" : String.valueOf(ROUTING.getBoolean(null));
        } catch (Throwable t) {
            return "?";
        }
    }

    private D2Detect() {}

    public static void executed(String site, BlockEntity host) {
        Site s = SITES.computeIfAbsent(site, k -> new Site());
        s.calls.increment();
        boolean main = MekDetect.isMain(Thread.currentThread());
        (main ? s.onMain : s.offMain).increment();
        if (host == null || !(host.getLevel() instanceof ServerLevel sl)) return;
        String type = String.valueOf(BlockEntityType.getKey(host.getType()));
        Boolean in = BeReadyDetect.inDomainOf(sl, host.getBlockPos(), type);
        if (in != null && !in) {
            s.offDomain.increment();
            ("false".equals(routing()) ? s.offDomainRoutingOff : s.offDomainRoutingOn).increment();
            if ("-".equals(s.firstOffDomain)) s.firstOffDomain = type + "@" + host.getBlockPos().toShortString() + " on " + Thread.currentThread().getName();
        }
    }

    public static void mb(boolean destroy) {
        boolean main = MekDetect.isMain(Thread.currentThread());
        if (destroy) { clusterDestroy.increment(); if (!main) clusterDestroyOffMain.increment(); }
        else { mbCalc.increment(); if (!main) mbCalcOffMain.increment(); }
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("d2 sites (addon routing now=" + routing() + "):");
        SITES.forEach((k, s) -> sb.append("\n  ").append(k).append(" calls=").append(s.calls.sum()).append(" onMain=").append(s.onMain.sum())
                .append(" offMain=").append(s.offMain.sum()).append(" offDomain=").append(s.offDomain.sum()).append(" [routingOn=").append(s.offDomainRoutingOn.sum())
                .append(" routingOff=").append(s.offDomainRoutingOff.sum()).append("] firstOffDomain=").append(s.firstOffDomain));
        sb.append("\n mbCalc=").append(mbCalc.sum()).append(" mbCalcOffMain=").append(mbCalcOffMain.sum())
                .append(" clusterDestroy=").append(clusterDestroy.sum()).append(" clusterDestroyOffMain=").append(clusterDestroyOffMain.sum());
        return sb.toString();
    }
}
