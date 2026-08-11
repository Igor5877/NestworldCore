package net.nestworld.region;

/**
 * Stage 4.1 (docs/LOCAL_TICK_STAGE4.md, "Global Tick / Barrier Elimination Research"):
 * a purely OBSERVATIONAL classification layer over {@link RegionThreadPool}'s existing
 * synchronous barrier. Deliberately does not change {@code awaitLatch()}'s semantics —
 * the global tick stays lockstep, every region still finishes tick N before the main
 * thread moves on. This exists to make "which regions are healthy vs struggling" a
 * first-class, queryable fact instead of something only visible by eyeballing
 * {@code /nestworld status}'s raw cost column, and to give later Stage 4 work (4.3-4.5)
 * a stable classification vocabulary to build on without re-deriving it each time.
 *
 * <p>Classification is derived entirely from state {@link RegionThread}/{@link WorldRegion}
 * already track for other reasons (tick-cost rolling average, budget-deferral counters) —
 * no new per-tick instrumentation was added to the hot path to support this.
 */
public final class RegionScheduler {

    private RegionScheduler() {}

    public enum State {
        /** No owned entities and nothing deferred from a prior round — this region's
         *  thread was skipped entirely this tick (see RegionThreadPool.tickAllRegions()'s
         *  own {@code workers} filter). Trivially "ready" the instant it gets work. */
        IDLE,
        /** Had work this round, budget was not exceeded, average cost is comfortably
         *  under budget. The common, healthy case. */
        RUNNING,
        /** Had work this round; not currently over budget, but its rolling average is
         *  elevated (over {@link #DELAYED_THRESHOLD_FRACTION} of the entity budget) —
         *  worth watching, not yet a problem. */
        DELAYED,
        /** This round's entity round-robin or work round actually hit its budget and had
         *  to defer entities/work to next tick ({@code lastDeferredCount}/{@code
         *  lastWorkDeferred} > 0) — the region contributing real backlog right now. */
        OVERLOADED
    }

    /** Rolling-average fraction of the entity budget above which a non-overloaded
     *  region is reported DELAYED rather than RUNNING — an early-warning band before
     *  it actually starts deferring work. */
    private static final double DELAYED_THRESHOLD_FRACTION = 0.5;

    public record Classification(WorldRegion region, State state, double avgTickMs,
                                  int lastDeferredEntities, int lastWorkDeferred) {
    }

    /** Classifies one region/thread pair using this tick's already-recorded state.
     *  Safe to call any time after the region's thread has finished its round for
     *  this tick (i.e. after {@link RegionThreadPool#tickAllRegions()} returns) —
     *  reads only volatile/already-published fields, no locking needed. */
    public static Classification classify(WorldRegion region, RegionThread thread) {
        int deferredEntities = thread.getLastDeferredCount();
        int workDeferred = thread.getLastWorkDeferred();
        double avgMs = region.getAvgTickMs();

        State state;
        if (region.getOwnedEntityIds().isEmpty() && workDeferred == 0) {
            state = State.IDLE;
        } else if (deferredEntities > 0 || workDeferred > 0) {
            state = State.OVERLOADED;
        } else {
            double budgetMs = NestworldTuning.REGION_ENTITY_BUDGET_NANOS / 1_000_000.0;
            state = (avgMs >= budgetMs * DELAYED_THRESHOLD_FRACTION) ? State.DELAYED : State.RUNNING;
        }
        return new Classification(region, state, avgMs, deferredEntities, workDeferred);
    }

    /** Classifies every currently active region. Called on demand (e.g. by
     *  {@code /nestworld scheduler}), not on a per-tick hot path. */
    public static java.util.List<Classification> classifyAll(RegionThreadPool pool) {
        java.util.List<Classification> out = new java.util.ArrayList<>();
        for (RegionThread t : pool.getThreads()) {
            out.add(classify(t.getRegion(), t));
        }
        return out;
    }
}
