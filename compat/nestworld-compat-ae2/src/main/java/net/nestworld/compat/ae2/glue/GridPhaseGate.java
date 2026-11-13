package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.me.Grid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * GRID-PHASE-001 (owner-approved): for one grid, an active Grid.on*Tick phase XOR a structural
 * topology mutation of that grid. Order: P (grid serial) → T0 → domains.
 *
 * <ul>
 *   <li>Main phase: blocks on P(G) (fair lock: a queued writer is served before main's next phase —
 *       the progress invariant).</li>
 *   <li>Non-main topology (outermost): all-or-nothing tryLock of the affected set; on failure release
 *       everything, bounded backoff, rediscover; after repeated failures block on ONE contended P while
 *       holding none (deadlock-free), then retry the rest.</li>
 *   <li>Main topology outside a kernel section may block (main holds at most P locks).</li>
 *   <li>Nested topology (already inside a kernel section, i.e. after T0): never block; grids created
 *       inside the operation are uncontended. An unavailable P there is an unaudited path.</li>
 * </ul>
 */
public final class GridPhaseGate {
    private static final int ESCALATE_AFTER = 16, STARVATION_RETRIES = 256;
    private static final long STARVATION_NANOS = TimeUnit.SECONDS.toNanos(1);

    static long serial(Grid g) {
        return ((NwEnergyService) g.getEnergyService()).nw$lock().serial();
    }

    // ---------------------------------------------------------------- MVP-001 bounded yield points (owner 2026-11-13)

    /** P(G) granularity (audit 2026-11-13, option A): the phase owner hands the FAIR P(G) to queued accessors at safe points
     *  (after a device tick outside T1; between BusTransit records) once it held it ≥ this long. 0 = phase-wide hold. */
    static final long YIELD_NS = Long.getLong("nestworld.compatAe2.pYieldUs", 0L) * 1000L;
    private static final java.util.Map<Grid, Thread> PHASE_OWNER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<long[]> LAST_ACQUIRE = ThreadLocal.withInitial(() -> new long[1]);
    static final java.util.concurrent.atomic.LongAdder pYields = new java.util.concurrent.atomic.LongAdder(),
            pYieldHandoffNs = new java.util.concurrent.atomic.LongAdder(), pYieldSkipped = new java.util.concurrent.atomic.LongAdder(),
            pTopologyPhaseActive = new java.util.concurrent.atomic.LongAdder();

    /** Is a phase of {@code g} active on ANOTHER thread (a yield gap)? Topology must not run there. */
    static boolean phaseActiveElsewhere(Grid g) {
        Thread o = PHASE_OWNER.get(g);
        return o != null && o != Thread.currentThread();
    }

    /** Phase owner, at a safe point: if accessors queue for P(G) and it was held ≥ YIELD_NS, let them through. */
    public static void yieldPoint(Grid g, String site) {
        if (YIELD_NS <= 0 || g == null) return;
        ReentrantLock p = ((NwGrid) g).nw$p();
        if (!p.isHeldByCurrentThread() || !p.hasQueuedThreads()) return;
        long now = System.nanoTime();
        long[] last = LAST_ACQUIRE.get();
        if (now - last[0] < YIELD_NS) return;
        if (p.getHoldCount() != 1 || PLocks.depth() != 1 || KERNEL.inSection() || PHASE_OWNER.get(g) != Thread.currentThread()) {
            pYieldSkipped.increment();
            return;
        }
        PLocks.released();
        p.unlock();
        p.lock();                      // fair: every thread queued before us goes first; worker holds nothing else here
        PLocks.acquired();
        long t = System.nanoTime();
        pYieldHandoffNs.add(t - now);
        last[0] = t;
        pYields.increment();
    }

    public static String yieldStatus() {
        return "pYield{us=" + YIELD_NS / 1000 + " yields=" + pYields.sum() + " handoffMs=" + pYieldHandoffNs.sum() / 1_000_000
                + " skipped=" + pYieldSkipped.sum() + " topologyPhaseActive=" + pTopologyPhaseActive.sum() + "}";
    }

    public static boolean holds(Grid g) {
        return g != null && ((NwGrid) g).nw$p().isHeldByCurrentThread();
    }

    public static boolean phaseActive(Grid g) {
        return g != null && ((NwGrid) g).nw$phaseDepth() > 0;
    }

    // ---------------------------------------------------------------- main phase

    /** Main: one Grid.on*Tick call under P(G). {@code drain} runs staged work first (server END only). */
    public static <T> T phase(Grid g, boolean drain, Supplier<T> body) {
        NwGrid n = (NwGrid) g;
        ReentrantLock p = n.nw$p();
        long t0 = System.nanoTime();
        MainService.lockOnMain(g, p);
        PLocks.acquired();
        COUNTERS.pWaitNs.add(System.nanoTime() - t0);
        try {
            if (drain) {
                Staging.drain(g);
                Staging.point("drain-done", g);
            }
            boolean outer = n.nw$phaseDepth() == 0;
            n.nw$phaseDepth(n.nw$phaseDepth() + 1);
            if (outer) { PHASE_OWNER.put(g, Thread.currentThread()); LAST_ACQUIRE.get()[0] = System.nanoTime(); }
            try {
                if (drain) Staging.point("phase-active", g);
                return body.get();
            } finally {
                n.nw$phaseDepth(n.nw$phaseDepth() - 1);
                if (outer) PHASE_OWNER.remove(g);
            }
        } finally {
            PLocks.released();
            p.unlock();
        }
    }

    // ---------------------------------------------------------------- topology

    public static <T> T topology(Supplier<List<Grid>> affected, Supplier<T> body) {
        List<Grid> held = KERNEL.inSection() ? acquireNested(affected.get()) : acquireOuter(affected);
        try {
            return body.get();
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) release(held.get(i));
        }
    }

    private static List<Grid> sortedDistinct(List<Grid> in) {
        ArrayList<Grid> out = new ArrayList<>();
        for (Grid g : in) if (g != null && !out.contains(g)) out.add(g);
        out.sort(Comparator.comparingLong(GridPhaseGate::serial));
        return out;
    }

    private static boolean sameSet(List<Grid> a, List<Grid> b) {
        return a.size() == b.size() && a.containsAll(b);
    }

    private static void release(Grid g) {
        PLocks.released();
        ((NwGrid) g).nw$p().unlock();
    }

    private static List<Grid> acquireOuter(Supplier<List<Grid>> affected) {
        boolean main = Ae2Compat.onMainThread();
        if (main) GridWorker.awaitAll();      // AE2-GRID-DOMAIN-001: MAIN topology never overtakes a queued grid phase
        long start = System.nanoTime();
        boolean warned = false;
        for (int attempt = 0; ; attempt++) {
            List<Grid> want = sortedDistinct(affected.get());
            List<Grid> got = new ArrayList<>(want.size());
            Grid contended = null;
            for (Grid g : want) {
                ReentrantLock p = ((NwGrid) g).nw$p();
                boolean ok;
                if (main && !PLocks.holdsAny()) {
                    MainService.lockOnMain(g, p);              // main may wait only while holding NO P (services MVP calls)
                    ok = true;
                } else {
                    ok = p.tryLock();                          // MVP-001: already holding a P ⇒ never block for another
                }
                if (ok && phaseActiveElsewhere(g)) {          // MVP-001: acquired in a yield gap — never mid-phase
                    p.unlock();
                    pTopologyPhaseActive.increment();
                    ok = false;
                }
                if (!ok) {
                    contended = g;
                    break;
                }
                PLocks.acquired();
                got.add(g);
            }
            if (contended == null) {
                if (sameSet(want, sortedDistinct(affected.get()))) {
                    if (attempt > 0) {
                        COUNTERS.pRetryCount.add(attempt);
                        COUNTERS.pMaxRetry.accumulate(attempt);
                    }
                    COUNTERS.pWaitNs.add(System.nanoTime() - start);
                    return got;
                }
                // affected set changed while acquiring: release and rediscover
            } else {
                COUNTERS.pTryFailures.increment();
            }
            for (int i = got.size() - 1; i >= 0; i--) release(got.get(i));
            if (!warned && (attempt >= STARVATION_RETRIES || System.nanoTime() - start > STARVATION_NANOS)) {
                warned = true;
                COUNTERS.pWriterStarvationWarning.increment();
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] P(G) writer starvation warning: {} retries, {} ms on {}",
                        attempt, (System.nanoTime() - start) / 1_000_000, Thread.currentThread().getName());
            }
            if (contended != null && attempt >= ESCALATE_AFTER) {
                // holding nothing: wait for the contended P in the fair queue, then retry the whole set
                ReentrantLock p = ((NwGrid) contended).nw$p();
                if (!PLocks.holdsAny()) {                     // holding nothing: wait (MAIN services calls), else back off
                    MainService.lockOnMain(contended, p);
                    p.unlock();
                } else {
                    LockSupport.parkNanos(200_000L);
                }
            } else {
                LockSupport.parkNanos(Math.min(2_000_000L, 20_000L << Math.min(attempt, 7)));
            }
        }
    }

    /**
     * Inside a kernel section (after T0) P is never waited for. If a required P cannot be taken the
     * topology body MUST NOT run: release what this call took, count, and fail closed (unaudited →
     * the kernel leaves NORMAL toward DEGRADED). Review item GRID-PHASE-001 1.1.
     */
    private static List<Grid> acquireNested(List<Grid> affected) {
        List<Grid> got = new ArrayList<>();
        for (Grid g : sortedDistinct(affected)) {
            ReentrantLock p = ((NwGrid) g).nw$p();
            if (p.isHeldByCurrentThread()) {
                p.lock();                                        // reentrant level, never blocks
                PLocks.acquired();
                got.add(g);
            } else if (p.tryLock()) {
                if (phaseActiveElsewhere(g)) {                  // MVP-001: yield gap of another thread's phase
                    p.unlock();
                    pTopologyPhaseActive.increment();
                    for (int i = got.size() - 1; i >= 0; i--) release(got.get(i));
                    COUNTERS.pNestedUnresolved.increment();
                    throw KERNEL.unaudited("P(G) taken in a phase yield gap inside a topology section");
                }
                PLocks.acquired();
                got.add(g);
            } else {
                for (int i = got.size() - 1; i >= 0; i--) release(got.get(i));
                COUNTERS.pNestedUnresolved.increment();
                throw KERNEL.unaudited("P(G) unavailable for a grid first touched inside a topology section (after T0)");
            }
        }
        return got;
    }

    private GridPhaseGate() {
    }
}
