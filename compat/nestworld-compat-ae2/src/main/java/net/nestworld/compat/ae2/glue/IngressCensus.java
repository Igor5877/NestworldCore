package net.nestworld.compat.ae2.glue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * AE2-GRID-DOMAIN-001 D0 (diagnostics only): who calls into AE2 network storage / energy from OUTSIDE the grid's own
 * tick. Applied only with {@code -Dnestworld.compatAe2.ingressCensus=true} (separate mixin config); sampling only
 * while {@link #on}. One call in {@link #SAMPLE} is stack-walked: if no AE2 grid-tick frame is on the stack, the first
 * frame outside AE2 / NestWorld / JDK / Mixin is the external caller. No behaviour change.
 */
public final class IngressCensus {
    public static volatile boolean on;
    static final int SAMPLE = Math.max(1, Integer.getInteger("nestworld.compatAe2.ingressSample", 64));
    private static int tickCounter;   // racy on purpose: sampling only
    private static final Map<String, LongAdder> EXTERNAL = new ConcurrentHashMap<>();
    private static final LongAdder internal = new LongAdder(), sampled = new LongAdder();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final String[] INTERNAL_FRAMES = {
            "appeng.me.Grid.onLevelEndTick", "appeng.me.Grid.onServerEndTick", "appeng.me.service.TickManagerService" };
    private static final String[] SKIP = { "appeng.", "net.nestworld.", "java.", "jdk.", "sun.", "org.spongepowered.",
            "com.llamalad7.", "com.google.", "it.unimi.", "com.appliedenhancements.", "net.pedroksl.advanced_ae.",
            "com.glodblock.github.extendedae.", "gripe._90.megacells.", "net.oktawia.crazyae2addons.", "cn.dancingsnow.neoecoae." };

    private IngressCensus() {}

    /** Hot path: called at the head of NetworkStorage.insert/extract and EnergyService.extractAEPower. */
    public static void call(String op) {
        if (!on || (++tickCounter % SAMPLE) != 0) return;
        sampled.increment();
        String[] result = WALKER.walk(s -> {
            String caller = null;
            for (var it = s.iterator(); it.hasNext(); ) {
                var f = it.next();
                String name = f.getClassName() + "." + f.getMethodName();
                for (String in : INTERNAL_FRAMES) if (name.startsWith(in)) return null;
                if (caller == null && !skip(f.getClassName())) caller = f.getClassName() + "#" + f.getMethodName();
            }
            return new String[] { caller == null ? "(only AE2/addon frames)" : caller };
        });
        if (result == null) { internal.increment(); return; }
        EXTERNAL.computeIfAbsent(op + "  " + Thread.currentThread().getName().replaceAll("\\d+", "N") + "  " + result[0],
                k -> new LongAdder()).increment();
    }

    private static boolean skip(String cls) {
        for (String p : SKIP) if (cls.startsWith(p)) return true;
        return false;
    }

    public static String start() {
        EXTERNAL.clear();
        internal.reset();
        sampled.reset();
        on = true;
        return "ingress census started (1/" + SAMPLE + " calls stack-walked)";
    }

    public static String report(int top) {
        on = false;
        List<Map.Entry<String, LongAdder>> l = new ArrayList<>(EXTERNAL.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        long ext = 0;
        for (var e : l) ext += e.getValue().sum();
        StringBuilder sb = new StringBuilder("ingress census: sampled=" + sampled.sum() + " (×" + SAMPLE + ") internal="
                + internal.sum() + " external=" + ext + " — external callers (op  thread  first non-AE2 frame):");
        for (int i = 0; i < Math.min(top, l.size()); i++)
            sb.append("\n  ").append(l.get(i).getValue().sum() * SAMPLE).append("  ").append(l.get(i).getKey());
        return sb.toString();
    }
}
