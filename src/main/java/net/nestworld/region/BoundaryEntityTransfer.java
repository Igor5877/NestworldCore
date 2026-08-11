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

    /**
     * Event-driven ownership ({@link NestworldTuning#EVENT_DRIVEN_OWNERSHIP}): entities whose
     * section changed since the last scan, marked by the section-move callback. Written by
     * region threads during the tick, drained on the main thread after the barrier — the tick
     * barrier separates the two, so there is no concurrent access (the concurrent queue is
     * belt-and-braces). A periodic full scan ({@link #eventFullPassCounter}) is the safety net.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Entity> dirtyEntities =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private int eventFullPassCounter = 0;

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

        // Event-driven path: process only entities whose section changed since the last scan
        // (marked via markDirty from the section-move callback). A periodic full scan and any
        // layout change still force the complete O(all) pass as a safety net, so a missed mark
        // self-heals within OWNERSHIP_FULL_PASS_TICKS and can never cause a double-tick.
        boolean doFullScan = fullPass || !net.nestworld.region.NestworldTuning.EVENT_DRIVEN_OWNERSHIP
                || (++eventFullPassCounter >= net.nestworld.region.NestworldTuning.OWNERSHIP_FULL_PASS_TICKS);
        if (doFullScan) {
            eventFullPassCounter = 0;
            dirtyEntities.clear(); // the full scan supersedes any pending marks
            for (Entity entity : level.getAllEntities()) {
                processEntity(entity, fullPass);
            }
        } else {
            // Drain the dirty set; dedup so an entity that changed section several times this
            // tick is processed once. processEntity re-checks position, so a mark for an entity
            // that ended up in the same chunk (settled) is a cheap no-op.
            Entity e;
            it.unimi.dsi.fastutil.ints.IntOpenHashSet seen = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            while ((e = dirtyEntities.poll()) != null) {
                if (seen.add(e.getId())) processEntity(e, false);
            }
        }

        // Stage 2 (docs/LOCAL_TICK_STAGE4.md): apply every ENTITY_TRANSFER message posted
        // this pass. Still runs synchronously in the same tick, at the same barrier-safe
        // point ownership changes have always been applied at — this does not change WHEN
        // ownership moves, only formalizes the interaction as a tagged message instead of
        // an ad-hoc direct field mutation, per the Stage 4 decision's Tier-2 shape.
        for (WorldRegion region : grid.getAllRegions()) {
            for (RegionMessage<?> msg : region.nestworldDrainMailbox(RegionMessage.Type.ENTITY_TRANSFER)) {
                region.addEntity((UUID) msg.payload());
                MailboxAudit.recordApplied(msg.auditId(), region, RegionMessage.Type.ENTITY_TRANSFER);
            }
        }
    }

    /** Per-entity ownership check, shared by the full scan and the event-driven drain. */
    private void processEntity(Entity entity, boolean fullPass) {
        if (entity.isRemoved()) return;
        // Players are ticked on the main thread (their position is mutated
        // by the network thread; region-thread ticking causes races that
        // manifest as "moved too quickly" rubber-banding).
        if (entity instanceof net.minecraft.world.entity.player.Player) return;

        UUID uuid = entity.getUUID();
        // Pinned types tick on the main thread (mod compat): keep them out of
        // every region's owned set so no region thread touches them. If a type
        // was pinned while already owned, release it here (one-time).
        if (!pins.isEmpty() && pins.isPinned(entity.getType())) {
            WorldRegion owner = findOwner(uuid);
            if (owner != null) owner.removeEntity(uuid);
            inTransfer.remove(uuid);
            lastChunkKey.remove(entity.getId());
            return;
        }
        if (inTransfer.containsKey(uuid)) {
            // Transfer was initiated last tick; it is now safe to clear the guard
            inTransfer.remove(uuid);
            return;
        }

        // NestWorld: Entity Safety Layer (docs/LOCAL_TICK_STAGE4.md) — findOwner only
        // checks ownedEntityIds set membership (no live Entity field read), so it's
        // always safe; moved earlier so we know WHICH region's snapshot to consult
        // below before touching any position-dependent field.
        WorldRegion currentOwner = findOwner(uuid);

        // NestWorld: read position via the OWNING region's own published
        // EntitySnapshot instead of the live Entity object — under free-running
        // regions, that region's own thread could be concurrently mutating these
        // exact fields (setPos/move during its own tick); the snapshot is a
        // detached copy, safe from any thread, published once per the owning
        // region's own tick (same Tier-1 pattern the ghost-zone BlockEntity
        // snapshot already uses). An UNOWNED entity (not yet assigned — never
        // true for a type other than Player, which is already excluded above,
        // except in the brief window before its first assignment) has no region
        // thread racing it yet, so a direct live read remains correct and safe.
        double ex, ey, ez, exo, eyo, ezo;
        if (currentOwner != null) {
            WorldRegion.EntitySnapshot snap = currentOwner.nestworldGetEntitySnapshot().get(uuid);
            if (snap == null || snap.removed()) {
                // Not in this cycle's snapshot yet (just transferred in, or the owner
                // hasn't published since) — skip this pass rather than fall back to a
                // live read (that would silently reintroduce the hazard). The periodic
                // full-pass safety net and the next cycle's snapshot both self-heal
                // this within one tick, same "up to one cycle stale" tolerance the
                // ghost-zone snapshot already accepts.
                return;
            }
            ex = snap.x(); ey = snap.y(); ez = snap.z();
            exo = snap.xo(); eyo = snap.yo(); ezo = snap.zo();
        } else {
            ex = entity.getX(); ey = entity.getY(); ez = entity.getZ();
            exo = entity.xo; eyo = entity.yo; ezo = entity.zo;
        }

        // NestWorld: an entity that did not move this tick cannot have changed chunk, so it
        // keeps its current owner. Skip the ChunkPos allocation + toLong + map work for
        // the (common, at high entity counts) stationary case — but only once it has been
        // assigned (lastChunkKey set, sentinel default = NO_CHUNK), so a spawned-stationary
        // entity still gets its initial owner on the pass that first sees it. Same no-movement
        // shortcut as the tracker (NestworldTuning.TRACKER_SPATIAL_CULL); also cuts the ChunkPos
        // allocation churn that pressures GC under big entity piles (e.g. 150k TNT).
        if (!fullPass && net.nestworld.region.NestworldTuning.TRACKER_SPATIAL_CULL
                && ex == exo && ey == eyo && ez == ezo
                && lastChunkKey.get(entity.getId()) != NO_CHUNK) {
            return;
        }

        ChunkPos currentChunk = new ChunkPos(net.minecraft.util.Mth.floor(ex) >> 4, net.minecraft.util.Mth.floor(ez) >> 4);
        long chunkKey = currentChunk.toLong();
        if (!fullPass) {
            // One int-keyed lookup; sentinel default means absent != any real chunk.
            if (lastChunkKey.get(entity.getId()) == chunkKey) return; // same chunk, same owner
        }
        lastChunkKey.put(entity.getId(), chunkKey);

        WorldRegion correctRegion = grid.getRegionFor(currentChunk);

        if (correctRegion == null || correctRegion == currentOwner) return;

        // Mark as transferring to skip double-tick for one tick
        inTransfer.put(uuid, Boolean.TRUE);

        // Stage 2: remove from the old owner immediately (same as before — this thread
        // already owns that mutation at this safe point), but hand the new ownership off
        // as a posted RegionMessage rather than calling correctRegion.addEntity() directly.
        // Applied at the end of checkAndReassign() via nestworldDrainMailbox(), still this
        // same tick — see the Stage 4 decision doc for why timing is unchanged for now.
        if (currentOwner != null) currentOwner.removeEntity(uuid);
        correctRegion.nestworldPostMessage(RegionMessage.entityTransfer(
                currentOwner, correctRegion, level.getServer().getTickCount(), uuid));

        LOGGER.debug("Entity {} moved from {} to {}", uuid,
                currentOwner != null ? currentOwner.getId() : "none", correctRegion.getId());
    }

    /**
     * Marks an entity as having changed section (called from the entity section-move callback)
     * so the next {@link #checkAndReassign} re-checks its region ownership without scanning all
     * entities. No-op unless {@link NestworldTuning#EVENT_DRIVEN_OWNERSHIP} is on. Safe to call
     * from region threads: marks are drained on the main thread after the tick barrier.
     */
    public void markDirty(Entity entity) {
        if (net.nestworld.region.NestworldTuning.EVENT_DRIVEN_OWNERSHIP && entity != null) {
            dirtyEntities.add(entity);
        }
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
