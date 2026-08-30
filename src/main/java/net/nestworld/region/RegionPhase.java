package net.nestworld.region;

/**
 * P0 RegionThreadPool redesign, Phase 1 (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md) -- tags each
 * of the 5 main-thread call sites into {@code RegionThreadPool}'s blocking dispatch methods, so
 * baseline telemetry (region_tick_time, region_wait_time, region_poll_task_time, timeout counts,
 * regions_completed/pending) can be tracked SEPARATELY per phase instead of one shared bucket.
 * Purely a label -- carries no behavior of its own.
 */
public enum RegionPhase {
    ENTITY,
    SCHEDULED_TICK,
    RANDOM_TICK,
    BLOCK_ENTITY,
    BLOCK_EVENT,
    /** Phase 11 #31.3 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — opt-in experiment only,
     *  see {@link PlayerTickExperiment}. */
    PLAYER_TICK_EXPERIMENT,
    /** Phase 11 #31.4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — opt-in experiment only,
     *  see {@link PlayerAttackExperiment}. */
    PLAYER_ATTACK_EXPERIMENT
}
