package net.nestworld.region;

/**
 * P0.1 pollTask() attribution audit (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md follow-up,
 * requested after Phase 1's RegionThreadPool telemetry showed poll_avg ≈ wait_avg for the ENTITY/
 * BLOCK_ENTITY phases -- i.e. most of what looked like "waiting on regions" was actually main-
 * thread pollTask() work). This class answers: what IS that pollTask() work?
 *
 * <p>Wired into {@code ServerChunkCache.pollTask()}/{@code MainThreadExecutor.pollTask()}
 * (vanilla-patched, see those files). Every method here is O(1) per call -- no scans, no
 * allocation on the hot path (categorization is a handful of {@code String.contains} checks on an
 * already-resolved class name, done once per EXECUTED task, not once per queued task). Main-
 * thread-only access (pollTask always runs on the main/region-dispatch thread), so plain longs are
 * safe without synchronization, matching this codebase's existing convention for this kind of
 * counter (see {@code RegionThreadPool.PhaseStats}).
 */
public final class PollTaskAttribution {
    private PollTaskAttribution() {}

    public enum Category {
        ADMISSION_DRAIN,      // nestworldDrainDeferredAdmissions()
        DISTANCE_MANAGER,     // distanceManager.runAllUpdates()
        CHUNK_MAP_PROMOTE,    // chunkMap.promoteChunkMap()
        LIGHTING,             // lightEngine.tryScheduleUpdate()
        GENERIC_CHUNK_MAP,    // generic-queue task whose class name mentions ChunkMap
        GENERIC_REGION,       // generic-queue task whose class name mentions net.nestworld/RegionThread
        GENERIC_LIGHTING,     // generic-queue task whose class name mentions LightEngine
        GENERIC_OTHER         // generic-queue task, uncategorized
    }

    private static final long[] nanosSum = new long[Category.values().length];
    private static final long[] maxNanos = new long[Category.values().length];
    private static final long[] callCount = new long[Category.values().length];

    // Per-tick correlation (P0.1's second ask): total pollTask time this "wait window" vs. region
    // execution vs. true idle wait vs. everything else, reset by whoever reads it per tick/window.
    private static long windowPollNanos = 0;
    private static long windowPollCalls = 0;

    public static void record(Category category, long elapsedNanos) {
        int i = category.ordinal();
        nanosSum[i] += elapsedNanos;
        if (elapsedNanos > maxNanos[i]) maxNanos[i] = elapsedNanos;
        callCount[i]++;
        windowPollNanos += elapsedNanos;
        windowPollCalls++;
    }

    /** Called once per game tick (from NestworldRegionSystem) to read+reset the correlation window. */
    public static long[] drainWindow() {
        long[] result = {windowPollNanos, windowPollCalls};
        windowPollNanos = 0;
        windowPollCalls = 0;
        return result;
    }

    public static Category classifyGeneric(Runnable task) {
        String name = task.getClass().getName();
        if (name.contains("ChunkMap")) return Category.GENERIC_CHUNK_MAP;
        if (name.contains("net.nestworld") || name.contains("RegionThread")) return Category.GENERIC_REGION;
        if (name.contains("LightEngine")) return Category.GENERIC_LIGHTING;
        return Category.GENERIC_OTHER;
    }

    // P1.0 audit (docs/P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md): the boundary the eventual P1
    // redesign needs to cut is "worldgen CompletableFuture continuation chain" vs "everything
    // else main-thread-required" -- this maps the SAME already-computed class name (no extra
    // scan) onto that boundary, empirically grounded in what's actually been observed landing in
    // GENERIC_OTHER across every P0.x reading this session (CompletableFuture$Uni*/AsyncSupply +
    // ChunkTaskPriorityQueueSorter = the worldgen status-pipeline continuation chain;
    // ServerChunkCache$$Lambda = NestWorld/vanilla's own high-frequency low-cost bookkeeping, not
    // generation work). CAVEAT: this is a class-name heuristic, not true task provenance -- under
    // real mixed player load (unlike this session's isolated bulk-gen-only tests) some non-
    // worldgen code could also produce CompletableFuture$Uni* instances and get misclassified as
    // WORLDGEN. Getting true per-task provenance (which chunk, which stage) would need either
    // reflection into CompletableFuture's internal dependency fields (fragile across JDK versions)
    // or tagging futures at creation time in ChunkMap (which is "moving/touching" scheduling code,
    // out of scope for an audit-only phase) -- deliberately not attempted here.
    public enum WorldgenBoundary { WORLDGEN, CHUNK_PROMOTION, NON_WORLDGEN, UNKNOWN }

    private static final long[] boundaryNanosSum = new long[WorldgenBoundary.values().length];
    private static final long[] boundaryMaxNanos = new long[WorldgenBoundary.values().length];
    private static final long[] boundaryCallCount = new long[WorldgenBoundary.values().length];

    // Percentiles specifically for WORLDGEN -- this is the bucket P1's acceptance criteria (its
    // section 14/15) cares about shrinking, so it's the one worth a proper p50/p95/p99 view, not
    // just avg/max. Ring buffer, same bounded-memory pattern as RegionThreadPool.PhaseStats.
    private static final int WORLDGEN_SAMPLE_RING = 4096;
    private static final long[] worldgenSamples = new long[WORLDGEN_SAMPLE_RING];
    private static int worldgenSampleIdx = 0, worldgenSampleCount = 0;

    public static WorldgenBoundary classifyBoundary(String className) {
        if (className.startsWith("java.util.concurrent.CompletableFuture$Uni")
                || className.startsWith("java.util.concurrent.CompletableFuture$Async")
                || className.contains("ChunkTaskPriorityQueueSorter")) return WorldgenBoundary.WORLDGEN;
        if (className.contains("ChunkMap")) return WorldgenBoundary.CHUNK_PROMOTION;
        if (className.contains("ServerChunkCache")) return WorldgenBoundary.NON_WORLDGEN;
        return WorldgenBoundary.UNKNOWN;
    }

    public static void recordBoundary(WorldgenBoundary b, long elapsedNanos) {
        int i = b.ordinal();
        boundaryNanosSum[i] += elapsedNanos;
        if (elapsedNanos > boundaryMaxNanos[i]) boundaryMaxNanos[i] = elapsedNanos;
        boundaryCallCount[i]++;
        if (b == WorldgenBoundary.WORLDGEN) {
            worldgenSamples[worldgenSampleIdx] = elapsedNanos;
            worldgenSampleIdx = (worldgenSampleIdx + 1) % WORLDGEN_SAMPLE_RING;
            if (worldgenSampleCount < WORLDGEN_SAMPLE_RING) worldgenSampleCount++;
        }
    }

    private static double percentile(long[] samples, int count, double p) {
        if (count == 0) return 0.0;
        long[] copy = java.util.Arrays.copyOf(samples, count);
        java.util.Arrays.sort(copy);
        int idx = Math.min(count - 1, (int) Math.ceil(count * p) - 1);
        return copy[Math.max(0, idx)] / 1e6;
    }

    public static String boundarySnapshot() {
        StringBuilder sb = new StringBuilder();
        long totalNanos = 0, totalCalls = 0;
        for (WorldgenBoundary b : WorldgenBoundary.values()) {
            totalNanos += boundaryNanosSum[b.ordinal()];
            totalCalls += boundaryCallCount[b.ordinal()];
        }
        sb.append(String.format("P1.0 worldgen/main-thread boundary (GENERIC_OTHER only, class-name heuristic): calls=%,d total=%.3fms%n",
                totalCalls, totalNanos / 1e6));
        for (WorldgenBoundary b : WorldgenBoundary.values()) {
            int i = b.ordinal();
            if (boundaryCallCount[i] == 0) continue;
            double pct = totalNanos == 0 ? 0.0 : 100.0 * boundaryNanosSum[i] / totalNanos;
            sb.append(String.format("  %-16s calls=%,-8d total=%9.3fms (%5.1f%%) avg=%.4fms max=%.3fms%n",
                    b, boundaryCallCount[i], boundaryNanosSum[i] / 1e6, pct,
                    boundaryNanosSum[i] / 1e6 / boundaryCallCount[i], boundaryMaxNanos[i] / 1e6));
        }
        if (worldgenSampleCount > 0) {
            sb.append(String.format("  WORLDGEN percentiles (n=%,d samples): p50=%.4fms p95=%.4fms p99=%.4fms%n",
                    worldgenSampleCount,
                    percentile(worldgenSamples, worldgenSampleCount, 0.50),
                    percentile(worldgenSamples, worldgenSampleCount, 0.95),
                    percentile(worldgenSamples, worldgenSampleCount, 0.99)));
        }
        return sb.toString();
    }

    // GENERIC_OTHER turned out to dominate one live reading (89% of poll_total) while the
    // ChunkMap/Region/Lighting substring heuristic above caught almost nothing of it -- this is
    // a bounded (capped distinct-class-name count, so still O(1) amortized, no unbounded growth)
    // breakdown of WHAT is actually landing in that bucket, keyed by class name.
    private static final int GENERIC_OTHER_DETAIL_CAP = 60;
    private static final java.util.Map<String, long[]> genericOtherDetail = new java.util.HashMap<>();

    /** {count, nanosSum, maxNanos} per class name, called only when classifyGeneric() returned GENERIC_OTHER. */
    public static void recordGenericOtherDetail(String className, long elapsedNanos) {
        long[] entry = genericOtherDetail.get(className);
        if (entry == null) {
            if (genericOtherDetail.size() >= GENERIC_OTHER_DETAIL_CAP) return; // bounded: stop admitting new distinct names once full
            entry = new long[3];
            genericOtherDetail.put(className, entry);
        }
        entry[0]++;
        entry[1] += elapsedNanos;
        if (elapsedNanos > entry[2]) entry[2] = elapsedNanos;
    }

    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        long totalNanos = 0, totalCalls = 0;
        for (Category c : Category.values()) {
            totalNanos += nanosSum[c.ordinal()];
            totalCalls += callCount[c.ordinal()];
        }
        sb.append(String.format("poll_total: calls=%,d total=%.3fms%n", totalCalls, totalNanos / 1e6));
        for (Category c : Category.values()) {
            int i = c.ordinal();
            if (callCount[i] == 0) continue;
            double pct = totalNanos == 0 ? 0.0 : 100.0 * nanosSum[i] / totalNanos;
            sb.append(String.format("  %-18s calls=%,-8d total=%9.3fms (%5.1f%%) avg=%.4fms max=%.3fms%n",
                    c, callCount[i], nanosSum[i] / 1e6, pct, nanosSum[i] / 1e6 / callCount[i], maxNanos[i] / 1e6));
        }
        if (!genericOtherDetail.isEmpty()) {
            sb.append(String.format("  GENERIC_OTHER breakdown by class (top by total time, cap=%d distinct names seen):%n",
                    GENERIC_OTHER_DETAIL_CAP));
            genericOtherDetail.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                    .limit(15)
                    .forEach(e -> {
                        long[] v = e.getValue();
                        sb.append(String.format("    %-70s calls=%,-8d total=%9.3fms avg=%.4fms max=%.3fms%n",
                                e.getKey(), v[0], v[1] / 1e6, v[1] / 1e6 / v[0], v[2] / 1e6));
                    });
        }
        return sb.toString();
    }
}
