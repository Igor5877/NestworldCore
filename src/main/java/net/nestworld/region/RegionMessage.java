package net.nestworld.region;

import java.util.UUID;

/**
 * A tagged cross-region message, per the Stage 4 architecture decision (see
 * {@code docs/LOCAL_TICK_STAGE4.md}): every cross-region interaction should
 * carry {@code sourceRegion}/{@code destinationRegion}/{@code logicalTick}/
 * {@code epoch}/{@code messageType}/{@code payload} even while the server
 * still runs one shared global tick, so a future Stage 5 (independent
 * per-region local time) migration does not require reworking the whole
 * cross-region protocol from scratch.
 *
 * <p>{@code epoch} is currently always 0 — reserved for a future
 * synchronization-epoch counter (Stage 5); every region shares the same
 * global logical tick today, so it carries no information yet. {@code
 * logicalTick} is {@code MinecraftServer.tickCount} at the moment the
 * message was created — see the Stage 4 decision doc for why this MUST stay
 * a single shared counter for now (LevelTicks, ticket expiry, and
 * inter-dimension sequencing all key off it).
 *
 * <p>{@code sourceRegion} may be null (e.g. an entity that had no owner yet —
 * first assignment on spawn, not a transfer between two owners).
 * {@code destinationRegion} may also be null: some messages (e.g. a wire
 * update whose network left its source region) have no single destination
 * region and are applied on the main thread instead — vanilla's own
 * propagation figures out which positions/regions are actually affected.
 *
 * @param <T> payload type — {@code UUID} for {@link Type#ENTITY_TRANSFER},
 *            {@code BlockPos} for {@link Type#WIRE_UPDATE}, {@code
 *            net.minecraft.world.level.Explosion.NestworldExplosionBatch}
 *            for {@link Type#EXPLOSION_APPLY}, {@link BlockWrite} for
 *            {@link Type#BLOCK_WRITE}.
 */
public record RegionMessage<T>(
        WorldRegion sourceRegion,
        WorldRegion destinationRegion,
        long logicalTick,
        int epoch,
        Type messageType,
        T payload,
        long createdNanos,
        long auditId
) {
    public enum Type {
        /** An entity's owning region changed (border crossing or initial assignment). */
        ENTITY_TRANSFER,
        /** A redstone wire update whose network reached outside its source region's bounds. */
        WIRE_UPDATE,
        /** A subset of an explosion's destroyed/ignited positions that belong to a
         *  DIFFERENT region than the one that triggered the explosion — see the Stage 3
         *  "entity-triggered block-write race" finding in docs/LOCAL_TICK_STAGE4.md. */
        EXPLOSION_APPLY,
        /** A single generic Level.setBlock() call (covers removeBlock/destroyBlock/
         *  setBlockAndUpdate, which all funnel through it) made by mob-AI or mod code
         *  directly, for a position outside the calling region thread's own bounds — the
         *  Stage 3 EXTENSION beyond Explosion, see Level.setBlock()'s NestWorld comment. */
        BLOCK_WRITE,
        /** An explosion's damage+knockback effect on ONE entity owned by a DIFFERENT
         *  region than the one that triggered the explosion — found during the Stage 5
         *  "Entity Safety Layer" audit (docs/LOCAL_TICK_STAGE4.md): a PRE-EXISTING race
         *  independent of Stage 5, since {@code Explosion.explode()}'s entity-damage loop
         *  reads/mutates entity fields with zero ownership check, and all regions already
         *  tick in parallel today. Applied on the OWNING region's OWN thread (not main —
         *  unlike EXPLOSION_APPLY/BLOCK_WRITE), matching this project's single-writer
         *  entity-ownership discipline: the entity's own position (needed even just to
         *  compute knockback DIRECTION) is only ever safely read by its owner. */
        ENTITY_IMPACT,
        /** A vanilla entity-collision push-apart ({@code Entity.push(Entity)}, reached via
         *  {@code LivingEntity.doPush}) directed at an entity owned by a DIFFERENT region —
         *  found by the {@link EntityOwnershipGuard} invariant test on real ATM9 load
         *  (293 violations, zero false positives). Same class of PRE-EXISTING race as
         *  ENTITY_IMPACT: vanilla's push(Entity) mutates BOTH sides' deltaMovement
         *  directly, so a currently-ticking region thread was calling {@code setDeltaMovement}
         *  on a foreign entity whenever two entities near a region border collided. Applied
         *  on the OWNING region's own thread via the entity's own {@code push(dx,dy,dz)}. */
        ENTITY_PUSH,
        /** A simple, atomic foreign-entity mutation ({@link EntityMutationOp}: damage, add
         *  effect, or ignite) — the generic Bucket A primitive (docs/LOCAL_TICK_STAGE4.md,
         *  "Entity Safety Layer, two generic primitives") replacing bespoke message types
         *  for the AOE-style "scan radius, mutate every entity found" call sites the audit
         *  found beyond Explosion/push (ThrownPotion, EvokerFangs, AreaEffectCloud, Guardian,
         *  Axolotl, Ravager.roar(), ...). Applied on the OWNING region's own thread. */
        ENTITY_MUTATE
    }

    /** Payload for {@link Type#BLOCK_WRITE} — the arguments {@code Level.setBlock()}
     *  was called with, replayed verbatim on the destination region's behalf once
     *  drained on the main thread (same barrier-safe point Explosion's batches use). */
    public record BlockWrite(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState newState,
                              int flags, int recursionLeft) {
    }

    public static RegionMessage<UUID> entityTransfer(WorldRegion source, WorldRegion destination,
                                                       long logicalTick, UUID entityId) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.ENTITY_TRANSFER, entityId, System.nanoTime(), MailboxAudit.nextId());
    }

    public static RegionMessage<net.minecraft.core.BlockPos> wireUpdate(
            WorldRegion source, long logicalTick, net.minecraft.core.BlockPos pos) {
        // No single destination region — vanilla's own wire propagation on main
        // determines which position(s)/region(s) the update actually reaches. Not
        // audited: MailboxAudit's exactly-once claim only makes sense for messages
        // with a single, well-defined apply site (the other 3 types); a wire update
        // fans out into vanilla's own propagation with no discrete "applied" event.
        return new RegionMessage<>(source, null, logicalTick, 0,
                Type.WIRE_UPDATE, pos, System.nanoTime(), MailboxAudit.nextId());
    }

    public static RegionMessage<net.minecraft.world.level.Explosion.NestworldExplosionBatch> explosionApply(
            WorldRegion source, WorldRegion destination, long logicalTick,
            net.minecraft.world.level.Explosion.NestworldExplosionBatch batch) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.EXPLOSION_APPLY, batch, System.nanoTime(), MailboxAudit.nextId());
    }

    public static RegionMessage<BlockWrite> blockWrite(WorldRegion source, WorldRegion destination,
            long logicalTick, BlockWrite write) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.BLOCK_WRITE, write, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#ENTITY_IMPACT} — the whole triggering {@code Explosion}
     *  (its center/radius/damage-source fields, needed to recompute the effect) plus
     *  which entity to apply it to. Replayed via {@code Explosion
     *  .nestworldApplyEntityImpact(Entity)} — recomputes damage/knockback from scratch
     *  using a safe, same-thread (the owner's) read of the entity's live position,
     *  rather than shipping a pre-computed result across threads. */
    public record EntityImpact(net.minecraft.world.level.Explosion explosion, UUID entityId) {}

    public static RegionMessage<EntityImpact> entityImpact(WorldRegion source, WorldRegion destination,
            long logicalTick, EntityImpact impact) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.ENTITY_IMPACT, impact, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#ENTITY_PUSH} — a raw velocity delta to apply via the
     *  target entity's own {@code push(dx,dy,dz)}, already fully computed by the sender
     *  using a safe snapshot read of the foreign entity's position; the owning thread
     *  only re-checks {@code isVehicle()}/{@code isPushable()} (cheap, and only safe to
     *  read on the owning thread) before applying. */
    public record EntityPush(UUID entityId, double dx, double dy, double dz) {}

    public static RegionMessage<EntityPush> entityPush(WorldRegion source, WorldRegion destination,
            long logicalTick, EntityPush push) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.ENTITY_PUSH, push, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#ENTITY_MUTATE}. */
    public record EntityMutate(UUID entityId, EntityMutationOp op) {}

    public static RegionMessage<EntityMutate> entityMutate(WorldRegion source, WorldRegion destination,
            long logicalTick, EntityMutate mutate) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.ENTITY_MUTATE, mutate, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Wall-clock age of this message in nanoseconds — Stage 4's "mailbox latency" metric. */
    public long ageNanos() {
        return System.nanoTime() - createdNanos;
    }
}
