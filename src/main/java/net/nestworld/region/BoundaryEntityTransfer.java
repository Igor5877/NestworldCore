package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects when an entity has moved into a different region and
 * reassigns ownership between RegionThreads atomically.
 *
 * <p>Called from the main thread after every tick barrier,
 * so no region thread is ticking while reassignments happen.
 *
 * <p>The entity itself is never serialised or respawned — it stays in
 * the same ServerLevel.  Only the internal ownership record changes.
 * From the entity's perspective nothing happens; the next tick it is
 * simply ticked by a different thread.
 *
 * <p>A {@code TRANSFERRING} guard (held in {@link #inTransfer}) prevents
 * double-ticking in the one-tick window between the position change and
 * the ownership record update.
 */
public class BoundaryEntityTransfer {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/EntityTransfer");

    private final ServerLevel level;
    private final WorldGrid grid;
    private final NestworldPins pins;

    /** UUIDs currently mid-transfer (skip ticking until transfer completes). */
    private final ConcurrentHashMap<UUID, Boolean> inTransfer = new ConcurrentHashMap<>();

    /**
     * Last seen chunk (ChunkPos.toLong) per entity. An entity whose chunk has
     * not changed since the last scan cannot have crossed a region border, so
     * the per-entity owner lookup is skipped entirely — profiling showed the
     * full scan costing ~1.6 ms/tick at 2400 mostly-stationary entities.
     * Main-thread only.
     */
    // Keyed by entity int id (not UUID) — this is looked up for every loaded entity
    // every tick; an int hash avoids the UUID hashing + Long boxing that dominated the
    // scan at high entity counts. Sentinel default distinguishes "absent" from chunk 0.
    private static final long NO_CHUNK = Long.MIN_VALUE;
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap lastChunkKey =
            new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    { lastChunkKey.defaultReturnValue(NO_CHUNK); }
    /** Region layout as of the last scan; a change forces one full pass. */
    private int lastLayoutVersion = -1;
    private int pruneInterval = 0;

    public BoundaryEntityTransfer(ServerLevel level, WorldGrid grid, NestworldPins pins) {
        this.level = level;
        this.grid = grid;
        this.pins = pins;
    }

    // -----------------------------------------------------------------------
    // Called from main thread after every tick barrier
    // -----------------------------------------------------------------------

    /**
     * Scans all loaded entities, detects region-boundary crossings, and
     * atomically reassigns ownership from the old region to the new one.
     */
    public void checkAndReassign() {
        // A split/merge moves ownership wholesale (doSplit hands everything to
        // child A and relies on this scan to sort the rest out), so a layout
        // change invalidates the no-movement shortcut for one full pass.
        int layout = grid.getLayoutVersion();
        boolean fullPass = layout != lastLayoutVersion;
        lastLayoutVersion = layout;

        // Prune ownership records of entities that despawned or unloaded —
        // without this the owned sets grow forever as the player explores.
        // Loaded entities are (re-)assigned in the loop below, so pruning a
        // briefly-unloaded entity is harmless. Once a second is enough; the
        // full per-entity lookup pass is too expensive to run every tick.
        if (++pruneInterval >= 20) {
            pruneInterval = 0;
            for (WorldRegion region : grid.getAllRegions()) {
                region.getOwnedEntityIds().removeIf(uuid -> {
                    Entity e = level.getEntity(uuid);
                    return e == null || e.isRemoved();
                });
            }
            lastChunkKey.keySet().removeIf((int id) -> {
                Entity e = level.getEntity(id);
                return e == null || e.isRemoved();
            });
        }

        for (Entity entity : level.getAllEntities()) {
            if (entity.isRemoved()) continue;
            // Players are ticked on the main thread (their position is mutated
            // by the network thread; region-thread ticking causes races that
            // manifest as "moved too quickly" rubber-banding).
            if (entity instanceof net.minecraft.world.entity.player.Player) continue;

            UUID uuid = entity.getUUID();
            // Pinned types tick on the main thread (mod compat): keep them out of
            // every region's owned set so no region thread touches them. If a type
            // was pinned while already owned, release it here (one-time).
            if (!pins.isEmpty() && pins.isPinned(entity.getType())) {
                WorldRegion owner = findOwner(uuid);
                if (owner != null) owner.removeEntity(uuid);
                inTransfer.remove(uuid);
                lastChunkKey.remove(entity.getId());
                continue;
            }
            if (inTransfer.containsKey(uuid)) {
                // Transfer was initiated last tick; it is now safe to clear the guard
                inTransfer.remove(uuid);
                continue;
            }

            ChunkPos currentChunk = entity.chunkPosition();
            long chunkKey = currentChunk.toLong();
            if (!fullPass) {
                // One int-keyed lookup; sentinel default means absent != any real chunk.
                if (lastChunkKey.get(entity.getId()) == chunkKey) continue; // same chunk, same owner
            }
            lastChunkKey.put(entity.getId(), chunkKey);

            WorldRegion currentOwner = findOwner(uuid);
            WorldRegion correctRegion = grid.getRegionFor(currentChunk);

            if (correctRegion == null || correctRegion == currentOwner) continue;

            // Mark as transferring to skip double-tick for one tick
            inTransfer.put(uuid, Boolean.TRUE);

            if (currentOwner != null) currentOwner.removeEntity(uuid);
            correctRegion.addEntity(uuid);

            LOGGER.debug("Entity {} moved from {} to {}", uuid,
                    currentOwner != null ? currentOwner.getId() : "none", correctRegion.getId());
        }
    }

    /**
     * Assigns an entity to the region that covers its current position.
     * Call this when an entity first loads or teleports across regions.
     */
    public void assignInitial(Entity entity) {
        WorldRegion region = grid.getRegionFor(entity.blockPosition());
        if (region != null) region.addEntity(entity.getUUID());
    }

    /** Removes an entity from whichever region currently owns it (e.g. on entity removal). */
    public void unassign(Entity entity) {
        UUID uuid = entity.getUUID();
        inTransfer.remove(uuid);
        lastChunkKey.remove(entity.getId());
        WorldRegion owner = findOwner(uuid);
        if (owner != null) owner.removeEntity(uuid);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorldRegion findOwner(UUID uuid) {
        for (WorldRegion r : grid.getAllRegions()) {
            if (r.ownsEntity(uuid)) return r;
        }
        return null;
    }
}
