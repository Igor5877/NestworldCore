package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * AE2-DEMAIN-001 D8 pre-B1 counters on AE2's JVM-global {@code MBCalculator.modificationInProgress}, observation only.
 * Counted at AE2's own code (calculateMultiblock / setModificationInProgress / cluster destroy), independent of the
 * addon's B1 lane, so they verify the lane from outside:
 * <ul>
 * <li>concurrentEntry — a thread enters calculateMultiblock / destroy while ANOTHER thread is inside one;</li>
 * <li>ise[site] — setModificationInProgress threw "A modification is already in-progress" (site = calling frame);</li>
 * <li>silentSkipForeign — calculateMultiblock entered while the flag is held by ANOTHER thread: MBCalculator:85 returns
 * without recalculating (lost recalc; K3a); silentSkipOwnChain = the same, flag held by this thread (AE2's intended
 * re-entrancy suppression, not a failure);</li>
 * <li>foreignFinallyReset — set(null) by a thread that did not set the flag while it was set (K3b / F3);</li>
 * <li>requested / completed / failed — calculateMultiblock entries / normal returns past the :85 guard / exceptions;
 * requested = completed + failed + straddleAbort + silentSkipForeign + silentSkipOwnChain.</li>
 * </ul>
 * The flag owner is tracked here (volatile), never inferred from AE2. Wrappers call the original exactly once.
 */
public final class MbDetect {
    static volatile Thread flagOwner;
    static final Set<Thread> INSIDE = ConcurrentHashMap.newKeySet();

    public static final LongAdder requested = new LongAdder(), completed = new LongAdder(), failed = new LongAdder(),
            silentSkipForeign = new LongAdder(), silentSkipOwnChain = new LongAdder(), concurrentEntry = new LongAdder(),
            foreignFinallyReset = new LongAdder(), iseTotal = new LongAdder(), sets = new LongAdder(), clears = new LongAdder(),
            destroys = new LongAdder(), calcOffMain = new LongAdder(), straddleAbort = new LongAdder();
    public static final AtomicLong calcNanos = new AtomicLong(), calcMaxNanos = new AtomicLong();
    static final Map<String, LongAdder> ISE_SITES = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> CALC_BY_CALCULATOR = new ConcurrentHashMap<>();
    static volatile String firstIse = "-", firstSilentSkip = "-", firstForeignReset = "-", firstConcurrent = "-";
    private static final StackWalker WALKER = StackWalker.getInstance();

    private MbDetect() {}

    private static void enterSection(Thread me) {
        for (Thread t : INSIDE) {
            if (t != me) {
                concurrentEntry.increment();
                if ("-".equals(firstConcurrent)) firstConcurrent = TickDetect.where("MB concurrent with " + t.getName());
                break;
            }
        }
        INSIDE.add(me);
    }

    /** calculateMultiblock entry. {@code flagSet} = MBCalculator.isModificationInProgress() at entry. */
    public static boolean calcEnter(Object calculator, boolean flagSet) {
        Thread me = Thread.currentThread();
        requested.increment();
        if (!MekDetect.isMain(me)) calcOffMain.increment();
        CALC_BY_CALCULATOR.computeIfAbsent(calculator.getClass().getName(), k -> new LongAdder()).increment();
        if (flagSet) {
            if (flagOwner != me) {
                silentSkipForeign.increment();
                if ("-".equals(firstSilentSkip)) firstSilentSkip = TickDetect.where("MB silent skip (flag held by " + flagOwner + ")");
            } else {
                silentSkipOwnChain.increment();
            }
        }
        boolean outer = !INSIDE.contains(me);
        if (outer) enterSection(me);
        return outer;
    }

    public static void calcExit(boolean outer, boolean skipped, long t0, Throwable err) {
        if (outer) INSIDE.remove(Thread.currentThread());
        long d = System.nanoTime() - t0;
        calcNanos.addAndGet(d);
        long m;
        while (d > (m = calcMaxNanos.get()) && !calcMaxNanos.compareAndSet(m, d)) { }
        if (err == null) { if (!skipped) completed.increment(); }
        else if (err.getClass().getName().endsWith("MbStraddle")) straddleAbort.increment();
        else failed.increment();
    }

    public static boolean destroyEnter() {
        Thread me = Thread.currentThread();
        destroys.increment();
        boolean outer = !INSIDE.contains(me);
        if (outer) enterSection(me);
        return outer;
    }

    public static void destroyExit(boolean outer) {
        if (outer) INSIDE.remove(Thread.currentThread());
    }

    /** Wraps MBCalculator.setModificationInProgress(cluster). */
    public static void set(Object cluster, boolean flagSetBefore, Runnable op) {
        Thread me = Thread.currentThread();
        if (cluster == null) {
            clears.increment();
            Thread owner = flagOwner;
            if (flagSetBefore && owner != null && owner != me) {
                foreignFinallyReset.increment();
                if ("-".equals(firstForeignReset)) firstForeignReset = TickDetect.where("MB foreign reset of flag set by " + owner.getName());
            }
            op.run();
            flagOwner = null;
            return;
        }
        sets.increment();
        try {
            op.run();
            flagOwner = me;
        } catch (IllegalStateException e) {
            iseTotal.increment();
            String site = WALKER.walk(st -> st.filter(f -> !f.getClassName().startsWith("net.nestworld.")
                            && !f.getMethodName().equals("setModificationInProgress") && !f.getMethodName().contains("wrapMethod$")
                            && !f.getMethodName().contains("nwd$") && !f.getMethodName().contains("nw$")
                            && !f.getClassName().contains("mixinextras"))
                    .findFirst().map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?"));
            ISE_SITES.computeIfAbsent(site, k -> new LongAdder()).increment();
            if ("-".equals(firstIse)) firstIse = TickDetect.where("MB ISE " + e.getMessage());
            throw e;
        }
    }

    public static String summary() {
        StringBuilder b = new StringBuilder("mbdetect requested=").append(requested.sum()).append(" completed=").append(completed.sum())
                .append(" failed=").append(failed.sum()).append(" straddleAbort=").append(straddleAbort.sum())
                .append(" silentSkipForeign=").append(silentSkipForeign.sum()).append(" silentSkipOwnChain=").append(silentSkipOwnChain.sum())
                .append(" concurrentEntry=").append(concurrentEntry.sum()).append(" ise=").append(iseTotal.sum())
                .append(" foreignFinallyReset=").append(foreignFinallyReset.sum()).append(" sets=").append(sets.sum())
                .append(" clears=").append(clears.sum()).append(" destroys=").append(destroys.sum())
                .append(" calcOffMain=").append(calcOffMain.sum())
                .append(String.format(" calcMs=%.1f calcMaxUs=%.0f", calcNanos.get() / 1e6, calcMaxNanos.get() / 1e3));
        b.append("\n ise sites:");
        new TreeMap<>(ISE_SITES).forEach((k, v) -> b.append("\n  ").append(k).append(" = ").append(v.sum()));
        b.append("\n calculators:");
        new TreeMap<>(CALC_BY_CALCULATOR).forEach((k, v) -> b.append("\n  ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }

    public static String stacks() {
        return "mbdetect first stacks:\n[ise] " + firstIse + "\n[silentSkip] " + firstSilentSkip + "\n[foreignReset] "
                + firstForeignReset + "\n[concurrent] " + firstConcurrent;
    }
}
