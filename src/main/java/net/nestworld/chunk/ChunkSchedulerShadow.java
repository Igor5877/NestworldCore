package net.nestworld.chunk;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.nestworld.region.NestworldRegionSystem;
import net.nestworld.region.NestworldTuning;
import net.nestworld.region.WorldRegion;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-Owned Chunk Scheduler, Phase 2 (docs/REGION_CHUNK_SCHEDULER_SPEC.md) -- SHADOW MODE ONLY.
 * Observes the same chunk-ticket-level-change signal {@code DistanceManager} already reacts to,
 * feeds it into the (Phase 1) per-region {@code RegionChunkScheduler}s, and records comparison
 * telemetry. Never influences real chunk loading: this class is called from {@code
 * ChunkTicketTracker.setLevel()} and {@code NestworldRegionSystem.tickAllRegions()}, but every
 * public method here is a no-op unless {@link NestworldTuning#CHUNK_SCHEDULER_SHADOW_MODE} is on,
 * and every exception is swallowed rather than allowed to propagate.
 *
 * <p>Gemini-reviewed 2026-08-12: {@link #observe(long, int)} (called synchronously from the
 * performance-sensitive {@code setLevel()} path) does nothing but a single {@code
 * ConcurrentLinkedQueue.add()} -- the real region lookup, priority classification, and scheduler
 * enqueue all happen later, in {@link #drainObservationsAndEnqueue(NestworldRegionSystem)}, called
 * once per tick from the main tick loop (same thread as {@code setLevel()}, but NOT on its call
 * stack), so shadow-mode observation can never add measurable latency to real chunk-ticket
 * classification.
 */
public final class ChunkSchedulerShadow {
    private ChunkSchedulerShadow() {}

    private record ObservedLevelChange(long pos, int newLevel) {}

    private static final ConcurrentLinkedQueue<ObservedLevelChange> PENDING = new ConcurrentLinkedQueue<>();
    /** Generous vs. expected setLevel() call volume per tick -- this is diagnostic-only work, a
     *  backlog here just means stale-by-a-tick telemetry, never a correctness or safety issue. */
    private static final int OBSERVE_DRAIN_BUDGET = 8000;
    private static final int STATS_DRAIN_BUDGET = 4000;

    private static final AtomicLong observed = new AtomicLong();
    private static final AtomicLong duplicatesSkipped = new AtomicLong();
    private static final AtomicLong misrouted = new AtomicLong();
    private static final AtomicLong drainedForStats = new AtomicLong();
    private static final AtomicLong latencySumNanos = new AtomicLong();
    private static volatile long latencyMaxNanos = 0;
    private static final AtomicLong[] tierEverEnqueued = initTierCounters();

    private static AtomicLong[] initTierCounters() {
        ChunkRequestPriority[] tiers = ChunkRequestPriority.values();
        AtomicLong[] arr = new AtomicLong[tiers.length];
        for (int i = 0; i < arr.length; i++) arr[i] = new AtomicLong();
        return arr;
    }

    /** Called from {@code ChunkTicketTracker.setLevel()}. MUST stay cheap/non-blocking -- see
     *  class doc. */
    public static void observe(long pos, int newLevel) {
        if (!NestworldTuning.CHUNK_SCHEDULER_SHADOW_MODE) return;
        PENDING.add(new ObservedLevelChange(pos, newLevel));
    }

    /** Called once per tick. Does the region lookup + priority classification + scheduler enqueue
     *  that {@code setLevel()} itself must never do synchronously. */
    public static void drainObservationsAndEnqueue(NestworldRegionSystem sys) {
        if (!NestworldTuning.CHUNK_SCHEDULER_SHADOW_MODE) return;
        List<ServerPlayer> players = sys.getOverworld().players();
        int processed = 0;
        ObservedLevelChange ev;
        while (processed < OBSERVE_DRAIN_BUDGET && (ev = PENDING.poll()) != null) {
            processed++;
            try {
                ChunkPos pos = new ChunkPos(ev.pos());
                WorldRegion region = sys.getGrid().getRegionForChunk(pos.x, pos.z);
                if (region == null) continue;
                ChunkRequestPriority tier = classify(pos, players);
                observed.incrementAndGet();
                tierEverEnqueued[tier.ordinal()].incrementAndGet();
                if (!region.getChunkScheduler().enqueue(pos, tier, "setLevel")) {
                    duplicatesSkipped.incrementAndGet();
                }
            } catch (Throwable t) {
                // Shadow path must never affect anything real -- swallow and move on.
            }
        }
    }

    private static ChunkRequestPriority classify(ChunkPos pos, List<ServerPlayer> players) {
        if (players.isEmpty()) return ChunkRequestPriority.BACKGROUND;
        int best = Integer.MAX_VALUE;
        for (ServerPlayer p : players) {
            int dx = Math.abs((p.getBlockX() >> 4) - pos.x);
            int dz = Math.abs((p.getBlockZ() >> 4) - pos.z);
            int dist = Math.max(dx, dz);
            if (dist < best) best = dist;
        }
        if (best <= NestworldTuning.CHUNK_PRIORITY_CRITICAL_RADIUS) return ChunkRequestPriority.CRITICAL;
        if (best <= NestworldTuning.CHUNK_PRIORITY_HIGH_RADIUS) return ChunkRequestPriority.HIGH;
        if (best <= NestworldTuning.CHUNK_PRIORITY_NORMAL_RADIUS) return ChunkRequestPriority.NORMAL;
        return ChunkRequestPriority.LOW;
    }

    /** Called once per tick, AFTER {@link #drainObservationsAndEnqueue}: drains a budgeted number
     *  of entries back OUT of each region's scheduler purely to record stats (latency proxy,
     *  misroute check) -- never acts on them, never influences real chunk loading. */
    public static void drainForStats(NestworldRegionSystem sys) {
        if (!NestworldTuning.CHUNK_SCHEDULER_SHADOW_MODE) return;
        int processed = 0;
        for (WorldRegion region : sys.getGrid().getAllRegions()) {
            if (processed >= STATS_DRAIN_BUDGET) break;
            while (processed < STATS_DRAIN_BUDGET) {
                ChunkRequest req = region.getChunkScheduler().pollNext();
                if (req == null) break;
                processed++;
                drainedForStats.incrementAndGet();
                try {
                    long latency = System.nanoTime() - req.enqueuedAtNanos();
                    latencySumNanos.addAndGet(latency);
                    if (latency > latencyMaxNanos) latencyMaxNanos = latency;
                    WorldRegion nowOwner = sys.getGrid().getRegionForChunk(req.pos().x, req.pos().z);
                    if (nowOwner != region) misrouted.incrementAndGet();
                } catch (Throwable t) {
                    // never let stats collection break anything
                }
            }
        }
    }

    // --- diagnostics accessors, used by /nestworld chunkscheduler ---
    public static long observedCount() { return observed.get(); }
    public static long duplicatesSkippedCount() { return duplicatesSkipped.get(); }
    public static long misroutedCount() { return misrouted.get(); }
    public static long drainedForStatsCount() { return drainedForStats.get(); }
    public static long tierEverEnqueuedCount(ChunkRequestPriority tier) { return tierEverEnqueued[tier.ordinal()].get(); }
    public static double avgLatencyMs() {
        long n = drainedForStats.get();
        return n == 0 ? 0.0 : (latencySumNanos.get() / 1e6) / n;
    }
    public static double maxLatencyMs() { return latencyMaxNanos / 1e6; }
}
