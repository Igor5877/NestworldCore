package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Observation-only detector for MEKANISM-REGISTRY-RACE-001 (Mekanism 10.4.16 transmitter network registry).
 * Producers: registry writers reachable from BE load/remove, neighbour change, capability invalidation.
 * Consumers: TransmitterNetworkRegistry.onTick (main) and NetworkAcceptorCache.commit.
 * Wrappers call the original exactly once; nothing here changes Mekanism behaviour.
 */
public final class MekDetect {
    public enum Op {
        TRACK, UNTRACK, INVALIDATE, ORPHAN, CHANGED_NETWORK, NETWORK_ADD, NETWORK_REMOVE,
        TICKET_CHANGE, ACCEPTOR_CHANGED, ADOPT, DEREGISTER
    }

    static final class OpStats {
        final LongAdder calls = new LongAdder();
        final LongAdder offMain = new LongAdder();
        final LongAdder regionThread = new LongAdder();
        /** another thread is inside TransmitterNetworkRegistry.onTick right now */
        final LongAdder whileMainConsuming = new LongAdder();
        /** another thread is inside a registry/cache producer at the same time */
        final LongAdder concurrentProducer = new LongAdder();
        volatile String firstOffMain;
        volatile String firstOverlap;
    }

    static final Map<Op, OpStats> STATS = new ConcurrentHashMap<>();
    /** normalized thread name (digits stripped) -> calls */
    static final Map<String, LongAdder> THREADS = new ConcurrentHashMap<>();

    /** threads currently inside TransmitterNetworkRegistry.onTick */
    static final Set<Thread> CONSUMING = ConcurrentHashMap.newKeySet();
    /** threads currently inside any producer */
    static final Map<Thread, AtomicInteger> PRODUCING = new ConcurrentHashMap<>();
    /** cache identity -> threads inside its commit() */
    static final Map<Object, Set<Thread>> COMMITTING = new ConcurrentHashMap<>();

    public static final LongAdder onTickCalls = new LongAdder();
    public static final LongAdder onTickOffMain = new LongAdder();
    public static final LongAdder commitCalls = new LongAdder();
    public static final LongAdder commitOffMain = new LongAdder();
    /** the live-crash shape: acceptorChanged on T while another thread is inside commit() of the SAME cache */
    public static final LongAdder acceptorChangedDuringForeignCommit = new LongAdder();
    public static volatile String firstCrashShape = "-";

    static {
        for (Op op : Op.values()) STATS.put(op, new OpStats());
    }

    private MekDetect() {}

    static boolean isMain(Thread t) {
        return "Server thread".equals(t.getName());
    }

    static String where(String what) {
        StringBuilder b = new StringBuilder(what).append('@').append(Thread.currentThread().getName())
                .append('@').append(new java.util.Date());
        StackTraceElement[] st = new Throwable().getStackTrace();
        int shown = 0;
        for (int i = 0; i < st.length && shown < 14; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("net.nestworld.ae2detect") || c.contains("$Mixin") ) continue;
            b.append("\n    at ").append(st[i]);
            shown++;
        }
        return b.toString();
    }

    /** Called before the original producer runs; pair with producerExit in a finally block. */
    public static void producerEnter(Op op, Object cache) {
        Thread me = Thread.currentThread();
        OpStats s = STATS.get(op);
        s.calls.increment();
        String n = me.getName().replaceAll("[0-9]+", "#");
        THREADS.computeIfAbsent(op + " " + n, k -> new LongAdder()).increment();
        if (!isMain(me)) {
            s.offMain.increment();
            if (me.getName().startsWith("NestWorld-Region")) s.regionThread.increment();
            if (s.firstOffMain == null) s.firstOffMain = where(op.name());
        }
        boolean overlap = false;
        for (Thread t : CONSUMING) if (t != me) { s.whileMainConsuming.increment(); overlap = true; break; }
        for (Thread t : PRODUCING.keySet()) if (t != me) { s.concurrentProducer.increment(); overlap = true; break; }
        if (overlap && s.firstOverlap == null) s.firstOverlap = where(op.name() + "-overlap");
        if (op == Op.ACCEPTOR_CHANGED && cache != null) {
            Set<Thread> c = COMMITTING.get(cache);
            if (c != null) for (Thread t : c) if (t != me) {
                acceptorChangedDuringForeignCommit.increment();
                if ("-".equals(firstCrashShape))
                    firstCrashShape = where("acceptorChanged-during-commit cache@"
                            + Integer.toHexString(System.identityHashCode(cache)) + " committer=" + t.getName());
                break;
            }
        }
        PRODUCING.computeIfAbsent(me, k -> new AtomicInteger()).incrementAndGet();
    }

    public static void producerExit() {
        Thread me = Thread.currentThread();
        AtomicInteger d = PRODUCING.get(me);
        if (d != null && d.decrementAndGet() <= 0) PRODUCING.remove(me);
    }

    public static void tickEnter() {
        onTickCalls.increment();
        if (!isMain(Thread.currentThread())) onTickOffMain.increment();
        CONSUMING.add(Thread.currentThread());
    }

    public static void tickExit() {
        CONSUMING.remove(Thread.currentThread());
    }

    public static void commitEnter(Object cache) {
        commitCalls.increment();
        if (!isMain(Thread.currentThread())) commitOffMain.increment();
        COMMITTING.computeIfAbsent(cache, k -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
    }

    public static void commitExit(Object cache) {
        Set<Thread> s = COMMITTING.get(cache);
        if (s != null) {
            s.remove(Thread.currentThread());
            if (s.isEmpty()) COMMITTING.remove(cache, s);
        }
    }

    /** M-A accounting from Core (present only on a Core carrying the MEKANISM-REGISTRY-RACE-001 fix). */
    static String coreAudit() {
        try {
            return String.valueOf(Class.forName("net.nestworld.compat.NestworldCoremodSupport")
                    .getMethod("mekAuditSummary").invoke(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "coreAudit=n/a";
        }
    }

    public static String summary() {
        StringBuilder b = new StringBuilder(coreAudit()).append("\nmek onTick=").append(onTickCalls.sum()).append(" onTickOffMain=")
                .append(onTickOffMain.sum()).append(" commit=").append(commitCalls.sum()).append(" commitOffMain=")
                .append(commitOffMain.sum()).append(" acceptorChangedDuringForeignCommit=")
                .append(acceptorChangedDuringForeignCommit.sum());
        for (Op op : Op.values()) {
            OpStats s = STATS.get(op);
            if (s.calls.sum() == 0) continue;
            b.append("\n ").append(op).append(" calls=").append(s.calls.sum()).append(" offMain=").append(s.offMain.sum())
                    .append(" region=").append(s.regionThread.sum()).append(" whileMainConsuming=")
                    .append(s.whileMainConsuming.sum()).append(" concurrentProducer=").append(s.concurrentProducer.sum());
        }
        return b.toString();
    }

    public static String threads() {
        StringBuilder b = new StringBuilder("mek producer threads:");
        new TreeMap<>(THREADS).forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }

    public static String stacks() {
        StringBuilder b = new StringBuilder("firstCrashShape=").append(firstCrashShape);
        for (Op op : Op.values()) {
            OpStats s = STATS.get(op);
            if (s.firstOffMain != null) b.append("\n[offMain ").append(op).append("] ").append(s.firstOffMain);
            if (s.firstOverlap != null) b.append("\n[overlap ").append(op).append("] ").append(s.firstOverlap);
        }
        return b.toString();
    }
}
