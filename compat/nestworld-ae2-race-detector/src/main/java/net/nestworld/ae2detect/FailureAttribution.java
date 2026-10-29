package net.nestworld.ae2detect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Observation-only causal attribution of Core tick failures (owner decisions 2026-10-29 (11)): for every failure
 * reaching {@code MinecraftServer.nestworldOnTickFailure} one compact record with the failing exception, the
 * readiness host snapshot taken at the throw (when the failure came out of onReady), and the activity of OTHER
 * threads at that moment: readiness, MBCalculator modification, topology commit (GridConnection create/destroy) and
 * node-listener delivery (routed through the addon's intent path or not). "During X" = another thread inside X at the
 * throw or X ended within {@link #WINDOW_NANOS}.
 */
public final class FailureAttribution {
    private static final Logger LOG = LogManager.getLogger("NestWorld/AE2-FAIL-ATTR");
    static final long WINDOW_NANOS = 50_000_000L;

    public enum Kind { READY, MB, TOPO, LISTENER_ROUTED, LISTENER_UNROUTED }

    static final class Activity {
        final Map<Thread, String> active = new ConcurrentHashMap<>();
        volatile long lastEndNanos;
        volatile String lastHost = "-";
    }

    static final Map<Kind, Activity> ACT = new ConcurrentHashMap<>();
    static {
        for (Kind k : Kind.values()) ACT.put(k, new Activity());
    }

    public static final LongAdder failures = new LongAdder();
    static final Map<String, LongAdder> BY_CLASS = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> DURING = new ConcurrentHashMap<>();
    private static final AtomicInteger logged = new AtomicInteger();

    /** Readiness failure snapshot taken while the exception unwinds out of onReady (main). */
    private static volatile String lastReadyFailure = "-";
    private static volatile long lastReadyFailureNanos;

    private FailureAttribution() {}

    public static void enter(Kind k, String host) {
        ACT.get(k).active.put(Thread.currentThread(), host == null ? "-" : host);
    }

    public static void exit(Kind k, String host) {
        Activity a = ACT.get(k);
        a.active.remove(Thread.currentThread());
        a.lastEndNanos = System.nanoTime();
        if (host != null) a.lastHost = host + "@" + Thread.currentThread().getName();
    }

    public static String describe(Object be) {
        if (!(be instanceof BlockEntity b)) return be == null ? "-" : be.getClass().getSimpleName();
        String type = String.valueOf(BlockEntityType.getKey(b.getType()));
        String domain = b.getLevel() instanceof ServerLevel sl ? BeReadyDetect.domainAt(sl, b.getBlockPos(), type) : "?";
        return type + "@" + b.getBlockPos().toShortString() + " domain[" + domain + "] pinned=" + pinned(type);
    }

    static String pinned(String type) {
        try {
            Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
            Object pins = sysC.getMethod("getPins").invoke(sysC.getMethod("get").invoke(null));
            return String.valueOf(pins.getClass().getMethod("isBePinned", String.class).invoke(pins, type));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Called from BeReadyDetect.afterReady when onReady threw (still on the throwing thread). */
    public static void readyFailed(Object be, Throwable t) {
        lastReadyFailure = describe(be) + " exc=" + t.getClass().getSimpleName() + " others{" + others() + "}";
        lastReadyFailureNanos = System.nanoTime();
    }

    /** Activity of threads other than the current one, plus recency of each kind. */
    static String others() {
        Thread me = Thread.currentThread();
        long now = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) {
            Activity a = ACT.get(k);
            StringBuilder act = new StringBuilder();
            a.active.forEach((th, host) -> {
                if (th != me) act.append(th.getName().replaceAll("\\d+", "#")).append(':').append(host).append(' ');
            });
            long age = a.lastEndNanos == 0 ? -1 : (now - a.lastEndNanos) / 1_000_000;
            sb.append(k).append("[active=").append(act.toString().trim()).append(" lastEndMs=").append(age)
                    .append(" lastHost=").append(a.lastHost).append("] ");
        }
        return sb.toString().trim();
    }

    private static boolean during(Kind k, Thread me, long now) {
        Activity a = ACT.get(k);
        for (Thread th : a.active.keySet()) if (th != me) return true;
        return a.lastEndNanos != 0 && now - a.lastEndNanos <= WINDOW_NANOS;
    }

    /** Core tick-failure hook (main). */
    public static void tickFailed(Throwable t) {
        failures.increment();
        Thread me = Thread.currentThread();
        long now = System.nanoTime();
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String title = t instanceof net.minecraft.ReportedException re ? re.getReport().getTitle() : "-";
        BY_CLASS.computeIfAbsent(root.getClass().getSimpleName() + " / " + title, k -> new LongAdder()).increment();
        boolean fromReady = now - lastReadyFailureNanos <= 1_000_000_000L && "Readying AE2 block entity".equals(title);
        StringBuilder dur = new StringBuilder();
        boolean any = false;
        if (fromReady) { DURING.computeIfAbsent("failureDuringReady", k -> new LongAdder()).increment(); dur.append("READY(own) "); }
        String[] names = {"failureDuringReadyOther", "failureDuringMB", "failureDuringTopologyCommit",
                "failureDuringListenerRouted", "failureDuringListenerUnrouted"};
        Kind[] kinds = {Kind.READY, Kind.MB, Kind.TOPO, Kind.LISTENER_ROUTED, Kind.LISTENER_UNROUTED};
        for (int i = 0; i < kinds.length; i++) {
            if (during(kinds[i], me, now)) {
                any = true;
                DURING.computeIfAbsent(names[i], k -> new LongAdder()).increment();
                dur.append(kinds[i]).append(' ');
            }
        }
        if (!any) DURING.computeIfAbsent("failureWithNone", k -> new LongAdder()).increment();
        StringBuilder frames = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : root.getStackTrace()) {
            if (!e.getClassName().startsWith("appeng.") || e.getMethodName().contains("mixinextras")) continue;
            frames.append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber()).append(' ');
            if (++n == 3) break;
        }
        if (logged.incrementAndGet() <= 100) {
            LOG.warn("[AE2-FAIL-ATTR] #{} thread={} exc={} title={} frames={} during={} readyHost={} others{{}}",
                    failures.sum(), me.getName(), root.getClass().getSimpleName(), title, frames.toString().trim(),
                    any || fromReady ? dur.toString().trim() : "NONE", fromReady ? lastReadyFailure : "-", others());
        }
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("tick failures=").append(failures.sum());
        BY_CLASS.forEach((k, v) -> sb.append("\n  ").append(v.sum()).append("  ").append(k));
        sb.append("\n during:");
        DURING.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v.sum()));
        return sb.toString();
    }
}
