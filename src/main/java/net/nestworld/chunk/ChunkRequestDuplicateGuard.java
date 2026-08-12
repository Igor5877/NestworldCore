package net.nestworld.chunk;

/**
 * Region-Owned Chunk Scheduler, Phase 1 skeleton (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section
 * 16). Will assert a {@code ChunkPos} isn't pending in two schedulers/tiers at once, once real
 * call sites exist to observe how often cross-tier requests for the same position actually happen
 * (see {@link RegionChunkScheduler}'s class doc -- Phase 1's dedup is per-tier only). No-op in
 * Phase 1.
 */
public final class ChunkRequestDuplicateGuard {
    private ChunkRequestDuplicateGuard() {}
}
