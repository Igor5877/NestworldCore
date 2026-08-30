package net.nestworld.region;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 2026-08-30, per the user's explicit ТЗ following the ftbchunks-repro FTB Quests
 * DeferredInventoryDetection race (see project memory
 * ftbchunks-repro-ladder-ftbquests-race-2026-08-30): before building any general "mod
 * compatibility / execution isolation" classifier, first PROVE the exact concurrency
 * boundary for real mod callback surfaces -- which mod-owned callback classes get invoked
 * from a region thread vs the main thread, and specifically whether the SAME class is
 * invoked from BOTH (the "MIXED" signal that indicates a shared/static-state race risk,
 * since a class only ever reached from one thread context can't race itself).
 *
 * Deliberately cheap: counters + ONE representative stack sample per (site, listenerClass,
 * threadKind) triple, not a full per-call trace -- a per-call stack capture at real
 * concurrent-join load would itself become a measurable cost and skew the very load profile
 * being measured.
 */
public final class ModCallbackAttribution {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.modCallbackAttribution");

    private static final class Stats {
        final AtomicLong mainThreadCount = new AtomicLong();
        final AtomicLong regionThreadCount = new AtomicLong();
        final ConcurrentHashMap<Integer, Boolean> distinctRegionIds = new ConcurrentHashMap<>();
        volatile String mainThreadSampleStack;
        volatile String regionThreadSampleStack;
    }

    private static final ConcurrentHashMap<String, Stats> STATS = new ConcurrentHashMap<>();

    private ModCallbackAttribution() {}

    /** Call immediately before invoking a mod-owned callback (e.g. ContainerListener.slotChanged). */
    public static void record(String site, Object listener) {
        if (!ENABLED || listener == null) return;
        Class<?> cls = listener.getClass();
        String pkg = cls.getPackageName();
        // skip our own code and vanilla/Forge internals -- only third-party mod callbacks matter here
        if (pkg.startsWith("net.nestworld.") || pkg.startsWith("net.minecraft.")
                || pkg.startsWith("net.minecraftforge.") || pkg.startsWith("com.mojang.")) {
            return;
        }
        boolean isRegion = NestworldRegionSystem.nestworldIsRegionThread();
        String key = site + "|" + cls.getName();
        Stats s = STATS.computeIfAbsent(key, k -> new Stats());
        if (isRegion) {
            s.regionThreadCount.incrementAndGet();
            if (Thread.currentThread() instanceof RegionThread rt) {
                s.distinctRegionIds.put(System.identityHashCode(rt.getRegion()), Boolean.TRUE);
            }
            if (s.regionThreadSampleStack == null) {
                s.regionThreadSampleStack = shortStack();
            }
        } else {
            s.mainThreadCount.incrementAndGet();
            if (s.mainThreadSampleStack == null) {
                s.mainThreadSampleStack = shortStack();
            }
        }
    }

    private static String shortStack() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        // skip getStackTrace/shortStack/record's own frames (first 3)
        int start = 3;
        int end = Math.min(frames.length, start + 8);
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(frames[i].getClassName()).append('.').append(frames[i].getMethodName());
        }
        return sb.toString();
    }

    public static String report() {
        if (STATS.isEmpty()) {
            return "NW mod-callback-attribution: enabled=" + ENABLED + " -- no third-party callbacks recorded yet";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("NW mod-callback-attribution: enabled=").append(ENABLED)
                .append(" distinctCallbacks=").append(STATS.size()).append('\n');
        STATS.forEach((key, s) -> {
            long main = s.mainThreadCount.get();
            long region = s.regionThreadCount.get();
            boolean mixed = main > 0 && region > 0;
            sb.append("  ").append(mixed ? "[MIXED] " : "[      ] ").append(key)
                    .append(" main=").append(main).append(" region=").append(region)
                    .append(" distinctRegions=").append(s.distinctRegionIds.size()).append('\n');
            if (s.mainThreadSampleStack != null) {
                sb.append("      mainSample:   ").append(s.mainThreadSampleStack).append('\n');
            }
            if (s.regionThreadSampleStack != null) {
                sb.append("      regionSample: ").append(s.regionThreadSampleStack).append('\n');
            }
        });
        return sb.toString();
    }

    public static void reset() {
        STATS.clear();
    }
}
