package net.nestworld.ae2detect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Observation-only detector for AE2-BE-READY-001 (AE2 15.4.10). Never changes AE2 behaviour: every wrapper calls the
 * original exactly once and rethrows whatever it throws.
 *
 * <p>Readiness is measured at the innermost point (the {@code initFunction.accept(be)} call inside
 * {@code FirstTickInfo.callInit}), so it records the thread that REALLY runs {@code onReady}, also when an addon defers
 * that call. {@code offDomain} = readiness on a thread that is not the block-entity execution domain of the BE (Core
 * {@code BlockEntityExecutionDomain}, looked up reflectively; {@code domainUnknown} on a Core without it).
 *
 * <p>Symptoms (each counted where AE2 throws, attributed by readiness activity at that moment):
 * <ul>
 * <li>S1 {@code ClassCastException} out of {@code onReady} (AirBlock → AbstractCraftingUnitBlock);</li>
 * <li>S2 "The node has already been initialized" ({@code ManagedGridNode.getInitData});</li>
 * <li>S3 "Connection between node … already exists" ({@code GridConnection.create}).</li>
 * </ul>
 * Attribution: {@code ownInit} = this thread is inside a readiness call; {@code foreignInit} = another thread is inside
 * one at that moment; {@code noInit} = no readiness running anywhere.
 */
public final class BeReadyDetect {
    private static final Logger LOG = LogManager.getLogger("NestWorld/AE2-BE-READY-DET");
    private static final int LOGGED_PER_SYMPTOM = 20;

    public enum Symptom { S1_CCE, S2_ALREADY_INITIALIZED, S3_DUPLICATE_CONNECTION }

    static final class Attributed {
        final LongAdder total = new LongAdder(), ownInit = new LongAdder(), foreignInit = new LongAdder(),
                noInit = new LongAdder();
        final AtomicInteger logged = new AtomicInteger();
        final Map<String, LongAdder> threads = new ConcurrentHashMap<>();
        volatile String firstStack = "-";
    }

    static final Map<Symptom, Attributed> SYMPTOMS = new ConcurrentHashMap<>();
    static {
        for (Symptom s : Symptom.values()) SYMPTOMS.put(s, new Attributed());
    }

    public static final LongAdder readyCalls = new LongAdder(), readyOnMain = new LongAdder(),
            readyOffMain = new LongAdder(), readyOffDomain = new LongAdder(), readyDomainUnknown = new LongAdder(),
            readyFailedOther = new LongAdder();
    static final Map<String, LongAdder> READY_THREADS = new ConcurrentHashMap<>();
    static volatile String firstOffDomain = "-";

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final Set<BlockEntity> IN_FLIGHT_BES = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final MethodHandle IS_CURRENT_THREAD_IN = lookupDomain();

    private BeReadyDetect() {}

    private static MethodHandle lookupDomain() {
        try {
            Class<?> c = Class.forName("net.nestworld.region.BlockEntityExecutionDomain");
            return MethodHandles.publicLookup().findStatic(c, "isCurrentThreadIn",
                    MethodType.methodType(boolean.class, ServerLevel.class, BlockPos.class, String.class));
        } catch (Throwable t) {
            LOG.info("[AE2-BE-READY-DET] Core has no BlockEntityExecutionDomain ({}) -- offDomain not measurable", t.toString());
            return null;
        }
    }

    /** Around the real {@code onReady} call. */
    public static void beforeReady(Object be) {
        FailureAttribution.enter(FailureAttribution.Kind.READY, hostKey(be));
        DEPTH.get()[0]++;
        IN_FLIGHT.incrementAndGet();
        if (be instanceof BlockEntity b) IN_FLIGHT_BES.add(b);
        readyCalls.increment();
        Thread me = Thread.currentThread();
        boolean main = be instanceof BlockEntity b && b.getLevel() != null && b.getLevel().getServer() != null
                && b.getLevel().getServer().isSameThread();
        (main ? readyOnMain : readyOffMain).increment();
        READY_THREADS.computeIfAbsent(threadClass(me), k -> new LongAdder()).increment();
        Boolean inDomain = inDomain(be);
        if (inDomain == null) {
            readyDomainUnknown.increment();
        } else if (!inDomain) {
            readyOffDomain.increment();
            if ("-".equals(firstOffDomain) && be instanceof BlockEntity b) {
                firstOffDomain = BlockEntityType.getKey(b.getType()) + "@" + b.getBlockPos().toShortString() + " on "
                        + me.getName();
            }
        }
    }

    static String hostKey(Object be) {
        return be instanceof BlockEntity b ? BlockEntityType.getKey(b.getType()) + "@" + b.getBlockPos().toShortString() : "-";
    }

    public static void afterReady(Object be, Throwable failure) {
        if (failure != null) {
            try { FailureAttribution.readyFailed(be, failure); } catch (Throwable ignored) { }
        }
        FailureAttribution.exit(FailureAttribution.Kind.READY, hostKey(be));
        if (be instanceof BlockEntity b) IN_FLIGHT_BES.remove(b);
        IN_FLIGHT.decrementAndGet();
        DEPTH.get()[0]--;
        if (failure instanceof ClassCastException) {
            record(Symptom.S1_CCE, failure, true);
        } else if (failure != null && !isS2(failure) && !isS3(failure)) {
            readyFailedOther.increment();
        }
    }

    // ---- AE2-NODE-LISTENER-OWNER-001 / scope 3: where node listeners actually execute ----
    /** Addon 0.3.0+ marks routed topology-intent delivery (TopologyCallbacks.DELIVERING, by name; absent = n/a). */
    private static final ThreadLocal<boolean[]> ADDON_DELIVERING = lookupDelivering();

    @SuppressWarnings("unchecked")
    private static ThreadLocal<boolean[]> lookupDelivering() {
        try {
            return (ThreadLocal<boolean[]>) Class.forName("net.nestworld.compat.ae2.glue.TopologyCallbacks")
                    .getField("DELIVERING").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Unrouted off-domain listener calls grouped by their AE2 calling site (first appeng frame above GridNode). */
    static final Map<String, LongAdder> UNROUTED_SITES = new ConcurrentHashMap<>();

    private static String callerSite() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (!c.startsWith("appeng.") || c.equals("appeng.me.GridNode") || c.startsWith("appeng.me.GridNode$")) continue;
            return c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber() + "@" + threadClass(Thread.currentThread());
        }
        return "non-appeng@" + threadClass(Thread.currentThread());
    }

    public static String listenerSites() {
        StringBuilder sb = new StringBuilder("unrouted off-domain listener sites:");
        UNROUTED_SITES.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum())).limit(12)
                .forEach(e -> sb.append("\n  ").append(e.getValue().sum()).append("  ").append(e.getKey()));
        return sb.toString();
    }

    public static final LongAdder listenerRouted = new LongAdder(), listenerRoutedOffDomain = new LongAdder(),
            listenerUnroutedOffDomain = new LongAdder();
    public static final LongAdder listenerCalls = new LongAdder(), listenerNoHost = new LongAdder(),
            listenerOffDomain = new LongAdder(), listenerRemovedHost = new LongAdder(), listenerDuringReady = new LongAdder();
    static final Map<String, LongAdder> LISTENER_THREADS = new ConcurrentHashMap<>();
    static volatile String firstListenerOffDomain = "-";

    /** The world host of a node owner: the owner itself if it is a block entity, else an AE2 part's block entity. */
    static BlockEntity hostOf(Object owner) {
        if (owner instanceof BlockEntity be) return be;
        if (owner instanceof appeng.parts.AEBasePart part) return part.getBlockEntity();
        return null;
    }

    /** Listener delivery activity (enter/exit around the real invocation). */
    public static boolean listenerEnter(Object owner) {
        boolean routed = ADDON_DELIVERING != null && ADDON_DELIVERING.get()[0];
        BlockEntity host = hostOf(owner);
        FailureAttribution.enter(routed ? FailureAttribution.Kind.LISTENER_ROUTED : FailureAttribution.Kind.LISTENER_UNROUTED, hostKey(host));
        return routed;
    }

    public static void listenerExit(Object owner, boolean routed) {
        FailureAttribution.exit(routed ? FailureAttribution.Kind.LISTENER_ROUTED : FailureAttribution.Kind.LISTENER_UNROUTED, hostKey(hostOf(owner)));
    }

    public static void listener(Object owner) {
        listenerCalls.increment();
        LISTENER_THREADS.computeIfAbsent(threadClass(Thread.currentThread()), k -> new LongAdder()).increment();
        if (IN_FLIGHT.get() > 0) listenerDuringReady.increment();
        BlockEntity host = hostOf(owner);
        if (host == null) { listenerNoHost.increment(); return; }
        if (host.isRemoved()) listenerRemovedHost.increment();
        boolean routed = ADDON_DELIVERING != null && ADDON_DELIVERING.get()[0];
        if (routed) listenerRouted.increment();
        Boolean in = inDomain(host);
        if (in != null && !in) {
            listenerOffDomain.increment();
            (routed ? listenerRoutedOffDomain : listenerUnroutedOffDomain).increment();
            if (!routed) UNROUTED_SITES.computeIfAbsent(callerSite(), k -> new LongAdder()).increment();
            if ("-".equals(firstListenerOffDomain)) {
                firstListenerOffDomain = BlockEntityType.getKey(host.getType()) + "@" + host.getBlockPos().toShortString()
                        + " on " + Thread.currentThread().getName();
            }
        }
    }

    public static boolean isS2(Throwable t) {
        return t instanceof IllegalStateException && t.getMessage() != null
                && t.getMessage().startsWith("The node has already been initialized");
    }

    public static boolean isS3(Throwable t) {
        return t instanceof IllegalStateException && t.getMessage() != null && t.getMessage().contains("already exists");
    }

    /** S2/S3 at their throw site; S1 from afterReady (always own init by construction). */
    public static void record(Symptom s, Throwable t, boolean forceOwn) {
        Attributed a = SYMPTOMS.get(s);
        a.total.increment();
        boolean own = forceOwn || DEPTH.get()[0] > 0;
        int inFlight = IN_FLIGHT.get() - (own ? 1 : 0);
        String attribution;
        if (own) { a.ownInit.increment(); attribution = "ownInit"; }
        else if (inFlight > 0) { a.foreignInit.increment(); attribution = "foreignInit"; }
        else { a.noInit.increment(); attribution = "noInit"; }
        a.threads.computeIfAbsent(threadClass(Thread.currentThread()), k -> new LongAdder()).increment();
        String stack = stack(t);
        if ("-".equals(a.firstStack)) a.firstStack = stack;
        if (a.logged.incrementAndGet() <= LOGGED_PER_SYMPTOM) {
            LOG.warn("[AE2-BE-READY-DET] {} #{} {} on {} readinessInFlight={} -- {}{}", s, a.total.sum(), attribution,
                    Thread.currentThread().getName(), IN_FLIGHT.get(), t, stack);
        }
    }

    private static Boolean inDomain(Object be) {
        if (IS_CURRENT_THREAD_IN == null || !(be instanceof BlockEntity b) || !(b.getLevel() instanceof ServerLevel sl)) {
            return null;
        }
        try {
            return (boolean) IS_CURRENT_THREAD_IN.invokeExact(sl, b.getBlockPos(),
                    String.valueOf(BlockEntityType.getKey(b.getType())));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String threadClass(Thread t) {
        return t.getName().replaceAll("\\d+", "#");
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(14, st.length); i++) sb.append("\n    at ").append(st[i]);
        return sb.toString();
    }

    /** The canonical BE execution domain of {@code pos} for block-entity type {@code type} (reflective; Core 401+). */
    public static String domainAt(ServerLevel level, BlockPos pos, String type) {
        try {
            Class<?> c = Class.forName("net.nestworld.region.BlockEntityExecutionDomain");
            Object d = c.getMethod("resolveBlockEntityExecutionDomain", ServerLevel.class, BlockPos.class, String.class)
                    .invoke(null, level, pos, type);
            return pos.toShortString() + " " + type + " -> " + d;
        } catch (Throwable t) {
            return pos.toShortString() + " -> unknown (" + t + ")";
        }
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder("ready calls=").append(readyCalls.sum()).append(" onMain=")
                .append(readyOnMain.sum()).append(" offMain=").append(readyOffMain.sum()).append(" offDomain=")
                .append(readyOffDomain.sum()).append(" domainUnknown=").append(readyDomainUnknown.sum())
                .append(" failedOther=").append(readyFailedOther.sum()).append(" threads=").append(sums(READY_THREADS))
                .append(" firstOffDomain=").append(firstOffDomain);
        sb.append("\nlisteners calls=").append(listenerCalls.sum()).append(" offDomain=").append(listenerOffDomain.sum())
                .append(" noHost=").append(listenerNoHost.sum()).append(" removedHost=").append(listenerRemovedHost.sum())
                .append(" duringReady=").append(listenerDuringReady.sum()).append(" routed=").append(listenerRouted.sum())
                .append(" routedOffDomain=").append(listenerRoutedOffDomain.sum()).append(" unroutedOffDomain=")
                .append(listenerUnroutedOffDomain.sum()).append(ADDON_DELIVERING == null ? " (addon<0.3: routed n/a)" : "").append(" threads=").append(sums(LISTENER_THREADS))
                .append(" firstOffDomain=").append(firstListenerOffDomain);
        for (Symptom s : Symptom.values()) {
            Attributed a = SYMPTOMS.get(s);
            sb.append("\n").append(s).append(" total=").append(a.total.sum()).append(" ownInit=").append(a.ownInit.sum())
                    .append(" foreignInit=").append(a.foreignInit.sum()).append(" noInit=").append(a.noInit.sum())
                    .append(" threads=").append(sums(a.threads));
        }
        return sb.toString();
    }

    public static String stacks() {
        StringBuilder sb = new StringBuilder();
        for (Symptom s : Symptom.values()) sb.append(s).append(":").append(SYMPTOMS.get(s).firstStack).append("\n");
        return sb.toString();
    }

    private static String sums(Map<String, LongAdder> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(k).append('=').append(v.sum()).append(' '));
        return sb.append('}').toString();
    }
}
