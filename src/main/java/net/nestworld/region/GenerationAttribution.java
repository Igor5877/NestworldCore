package net.nestworld.region;

/**
 * Phase 1 (docs/CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md, section 5.2 "Generation
 * bottleneck"): telemetry for the CPU-bound NOISE/SURFACE/CARVERS/FEATURES generation
 * work itself -- distinct from promotion (DistanceManager's updateFutures(), already
 * instrumented) and integration ({@link WorldgenGlueAttribution}'s chunk_finalize,
 * already instrumented). This is the missing third leg: how long the actual gated
 * {@code NestworldGenPool} tasks in {@code ChunkMap} take from admission to completion.
 *
 * <p>Coarse-grained by design for Phase 1 (spec section 18): one combined counter set
 * across both gated pools (NOISE/SURFACE/CARVERS and FEATURES), not yet split by
 * dimension or by individual status. Splitting further is explicitly deferred to a
 * later phase if this coarse view shows it's needed -- same "don't build telemetry
 * detail nobody asked for yet" discipline as the rest of this project's instrumentation.
 *
 * <p>Same ring-buffer percentile pattern as {@link WorldgenGlueAttribution} and {@code
 * DistanceManager}'s own promotion samples, for consistency. Static (JVM-wide, not
 * per-ChunkMap-instance): {@code NestworldGenPool} exists twice per dimension (the
 * shared NOISE/SURFACE/CARVERS pool and the separate FEATURES pool) and only the
 * Overworld is managed by default anyway -- a combined total is the right level of
 * detail until a real need for per-dimension/per-status generation numbers shows up.
 */
public final class GenerationAttribution {
    private GenerationAttribution() {}

    private static long completedCalls = 0, completedNanosSum = 0, completedMaxNanos = 0;
    private static long failedCalls = 0;
    private static long deferredCount = 0;

    private static final int SAMPLE_RING = 4096;
    private static final long[] samples = new long[SAMPLE_RING];
    private static int sampleIdx = 0, sampleCount = 0;

    /** Called once a gated generation task's real future completes (success or failure)
     *  -- elapsedNanos spans from task admission (dequeue/immediate-run) to completion,
     *  which is near-zero for an already-computed future and the real generation time
     *  otherwise (see NestworldGenPool.gate()'s own comment on why both happen). */
    public static void recordCompletion(long elapsedNanos, boolean success) {
        if (success) {
            completedCalls++;
            completedNanosSum += elapsedNanos;
            if (elapsedNanos > completedMaxNanos) completedMaxNanos = elapsedNanos;
            samples[sampleIdx] = elapsedNanos;
            sampleIdx = (sampleIdx + 1) % SAMPLE_RING;
            if (sampleCount < SAMPLE_RING) sampleCount++;
        } else {
            failedCalls++;
        }
    }

    /** Called each time a generation task is queued behind a full pool instead of
     *  admitted immediately -- the "deferred" counter from the spec's ChunkGenSnapshot. */
    public static void recordDeferred() {
        deferredCount++;
    }

    private static double percentile(double p) {
        if (sampleCount == 0) return 0.0;
        long[] copy = java.util.Arrays.copyOf(samples, sampleCount);
        java.util.Arrays.sort(copy);
        int idx = Math.min(sampleCount - 1, (int) Math.ceil(sampleCount * p) - 1);
        return copy[Math.max(0, idx)] / 1e6;
    }

    public static long completedTotal() { return completedCalls; }
    public static long failedTotal() { return failedCalls; }
    public static long deferredTotal() { return deferredCount; }
    public static double avgMs() {
        return completedCalls == 0 ? 0.0 : completedNanosSum / 1e6 / completedCalls;
    }
    public static double p50Ms() { return percentile(0.50); }
    public static double p95Ms() { return percentile(0.95); }
    public static double p99Ms() { return percentile(0.99); }
    public static double maxMs() { return completedMaxNanos / 1e6; }
}
