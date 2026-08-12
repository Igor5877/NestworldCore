package net.nestworld.region;

/**
 * P0.5 (docs/P0_3_AUTONOMOUS_BASELINE_SPEC.md follow-up ТЗ): live-settable override for
 * {@link NestworldTuning#CHUNK_GEN_BUDGET}, so a chunkGenBudget=2/4/8 sweep can run on ONE warm
 * JVM without a restart between data points -- same rationale as {@code ChunkMap
 * .nestworldSetGenConcurrency}'s existing live concurrency toggle (a restart-per-data-point sweep
 * was found to introduce a real JIT-compilation-storm confound, see project memory
 * layer13-concurrency-sweep-partial-and-jit-storm). {@code -1} means "use
 * NestworldTuning.CHUNK_GEN_BUDGET's boot-time value" (the default, no behavior change unless
 * explicitly overridden via {@code /nestworld setgenbudget}).
 */
public final class NestworldLiveTuning {
    private NestworldLiveTuning() {}

    public static volatile int chunkGenBudgetOverride = -1;

    /** Effective Tier2 (bulk) per-tick promotion budget right now. */
    public static int effectiveChunkGenBudget() {
        return chunkGenBudgetOverride >= 0 ? chunkGenBudgetOverride : NestworldTuning.CHUNK_GEN_BUDGET;
    }
}
