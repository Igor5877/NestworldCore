package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Observation-only detector for AE2-TICKMANAGER-RACE-001 (AE2 15.4.10 TickManagerService, one per grid).
 * Consumer: tickQueue (main, from onLevelEndTick / onServerEndTick). Producers: addToQueue, removeFromQueue,
 * updateQueuePosition and the public entry points wakeDevice / alertDevice / sleepDevice / addNode / removeNode.
 * Wrappers call the original exactly once; nothing here changes AE2 behaviour.
 */
public final class TickDetect {
    public enum Op { ADD_TO_QUEUE, REMOVE_FROM_QUEUE, UPDATE_POSITION, WAKE, ALERT, SLEEP, ADD_NODE, REMOVE_NODE }

    static final class OpStats {
        final LongAdder calls = new LongAdder(), offMain = new LongAdder(), region = new LongAdder();
        /** another thread is inside tickQueue of the SAME service right now (the A234 crash shape) */
        final LongAdder duringForeignTickQueue = new LongAdder();
        /** another thread is inside a producer of the SAME service right now */
        final LongAdder concurrentForeignProducer = new LongAdder();
        /** another thread is inside tickQueue of the SAME service AND not inside the device callback, i.e. in
         *  PriorityQueue operations (peek/poll/add/remove). This is the unsafe overlap. */
        final LongAdder duringForeignQueueOps = new LongAdder();
        volatile String firstOffMain, firstOverlap, firstQueueOps;
    }

    static final Map<Op, OpStats> STATS = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> THREADS = new ConcurrentHashMap<>();
    /** service -> threads inside tickQueue */
    static final Map<Object, Set<Thread>> TICKING = new ConcurrentHashMap<>();
    /** service -> threads inside a producer */
    static final Map<Object, Set<Thread>> PRODUCING = new ConcurrentHashMap<>();
    /** service -> threads inside tickQueue that are currently running the device callback (unsafeTickingRequest) */
    static final Map<Object, Set<Thread>> IN_CALLBACK = new ConcurrentHashMap<>();
    public static final LongAdder tickQueueCalls = new LongAdder(), tickQueueOffMain = new LongAdder();
    public static final LongAdder tickQueueConcurrent = new LongAdder();

    static {
        for (Op op : Op.values()) STATS.put(op, new OpStats());
    }

    private TickDetect() {}

    static String where(String what) {
        StringBuilder b = new StringBuilder(what).append('@').append(Thread.currentThread().getName()).append('@').append(new java.util.Date());
        StackTraceElement[] st = new Throwable().getStackTrace();
        int shown = 0;
        for (int i = 0; i < st.length && shown < 18; i++) {
            if (st[i].getClassName().startsWith("net.nestworld.ae2detect")) continue;
            b.append("\n    at ").append(st[i]);
            shown++;
        }
        return b.toString();
    }

    static boolean foreignIn(Map<Object, Set<Thread>> m, Object svc, Thread me) {
        Set<Thread> s = m.get(svc);
        if (s == null) return false;
        for (Thread t : s) if (t != me) return true;
        return false;
    }

    public static void producerEnter(Op op, Object svc) {
        Thread me = Thread.currentThread();
        OpStats s = STATS.get(op);
        s.calls.increment();
        THREADS.computeIfAbsent(op + " " + me.getName().replaceAll("[0-9]+", "#"), k -> new LongAdder()).increment();
        if (!MekDetect.isMain(me)) {
            s.offMain.increment();
            if (me.getName().startsWith("NestWorld-Region")) s.region.increment();
            if (s.firstOffMain == null) s.firstOffMain = where(op.name());
        }
        boolean overlap = false;
        if (foreignIn(TICKING, svc, me)) { s.duringForeignTickQueue.increment(); overlap = true; }
        Set<Thread> ticking = TICKING.get(svc), cb = IN_CALLBACK.get(svc);
        if (ticking != null) for (Thread t : ticking) if (t != me && (cb == null || !cb.contains(t))) {
            s.duringForeignQueueOps.increment();
            if (s.firstQueueOps == null) s.firstQueueOps = where(op.name() + "-during-queue-ops of " + t.getName());
            break;
        }
        if (foreignIn(PRODUCING, svc, me)) { s.concurrentForeignProducer.increment(); overlap = true; }
        if (overlap && s.firstOverlap == null) s.firstOverlap = where(op.name() + "-overlap");
        PRODUCING.computeIfAbsent(svc, k -> ConcurrentHashMap.newKeySet()).add(me);
    }

    public static void producerExit(Object svc) {
        Set<Thread> s = PRODUCING.get(svc);
        if (s != null) s.remove(Thread.currentThread());
    }

    /** every TickManagerService seen ticking (weak: grids come and go) */
    static final Set<Object> SERVICES = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void tickEnter(Object svc) {
        synchronized (SERVICES) { SERVICES.add(svc); }
        Thread me = Thread.currentThread();
        tickQueueCalls.increment();
        if (!MekDetect.isMain(me)) tickQueueOffMain.increment();
        if (foreignIn(TICKING, svc, me)) tickQueueConcurrent.increment();
        TICKING.computeIfAbsent(svc, k -> ConcurrentHashMap.newKeySet()).add(me);
    }

    public static void callbackEnter(Object svc) {
        IN_CALLBACK.computeIfAbsent(svc, k -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
    }

    public static void callbackExit(Object svc) {
        Set<Thread> s = IN_CALLBACK.get(svc);
        if (s != null) s.remove(Thread.currentThread());
    }

    public static void tickExit(Object svc) {
        Set<Thread> s = TICKING.get(svc);
        if (s != null) s.remove(Thread.currentThread());
    }

    /**
     * Quiescent structural invariant (run on the server thread with the workload paused): for every service,
     * every awake tracker is in its level queue exactly once, queues hold no null and no tracker that is not awake.
     *   nulls       -> corrupted PriorityQueue
     *   duplicates  -> the same device would tick twice
     *   lostAwake   -> awake device not scheduled at all (lost wake: it never ticks again)
     *   staleQueued -> queued tracker that is no longer awake
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static String verify() {
        long services = 0, awakeTotal = 0, queued = 0, nulls = 0, dups = 0, lost = 0, stale = 0, errors = 0;
        String firstError = "-";
        java.util.List<Object> svcs;
        synchronized (SERVICES) { svcs = new java.util.ArrayList<>(SERVICES); }
        for (Object svc : svcs) {
            try {
                Class<?> c = svc.getClass();
                java.lang.reflect.Field fa = c.getDeclaredField("awake"), fq = c.getDeclaredField("upcomingTicks");
                fa.setAccessible(true); fq.setAccessible(true);
                Map awake = (Map) fa.get(svc);
                Map<Object, java.util.PriorityQueue> queues = (Map) fq.get(svc);
                services++;
                java.util.Set<Object> awakeSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                awakeSet.addAll(awake.values());
                awakeTotal += awakeSet.size();
                java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (java.util.PriorityQueue q : queues.values()) {
                    for (Object e : q.toArray()) {
                        queued++;
                        if (e == null) { nulls++; continue; }
                        if (!seen.add(e)) dups++;
                        if (!awakeSet.contains(e)) stale++;
                    }
                }
                for (Object a : awakeSet) if (!seen.contains(a)) lost++;
            } catch (Throwable t) {
                errors++;
                if ("-".equals(firstError)) firstError = String.valueOf(t);
            }
        }
        return "ae2tick verify services=" + services + " awake=" + awakeTotal + " queued=" + queued + " nulls=" + nulls
                + " duplicates=" + dups + " lostAwake=" + lost + " staleQueued=" + stale + " errors=" + errors + " firstError=" + firstError;
    }

    public static String summary() {
        StringBuilder b = new StringBuilder("ae2tick tickQueue=").append(tickQueueCalls.sum()).append(" tickQueueOffMain=")
                .append(tickQueueOffMain.sum()).append(" tickQueueConcurrent=").append(tickQueueConcurrent.sum());
        for (Op op : Op.values()) {
            OpStats s = STATS.get(op);
            if (s.calls.sum() == 0) continue;
            b.append("\n ").append(op).append(" calls=").append(s.calls.sum()).append(" offMain=").append(s.offMain.sum())
                    .append(" region=").append(s.region.sum()).append(" duringForeignTickQueue=").append(s.duringForeignTickQueue.sum())
                    .append(" concurrentForeignProducer=").append(s.concurrentForeignProducer.sum())
                    .append(" duringForeignQueueOps=").append(s.duringForeignQueueOps.sum());
        }
        return b.toString();
    }

    public static String threads() {
        StringBuilder b = new StringBuilder("ae2tick producer threads:");
        new TreeMap<>(THREADS).forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }

    public static String stacks() {
        StringBuilder b = new StringBuilder("ae2tick first stacks:");
        for (Op op : Op.values()) {
            OpStats s = STATS.get(op);
            if (s.firstOffMain != null) b.append("\n[offMain ").append(op).append("] ").append(s.firstOffMain);
            if (s.firstOverlap != null) b.append("\n[overlap ").append(op).append("] ").append(s.firstOverlap);
            if (s.firstQueueOps != null) b.append("\n[queueOps ").append(op).append("] ").append(s.firstQueueOps);
        }
        return b.toString();
    }
}
