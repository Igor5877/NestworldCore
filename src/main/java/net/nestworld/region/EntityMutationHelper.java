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
            return false;
        }
        selfRegion.nestworldSendOrQueue(owner, RegionMessage.entityMutate(selfRegion, owner,
                self.level().getServer().getTickCount(),
                new RegionMessage.EntityMutate(target.getUUID(), op)));
        return true;
    }
}
