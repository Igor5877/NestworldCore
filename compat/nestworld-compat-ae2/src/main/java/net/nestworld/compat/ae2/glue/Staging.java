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

    /** The owner of the staged operation being replayed right now (watcher or provider identity). */
    private static final ThreadLocal<Object> REPLAY_OWNER = new ThreadLocal<>();
    /** The grid whose staging main is draining right now: fresh operations on it are after the cut. */
    private static final ThreadLocal<Grid> DRAINING_GRID = new ThreadLocal<>();

    /** Test hook at the drain cut (fixtures only). */
    public interface Hook {
        void at(String point, Grid grid);
    }

    public static volatile Hook hook;

    /**
     * Review 1.3: only the replayed operation itself (and its own nested calls on the same owner, e.g.
     * StackWatcher.reset → setWatchAll) bypasses staging; any FRESH operation published during the drain
     * of the same grid is after the cut and is staged for the next phase.
     */
    public static Decision decide(Grid g, Object owner) {
        if (g == null) return Decision.RUN;
        Object replaying = REPLAY_OWNER.get();
        if (replaying != null && replaying == owner) return Decision.RUN;
        if (DRAINING_GRID.get() == g) return Decision.STAGE;              // published after the drain cut
        if (GridPhaseGate.phaseActive(g)) return Decision.STAGE;         // forbidden immediate, even on main
        if (GridPhaseGate.holds(g)) return Decision.RUN;                 // structural context, phase not active
        if (Ae2Compat.onMainThread() && !KERNEL.inSection()) return Decision.RUN_UNDER_P;
        return Decision.STAGE;
    }

    /** Apply a decision; {@code stage} records the replay when the decision is STAGE. */
    public static void apply(Grid g, Object owner, Runnable now, Runnable stage) {
        switch (decide(g, owner)) {
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
        if (((NwGrid) g).nw$staging().rebuilds.putIfAbsent(new GridStaging.Key(provider, kind), new GridStaging.Item(provider, replay)) == null) {
            COUNTERS.stagedRebuild.increment();
        } else {
            COUNTERS.coalescedRebuild.increment();
        }
    }

    public static void stageWatcher(Grid g, Object watcher, Runnable replay) {
        ((NwGrid) g).nw$staging().watcherOps.add(new GridStaging.Item(watcher, replay));
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
        // only what was published before this point is drained; the queue/map snapshot is taken now
        java.util.List<GridStaging.Item> ops = new ArrayList<>();
        for (GridStaging.Item it; (it = st.watcherOps.poll()) != null; ) ops.add(it);
        java.util.List<GridStaging.Item> rebuilds = new ArrayList<>();
        for (var key : new ArrayList<>(st.rebuilds.keySet())) {
            GridStaging.Item it = st.rebuilds.remove(key);
            if (it != null) rebuilds.add(it);
        }
        DRAINING_GRID.set(g);
        try {
            for (GridStaging.Item it : ops) {                           // FIFO, no coalescing
                replay(it);
                COUNTERS.drainedWatcherOps.increment();
            }
            for (GridStaging.Item it : rebuilds) {                      // coalesced rebuilds
                replay(it);
                COUNTERS.drainedRebuild.increment();
            }
        } finally {
            DRAINING_GRID.remove();
        }
    }

    /** Main, during a drain: a staged replay found its target gone (node left the grid) — drop it. */
    public static void dropStale() {
        COUNTERS.droppedStaged.increment();
    }

    /**
     * Review 1.4: the only tolerated failure is the proven stale case (AE2 throws IllegalArgumentException
     * from refresh*Provider when the node/provider is no longer part of the grid). Anything else may have
     * left a partial structural mutation: count, fail closed (DEGRADED), abort the drain and the phase.
     */
    private static void replay(GridStaging.Item it) {
        Object saved = REPLAY_OWNER.get();
        REPLAY_OWNER.set(it.owner());
        try {
            it.replay().run();
        } catch (IllegalArgumentException gone) {
            COUNTERS.droppedStaged.increment();
        } catch (RuntimeException e) {
            COUNTERS.stagingErrors.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] staged AE2 service mutation failed; switching to serialization", e);
            KERNEL.unaudited("staged AE2 service mutation failed: " + e);
            throw e;
        } finally {
            if (saved == null) REPLAY_OWNER.remove();
            else REPLAY_OWNER.set(saved);
        }
    }

    private Staging() {
    }
}
