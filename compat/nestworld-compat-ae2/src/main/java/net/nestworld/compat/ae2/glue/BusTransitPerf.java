package net.nestworld.compat.ae2.glue;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;

/** Fixture-only WP3-B measurement of complete tick windows; never changes scheduling or world state. */
public final class BusTransitPerf {
    private static final long[] TIMES = new long[120_000];
    private static volatile boolean active;
    private static final ThreadLocal<Boolean> INLINE = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.LongAdder INLINE_IMPORT = new java.util.concurrent.atomic.LongAdder(),
            INLINE_EXPORT = new java.util.concurrent.atomic.LongAdder(), TRANSIT_IMPORT = new java.util.concurrent.atomic.LongAdder(),
            TRANSIT_EXPORT = new java.util.concurrent.atomic.LongAdder();
    private static int count, firstTick, lastTick;
    private static long cpuStart;

    private BusTransitPerf() {}

    public static String start(MinecraftServer server) {
        count = 0;
        firstTick = lastTick = server.getTickCount();
        cpuStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        INLINE_IMPORT.reset(); INLINE_EXPORT.reset(); TRANSIT_IMPORT.reset(); TRANSIT_EXPORT.reset();
        active = true;
        return "WP3-B perf started at tick=" + firstTick;
    }

    public static void tick(MinecraftServer server) {
        if (!active) return;
        // At server-END the current tick's duration has not been written yet.
        int completed = server.getTickCount() - 1;
        if (completed <= lastTick) return;
        lastTick = completed;
        if (count < TIMES.length) TIMES[count++] = server.tickTimes[Math.floorMod(completed, server.tickTimes.length)];
    }

    public static String report(MinecraftServer server) {
        long cpuEnd = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        active = false;
        if (count == 0) return "WP3-B perf INVALID: no completed tick samples";
        long[] sorted = Arrays.copyOf(TIMES, count);
        Arrays.sort(sorted);
        double avg = Arrays.stream(sorted).average().orElse(0) / 1e6;
        double cpu = cpuStart >= 0 && cpuEnd >= cpuStart ? (cpuEnd - cpuStart) / 1e6 / (server.getTickCount() - firstTick) : -1;
        return String.format(Locale.ROOT,
                "WP3-B perf n=%d saturated=%s MSPTavg=%.6f p50=%.6f p95=%.6f p99=%.6f max=%.6f MAINcpuMsPerTick=%.6f inlineImportItems=%d inlineExportItems=%d transitImportItems=%d transitExportItems=%d",
                count, count == TIMES.length, avg, percentile(sorted, .50), percentile(sorted, .95),
                percentile(sorted, .99), sorted[count - 1] / 1e6, cpu, INLINE_IMPORT.sum(), INLINE_EXPORT.sum(),
                TRANSIT_IMPORT.sum(), TRANSIT_EXPORT.sum());
    }

    public static Boolean beginInline() {
        if (!active) return null;
        Boolean previous = INLINE.get();
        INLINE.set(true);
        return previous;
    }

    public static void endInline(Boolean previous) {
        if (!active) return;
        if (previous == null) INLINE.remove(); else INLINE.set(previous);
    }

    public static void inlineItems(boolean export, long amount) {
        if (active && Boolean.TRUE.equals(INLINE.get())) (export ? INLINE_EXPORT : INLINE_IMPORT).add(amount);
    }

    public static void transitItems(boolean export, long amount) {
        if (active) (export ? TRANSIT_EXPORT : TRANSIT_IMPORT).add(amount);
    }

    private static double percentile(long[] values, double p) {
        return values[Math.min(values.length - 1, (int) (values.length * p))] / 1e6;
    }
}
