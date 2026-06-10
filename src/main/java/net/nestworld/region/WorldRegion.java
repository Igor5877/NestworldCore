package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Represents a rectangular chunk-range of the world handled by one RegionThread.
 * The StampedLock protects cross-region chunk reads: any thread may hold a read stamp,
 * but only the owning RegionThread acquires write stamps during ticking.
 */
public class WorldRegion {

    private final int id;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX; // inclusive
    private final int maxChunkZ; // inclusive

    private final StampedLock chunkLock = new StampedLock();

    // Entity UUIDs whose ticking is owned by this region's thread
    private final Set<UUID> ownedEntityIds = ConcurrentHashMap.newKeySet();

    // Rolling TPS window: last 100 tick durations in nanoseconds
    private final long[] tickDurationsNs = new long[100];
    private int tickIndex = 0;
    private final AtomicLong windowSumNs = new AtomicLong(0);

    // Counters for split/merge hysteresis (guarded by RegionSplitManager's lock)
    int ticksBelowThreshold = 0;
    int ticksAboveThreshold = 0;

    volatile RegionThread owningThread;

    public WorldRegion(int id, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.id = id;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    // --- Spatial queries ---

    public boolean containsChunk(int cx, int cz) {
        return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
    }

    public boolean containsChunk(ChunkPos pos) {
        return containsChunk(pos.x, pos.z);
    }

    public boolean containsBlock(BlockPos pos) {
        return containsChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // --- Entity ownership ---

    public void addEntity(UUID id) { ownedEntityIds.add(id); }
    public void removeEntity(UUID id) { ownedEntityIds.remove(id); }
    public boolean ownsEntity(UUID id) { return ownedEntityIds.contains(id); }
    public Set<UUID> getOwnedEntityIds() { return ownedEntityIds; }

    // --- TPS tracking ---

    /** Called by RegionThread at the end of each tick with measured duration. */
    public void recordTickDuration(long durationNs) {
        long old = tickDurationsNs[tickIndex];
        tickDurationsNs[tickIndex] = durationNs;
        tickIndex = (tickIndex + 1) % tickDurationsNs.length;
        windowSumNs.addAndGet(durationNs - old);
    }

    /** Returns estimated TPS (0–20). */
    public double getCurrentTps() {
        long avgNs = windowSumNs.get() / Math.max(1, tickDurationsNs.length);
        if (avgNs <= 0) return 20.0;
        return Math.min(20.0, 1_000_000_000.0 / avgNs);
    }

    // --- Split eligibility ---

    /** A region can be split only when it covers more than 1 chunk in at least one axis. */
    public boolean canSplit() {
        return (maxChunkX - minChunkX) > 0 || (maxChunkZ - minChunkZ) > 0;
    }

    /** Returns the axis along which to cut (always prefer the longer axis for balance). */
    public SplitAxis preferredSplitAxis() {
        return (maxChunkX - minChunkX) >= (maxChunkZ - minChunkZ) ? SplitAxis.X : SplitAxis.Z;
    }

    // --- Accessors ---

    public int getId() { return id; }
    public int getMinChunkX() { return minChunkX; }
    public int getMinChunkZ() { return minChunkZ; }
    public int getMaxChunkX() { return maxChunkX; }
    public int getMaxChunkZ() { return maxChunkZ; }
    public int getChunkSpanX() { return maxChunkX - minChunkX + 1; }
    public int getChunkSpanZ() { return maxChunkZ - minChunkZ + 1; }
    public StampedLock getChunkLock() { return chunkLock; }

    @Override
    public String toString() {
        return String.format("Region{id=%d, chunks=(%d,%d)–(%d,%d), tps=%.1f}",
                id, minChunkX, minChunkZ, maxChunkX, maxChunkZ, getCurrentTps());
    }
}
