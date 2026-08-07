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
    int splitPressureChecks = 0;
    int mergePressureChecks = 0;

    volatile RegionThread owningThread;

    /** Pinned regions (manual /nestworld split) are exempt from automatic merge. */
    public volatile boolean pinned = false;

    // NestWorld: Folia-style Stage 1 "Region Mailbox" pilot (see project memory
    // folia-actor-model-staged-plan.md). Published once per tick by BoundaryManager.
    // syncGhostZones() (main thread, called right after the region barrier — every
    // region thread has released its write lock and is parked waiting for the next
    // tick signal at that point, so this publish never races a region's own writes).
    // Keyed by BlockPos.asLong() -> a full NBT snapshot (BlockEntity.saveWithFullMetadata())
    // of every block entity in this region's chunks within BoundaryManager.GHOST_DEPTH
    // of a border. Readers on OTHER region threads reconstruct a DETACHED copy via
    // BlockEntity.loadStatic(pos, state, tag) — never touch the live, owner-mutated
    // instance. Replaces the old "ghost zone" live unsynchronized chunk.getBlockEntity()
    // pass-through, which despite this class's javadoc promising "1-tick-stale" data was
    // actually a genuine, unprotected data race on the live BlockEntity's mutable fields
    // (unlike PalettedContainer's own getBlockState() reads, which vanilla already makes
    // safe via a lock-free volatile-snapshot design — verified by reading PalettedContainer
    // directly, not assumed; see the class's own nestworldContainerLock comment).
    // AtomicReference gives safe publication (JMM) for the whole map as one unit — readers
    // never see a partially-populated snapshot.
    private final java.util.concurrent.atomic.AtomicReference<java.util.Map<Long, net.minecraft.nbt.CompoundTag>>
            nestworldGhostBeSnapshot = new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());

    /** Called only from BoundaryManager.syncGhostZones() (main thread, post-barrier). */
    void nestworldPublishGhostSnapshot(java.util.Map<Long, net.minecraft.nbt.CompoundTag> snapshot) {
        this.nestworldGhostBeSnapshot.set(snapshot);
    }

    /** Safe to call from any region thread — lock-free read of the last-published snapshot. */
    public java.util.Map<Long, net.minecraft.nbt.CompoundTag> nestworldGetGhostSnapshot() {
        return this.nestworldGhostBeSnapshot.get();
    }

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

    /**
     * Average duration of this region's own tick work in milliseconds.
     *
     * <p>Unlike {@link #getCurrentTps()} (which caps at 20 and therefore reads
     * "healthy" for any duration under the full 50 ms budget) this is the raw
     * cost the region contributes to the server tick — the split/merge
     * heuristic compares it against a share of the budget.
     */
    public double getAvgTickMs() {
        return windowSumNs.get() / (double) Math.max(1, tickDurationsNs.length) / 1_000_000.0;
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

    public void resetThresholdCounters() {
        splitPressureChecks = 0;
        mergePressureChecks = 0;
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
        return String.format("Region{id=%d, chunks=(%d,%d)–(%d,%d), cost=%.1fms}",
                id, minChunkX, minChunkZ, maxChunkX, maxChunkZ, getAvgTickMs());
    }
}
