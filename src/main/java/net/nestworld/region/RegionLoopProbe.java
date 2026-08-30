package net.nestworld.region;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only, temporary: isolates whether blocking the main thread stalls a free-running
 * region's own local-tick loop, independent of any Minecraft/interaction-specific code. Counts
 * how many times each region's normal entity-round tick body executes (same location as {@code
 * applyPendingPlayerBlockInteractions()}), so a probe can compare the count immediately before
 * and after a controlled main-thread block and see whether the region kept progressing.
 */
public final class RegionLoopProbe {
    private static final ConcurrentHashMap<Integer, AtomicLong> counters = new ConcurrentHashMap<>();

    private RegionLoopProbe() {}

    public static void tick(int regionId) {
        counters.computeIfAbsent(regionId, k -> new AtomicLong()).incrementAndGet();
    }

    public static long get(int regionId) {
        AtomicLong c = counters.get(regionId);
        return c == null ? 0 : c.get();
    }
}
