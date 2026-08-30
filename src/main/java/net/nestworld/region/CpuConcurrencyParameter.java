package net.nestworld.region;

/**
 * CPU Capacity Planner -- typed metadata for one CPU-relevant configuration parameter, Phase 1
 * (ТЗ, 2026-08-30 §7-8). The user's explicit, central requirement: a thread-count parameter and a
 * per-tick operation-count budget must NEVER be treated as equivalent CPU demand ("§7:
 * chunkGenBudget=200 не означає 200 CPU threads") -- {@link WorkType} plus a per-parameter {@link
 * #intensityWeight} encode that distinction so {@link CpuCapacityPlanner}'s demand model can
 * weight each contributor correctly instead of naively summing raw numbers.
 */
public final class CpuConcurrencyParameter {
    public enum WorkType {
        /** A genuine worker-thread pool competing for CPU whenever active (e.g. Netty workers,
         *  chunk-gen worker threads). */
        CPU_CONCURRENCY,
        /** A per-tick operation-count cap, NOT a thread count -- bounds how much work one
         *  (already-running) thread does per tick, not how many threads exist. */
        CPU_WORK_BUDGET,
        /** Cost that runs serially on the main thread regardless of any budget/thread setting. */
        MAIN_THREAD_WORK,
        /** Parallel work distributed across the region-thread pool. */
        REGION_WORK,
        /** Netty/connection-layer concurrency specifically (kept distinct from generic
         *  CPU_CONCURRENCY so the planner can report network vs compute demand separately). */
        NETWORK_WORK,
        /** A backlog-size cap -- bounds memory/staleness, not CPU demand by itself. */
        QUEUE_LIMIT,
        /** Not a CPU concern at all (e.g. a memory or bandwidth limit) -- informational only. */
        NON_CPU_LIMIT
    }

    public final String name;
    public final WorkType type;
    public final double value;
    /** Rough per-unit CPU-intensity weight used by the demand model. ~1.0 for a parameter whose
     *  units are literally concurrent threads that fully occupy a core when busy (Netty/region
     *  worker counts); much smaller (a few hundredths) for a per-tick budget number, since a
     *  budget of N does not mean N concurrent threads -- it means one thread may do up to N cheap
     *  operations that tick before yielding. */
    public final double intensityWeight;
    public final boolean isAuto;

    public CpuConcurrencyParameter(String name, WorkType type, double value, double intensityWeight, boolean isAuto) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.intensityWeight = intensityWeight;
        this.isAuto = isAuto;
    }

    /** This parameter's contribution to {@link CpuCapacityPlanner}'s effective CPU demand model
     *  -- QUEUE_LIMIT/NON_CPU_LIMIT never contribute (they don't consume CPU by existing), every
     *  other type contributes {@code value * intensityWeight} (the weight is what actually
     *  encodes "is this really N threads' worth of demand, or something far lighter"). */
    public double effectiveDemand() {
        return switch (type) {
            case QUEUE_LIMIT, NON_CPU_LIMIT -> 0.0;
            default -> value * intensityWeight;
        };
    }
}
