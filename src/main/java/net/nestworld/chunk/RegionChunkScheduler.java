package net.nestworld.chunk;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-Owned Chunk Scheduler, Phase 1 (docs/REGION_CHUNK_SCHEDULER_SPEC.md). One instance per
 * {@code WorldRegion}. Gemini-reviewed 2026-08-12: enqueue is expected to be called from the main
 * thread (spec section 4/7: "request -&gt; find owner region -&gt; enqueue -&gt; return") while
 * draining happens on the region's own thread, so every field here is a genuinely thread-safe
 * structure, not "single writer, main-thread-owned" like most of {@code WorldRegion}'s other
 * per-tick state.
 *
 * <p><b>PHASE 1 ONLY -- dark code, nothing calls this yet.</b> No wiring into
 * {@code DistanceManager}/{@code ChunkMap}/{@code ChunkHolder}, no worldgen admission, no
 * completion/integration, no split/merge reownership. Those are later phases (see project memory
 * region-chunk-scheduler-spec.md and the task list). This class exists only to settle the queue
 * shape now, so Phase 2's wiring doesn't force an API change.
 *
 * <p>Per-tier {@code ConcurrentLinkedDeque} gives O(1) {@code addLast}/{@code pollFirst}. A
 * parallel {@code ConcurrentHashMap}-backed key set per tier gives O(1) duplicate-enqueue
 * detection (same pattern as {@code WorldRegion.ownedEntityIds}). Dedup is currently PER-TIER
 * only -- the same {@code ChunkPos} could in principle be enqueued in two different tiers
 * simultaneously (e.g. requested BACKGROUND by forceload, then CRITICAL by a player arriving
 * seconds later); {@link ChunkRequestDuplicateGuard} is where a future phase should decide whether
 * cross-tier dedup is actually required once real call sites exist to observe how often this
 * happens.
 */
public final class RegionChunkScheduler {
    private static final int TIER_COUNT = ChunkRequestPriority.values().length;
    private static final AtomicLong nestworldRequestIdGen = new AtomicLong();

    private final ConcurrentLinkedDeque<ChunkRequest>[] queues;
    private final Set<ChunkPos>[] pending;
    private final AtomicInteger[] sizes;

    @SuppressWarnings("unchecked")
    public RegionChunkScheduler() {
        this.queues = new ConcurrentLinkedDeque[TIER_COUNT];
        this.pending = new Set[TIER_COUNT];
        this.sizes = new AtomicInteger[TIER_COUNT];
        for (int i = 0; i < TIER_COUNT; i++) {
            this.queues[i] = new ConcurrentLinkedDeque<>();
            this.pending[i] = ConcurrentHashMap.newKeySet();
            this.sizes[i] = new AtomicInteger();
        }
    }

    /**
     * Enqueues a chunk request at the given priority. Returns {@code false} (no-op) if this exact
     * position is already pending in the SAME tier -- callers don't need their own dedup check.
     */
    public boolean enqueue(ChunkPos pos, ChunkRequestPriority priority, String source) {
        int idx = priority.ordinal();
        if (!this.pending[idx].add(pos)) return false;
        ChunkRequest request = new ChunkRequest(pos, priority, nestworldRequestIdGen.incrementAndGet(),
                System.nanoTime(), source);
        this.queues[idx].addLast(request);
        this.sizes[idx].incrementAndGet();
        return true;
    }

    /** Drains the single highest-priority pending request (CRITICAL first), or {@code null} if empty. */
    public ChunkRequest pollNext() {
        for (int i = 0; i < TIER_COUNT; i++) {
            ChunkRequest request = this.queues[i].pollFirst();
            if (request != null) {
                this.pending[i].remove(request.pos());
                this.sizes[i].decrementAndGet();
                return request;
            }
        }
        return null;
    }

    public int queueSize(ChunkRequestPriority priority) {
        return this.sizes[priority.ordinal()].get();
    }

    public int totalQueueSize() {
        int total = 0;
        for (AtomicInteger s : this.sizes) total += s.get();
        return total;
    }
}
