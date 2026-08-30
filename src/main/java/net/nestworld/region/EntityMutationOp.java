package net.nestworld.region;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;

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
 * reference points at may be foreign to the APPLYING thread too), but this exact shape has
 * run clean through 44,000+ real ENTITY_IMPACT messages under heavy cross-region TNT-flood
 * testing on both a repro world and a 419-mod real pack with zero observed corruption or
 * exceptions. Empirical precedent in THIS codebase over-rides the abstract caution here;
 * revisit with UUID-based reconstruction if {@link EntityOwnershipGuard} or soak testing
 * ever actually surfaces an issue traceable to it.
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
}
