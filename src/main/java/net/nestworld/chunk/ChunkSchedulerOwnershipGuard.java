package net.nestworld.chunk;

/**
 * Region-Owned Chunk Scheduler, Phase 1 skeleton (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section
 * 16). Will assert a chunk's scheduler owner matches its {@code RegionTree}/{@code WorldGrid}
 * owner, once a later phase gives it real call sites -- matches the {@code EntityOwnershipGuard}/
 * {@code EntityOwnershipRecheck} pattern already used for entity ownership elsewhere in this
 * codebase. No-op in Phase 1: nothing routes chunk requests through a scheduler yet.
 */
public final class ChunkSchedulerOwnershipGuard {
    private ChunkSchedulerOwnershipGuard() {}
}
