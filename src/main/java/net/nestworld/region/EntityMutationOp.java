package net.nestworld.region;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Entity Safety Layer, "Bucket A" generic mutation primitive (docs/LOCAL_TICK_STAGE4.md):
 * a closed set of simple, atomic foreign-entity mutations, replacing bespoke per-site
 * {@code RegionMessage.Type} values for every new AOE-style "scan radius, then hurt/
 * addEffect/ignite every entity found" call site the Entity Safety Layer audit found
 * (ThrownPotion, EvokerFangs, AreaEffectCloud/DragonFireball, Guardian, Axolotl,
 * Ravager.roar(), ...). {@code Push} deliberately has its OWN dedicated, already-shipped
 * {@link RegionMessage.Type#ENTITY_PUSH} — not migrated here to avoid touching validated
 * code without reason. Anything that doesn't fit this closed shape (needs more than one
 * method call, or needs to recompute from scratch like an explosion) gets its own bespoke
 * message type instead, same as {@link RegionMessage.Type#ENTITY_IMPACT} already does.
 *
 * <p>{@code Damage} carries a full {@link DamageSource} object (immutable — all its fields
 * are final) rather than reconstructing one from primitive data on the applying thread.
 * This matches the ALREADY-shipped, extensively-validated precedent of {@code ENTITY_IMPACT}
 * (whose payload is the whole {@code Explosion} object, itself holding a live {@code source}
 * Entity field) — Gemini's independent review flagged carrying live Entity references inside
 * a message payload as a theoretical residual read-hazard (whoever the payload's entity
 * reference points at may be foreign to the APPLYING thread too). <b>Confirmed cross-owner
 * write, currently rare in practice</b> — see the API-1A.3 gate audit
 * ({@code docs/NESTWORLD_API_ARCHITECTURE.md}) for the direct-trace proof: {@code Damage}'s
 * {@code source.getEntity()} ends up passed to {@code awardKillScore}
 * ({@code LivingEntity.java:1309} → {@code ServerPlayer.java:637-654}), which mutates the
 * CAUSING entity's own scoreboard/stat/criteria state on the TARGET's owner thread, with no
 * ownership check on the causing entity. The 44,000+ clean ENTITY_IMPACT runs under
 * cross-region TNT-flood testing reflect that attacker/target region-split is an uncommon
 * runtime condition in that workload, not that this write is absent or safe — do not read
 * that empirical precedent as having ruled this out. Revisit with UUID-based reconstruction
 * plus a bounded second-hop reroute to the causing entity's actual owner (see
 * {@link EntityOwnershipGuard}, and API-1A.3's write-up) rather than treating this as merely
 * theoretical.
 */
public sealed interface EntityMutationOp {
    record Damage(DamageSource source, float amount) implements EntityMutationOp {}
    /** {@code source} mirrors {@code LivingEntity.addEffect(MobEffectInstance, Entity)}'s
     *  attribution parameter (nullable) — same live-Entity-reference precedent as {@link Damage}. */
    record AddEffect(MobEffectInstance effect, @Nullable Entity source) implements EntityMutationOp {}
    record Ignite(int seconds) implements EntityMutationOp {}
    /** Bucket A follow-up (ThrownPotion.applyWater): {@code Entity.extinguishFire()}. */
    record ExtinguishFire() implements EntityMutationOp {}
    /** Full-core-audit follow-up (2026-08-27): {@code LivingEntity.knockback(double,double,double)}
     *  -- combat knockback (Player.attack() melee/sweep, projectile impacts, boss AOE), distinct
     *  from {@link EntityPushHelper}'s physics-collision push (different math, different call
     *  shape) and previously uncovered by any existing helper. */
    record Knockback(double strength, double x, double z) implements EntityMutationOp {}
    /** Phase 9.2 (EntityMutationDispatcher, docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md §4/§6):
     *  {@code Entity.kill()} -- vanilla's own "instant death" path (sets health to 0 for
     *  LivingEntity, or removes directly for non-living), distinct from {@code Damage} with a
     *  huge amount since it bypasses armor/enchantment/absorption calculation entirely, matching
     *  vanilla's own semantics for e.g. {@code /kill}. */
    record Kill() implements EntityMutationOp {}
    /** Phase 9.2 follow-up: {@code Entity.remove(RemovalReason.DISCARDED)} -- silent removal, no
     *  death event/drops/advancement triggers (vanilla's own distinction between "killed" and
     *  "discarded" -- see {@code Entity.RemovalReason}). */
    record Discard() implements EntityMutationOp {}
    /** API-1A.6: {@code Entity.setDeltaMovement(Vec3)} -- backs the public Mod API primitive
     *  {@code net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity, Vec3)}
     *  (docs/contracts/SetVelocityPrimitive.md). {@code Vec3} is genuinely immutable (three
     *  final doubles, no back-references) -- unlike {@link Damage}'s {@code DamageSource}, it
     *  carries zero live-reference hazard, confirmed during that primitive's contract audit;
     *  no UUID-based reconstruction step is needed for this payload.
     *  <p>API-1A.8: {@code intentVersion} is assigned at the public API call site (submission
     *  time, see {@code net.nestworld.modapi.entity.SetVelocityIntentTracker}) and checked
     *  immediately before the actual mutation in {@code EntityMutationDispatcher.applyDirect} --
     *  an older intent that arrives after a newer one already applied is silently dropped
     *  (LATEST_INTENT_WINS), not applied out of order over the newer state. */
    record SetVelocity(Vec3 velocity, long intentVersion) implements EntityMutationOp {}
}
