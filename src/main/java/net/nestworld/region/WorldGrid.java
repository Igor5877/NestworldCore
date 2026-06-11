package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps chunk positions to their owning WorldRegion.
 *
 * <p>Lookup scans the active region list and tests rectangular bounds.
 * Regions can span the whole world (millions of chunks), so a per-chunk map
 * is not an option; with the BSP keeping the active set small (a handful to
 * a few dozen leaves) a bounds scan is faster than hashing anyway.
 * Thread-safe: the list is copy-on-write, mutated only between ticks.
 */
public class WorldGrid {

    private final CopyOnWriteArrayList<WorldRegion> regions = new CopyOnWriteArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    /** Bumped on every register/unregister; lets per-tick scans detect layout changes cheaply. */
    private final AtomicInteger layoutVersion = new AtomicInteger(0);

    // --- Lookup ---

    public WorldRegion getRegionFor(ChunkPos pos) {
        return getRegionForChunk(pos.x, pos.z);
    }

    public WorldRegion getRegionFor(BlockPos pos) {
        return getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public WorldRegion getRegionForChunk(int cx, int cz) {
        for (WorldRegion r : regions) {
            if (r.containsChunk(cx, cz)) return r;
        }
        return null;
    }

    /** Returns true when the given block position is covered by any registered region. */
    public boolean isManaged(BlockPos pos) {
        return getRegionFor(pos) != null;
    }

    // --- Registration ---

    public void register(WorldRegion region) {
        if (regions.addIfAbsent(region)) layoutVersion.incrementAndGet();
    }

    public void unregister(WorldRegion region) {
        if (regions.remove(region)) layoutVersion.incrementAndGet();
    }

    /** Changes whenever the set of active regions changes (split/merge). */
    public int getLayoutVersion() {
        return layoutVersion.get();
    }

    public Collection<WorldRegion> getAllRegions() {
        return Collections.unmodifiableList(regions);
    }

    /** Allocates the next unique region ID. */
    public int nextId() {
        return idCounter.getAndIncrement();
    }
}
