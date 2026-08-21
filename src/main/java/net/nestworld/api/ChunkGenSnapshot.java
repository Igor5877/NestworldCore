package net.nestworld.api;

/**
 * Read-only snapshot of the chunk-generation/promotion pipeline for one dimension.
 * Mirrors the data already surfaced by {@code /nestworld chunkpromotion} and
 * {@code /nestworld genconcurrency} — this is that same data as a stable Java API
 * instead of parsed command text, for external consumers (metrics exporters, future
 * mods).
 *
 * <p>Phase 1 (2026-08-21, docs/CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md): the
 * generation*&#47;integration* fields separate two buckets promotedTotal/promotedAvgMs
 * used to conflate under one "chunk promotion" umbrella — {@code promoted*} is
 * DistanceManager's updateFutures() (scheduling), {@code generation*} is the actual
 * CPU-bound NOISE/SURFACE/CARVERS/FEATURES work on the gated worker pool, and
 * {@code integration*} is the main-thread ProtoChunk->LevelChunk commit step. Telling
 * these apart is the whole point of this phase: "generation slow" and "generation
 * fast but integration slow" now show up as different numbers instead of one blended
 * average. generation*&#47;integration* are coarse-grained (JVM-wide, not per-dimension —
 * see GenerationAttribution/WorldgenGlueAttribution's own javadoc for why) so their
 * value is identical across every dimension's snapshot; still exposed per-dimension
 * here for a uniform snapshot shape, splitting is deferred to a later phase if needed.
 *
 * @param pendingPlayer      backlog: chunk holders with an active PLAYER ticket still
 *                           awaiting a promotion pass (Tier 1/1a/1b).
 * @param pendingBulk        backlog: chunk holders with no PLAYER ticket (Tier 2 —
 *                           forceload, predictive, distant/teleport tickets).
 * @param promotedTotal      cumulative {@code updateFutures()} calls applied since boot.
 * @param promotedAvgMs      average wall-clock cost of one {@code updateFutures()} call.
 * @param promotedP99Ms      99th percentile cost of one {@code updateFutures()} call.
 * @param admittedTotal      cumulative Tier 1b/Tier 2 holders selected for a promotion
 *                           attempt this session, independent of whether the pump-budget
 *                           deadline actually let them run — see
 *                           {@code NestworldTuning.CHUNK_PROMOTION_PUMP_BUDGET_MS}.
 * @param pumpDeadlineHits   cumulative times the pump-budget deadline clamp actually cut
 *                           a promotion pass short. Zero is healthy; non-zero under a real
 *                           backlog-drain means the safety valve is doing its job.
 * @param predictiveRequested cumulative frontier chunk tickets issued by
 *                           {@code PredictiveChunkGen} across all managed dimensions.
 * @param genPoolConcurrency current cap on simultaneously-active CPU-bound
 *                           NOISE/SURFACE/CARVERS/FEATURES generation calls.
 * @param genPoolActive      live gauge: generation calls currently occupying a slot
 *                           (actually running/awaiting their real future right now).
 * @param genPoolPending     live gauge: generation calls queued behind a full pool.
 * @param generatedTotal     cumulative successfully-completed gated generation calls.
 * @param generationFailedTotal cumulative gated generation calls that completed
 *                           exceptionally (a real Exception/Error from generation code,
 *                           not a timeout or admission rejection).
 * @param generationDeferredTotal cumulative times a generation call found the pool full
 *                           and was queued instead of admitted immediately.
 * @param generationAvgMs    average wall-clock cost of one gated generation call, from
 *                           admission to its real future completing.
 * @param generationP99Ms    99th percentile cost of one gated generation call.
 * @param generationMaxMs    worst observed cost of one gated generation call.
 * @param integrationCompletedTotal cumulative main-thread chunk_finalize calls (the
 *                           ProtoChunk->LevelChunk commit) since boot.
 * @param integrationAvgMs   average wall-clock cost of one chunk_finalize call.
 * @param integrationP99Ms   99th percentile cost of one chunk_finalize call.
 */
public record ChunkGenSnapshot(
        long pendingPlayer,
        long pendingBulk,
        long promotedTotal,
        double promotedAvgMs,
        double promotedP99Ms,
        long admittedTotal,
        long pumpDeadlineHits,
        long predictiveRequested,
        int genPoolConcurrency,
        int genPoolActive,
        int genPoolPending,
        long generatedTotal,
        long generationFailedTotal,
        long generationDeferredTotal,
        double generationAvgMs,
        double generationP99Ms,
        double generationMaxMs,
        long integrationCompletedTotal,
        double integrationAvgMs,
        double integrationP99Ms
) {
}
