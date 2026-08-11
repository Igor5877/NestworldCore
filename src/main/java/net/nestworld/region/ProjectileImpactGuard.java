package net.nestworld.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Entity Safety Layer follow-up, task #55 (docs/LOCAL_TICK_STAGE4.md): the
 * {@code AbstractArrow}/{@code Projectile} hit-chain (highest raw call frequency in
 * "Bucket A" — every in-flight projectile, every tick) chains {@code hurt()} -> conditional
 * {@code push()}/{@code setArrowCount()}/enchantment procs/criteria triggers, all gated on
 * {@code hurt()}'s return value — too tightly coupled to fit the simple {@link
 * EntityMutationHelper} redirect-via-message primitive without losing that conditional
 * structure (a full bespoke Explosion-style replay was the original plan for this task).
 *
 * <p>Turned out not to need one: every projectile subtype's hit-detection funnels through
 * one of four small dispatch points (in {@code AbstractArrow}, {@code ThrowableProjectile},
 * {@code FishingHook}, {@code AbstractHurtingProjectile}) that compute a {@link HitResult}
 * and then call {@code onHit(hitresult)} to apply it. Checking ownership THERE — same
 * "safe-skip, retry next tick" pattern already used for the Goal-based melee-attack fix
 * (task #53) — is much simpler and lower-risk than a full replay: skip calling
 * {@code onHit()} for a foreign-owned target this tick; the projectile keeps flying/embeds
 * nowhere, and either the target's ownership settles (via {@code BoundaryEntityTransfer})
 * or the projectile flies past by the next tick. No projectile-specific state needs to be
 * carried across a thread boundary at all.
 */
public final class ProjectileImpactGuard {
    private ProjectileImpactGuard() {}

    /**
     * Returns {@code true} if {@code hitresult} is an entity hit whose target is owned by a
     * DIFFERENT region than the calling (projectile-owning) region thread — the caller should
     * skip calling {@code onHit(hitresult)} this tick. Returns {@code false} (proceed as
     * normal) for block hits, misses, main-thread calls, or a same-region/unowned target.
     */
    public static boolean isForeignHit(Entity projectile, HitResult hitresult) {
        if (!(hitresult instanceof EntityHitResult entityHit)) {
            return false;
        }
        if (!NestworldRegionSystem.isInitialised()
                || !(Thread.currentThread() instanceof RegionThread rt)
                || projectile.level() != NestworldRegionSystem.get().getOverworld()) {
            return false;
        }
        WorldRegion self = rt.getRegion();
        Entity target = entityHit.getEntity();
        if (self.ownsEntity(target.getUUID())) {
            return false;
        }
        WorldRegion owner = NestworldRegionSystem.get().getGrid().findOwningRegion(target.getUUID());
        return owner != null && owner != self;
    }
}
