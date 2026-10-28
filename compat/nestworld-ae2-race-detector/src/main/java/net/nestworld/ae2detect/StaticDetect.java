package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Observation-only detector for AE2-AUDIT-STATIC-001 candidates (AE2 15.4.10). Each "site" guards ONE static field
 * shared by every grid / cluster / item entity; any two different threads inside the same site at once is a race
 * on that static (the static has no lock of its own).
 *   ITERATION_BUFFER : Grid.notifyAllNodes (static ArrayList used by all grids)
 *   MB_MODIFICATION  : QuantumCluster / CraftingCPUCluster / SpatialPylonCluster.destroy (static WeakReference flag)
 *   TRANSFORM_CACHE  : TransformLogic.canTransformIn* / tryTransform (static IdentityHashMap + lazy sets)
 *   FAKE_PLAYERS     : FakePlayer.get (static WeakHashMap)
 * Wrappers call the original exactly once.
 */
public final class StaticDetect {
    public enum Site { ITERATION_BUFFER, MB_MODIFICATION, TRANSFORM_CACHE, FAKE_PLAYERS }

    static final class Stats {
        final LongAdder calls = new LongAdder(), offMain = new LongAdder(), concurrent = new LongAdder();
        final Set<Thread> inside = ConcurrentHashMap.newKeySet();
        volatile String firstOffMain, firstConcurrent;
    }

    static final Map<Site, Stats> STATS = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> THREADS = new ConcurrentHashMap<>();

    static {
        for (Site s : Site.values()) STATS.put(s, new Stats());
    }

    private StaticDetect() {}

    public static void enter(Site site) {
        Thread me = Thread.currentThread();
        Stats s = STATS.get(site);
        s.calls.increment();
        THREADS.computeIfAbsent(site + " " + me.getName().replaceAll("[0-9]+", "#"), k -> new LongAdder()).increment();
        if (!MekDetect.isMain(me)) {
            s.offMain.increment();
            if (s.firstOffMain == null) s.firstOffMain = TickDetect.where(site + "-offMain");
        }
        for (Thread t : s.inside) {
            if (t != me) {
                s.concurrent.increment();
                if (s.firstConcurrent == null) s.firstConcurrent = TickDetect.where(site + "-concurrent with " + t.getName());
                break;
            }
        }
        s.inside.add(me);
    }

    public static void exit(Site site) {
        STATS.get(site).inside.remove(Thread.currentThread());
    }

    public static String summary() {
        StringBuilder b = new StringBuilder("ae2static");
        for (Site site : Site.values()) {
            Stats s = STATS.get(site);
            b.append("\n ").append(site).append(" calls=").append(s.calls.sum()).append(" offMain=").append(s.offMain.sum())
                    .append(" concurrent=").append(s.concurrent.sum());
        }
        return b.toString();
    }

    public static String threads() {
        StringBuilder b = new StringBuilder("ae2static threads:");
        new TreeMap<>(THREADS).forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }

    public static String stacks() {
        StringBuilder b = new StringBuilder("ae2static first stacks:");
        for (Site site : Site.values()) {
            Stats s = STATS.get(site);
            if (s.firstOffMain != null) b.append("\n[offMain ").append(site).append("] ").append(s.firstOffMain);
            if (s.firstConcurrent != null) b.append("\n[concurrent ").append(site).append("] ").append(s.firstConcurrent);
        }
        return b.toString();
    }
}
