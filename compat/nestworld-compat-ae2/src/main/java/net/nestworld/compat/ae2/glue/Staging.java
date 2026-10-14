package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.me.Grid;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase-deferred service mutations (GRID-PHASE, owner-approved). Normative: mutations published while a
 * service phase is active never alter that phase's structural view; they become visible at the next
 * permitted phase boundary. Published before the drain cut (main, under P(G), head of the grid's
 * server-END phase) → visible in that phase; after it → by the next one.
 */
public final class Staging {
    public enum Decision { RUN, RUN_UNDER_P, STAGE }

    private static final ThreadLocal<Boolean> DRAINING = ThreadLocal.withInitial(() -> false);

    /** Test hook at the drain cut (fixtures only). */
    public interface Hook {
        void at(String point, Grid grid);
    }

    public static volatile Hook hook;

    public static Decision decide(Grid g) {
        if (g == null || DRAINING.get()) return Decision.RUN;
        if (GridPhaseGate.phaseActive(g)) return Decision.STAGE;         // forbidden immediate, even on main
        if (GridPhaseGate.holds(g)) return Decision.RUN;                 // structural context, phase not active
        if (Ae2Compat.onMainThread() && !KERNEL.inSection()) return Decision.RUN_UNDER_P;
        return Decision.STAGE;
    }

    /** Apply a decision; {@code stage} records the replay when the decision is STAGE. */
    public static void apply(Grid g, Runnable now, Runnable stage) {
        switch (decide(g)) {
            case RUN -> now.run();
            case RUN_UNDER_P -> {
                ReentrantLock p = ((NwGrid) g).nw$p();
                p.lock();
                try {
                    if (GridPhaseGate.phaseActive(g)) stage.run();
                    else now.run();
                } finally {
                    p.unlock();
                }
                COUNTERS.stagingRunUnderP.increment();
            }
            case STAGE -> stage.run();
        }
    }

    public static void stageRebuild(Grid g, Object provider, String kind, Runnable replay) {
        if (((NwGrid) g).nw$staging().rebuilds.putIfAbsent(new GridStaging.Key(provider, kind), replay) == null) {
            COUNTERS.stagedRebuild.increment();
        } else {
            COUNTERS.coalescedRebuild.increment();
        }
    }

    public static void stageWatcher(Grid g, Runnable replay) {
        ((NwGrid) g).nw$staging().watcherOps.add(replay);
        COUNTERS.stagedWatcherOps.increment();
    }

    static void point(String name, Grid g) {
        Hook h = hook;
        if (h != null) h.at(name, g);
    }

    /** Main, under P(G), at the drain cut. */
    static void drain(Grid g) {
        point("drain-cut", g);
        GridStaging st = ((NwGrid) g).nw$staging();
        if (st.isEmpty()) return;
        if (g.getPivot() == null) {                                     // dead grid: nothing to apply to
            COUNTERS.droppedStaged.add(st.rebuilds.size() + st.watcherOps.size());
            st.rebuilds.clear();
            st.watcherOps.clear();
            return;
        }
        DRAINING.set(true);
        try {
            for (Runnable r; (r = st.watcherOps.poll()) != null; ) {   // FIFO, no coalescing
                run(r);
                COUNTERS.drainedWatcherOps.increment();
            }
            for (var key : new ArrayList<>(st.rebuilds.keySet())) {    // coalesced rebuilds
                Runnable r = st.rebuilds.remove(key);
                if (r != null) {
                    run(r);
                    COUNTERS.drainedRebuild.increment();
                }
            }
        } finally {
            DRAINING.set(false);
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException gone) {
            COUNTERS.droppedStaged.increment();                         // provider/node left the grid meanwhile
        } catch (RuntimeException e) {
            COUNTERS.stagingErrors.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] staged AE2 service mutation failed", e);
        }
    }

    private Staging() {
    }
}
