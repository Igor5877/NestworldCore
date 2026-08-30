package net.nestworld.region;

import java.util.concurrent.atomic.LongAdder;

/**
 * NaturalSpawner Attribution / Budget Investigation (ТЗ, 2026-08-29) -- MEASUREMENT ONLY. Targets
 * the 4th distinct bottleneck found this session (see project memory
 * player-tick-decision-attribution-2026-08-29.md): after fixing the three outbound-delivery
 * queues and enabling the dormant TRACKER_SPATIAL_CULL optimization, n=200's mass teleport
 * finally survived the NETWORK-focused crash class, only to hit a NEW watchdog stall inside
 * {@code NaturalSpawner}/{@code BiomeManager} mob-spawning logic seconds later.
 *
 * <p>Code-level hypothesis (confirmed by reading, not yet by live numbers): {@code EntityGetter
 * .getNearestPlayer(x,y,z,radius,...)} is an UNBOUNDED linear scan (`for (Player p : this
 * .players())`, no spatial index) -- and it's called from deep inside {@code NaturalSpawner
 * .spawnCategoryForPosition()}'s innermost per-attempt loop (up to ~3 outer x ~4 inner
 * candidate-position tries per category per chunk), for EVERY spawn-eligible chunk, EVERY tick,
 * entirely on the main thread -- an O(chunks x attempts x players) cost with no relation to any
 * of the three outbound queues or the tracker's own spatial cull, both of which only touch
 * entity-tracking/visibility, not mob spawning.
 */
public final class NaturalSpawnerMetrics {
    private NaturalSpawnerMetrics() {}

    private static final LongAdder tickChunksCalls = new LongAdder();
    private static final LongAdder tickChunksTimeNanos = new LongAdder();
    private static final LongAdder createStateTimeNanos = new LongAdder();
    private static final LongAdder totalTickingChunks = new LongAdder();
    private static final LongAdder spawnEligibleChunks = new LongAdder();
    private static final LongAdder spawnForChunkCalls = new LongAdder();
    private static final LongAdder spawnCategoryCalls = new LongAdder();
    private static final LongAdder successfulSpawns = new LongAdder();
    private static volatile long maxTickChunksNanos = 0L;
    private static volatile long maxCreateStateNanos = 0L;

    public static long nowNanos() {
        return System.nanoTime();
    }

    public static void recordTickChunks(long elapsedNanos, int tickingChunks, int eligibleChunks) {
        tickChunksCalls.increment();
        tickChunksTimeNanos.add(elapsedNanos);
        if (elapsedNanos > maxTickChunksNanos) maxTickChunksNanos = elapsedNanos;
        totalTickingChunks.add(tickingChunks);
        spawnEligibleChunks.add(eligibleChunks);
    }

    public static void recordCreateState(long elapsedNanos) {
        createStateTimeNanos.add(elapsedNanos);
        if (elapsedNanos > maxCreateStateNanos) maxCreateStateNanos = elapsedNanos;
    }

    public static void recordSpawnForChunk() {
        spawnForChunkCalls.increment();
    }

    public static void recordSpawnCategoryCall() {
        spawnCategoryCalls.increment();
    }

    public static void recordSuccessfulSpawn() {
        successfulSpawns.increment();
    }

    public static void reset() {
        tickChunksCalls.reset();
        tickChunksTimeNanos.reset();
        createStateTimeNanos.reset();
        totalTickingChunks.reset();
        spawnEligibleChunks.reset();
        spawnForChunkCalls.reset();
        spawnCategoryCalls.reset();
        successfulSpawns.reset();
        maxTickChunksNanos = 0L;
        maxCreateStateNanos = 0L;
    }

    public static String report() {
        long calls = tickChunksCalls.sum();
        double avgTickChunksMs = calls > 0 ? (tickChunksTimeNanos.sum() / 1_000_000.0) / calls : 0;
        double avgCreateStateMs = calls > 0 ? (createStateTimeNanos.sum() / 1_000_000.0) / calls : 0;
        double avgTickingChunks = calls > 0 ? (double) totalTickingChunks.sum() / calls : 0;
        double avgEligibleChunks = calls > 0 ? (double) spawnEligibleChunks.sum() / calls : 0;
        return String.format(
                "NW natural-spawner: tickChunksCalls=%,d | avgTickChunksMs=%.2f maxTickChunksMs=%.2f | avgCreateStateMs=%.3f maxCreateStateMs=%.3f | avgTickingChunks=%.1f avgSpawnEligibleChunks=%.1f | spawnForChunkCalls=%,d spawnCategoryCalls=%,d successfulSpawns=%,d",
                calls, avgTickChunksMs, maxTickChunksNanos / 1_000_000.0, avgCreateStateMs, maxCreateStateNanos / 1_000_000.0,
                avgTickingChunks, avgEligibleChunks, spawnForChunkCalls.sum(), spawnCategoryCalls.sum(), successfulSpawns.sum());
    }
}
