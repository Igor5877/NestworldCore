package net.nestworld.region;

/**
 * P1.2/P1.4 (docs/P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md): precise, per-call-site telemetry for
 * the vanilla main-thread "glue" that fires when worldgen continuations reach a chunk-status
 * commit point -- as opposed to the class-name-heuristic {@link PollTaskAttribution.WorldgenBoundary}
 * from P1.0, which infers WORLDGEN/CHUNK_PROMOTION from a generic queued task's class name. These
 * hooks sit directly inside the two confirmed main-thread commit points in ChunkMap
 * ({@code onFullChunkStatusChange}, {@code protoChunkToFullChunk}'s continuation body) -- exact,
 * not inferred. O(1) per call, no scans; main-thread-only access (both hook points only ever run
 * on the main thread -- confirmed for protoChunkToFullChunk via the mainThreadMailbox trace in
 * project memory p1-1-protochunktofullchunk-commonpool-finding, correcting an earlier
 * ForkJoinPool.commonPool() misreading), so plain longs are safe.
 *
 * <p>"worldgen_tier_update" (bucket B, NestWorld's own Tier1/Tier2 promotion bookkeeping) and
 * "worldgen_promotion" (the underlying updateFutures() calls) are NOT duplicated here -- both
 * already have dedicated, more detailed telemetry in {@link DistanceManagerAttribution} and
 * DistanceManager's own nestworldPromotionXxx() counters respectively; {@link
 * NestworldRegionSystem#nestworldWorldgenGlueReport()} pulls those in alongside this class's A/C
 * numbers for one combined P1.2 report rather than re-instrumenting them.
 *
 * <p>"worldgen_commonpool": deliberately NOT instrumented as a real counter. The P1.1 investigation
 * that motivated this class found the one suspected commonPool call site (protoChunkToFullChunk's
 * bare-looking thenApplyAsync) actually routes through mainThreadMailbox/mainThreadExecutor, not
 * ForkJoinPool.commonPool() -- see project memory p1-1-protochunktofullchunk-commonpool-finding for
 * the corrected trace. No confirmed commonPool usage was found anywhere in the audited chunk-gen
 * continuation chain, so a counter here would only ever read zero; the finding itself (documented
 * in memory) is the answer to the ТЗ's "ForkJoinPool.commonPool() повністю пояснений" criterion.
 */
public final class WorldgenGlueAttribution {
    private WorldgenGlueAttribution() {}

    private static long mainGlueCalls = 0, mainGlueNanosSum = 0, mainGlueMaxNanos = 0;
    private static long chunkFinalizeCalls = 0, chunkFinalizeNanosSum = 0, chunkFinalizeMaxNanos = 0;

    private static final int SAMPLE_RING = 4096;
    private static final long[] mainGlueSamples = new long[SAMPLE_RING];
    private static int mainGlueSampleIdx = 0, mainGlueSampleCount = 0;
    private static final long[] chunkFinalizeSamples = new long[SAMPLE_RING];
    private static int chunkFinalizeSampleIdx = 0, chunkFinalizeSampleCount = 0;

    /** Bucket A: ChunkMap.onFullChunkStatusChange -- vanilla's own tracking-state commit hook. */
    public static void recordMainGlue(long elapsedNanos) {
        mainGlueCalls++;
        mainGlueNanosSum += elapsedNanos;
        if (elapsedNanos > mainGlueMaxNanos) mainGlueMaxNanos = elapsedNanos;
        mainGlueSamples[mainGlueSampleIdx] = elapsedNanos;
        mainGlueSampleIdx = (mainGlueSampleIdx + 1) % SAMPLE_RING;
        if (mainGlueSampleCount < SAMPLE_RING) mainGlueSampleCount++;
    }

    /** Bucket C: protoChunkToFullChunk's continuation body -- ProtoChunk->LevelChunk + Forge event. */
    public static void recordChunkFinalize(long elapsedNanos) {
        chunkFinalizeCalls++;
        chunkFinalizeNanosSum += elapsedNanos;
        if (elapsedNanos > chunkFinalizeMaxNanos) chunkFinalizeMaxNanos = elapsedNanos;
        chunkFinalizeSamples[chunkFinalizeSampleIdx] = elapsedNanos;
        chunkFinalizeSampleIdx = (chunkFinalizeSampleIdx + 1) % SAMPLE_RING;
        if (chunkFinalizeSampleCount < SAMPLE_RING) chunkFinalizeSampleCount++;
    }

    private static double percentile(long[] samples, int count, double p) {
        if (count == 0) return 0.0;
        long[] copy = java.util.Arrays.copyOf(samples, count);
        java.util.Arrays.sort(copy);
        int idx = Math.min(count - 1, (int) Math.ceil(count * p) - 1);
        return copy[Math.max(0, idx)] / 1e6;
    }

    /** Phase 1 (docs/CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md): public numeric accessors
     *  for bucket C (chunk_finalize) -- this IS the "integration" telemetry the spec's
     *  section 5.3 asks for (ProtoChunk -> LevelChunk, the main-thread commit point),
     *  already collected here since P1.2/P1.4; only needed a stable API surface to
     *  reach net.nestworld.api instead of the pre-existing formatted-string snapshot(). */
    public static long chunkFinalizeCompletedTotal() { return chunkFinalizeCalls; }
    public static double chunkFinalizeAvgMs() {
        return chunkFinalizeCalls == 0 ? 0.0 : chunkFinalizeNanosSum / 1e6 / chunkFinalizeCalls;
    }
    public static double chunkFinalizeP95Ms() { return percentile(chunkFinalizeSamples, chunkFinalizeSampleCount, 0.95); }
    public static double chunkFinalizeP99Ms() { return percentile(chunkFinalizeSamples, chunkFinalizeSampleCount, 0.99); }

    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("P1.2/P1.4 worldgen main-thread glue (bucket A + C, exact hook points, not heuristic):\n");
        sb.append(String.format("  A main_glue        calls=%,-8d total=%9.3fms avg=%.4fms max=%.3fms p50=%.4fms p95=%.4fms p99=%.4fms%n",
                mainGlueCalls, mainGlueNanosSum / 1e6,
                mainGlueCalls == 0 ? 0.0 : mainGlueNanosSum / 1e6 / mainGlueCalls, mainGlueMaxNanos / 1e6,
                percentile(mainGlueSamples, mainGlueSampleCount, 0.50),
                percentile(mainGlueSamples, mainGlueSampleCount, 0.95),
                percentile(mainGlueSamples, mainGlueSampleCount, 0.99)));
        sb.append(String.format("  C chunk_finalize    calls=%,-8d total=%9.3fms avg=%.4fms max=%.3fms p50=%.4fms p95=%.4fms p99=%.4fms%n",
                chunkFinalizeCalls, chunkFinalizeNanosSum / 1e6,
                chunkFinalizeCalls == 0 ? 0.0 : chunkFinalizeNanosSum / 1e6 / chunkFinalizeCalls, chunkFinalizeMaxNanos / 1e6,
                percentile(chunkFinalizeSamples, chunkFinalizeSampleCount, 0.50),
                percentile(chunkFinalizeSamples, chunkFinalizeSampleCount, 0.95),
                percentile(chunkFinalizeSamples, chunkFinalizeSampleCount, 0.99)));
        sb.append("  commonpool_calls    0 (confirmed: no ForkJoinPool.commonPool() usage found in the audited chain -- see memory p1-1-protochunktofullchunk-commonpool-finding)\n");
        return sb.toString();
    }
}
