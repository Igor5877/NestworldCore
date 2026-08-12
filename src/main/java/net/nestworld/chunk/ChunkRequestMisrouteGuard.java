package net.nestworld.chunk;

/**
 * Region-Owned Chunk Scheduler, Phase 1 skeleton (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section
 * 16). Will assert an enqueue's target region matches {@code RegionTree.findRegion(pos)} once a
 * later phase gives it real call sites. No-op in Phase 1: nothing routes chunk requests through a
 * scheduler yet.
 */
public final class ChunkRequestMisrouteGuard {
    private ChunkRequestMisrouteGuard() {}
}
