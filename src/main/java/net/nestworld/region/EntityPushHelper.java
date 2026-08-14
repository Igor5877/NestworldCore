package net.nestworld.region;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Entity Safety Layer follow-up (docs/LOCAL_TICK_STAGE4.md): {@code LivingEntity.doPush}
 * was not the only vanilla call site that does {@code foreign.push(self)} directly —
 * a systematic audit of every {@code getEntities}-family consumer found two more
 * ("orphan") direct-push sites that bypass that fix entirely: {@code Ravager
 * .blockedByShield} (shield-block knockback) and {@code ArmorStand.pushEntities}
 * (minecart-vs-armorstand physics). Same bug, same fix shape — factored out here
 * instead of tripling the inline branch {@code LivingEntity.doPush} already carries,
 * since that inline version is freshly proven Mixin-safe on a 419-mod real pack and
 * these two call sites are new code with no such track record to preserve.
 *
 * @see RegionMessage.Type#ENTITY_PUSH
 */
public final class EntityPushHelper {
    private EntityPushHelper() {}

    /**
     * Call in place of {@code other.push(self)}. Returns {@code true} if the foreign
     * case was detected and handled (message sent or a vanilla guard made it a no-op) —
     * the caller should skip its own original push call. Returns {@code false} when
     * {@code other} is not foreign-owned (or the system isn't sharded) — the caller
     * should fall through to the original {@code other.push(self)} call unchanged.
     */
    public static boolean redirectIfForeign(Entity self, Entity other) {
        if (!NestworldRegionSystem.isInitialised()
                || !(Thread.currentThread() instanceof RegionThread rt)
                || self.level() != NestworldRegionSystem.get().getOverworld()) {
            return false;
        }
        WorldRegion selfRegion = rt.getRegion();
        WorldRegion owner = NestworldRegionSystem.get().getGrid().findOwningRegion(other.getUUID());
        if (owner == null || owner == selfRegion) {
            return false;
        }
        if (self.isPassengerOfSameVehicle(other) || other.noPhysics || self.noPhysics) {
            return true;
        }
        WorldRegion.EntitySnapshot snap = owner.nestworldGetEntitySnapshot().get(other.getUUID());
        if (snap == null || snap.removed()) {
            return true;
        }
        double d0 = self.getX() - snap.x();
        double d1 = self.getZ() - snap.z();
        double d2 = Mth.absMax(d0, d1);
        if (d2 >= 0.01D) {
            d2 = Math.sqrt(d2);
            d0 /= d2;
            d1 /= d2;
            double d3 = Math.min(1.0D / d2, 1.0D);
            d0 *= d3 * 0.05D;
            d1 *= d3 * 0.05D;
            if (!self.isVehicle() && self.isPushable()) {
                self.push(d0, 0.0D, d1);
            }
            selfRegion.nestworldSendOrQueue(owner, RegionMessage.entityPush(selfRegion, owner,
                    self.level().getServer().getTickCount(),
                    new RegionMessage.EntityPush(other.getUUID(), -d0, 0.0D, -d1)));
        }
        return true;
    }
}
