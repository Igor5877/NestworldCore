package net.nestworld.metrics;

import net.minecraft.server.MinecraftServer;
import net.nestworld.api.ChunkGenSnapshot;
import net.nestworld.api.ChunkTickCost;
import net.nestworld.api.ModTickCost;
import net.nestworld.api.NestworldApi;
import net.nestworld.api.RegionSnapshot;
import net.nestworld.api.TickSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * All Prometheus text-rendering logic, and every reference to {@code
 * net.nestworld.api}, deliberately kept OUT of {@link NestworldMetricsMod} itself --
 * see that class's javadoc for the exact crash this split fixes (Forge's EventBus
 * reflectively enumerating {@code NestworldMetricsMod}'s declared methods at mod-
 * construction time, which must never see a {@code net.nestworld.api} type in any
 * method/lambda signature on THAT class). This class is never registered with
 * EventBus and is only loaded lazily, on the first actual {@code /metrics} request,
 * by which point {@code NestworldMetricsMod.onServerStarted}'s own presence check
 * has already confirmed NestworldCore's API exists.
 */
final class NestworldMetricsRenderer {
    private NestworldMetricsRenderer() {}

    /** Per-thread-group cumulative CPU time (nanoseconds) as of the last scrape --
     *  Prometheus counters must be monotonic, so we track our own running total per
     *  GROUP (thread IDs churn as pools recycle threads; names/groups don't). */
    private static final Map<String, Long> cpuNanosByGroup = new ConcurrentHashMap<>();
    /** Thread IDs already fully accounted for once they die, so a recycled ID from a
     *  brand-new thread doesn't inherit a dead thread's stale baseline. */
    private static final Map<Long, Long> lastSeenCpuNanosByThreadId = new ConcurrentHashMap<>();

    private static final Pattern TRAILING_NUMBER =
            Pattern.compile("[ #-]?\\d+$");

    static String render(MinecraftServer server) {
        StringBuilder sb = new StringBuilder(4096);
        if (server == null) {
            return "# server not ready\n";
        }

        // ---- overall tick health ----
        TickSnapshot tick = NestworldApi.ticks(server);
        gauge(sb, "nestworld_mspt_ms", "quantile", "avg", tick.msptAvg());
        gauge(sb, "nestworld_mspt_ms", "quantile", "p50", tick.msptP50());
        gauge(sb, "nestworld_mspt_ms", "quantile", "p95", tick.msptP95());
        gauge(sb, "nestworld_mspt_ms", "quantile", "p99", tick.msptP99());
        gauge(sb, "nestworld_mspt_ms", "quantile", "max", tick.msptMax());
        sb.append("nestworld_tps ").append(tick.tps()).append('\n');

        // ---- per-dimension chunk-gen + region state ----
        // Deliberately routed entirely through NestworldApi (dimensionKeys/chunkGen/
        // regions overloads keyed by String, not ServerLevel) -- this module is a plain
        // Forge mod compiled against MAPPED vanilla method names that do NOT survive
        // this project's SRG reobfuscation step, unlike NestworldApi's own
        // implementation (part of the reobfuscated core). A prior version called
        // MinecraftServer.getAllLevels()/ServerLevel methods directly here and failed
        // at runtime with NoSuchMethodError on the reobfuscated production jar.
        for (String dim : NestworldApi.dimensionKeys(server)) {
            ChunkGenSnapshot cg = NestworldApi.chunkGen(server, dim);
            labeled(sb, "nestworld_chunkgen_pending_player", dim, cg.pendingPlayer());
            labeled(sb, "nestworld_chunkgen_pending_bulk", dim, cg.pendingBulk());
            labeled(sb, "nestworld_chunkgen_promoted_total", dim, cg.promotedTotal());
            labeled(sb, "nestworld_chunkgen_promoted_avg_ms", dim, cg.promotedAvgMs());
            labeled(sb, "nestworld_chunkgen_promoted_p99_ms", dim, cg.promotedP99Ms());
            labeled(sb, "nestworld_chunkgen_admitted_total", dim, cg.admittedTotal());
            labeled(sb, "nestworld_chunkgen_pump_deadline_hits_total", dim, cg.pumpDeadlineHits());
            labeled(sb, "nestworld_chunkgen_predictive_requested_total", dim, cg.predictiveRequested());
            labeled(sb, "nestworld_chunkgen_pool_concurrency", dim, cg.genPoolConcurrency());
            labeled(sb, "nestworld_chunkgen_pool_active", dim, cg.genPoolActive());
            labeled(sb, "nestworld_chunkgen_pool_pending", dim, cg.genPoolPending());
            // generation* and integration* are JVM-wide (not per-dimension) --
            // see ChunkGenSnapshot's javadoc -- so these read identically across every
            // dim label; still emitted per-dimension for a uniform snapshot shape.
            labeled(sb, "nestworld_chunkgen_generated_total", dim, cg.generatedTotal());
            labeled(sb, "nestworld_chunkgen_generation_failed_total", dim, cg.generationFailedTotal());
            labeled(sb, "nestworld_chunkgen_generation_deferred_total", dim, cg.generationDeferredTotal());
            labeled(sb, "nestworld_chunkgen_generation_avg_ms", dim, cg.generationAvgMs());
            labeled(sb, "nestworld_chunkgen_generation_p99_ms", dim, cg.generationP99Ms());
            labeled(sb, "nestworld_chunkgen_generation_max_ms", dim, cg.generationMaxMs());
            labeled(sb, "nestworld_chunkgen_integration_completed_total", dim, cg.integrationCompletedTotal());
            labeled(sb, "nestworld_chunkgen_integration_avg_ms", dim, cg.integrationAvgMs());
            labeled(sb, "nestworld_chunkgen_integration_p99_ms", dim, cg.integrationP99Ms());

            List<RegionSnapshot> regions = NestworldApi.regions(server, dim);
            labeled(sb, "nestworld_region_count", dim, regions.size());
            double maxCost = 0;
            long totalEntities = 0;
            int freeRunning = 0;
            for (RegionSnapshot r : regions) {
                if (r.avgTickMs() > maxCost) {
                    maxCost = r.avgTickMs();
                }
                totalEntities += r.entityCount();
                if (r.freeRunning()) {
                    freeRunning++;
                }
            }
            labeled(sb, "nestworld_region_cost_max_ms", dim, maxCost);
            labeled(sb, "nestworld_region_entities_total", dim, totalEntities);
            labeled(sb, "nestworld_region_free_running_count", dim, freeRunning);

            // Top 5 hottest regions individually, by id -- an aggregate max/avg hides
            // WHICH region is hot; this is the "single hot spot vs uniformly loaded"
            // distinction, same reasoning as /nestworld status's own top-N default.
            List<RegionSnapshot> sorted = new java.util.ArrayList<>(regions);
            sorted.sort((a, b) -> Double.compare(b.avgTickMs(), a.avgTickMs()));
            for (int i = 0; i < Math.min(5, sorted.size()); i++) {
                RegionSnapshot r = sorted.get(i);
                sb.append("nestworld_region_top_cost_ms{dimension=\"").append(dim)
                        .append("\",region_id=\"").append(r.id()).append("\"} ")
                        .append(r.avgTickMs()).append('\n');
            }
        }

        // ---- "хто винен" -- cumulative measured tick time by mod (exact, not sampled) ----
        for (String kind : new String[] {"entity", "blockentity"}) {
            for (ModTickCost m : NestworldApi.modLoad(kind, 30)) {
                sb.append("nestworld_tick_ms_total{kind=\"").append(kind)
                        .append("\",mod=\"").append(m.modId()).append("\"} ")
                        .append(m.totalMs()).append('\n');
                sb.append("nestworld_tick_count_total{kind=\"").append(kind)
                        .append("\",mod=\"").append(m.modId()).append("\"} ")
                        .append(m.count()).append('\n');
            }
        }

        // ---- "в яких чанках" -- top-N chunks by measured tick cost, current window.
        // Deliberately bounded (top 20, not every loaded chunk) -- an unbounded per-chunk
        // series would be a Prometheus cardinality explosion on a real world with
        // thousands of loaded chunks. This is a rolling top-N snapshot, not a full map;
        // for full detail use /nestworld tickchunks in-game. ----
        int rank = 1;
        for (ChunkTickCost c : NestworldApi.tickHotspots(20)) {
            sb.append("nestworld_tick_hotspot_chunk_ms{rank=\"").append(rank++)
                    .append("\",dimension=\"").append(c.dimensionKey())
                    .append("\",chunk_x=\"").append(c.chunkX())
                    .append("\",chunk_z=\"").append(c.chunkZ()).append("\"} ")
                    .append(c.totalMs()).append('\n');
        }

        // ---- per-thread-group CPU time: the "what's actually saturated" answer ----
        renderThreadCpu(sb);

        // ---- JVM heap/GC ----
        var mem = ManagementFactory.getMemoryMXBean();
        var heap = mem.getHeapMemoryUsage();
        sb.append("nestworld_jvm_heap_used_bytes ").append(heap.getUsed()).append('\n');
        sb.append("nestworld_jvm_heap_committed_bytes ").append(heap.getCommitted()).append('\n');
        sb.append("nestworld_jvm_heap_max_bytes ").append(heap.getMax()).append('\n');
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = gc.getName().replaceAll("[^a-zA-Z0-9_]", "_");
            sb.append("nestworld_jvm_gc_collections_total{gc=\"").append(name).append("\"} ")
                    .append(gc.getCollectionCount()).append('\n');
            // Cumulative time spent IN that GC -- rate() of this over a window is
            // literally "% of wall-clock time this process spent GC-paused", the exact
            // number needed to tell a GC-driven stall from a CPU-starved-by-worldgen one.
            sb.append("nestworld_jvm_gc_collection_time_ms_total{gc=\"").append(name).append("\"} ")
                    .append(gc.getCollectionTime()).append('\n');
        }
        sb.append("nestworld_jvm_thread_count ").append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');
        sb.append("nestworld_jvm_available_processors ")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');

        return sb.toString();
    }

    /**
     * Groups live threads by name (trailing " N"/"-N"/"#N" suffix stripped, so a pool's
     * worker threads collapse into one series instead of one per ever-incrementing ID)
     * and reports CUMULATIVE CPU nanoseconds per group as a Prometheus counter.
     * {@code rate(nestworld_jvm_thread_cpu_seconds_total{group="X"}[1m])} in Grafana
     * gives fractional-core CPU usage for that group over time -- if it sums to
     * roughly {@code availableProcessors}, the box is genuinely CPU-saturated; if one
     * group alone tracks close to 1.0 while others sit near 0, that group is the
     * single-threaded bottleneck; if MSPT is high but total CPU usage across every
     * group is low, the stall is NOT CPU-bound at all (blocked on I/O/locks/GC-STW),
     * pointing back at the GC-time or thread-BLOCKED angle instead.
     */
    private static void renderThreadCpu(StringBuilder sb) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            return;
        }
        if (!bean.isThreadCpuTimeEnabled()) {
            bean.setThreadCpuTimeEnabled(true);
        }
        long[] ids = bean.getAllThreadIds();
        Map<Long, Long> currentIds = new HashMap<>(ids.length * 2);
        for (long id : ids) {
            long cpuNanos = bean.getThreadCpuTime(id);
            if (cpuNanos < 0) {
                continue; // thread died between getAllThreadIds() and this call
            }
            currentIds.put(id, cpuNanos);
            Long last = lastSeenCpuNanosByThreadId.get(id);
            long delta = last == null ? cpuNanos : Math.max(0, cpuNanos - last);
            lastSeenCpuNanosByThreadId.put(id, cpuNanos);
            if (delta == 0) {
                continue;
            }
            var threadInfo = bean.getThreadInfo(id);
            String name = threadInfo == null ? null : threadInfo.getThreadName();
            String group = name == null ? "unknown" : groupName(name);
            cpuNanosByGroup.merge(group, delta, Long::sum);
        }
        // Drop bookkeeping for threads that no longer exist -- unbounded growth guard
        // for a server that's been up a long time and recycled thousands of pool threads.
        lastSeenCpuNanosByThreadId.keySet().retainAll(currentIds.keySet());

        for (Map.Entry<String, Long> e : cpuNanosByGroup.entrySet()) {
            sb.append("nestworld_jvm_thread_cpu_seconds_total{group=\"").append(e.getKey()).append("\"} ")
                    .append(e.getValue() / 1_000_000_000.0).append('\n');
        }
    }

    private static String groupName(String threadName) {
        return TRAILING_NUMBER.matcher(threadName).replaceAll("");
    }

    private static void gauge(StringBuilder sb, String metric, String labelKey, String labelVal, double value) {
        sb.append(metric).append('{').append(labelKey).append("=\"").append(labelVal).append("\"} ")
                .append(value).append('\n');
    }

    private static void labeled(StringBuilder sb, String metric, String dim, double value) {
        sb.append(metric).append("{dimension=\"").append(dim).append("\"} ").append(value).append('\n');
    }
}
