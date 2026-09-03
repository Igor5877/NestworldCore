package net.nestworld.region;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Diagnostic-only, no behavior change. Shadow-mode validation for RecipeCandidateIndex: for
 * every lookup, records how many candidates the full scan checked vs how many the index would
 * have returned, and -- critically -- whether the index's candidate set ever failed to contain
 * every recipe that actually matched (a false negative, which must be exactly zero before any
 * production use is even considered) or whether re-applying BCLib's comparator to just the
 * index's candidates would have picked a different winner than the real full scan did.
 *
 * 2026-09-03, Candidate Recipe Index ТЗ Phase: shadow mode + differential validation.
 */
public final class RecipeCandidateIndexShadowStats {

    private RecipeCandidateIndexShadowStats() {}

    private static volatile boolean enabled = false;
    private static volatile long windowStartNanos = 0L;

    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder fullCandidateSum = new LongAdder();
    private static final LongAdder indexedCandidateSum = new LongAdder();
    private static final LongAdder falseNegatives = new LongAdder();
    private static final LongAdder comparatorMismatches = new LongAdder();

    private static final int CAP = 200_000;
    private static final AtomicIntegerArray indexedCountSamples = new AtomicIntegerArray(CAP);
    private static final AtomicLong writeIndex = new AtomicLong(0);

    // Detail log of every false negative / mismatch ever seen (should stay empty). Bounded so
    // a bug can't cause unbounded memory growth during a long shadow run.
    private static final int DETAIL_CAP = 200;
    private static final java.util.List<String> falseNegativeDetails =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static void reset() {
        lookups.reset();
        fullCandidateSum.reset();
        indexedCandidateSum.reset();
        falseNegatives.reset();
        comparatorMismatches.reset();
        writeIndex.set(0);
        falseNegativeDetails.clear();
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void record(int fullCandidateCount, int indexedCandidateCount,
                               boolean falseNegative, boolean comparatorMismatch, String detailIfBad) {
        if (!enabled) return;
        lookups.increment();
        fullCandidateSum.add(fullCandidateCount);
        indexedCandidateSum.add(indexedCandidateCount);
        if (falseNegative) {
            falseNegatives.increment();
            if (falseNegativeDetails.size() < DETAIL_CAP) {
                falseNegativeDetails.add(detailIfBad);
            }
        }
        if (comparatorMismatch) {
            comparatorMismatches.increment();
            if (falseNegativeDetails.size() < DETAIL_CAP) {
                falseNegativeDetails.add("MISMATCH: " + detailIfBad);
            }
        }

        long idx = writeIndex.getAndIncrement();
        indexedCountSamples.set((int) (idx % CAP), indexedCandidateCount);
    }

    public static String report() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        long n = lookups.sum();
        long totalWritten = writeIndex.get();
        int sampleCount = (int) Math.min(totalWritten, CAP);

        int[] snap = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            snap[i] = indexedCountSamples.get(i);
        }
        Arrays.sort(snap);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RecipeCandidateIndexShadowStats: window=%.1fs enabled=%s lookups=%d%n",
                windowSeconds, enabled, n));
        sb.append(String.format("  FALSE NEGATIVES=%d  COMPARATOR MISMATCHES=%d  (both MUST be 0)%n",
                falseNegatives.sum(), comparatorMismatches.sum()));
        if (n > 0) {
            sb.append(String.format("  mean full candidates=%.1f  mean indexed candidates=%.1f  mean reduction=%.1fx%n",
                    fullCandidateSum.sum() / (double) n, indexedCandidateSum.sum() / (double) n,
                    indexedCandidateSum.sum() == 0 ? 0.0 : fullCandidateSum.sum() / (double) Math.max(1, indexedCandidateSum.sum())));
        }
        if (sampleCount > 0) {
            sb.append(String.format("  indexedCandidateCount: median=%d p95=%d p99=%d max=%d (from %d samples, cap=%d)%n",
                    pct(snap, 0.50), pct(snap, 0.95), pct(snap, 0.99), snap[snap.length - 1], sampleCount, CAP));
        }
        if (!falseNegativeDetails.isEmpty()) {
            sb.append("  --- details (first ").append(falseNegativeDetails.size()).append(") ---\n");
            for (String d : falseNegativeDetails) {
                sb.append("    ").append(d).append("\n");
            }
        }
        return sb.toString();
    }

    private static int pct(int[] sortedAsc, double p) {
        if (sortedAsc.length == 0) return 0;
        int idx = (int) Math.ceil(p * sortedAsc.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedAsc.length) idx = sortedAsc.length - 1;
        return sortedAsc[idx];
    }
}
