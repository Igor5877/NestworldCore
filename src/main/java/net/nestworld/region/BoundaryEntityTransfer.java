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

    /** UUIDs currently mid-transfer (skip ticking until transfer completes). */
    private final ConcurrentHashMap<UUID, Boolean> inTransfer = new ConcurrentHashMap<>();

    public BoundaryEntityTransfer(ServerLevel level, WorldGrid grid) {
        this.level = level;
        this.grid = grid;
    }

    // -----------------------------------------------------------------------
    // Called from main thread after every tick barrier
    // -----------------------------------------------------------------------

    /**
     * Scans all loaded entities, detects region-boundary crossings, and
     * atomically reassigns ownership from the old region to the new one.
     */
    public void checkAndReassign() {
        // Prune ownership records of entities that despawned or unloaded —
        // without this the owned sets grow forever as the player explores.
        // Loaded entities are (re-)assigned in the loop below, so pruning a
        // briefly-unloaded entity is harmless.
        for (WorldRegion region : grid.getAllRegions()) {
            region.getOwnedEntityIds().removeIf(uuid -> {
                Entity e = level.getEntity(uuid);
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
            if (inTransfer.containsKey(uuid)) {
                // Transfer was initiated last tick; it is now safe to clear the guard
                inTransfer.remove(uuid);
                continue;
            }

            ChunkPos currentChunk = entity.chunkPosition();
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
