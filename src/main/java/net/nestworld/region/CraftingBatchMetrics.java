package net.nestworld.region;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only, no behavior change (see CraftingMenu's nestworld$beginBatch/endBatch).
 * Tracks how many logical bulk crafting-grid operations (quickMoveStack / Shift-click) run,
 * how many recipe lookups they would have triggered without batching (suppressed) vs how many
 * actually ran, and the distribution of mutations coalesced per batch.
 *
 * 2026-09-03, ТЗ: Transaction-Scoped Batching for CraftingMenu.slotsChanged.
 */
public final class CraftingBatchMetrics {

    private CraftingBatchMetrics() {}

    private static final LongAdder batchesStarted = new LongAdder();
    private static final LongAdder batchesCompleted = new LongAdder();
    private static final LongAdder suppressedSlotsChanged = new LongAdder();
    private static final LongAdder unbatchedSlotsChanged = new LongAdder();
    private static final LongAdder recipeLookups = new LongAdder();

    private static final int CAPACITY = 200_000;
    private static final AtomicIntegerArray mutationsPerBatchSamples = new AtomicIntegerArray(CAPACITY);
    private static final AtomicLong writeIndex = new AtomicLong(0);

    public static void reset() {
        batchesStarted.reset();
        batchesCompleted.reset();
        suppressedSlotsChanged.reset();
        unbatchedSlotsChanged.reset();
        recipeLookups.reset();
        writeIndex.set(0);
    }

    public static void recordBatchStarted() {
        batchesStarted.increment();
    }

    public static void recordBatchCompleted(int mutationsInBatch) {
        batchesCompleted.increment();
        long idx = writeIndex.getAndIncrement();
        mutationsPerBatchSamples.set((int) (idx % CAPACITY), mutationsInBatch);
    }

    public static void recordSuppressed() {
        suppressedSlotsChanged.increment();
    }

    public static void recordUnbatchedSlotsChanged() {
        unbatchedSlotsChanged.increment();
    }

    public static void recordRecipeLookup() {
        recipeLookups.increment();
    }

    public static String report() {
        long started = batchesStarted.sum();
        long completed = batchesCompleted.sum();
        long suppressed = suppressedSlotsChanged.sum();
        long unbatched = unbatchedSlotsChanged.sum();
        long lookups = recipeLookups.sum();
        long totalWritten = writeIndex.get();
        int sampleCount = (int) Math.min(totalWritten, CAPACITY);

        int[] snap = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            snap[i] = mutationsPerBatchSamples.get(i);
        }
        Arrays.sort(snap);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "CraftingBatchMetrics: batchesStarted=%d batchesCompleted=%d%n", started, completed));
        sb.append(String.format(
                "  slotsChanged: unbatched=%d  suppressed(batched)=%d  actualRecipeLookups=%d%n",
                unbatched, suppressed, lookups));
        long wouldHaveBeen = unbatched + suppressed;
        if (wouldHaveBeen > 0) {
            sb.append(String.format(
                    "  reduction: %d slot-changes -> %d recipe lookups (%.1f%% suppressed)%n",
                    wouldHaveBeen, lookups, 100.0 * suppressed / wouldHaveBeen));
        }
        if (sampleCount > 0) {
            sb.append(String.format(
                    "  mutationsPerBatch: mean=%.2f  median=%d  p95=%d  p99=%d  max=%d  (from %d samples, cap=%d)%n",
                    mean(snap), pct(snap, 0.50), pct(snap, 0.95), pct(snap, 0.99), snap[snap.length - 1],
                    sampleCount, CAPACITY));
        }
        return sb.toString();
    }

    private static double mean(int[] sortedAsc) {
        long sum = 0;
        for (int v : sortedAsc) sum += v;
        return sortedAsc.length == 0 ? 0.0 : (double) sum / sortedAsc.length;
    }

    private static int pct(int[] sortedAsc, double p) {
        if (sortedAsc.length == 0) return 0;
        int idx = (int) Math.ceil(p * sortedAsc.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedAsc.length) idx = sortedAsc.length - 1;
        return sortedAsc[idx];
    }
}
