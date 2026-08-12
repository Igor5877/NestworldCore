package net.nestworld.chunk;

import net.minecraft.world.level.ChunkPos;

/**
 * Region-Owned Chunk Scheduler, Phase 1 (docs/REGION_CHUNK_SCHEDULER_SPEC.md, sections 4 + 19).
 * Immutable, no live region/entity/thread references -- same discipline as {@code RegionMessage}
 * elsewhere in this codebase (a closure over live state would stay correct only as long as the
 * originating region/thread's identity doesn't change between enqueue and drain, which isn't
 * guaranteed once split/merge can reassign ownership mid-flight).
 *
 * <p>{@code requestId} is a per-request trace id (spec section 19: "REQ-84931 ... total: 628ms") --
 * NOT yet consumed by anything in Phase 1, reserved so later phases don't need an API change to
 * add tracing.
 */
public record ChunkRequest(
        ChunkPos pos,
        ChunkRequestPriority priority,
        long requestId,
        long enqueuedAtNanos,
        String source
) {
}
