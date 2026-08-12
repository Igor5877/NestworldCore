package net.nestworld.chunk;

/**
 * Region-Owned Chunk Scheduler, Phase 1 (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section 5). Ordinal
 * order IS priority order -- CRITICAL drains first, BACKGROUND last/only-when-idle. Distance-to-
 * player thresholds mapping a chunk's distance to a tier live in NestworldTuning, not here.
 */
public enum ChunkRequestPriority {
    /** Distance 0-2 chunks: needed by a player right now. */
    CRITICAL,
    /** Distance 3-6 chunks: near an active player. */
    HIGH,
    /** Distance 7-12 chunks: ordinary chunk requests. */
    NORMAL,
    /** Distance 13+ chunks: remote requests. */
    LOW,
    /** forceload / bulk / pre-generation. */
    BACKGROUND
}
