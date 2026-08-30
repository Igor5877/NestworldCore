package net.nestworld.region;

import net.minecraft.world.entity.Entity;

/**
 * Entity Safety Layer "Bucket A" generic primitive (docs/LOCAL_TICK_STAGE4.md): the
 * ownership-check-then-message shared shape, same pattern as {@link EntityPushHelper},
 * used at every AOE-style "scan radius, mutate every entity found" call site the audit
 * found (ThrownPotion, EvokerFangs, AreaEffectCloud, Guardian, Axolotl, Ravager.roar()).
 *
 * @see RegionMessage.Type#ENTITY_MUTATE
 */
public final class EntityMutationHelper {
    private EntityMutationHelper() {}

    /**
     * Call in place of a direct mutation (hurt/addEffect/setSecondsOnFire) on {@code target}.
     * Returns {@code true} if {@code target} is foreign-owned and the mutation was redirected
     * via message — the caller should skip its own original mutation call. Returns
     * {@code false} when not foreign-owned (or the system isn't sharded) — the caller should
     * fall through to the original direct call unchanged.
     */
    public static boolean redirectIfForeign(Entity self, Entity target, EntityMutationOp op) {
        if (!NestworldRegionSystem.isInitialised()
                || !(Thread.currentThread() instanceof RegionThread rt)
                || !NestworldRegionSystem.get().isManagedLevel(self.level())) {
            return false;
        }
        WorldRegion selfRegion = rt.getRegion();
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(self.level()).getGrid().findOwningRegion(target.getUUID());
        if (owner == null || owner == selfRegion) {
            // Phase 9 (docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md §13): validates every existing
            // redirectIfForeign call site's "safe to mutate directly" belief in one place, instead
            // of instrumenting each of the ~18 call sites individually. No-op unless
            // -Dnestworld.ownershipAssertionsEnabled=true.
            if (owner != null) {
                NestworldOwnershipAssertions.assertEntityOwner(target, "redirectIfForeign.directFallthrough");
            }
            return false;
        }
        selfRegion.nestworldSendOrQueue(owner, RegionMessage.entityMutate(selfRegion, owner,
                self.level().getServer().getTickCount(),
                new RegionMessage.EntityMutate(target.getUUID(), op)));
        return true;
    }

    /**
     * P0 follow-up to the full-core audit (2026-08-27, see project memory
     * player-region-ownership-architecture.md): call in place of a direct mutation on {@code
     * target} when called from a thread that is NOT a {@link RegionThread} (today, in practice,
     * only the main thread -- e.g. {@code Player.attack()}'s melee/sweep hits, since players are
     * deliberately ticked on main, not a region thread, to avoid rubber-banding). Unlike {@link
     * #redirectIfForeign}, this doesn't check "is the caller a region thread" -- callers here are
     * BY DEFINITION not one, so ANY region-owned target is inherently foreign to them (a
     * non-region caller owns no entities of its own to compare against). Returns {@code true} if
     * {@code target} is region-owned and the mutation was redirected via mailbox -- the caller
     * should skip its own original mutation call. Returns {@code false} when the target isn't
     * currently owned by any region (system not sharded, dimension not managed) -- caller should
     * fall through to the original direct call, exactly as safe/unsafe as it always was in that
     * case (nobody else is claiming ownership to race against).
     *
     * <p>Backpressure: unlike {@code redirectIfForeign}'s region-to-region senders, a main-thread
     * caller has no "own region" to hold a pending-retry queue for (see {@code
     * nestworldSendOrQueue}/{@code nestworldOutboundPending} -- those live on, and are drained by,
     * a SENDING region's own next tick, which doesn't exist here). If the destination's mailbox is
     * already over the backpressure threshold, this method deliberately falls back to applying the
     * mutation directly instead of growing an unbounded queue with nothing to drain it (matches
     * this project's own prior incident, "mailbox growing 43,821 -&gt; 139,541" -- see WorldRegion's
     * backpressure comments) -- strictly no worse than the pre-fix behavior in that one rare,
     * already-degraded case, and safe in the overwhelmingly common (not-over-threshold) case.
     */
    public static boolean redirectFromMainThread(Entity target, EntityMutationOp op) {
        if (!NestworldRegionSystem.isInitialised()
                || !NestworldRegionSystem.get().isManagedLevel(target.level())) {
            return false;
        }
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(target.level()).getGrid().findOwningRegion(target.getUUID());
        if (owner == null) {
            return false;
        }
        if (Thread.currentThread() instanceof RegionThread rt && rt.getRegion() == owner) {
            // Already executing on the target's own owning region thread (e.g. a fake-player
            // mod driving Player.attack() from within a region tick, not the real main thread) --
            // direct call is already correct and cheaper, no redirect needed.
            NestworldOwnershipAssertions.assertEntityOwner(target, "redirectFromMainThread.alreadyOnOwner");
            return false;
        }
        if (owner.nestworldMailboxDepthFast() >= NestworldTuning.MAILBOX_BACKPRESSURE_THRESHOLD) {
            // Deliberate, accepted-risk direct mutation despite being foreign -- see this
            // method's own javadoc on backpressure. Intentionally NOT asserted here: this path
            // is a KNOWN, accepted cross-region write, not a bug to catch.
            return false;
        }
        owner.nestworldPostMessage(RegionMessage.entityMutate(null, owner,
                target.level().getServer().getTickCount(),
                new RegionMessage.EntityMutate(target.getUUID(), op)));
        return true;
    }
}
