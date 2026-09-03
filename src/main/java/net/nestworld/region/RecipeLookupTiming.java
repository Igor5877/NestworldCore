package net.nestworld.region;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only, no behavior change. Measures END-TO-END wall time of a recipe-lookup call
 * from the CALL SITE (CraftingMenu.slotChangedCraftingGrid, ResultSlot.onTake), not from inside
 * RecipeManager/BCLib. This is deliberate: BCLib's bcl_sort is a cancellable @Inject(at=HEAD),
 * so any timing hook placed inside RecipeManager's own body can be skipped depending on which
 * BCLib jar (original vs the NestWorld single-pass patch) is loaded and whether it cancels.
 * Wrapping the call site instead measures the true cost transparently, for either BCLib variant,
 * with zero changes needed between A/B runs -- only the BCLib jar on disk changes.
 *
 * 2026-09-02, BCLib bcl_sort single-pass patch validation (see RecipeLookupCensus for the
 * call-count/attribution side of this investigation).
 */
public final class RecipeLookupTiming {

    private RecipeLookupTiming() {}

    private static volatile boolean enabled = false;
    private static volatile long windowStartNanos = 0L;

    private static final LongAdder count = new LongAdder();
    private static final LongAdder sumNanos = new LongAdder();
    private static final AtomicLong maxNanos = new AtomicLong(0L);

    private static final LongAdder over100ms = new LongAdder();
    private static final LongAdder over500ms = new LongAdder();
    private static final LongAdder over1s = new LongAdder();
    private static final LongAdder over5s = new LongAdder();
    private static final LongAdder over10s = new LongAdder();

    // Bounded reservoir for percentile estimation -- fixed-size ring buffer, lock-free.
    private static final int CAPACITY = 500_000;
    private static final AtomicLongArray samples = new AtomicLongArray(CAPACITY);
    private static final AtomicLong writeIndex = new AtomicLong(0);

    public static void reset() {
        count.reset();
        sumNanos.reset();
        maxNanos.set(0L);
        over100ms.reset();
        over500ms.reset();
        over1s.reset();
        over5s.reset();
        over10s.reset();
        writeIndex.set(0);
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static void record(long durationNanos) {
        if (!enabled) return;
        count.increment();
        sumNanos.add(durationNanos);
        maxNanos.accumulateAndGet(durationNanos, Math::max);

        long ms = durationNanos / 1_000_000L;
        if (ms >= 100) over100ms.increment();
        if (ms >= 500) over500ms.increment();
        if (ms >= 1000) over1s.increment();
        if (ms >= 5000) over5s.increment();
        if (ms >= 10000) over10s.increment();

        long idx = writeIndex.getAndIncrement();
        samples.set((int) (idx % CAPACITY), durationNanos);
    }

    public static String report() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        long n = count.sum();
        long totalWritten = writeIndex.get();
        int sampleCount = (int) Math.min(totalWritten, CAPACITY);

        long[] snap = new long[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            snap[i] = samples.get(i);
        }
        Arrays.sort(snap);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "RecipeLookupTiming: window=%.1fs enabled=%s calls=%d callsPerSec=%.2f%n",
                windowSeconds, enabled, n, n / windowSeconds));
        if (n > 0) {
            sb.append(String.format("  mean=%.3fms  max=%.3fms%n",
                    (sumNanos.sum() / (double) n) / 1_000_000.0, maxNanos.get() / 1_000_000.0));
            if (sampleCount > 0) {
                sb.append(String.format("  median=%.3fms  p95=%.3fms  p99=%.3fms  (from %d reservoir samples, cap=%d)%n",
                        pct(snap, 0.50) / 1_000_000.0, pct(snap, 0.95) / 1_000_000.0, pct(snap, 0.99) / 1_000_000.0,
                        sampleCount, CAPACITY));
            }
        }
        sb.append(String.format(
                "  stalls: >100ms=%d  >500ms=%d  >1s=%d  >5s=%d  >10s=%d%n",
                over100ms.sum(), over500ms.sum(), over1s.sum(), over5s.sum(), over10s.sum()));
        return sb.toString();
    }

    private static long pct(long[] sortedAsc, double p) {
        if (sortedAsc.length == 0) return 0L;
        int idx = (int) Math.ceil(p * sortedAsc.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedAsc.length) idx = sortedAsc.length - 1;
        return sortedAsc[idx];
    }
}
