package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps every loaded ChunkPos to its owning WorldRegion.
 * Thread-safe for concurrent reads and writes.
 */
public class WorldGrid {

    private final ConcurrentHashMap<Long, WorldRegion> chunkMap = new ConcurrentHashMap<>();
    private final Set<WorldRegion> regions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger idCounter = new AtomicInteger(0);

    // --- Lookup ---

    public WorldRegion getRegionFor(ChunkPos pos) {
        return chunkMap.get(pos.toLong());
    }

    public WorldRegion getRegionFor(BlockPos pos) {
        return chunkMap.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** Returns true when the given block position is covered by any registered region. */
    public boolean isManaged(BlockPos pos) {
        return getRegionFor(pos) != null;
    }

    // --- Registration ---

    /** Registers all chunks in the region and adds it to the region set. */
    public void register(WorldRegion region) {
        for (int cx = region.getMinChunkX(); cx <= region.getMaxChunkX(); cx++) {
            for (int cz = region.getMinChunkZ(); cz <= region.getMaxChunkZ(); cz++) {
                chunkMap.put(ChunkPos.asLong(cx, cz), region);
            }
        }
        regions.add(region);
    }

    /** Removes all chunk mappings for the region and removes it from the region set. */
    public void unregister(WorldRegion region) {
        for (int cx = region.getMinChunkX(); cx <= region.getMaxChunkX(); cx++) {
            for (int cz = region.getMinChunkZ(); cz <= region.getMaxChunkZ(); cz++) {
                chunkMap.remove(ChunkPos.asLong(cx, cz));
            }
        }
        regions.remove(region);
    }

    public Collection<WorldRegion> getAllRegions() {
        return Collections.unmodifiableCollection(regions);
    }

    /** Allocates the next unique region ID. */
    public int nextId() {
        return idCounter.getAndIncrement();
    }
}
