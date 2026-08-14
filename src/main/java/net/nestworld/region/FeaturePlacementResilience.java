package net.nestworld.region;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hardening for {@link NestworldTuning#RESILIENT_FEATURE_PLACEMENT} (2026-08-12 live-play
 * incident): vanilla's {@code ChunkGenerator.applyBiomeDecoration} deliberately re-throws any
 * feature/structure placement exception as a fatal {@code ReportedException}, crashing the whole
 * server over one broken feature (usually a third-party mod/datapack content bug). When the
 * resilient mode is on, the offending placement is skipped instead — this class logs it once per
 * distinct feature key (full detail) and counts further occurrences without re-logging each one,
 * so a repeatedly-broken feature doesn't spam the console but is still fully visible via {@code
 * /nestworld featurefails}.
 */
public final class FeaturePlacementResilience {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/FeatureResilience");
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private FeaturePlacementResilience() {}

    public static void recordSkipped(String featureKey, int chunkX, int chunkZ, Throwable t) {
        AtomicLong count = COUNTS.computeIfAbsent(featureKey, k -> new AtomicLong());
        long n = count.incrementAndGet();
        if (n == 1) {
            LOGGER.warn("Skipped broken worldgen feature/structure '{}' at chunk ({},{}): {} -- "
                    + "this is almost always a third-party mod/datapack content bug, not a "
                    + "NestWorld core issue. Further occurrences of this SAME feature will be "
                    + "counted but not logged individually (see /nestworld featurefails).",
                    featureKey, chunkX, chunkZ, String.valueOf(t));
        } else if (n == 10 || n == 100 || n == 1000 || n % 10000 == 0) {
            LOGGER.warn("Feature/structure '{}' has now failed and been skipped {} times.",
                    featureKey, n);
        }
    }

    public static String summary() {
        if (COUNTS.isEmpty()) {
            return "NW Feature-placement resilience: 0 skipped features/structures since boot.";
        }
        StringBuilder sb = new StringBuilder("NW Feature-placement resilience -- skipped since boot:\n");
        COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(30)
                .forEach(e -> sb.append(String.format("  %-60s count=%d%n", e.getKey(), e.getValue().get())));
        return sb.toString();
    }
}
