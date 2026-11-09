package net.nestworld.ae2detect;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * AE2-DEMAIN-001 B0 (owner D9): diagnostic timing of AE2's MAIN-thread work, observation only (test copies).
 * Every wrapper calls the original exactly once. Timing is OFF until {@code /nwae2b0 start} (cost when off: one
 * volatile read per wrapped call); counters are split by thread class MAIN / REGION / OTHER.
 * Keys: tick.server / tick.level / tick.ready (TickHandler), svc.serverEnd.&lt;Service&gt; / svc.levelEnd.&lt;Service&gt;
 * (per-service split of the grid end-of-tick work), crafting.tickLogic (CraftingCpuLogic), storage.insert|extract.MOD|SIM
 * (NetworkStorage ops), transit.item.insert|extract / transit.fluid.fill|drain (Forge capability adapters of AE2
 * generic inventories: interfaces, pattern providers — the proxy for B5 transit), mb.calc / mb.destroy.
 */
public final class B0Detect {
    public static volatile boolean on;
    static volatile long startNanos, startTick;
    static volatile int startTickCount;

    public enum Cls { MAIN, REGION, OTHER }

    static final class Stat {
        final LongAdder[] calls = {new LongAdder(), new LongAdder(), new LongAdder()};
        final LongAdder[] nanos = {new LongAdder(), new LongAdder(), new LongAdder()};
        final AtomicLong maxNanos = new AtomicLong();
    }

    static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    /** off-MAIN callers of transit adapters / NetworkStorage: first non-AE2 frame class, sampled (bounded). */
    static final Map<String, LongAdder> CALLERS = new ConcurrentHashMap<>();
    static final LongAdder callerSamples = new LongAdder();
    static final int MAX_CALLER_SAMPLES = 20000;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private B0Detect() {}

    public static Cls cls(Thread t) {
        if (MekDetect.isMain(t)) return Cls.MAIN;
        return t.getName().startsWith("NestWorld-Region") ? Cls.REGION : Cls.OTHER;
    }

    /** @return start nanos, or 0 when timing is off */
    public static long begin() {
        return on ? System.nanoTime() : 0L;
    }

    public static void end(String key, long t0) {
        if (t0 == 0L) return;
        long d = System.nanoTime() - t0;
        Stat s = STATS.computeIfAbsent(key, k -> new Stat());
        int c = cls(Thread.currentThread()).ordinal();
        s.calls[c].increment();
        s.nanos[c].add(d);
        long m;
        while (d > (m = s.maxNanos.get()) && !s.maxNanos.compareAndSet(m, d)) { }
    }

    /** Off-MAIN caller sample for transit / storage ops (only while on, only off MAIN, bounded). */
    public static void caller(String what) {
        if (!on) return;
        Thread me = Thread.currentThread();
        if (MekDetect.isMain(me) || callerSamples.sum() >= MAX_CALLER_SAMPLES) return;
        callerSamples.increment();
        String c = WALKER.walk(st -> st.map(f -> f.getClassName())
                .filter(n -> !n.startsWith("appeng.") && !n.startsWith("net.nestworld.ae2detect") && !n.startsWith("java.")
                        && !n.contains("mixinextras") && !n.startsWith("net.minecraftforge.common.util.LazyOptional"))
                .findFirst().orElse("?"));
        CALLERS.computeIfAbsent(what + " <- " + c + " [" + cls(me) + "]", k -> new LongAdder()).increment();
    }

    public static String start(int tickCount) {
        STATS.clear();
        CALLERS.clear();
        callerSamples.reset();
        startNanos = System.nanoTime();
        startTickCount = tickCount;
        on = true;
        return "b0 timing ON (reset) at tick " + tickCount;
    }

    public static String stop() {
        on = false;
        return "b0 timing OFF (data kept; report still valid)";
    }

    public static String report(int tickCount, double avgTickMs) {
        long wall = startNanos == 0 ? 0 : System.nanoTime() - startNanos;
        int ticks = Math.max(1, tickCount - startTickCount);
        StringBuilder b = new StringBuilder("b0 on=").append(on).append(" window=").append(String.format("%.1f", wall / 1e9))
                .append("s ticks=").append(ticks).append(" serverAvgTickMs=").append(String.format("%.2f", avgTickMs))
                .append("\n key | calls MAIN/REGION/OTHER | ms MAIN/REGION/OTHER | MAIN us/tick | max us");
        long mainTopLevel = 0;
        for (var e : new TreeMap<>(STATS).entrySet()) {
            Stat s = e.getValue();
            long cm = s.calls[0].sum(), cr = s.calls[1].sum(), co = s.calls[2].sum();
            long nm = s.nanos[0].sum(), nr = s.nanos[1].sum(), no = s.nanos[2].sum();
            if (e.getKey().equals("tick.server") || e.getKey().equals("tick.level")) mainTopLevel += nm;
            b.append("\n ").append(e.getKey()).append(" | ").append(cm).append('/').append(cr).append('/').append(co)
                    .append(" | ").append(ms(nm)).append('/').append(ms(nr)).append('/').append(ms(no))
                    .append(" | ").append(String.format("%.1f", nm / 1e3 / ticks)).append(" | ")
                    .append(String.format("%.0f", s.maxNanos.get() / 1e3));
        }
        b.append("\n AE2 MAIN (tick.server+tick.level) us/tick=").append(String.format("%.1f", mainTopLevel / 1e3 / ticks));
        if (avgTickMs > 0) b.append(" share of avg tick=").append(String.format("%.2f%%", 100.0 * mainTopLevel / 1e6 / ticks / avgTickMs));
        return b.toString();
    }

    public static String callers(int top) {
        StringBuilder b = new StringBuilder("b0 off-MAIN callers (samples=").append(callerSamples.sum()).append("):");
        CALLERS.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum())).limit(top)
                .forEach(e -> b.append("\n ").append(e.getValue().sum()).append("  ").append(e.getKey()));
        return b.toString();
    }

    private static String ms(long n) {
        return String.format("%.1f", n / 1e6);
    }
}
