package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11 #31.4 step 4 measurement-only diagnostics: how much wall time {@code
 * ItemStack.useOn(UseOnContext)} costs on each side, main thread (fallback path) vs. region
 * thread (the dispatched path). Same reasoning as {@link PlayerAttackTiming}/{@link
 * PlayerInteractionTiming}/{@link PlayerUseItemTiming}.
 */
public final class PlayerUseItemOnBlockTiming {
    private static final AtomicLong mainNanos = new AtomicLong();
    private static final AtomicLong mainCount = new AtomicLong();
    private static final AtomicLong regionNanos = new AtomicLong();
    private static final AtomicLong regionCount = new AtomicLong();

    private PlayerUseItemOnBlockTiming() {}

    public static void recordMain(long nanos) {
        mainNanos.addAndGet(nanos);
        mainCount.incrementAndGet();
    }

    public static void recordRegion(long nanos) {
        regionNanos.addAndGet(nanos);
        regionCount.incrementAndGet();
    }

    public static void reset() {
        mainNanos.set(0);
        mainCount.set(0);
        regionNanos.set(0);
        regionCount.set(0);
    }

    public static String summary() {
        long mc = mainCount.get();
        long rc = regionCount.get();
        double mainAvgMicros = mc == 0 ? 0.0 : (mainNanos.get() / 1000.0) / mc;
        double regionAvgMicros = rc == 0 ? 0.0 : (regionNanos.get() / 1000.0) / rc;
        return String.format(
                "main: count=%,d totalMs=%.2f avgUs=%.1f | region: count=%,d totalMs=%.2f avgUs=%.1f",
                mc, mainNanos.get() / 1_000_000.0, mainAvgMicros,
                rc, regionNanos.get() / 1_000_000.0, regionAvgMicros);
    }
}
