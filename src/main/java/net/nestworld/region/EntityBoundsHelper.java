package net.nestworld.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Entity Safety Layer, "EntityGetter/broad-query" audit item (docs/LOCAL_TICK_STAGE4.md):
 * {@link net.minecraft.world.level.EntityGetter#getEntityCollisions} builds a collision
 * shape for every entity a broad-phase query returns by calling {@code entity.getBoundingBox()}
 * directly — including entities owned by a DIFFERENT region than the caller. This runs every
 * tick for every moving entity near others, so cross-region collision checks near a border are
 * common, not rare (same frequency class as the {@code Entity.push} race this session already
 * fixed). Unlike that race, this is a READ, not a mutation — no data corruption, just a possible
 * stale/torn position feeding a collision box (self-correcting next tick) — but the project's
 * own audit criteria explicitly names {@code getBoundingBox} as one of the hazard call sites to
 * classify, so it gets the same fix discipline: substitute {@link WorldRegion.EntitySnapshot}
 * for the foreign entity's position.
 *
 * <p>Width/height are taken from the entity's own (possibly slightly stale, but internally
 * self-consistent — {@code Entity.bb} is a single plain field, not recomputed from position on
 * read) live bounding box; only the POSITION component is replaced with the snapshot's. Entity
 * dimensions change far less often than position and are not the hazard this audit targeted.
 */
public final class EntityBoundsHelper {
    private EntityBoundsHelper() {}

    public static AABB safeBoundingBox(Entity entity) {
        AABB live = entity.getBoundingBox();
        if (!NestworldRegionSystem.isInitialised()
                || !(Thread.currentThread() instanceof RegionThread rt)
                || entity.level() != NestworldRegionSystem.get().getOverworld()) {
            return live;
        }
        WorldRegion self = rt.getRegion();
        if (self.ownsEntity(entity.getUUID())) {
            return live;
        }
        WorldRegion owner = NestworldRegionSystem.get().getGrid().findOwningRegion(entity.getUUID());
        if (owner == null || owner == self) {
            return live;
        }
        WorldRegion.EntitySnapshot snap = owner.nestworldGetEntitySnapshot().get(entity.getUUID());
        if (snap == null || snap.removed()) {
            return live;
        }
        double halfWidth = (live.maxX - live.minX) / 2.0;
        double height = live.maxY - live.minY;
        return new AABB(
                snap.x() - halfWidth, snap.y(), snap.z() - halfWidth,
                snap.x() + halfWidth, snap.y() + height, snap.z() + halfWidth);
    }
}
