/*
 * NestworldCore - Runtime metrics collection
 */
package net.nestworld.metrics;

import com.google.gson.JsonObject;
import net.nestworld.island.Island;
import net.nestworld.island.IslandLifecycleManager;
import net.nestworld.island.IslandState;
import net.nestworld.tick.IslandTickProfile;
import net.nestworld.tick.TickBudgetManager;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and serialises runtime metrics for the orchestrator.
 *
 * Metrics are pushed to the orchestrator every 30s via SkyBlockAPIClient.
 * The orchestrator aggregates across all island-processes for cluster-wide view.
 */
public class MetricsCollector {

    private final IslandLifecycleManager lifecycleManager;
    private final TickBudgetManager tickBudgetManager;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final AtomicLong reportId = new AtomicLong(0);

    private long lastGcCount;
    private long lastGcTimeMs;
    private long lastCollectAt;

    public MetricsCollector(IslandLifecycleManager lifecycleManager, TickBudgetManager tickBudgetManager) {
        this.lifecycleManager = lifecycleManager;
        this.tickBudgetManager = tickBudgetManager;
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }

    /**
     * Collects a snapshot. Safe to call from any thread.
     */
    public JsonObject collect() {
        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("report_id", reportId.incrementAndGet());
        root.addProperty("timestamp", now);

        appendIslandMetrics(root);
        appendTickMetrics(root);
        appendJvmMetrics(root, now);

        lastCollectAt = now;
        return root;
    }

    private void appendIslandMetrics(JsonObject root) {
        int active = 0, warm = 0, warming = 0, cooling = 0, cold = 0, players = 0;
        for (Island island : lifecycleManager.all()) {
            players += island.getPlayerCount();
            switch (island.getState()) {
                case ACTIVE  -> active++;
                case WARM    -> warm++;
                case WARMING -> warming++;
                case COOLING -> cooling++;
                case COLD    -> cold++;
            }
        }
        JsonObject islands = new JsonObject();
        islands.addProperty("active",  active);
        islands.addProperty("warm",    warm);
        islands.addProperty("warming", warming);
        islands.addProperty("cooling", cooling);
        islands.addProperty("cold",    cold);
        islands.addProperty("total",   active + warm + warming + cooling + cold);
        islands.addProperty("players", players);
        root.add("islands", islands);
    }

    private void appendTickMetrics(JsonObject root) {
        IslandTickProfile profile = tickBudgetManager.getProfile();
        JsonObject tick = new JsonObject();
        tick.addProperty("tps",            String.format("%.2f", profile.getCurrentTps()));
        tick.addProperty("violations",     profile.getViolationCount());
        tick.addProperty("adaptive_state", profile.getAdaptiveState().name());
        tick.addProperty("total_ticks",    profile.getTotalTicks());
        root.add("tick", tick);
    }

    private void appendJvmMetrics(JsonObject root, long now) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        JsonObject mem = new JsonObject();
        mem.addProperty("used_mb",  heap.getUsed()      / (1024 * 1024));
        mem.addProperty("max_mb",   heap.getMax()       / (1024 * 1024));
        mem.addProperty("pct",      heap.getMax() > 0
            ? Math.round(heap.getUsed() * 100.0 / heap.getMax())
            : 0);
        root.add("memory", mem);

        // GC delta since last collect
        long gcCount = 0, gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            gcCount  += gc.getCollectionCount();
            gcTimeMs += gc.getCollectionTime();
        }
        long intervalMs = lastCollectAt > 0 ? now - lastCollectAt : 30_000;
        JsonObject gc = new JsonObject();
        gc.addProperty("collections_delta", gcCount  - lastGcCount);
        gc.addProperty("pause_ms_delta",    gcTimeMs - lastGcTimeMs);
        gc.addProperty("interval_ms",       intervalMs);
        root.add("gc", gc);

        lastGcCount  = gcCount;
        lastGcTimeMs = gcTimeMs;
    }
}
