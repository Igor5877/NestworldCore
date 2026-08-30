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
        ENTITY_MUTATE,
        /** Phase 11 #31.2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — SHADOW-MODE ONLY at
         *  this stage: a captured, sequenced player-input snapshot (today, movement packets
         *  only), posted to the input's position-resolved owner region for apply-time
         *  ownership/sequence-order validation. Gated behind {@link
         *  NestworldTuning#PLAYER_INPUT_REGION_EXECUTION} (default off). Does NOT mutate the
         *  player or anything else — the old main-thread path keeps applying 100% of real
         *  movement regardless of this message's outcome. Purpose: prove the transport
         *  (sequence ordering, apply-time ownership re-check, bounded queue, reroute-on-stale)
         *  is correct before #31.3 lets anything real depend on it. */
        PLAYER_INPUT,
        /** Phase 11 #31.4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — a whole {@code
         *  Player.attack(target)} invocation, routed to the attacker's position-resolved owner
         *  region for a per-player opt-in set ({@link PlayerAttackExperiment}), gated behind
         *  {@link NestworldTuning#PLAYER_ATTACK_REGION_EXECUTION}. Deliberately a real mailbox
         *  message (not {@code RegionThreadPool.runWorkRound()}, which #31.3's tick experiment
         *  uses) — {@code runWorkRound()}'s free-running-region path posts through a single-slot
         *  {@code AtomicReference} that OVERWRITES a not-yet-drained previous batch (an accepted
         *  tradeoff there because it posts at most once per main tick per region); attacks are
         *  dispatched per PACKET, so two attacks landing in the same region before it drains
         *  would silently lose the first one under that mechanism. A real queue (this) cannot
         *  drop a still-pending attack that way. */
        PLAYER_ATTACK,
        /** Phase 11 #31.4 step 2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — a single
         *  {@code BlockState.use()} block interaction (lever/button/door-class "flip state"
         *  right-click), routed to the block's OWNER-BY-POSITION region for a per-player opt-in
         *  set ({@link PlayerInteractionExperiment}), gated behind {@link
         *  NestworldTuning#PLAYER_INTERACTION_REGION_EXECUTION}. Unlike every other message type
         *  here, this one carries its own synchronous result channel ({@link
         *  BlockInteraction#latch()}/{@link BlockInteraction#resultRef()}) -- the packet
         *  handler's vanilla flow needs the real {@code InteractionResult} back before it can
         *  decide whether to swing the arm, so {@link PlayerInteractionDispatcher#dispatch}
         *  blocks (bounded, with a main-thread fallback on timeout) rather than fire-and-forget
         *  like {@link Type#PLAYER_ATTACK}. The RESULT itself ({@code InteractionResult}, a
         *  plain enum) is immutable/value-like crossing the thread boundary, per this step's
         *  design principle -- no live {@code BlockEntity}/{@code ItemStack} is ever returned. */
        PLAYER_BLOCK_INTERACTION,
        /** Phase 11 #31.4 step 3 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) -- a single
         *  {@code ServerPlayerGameMode.useItem()} call (no-target item use: eating, drinking,
         *  bow draw, ender pearl throw), routed to the ACTING PLAYER's position-resolved owner
         *  region for a per-player opt-in set ({@link PlayerUseItemExperiment}), gated behind
         *  {@link NestworldTuning#PLAYER_USE_ITEM_REGION_EXECUTION}. Same synchronous-result-
         *  channel shape as {@link Type#PLAYER_BLOCK_INTERACTION} ({@code latch}/{@code
         *  resultRef}/{@code claimed} -- see that type's javadoc for why a real mailbox message
         *  is used instead of {@code RegionThreadPool.runWorkRound()}), owner resolved by PLAYER
         *  position (like {@link Type#PLAYER_ATTACK}) rather than a foreign block/entity position
         *  -- this choke point never touches anything but the acting player's own ItemStack/
         *  hunger/health/effects. */
        PLAYER_USE_ITEM,
        /** Phase 11 #31.4 step 4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) -- a single
         *  {@code ItemStack.useOn(UseOnContext)} call (item-on-block: axe strip/scrape/wax-off
         *  first), routed to the CLICKED BLOCK's owner-by-position region for a per-player
         *  opt-in set ({@link PlayerUseItemOnBlockExperiment}), gated behind {@link
         *  NestworldTuning#PLAYER_USE_ITEM_ON_BLOCK_REGION_EXECUTION}. Same synchronous-result-
         *  channel shape as {@link Type#PLAYER_BLOCK_INTERACTION} -- owner resolved by BLOCK
         *  position (like block interaction), not player position (unlike {@link
         *  Type#PLAYER_USE_ITEM}), since this call can mutate a block the acting player doesn't
         *  own. */
        PLAYER_USE_ITEM_ON_BLOCK,
        /** Phase 11 #31.3/#31.4 player-action-ownership hardening (see the #31.4 step4b closing
         *  design discussion) -- a {@code void}, no-result "control" mutation on the ACTING
         *  PLAYER's own use-item state ({@code releaseUsingItem()}/{@code stopUsingItem()}),
         *  routed here ONLY when that player is opted into {@link PlayerTickExperiment} (i.e.
         *  their own {@code LivingEntity.tick()} -- and therefore the use-item countdown and
         *  {@code completeUsingItem()} -- already runs on their owner region thread, not main).
         *
         *  <p>Deliberately a DIFFERENT shape than {@link Type#PLAYER_USE_ITEM}/{@link
         *  Type#PLAYER_BLOCK_INTERACTION}: those are synchronous request-response (the packet
         *  handler needs a real {@code InteractionResult} back, so they carry a {@code latch}/
         *  {@code resultRef}/{@code claimed} and fall back to main-thread execution on timeout).
         *  {@code releaseUsingItem()}/{@code stopUsingItem()} are {@code void} -- the vanilla
         *  packet handlers never read a return value from them today -- so there is nothing to
         *  wait for. More importantly, a timeout-fallback here would be WRONG, not just
         *  unnecessary: if the player's tick is running on their owner region, a main-thread
         *  fallback call would mutate the exact same use-item state the region thread may be
         *  concurrently mid-tick with -- the very race this message type exists to eliminate.
         *  So this is fire-and-forget: post and return, no wait, no fallback path at all. When a
         *  player is NOT in {@link PlayerTickExperiment}, the packet handler skips this entirely
         *  and calls the vanilla method directly on main, exactly as before this type existed.
         *
         *  <p>{@code rerouteHops} mirrors {@link PlayerAttack}'s field -- the player may cross a
         *  region boundary between packet arrival (main thread) and mailbox drain (destination
         *  region's own tick); bounded by {@link NestworldTuning#PLAYER_INPUT_MAX_REROUTE_HOPS}
         *  (reused, same failure mode). Unlike a dropped attack (harmless -- the game state
         *  wasn't left inconsistent), a silently-dropped release/stop would leave the player
         *  stuck mid-{@code useItem} forever, so on an ownership mismatch this REROUTES to the
         *  player's current owner rather than discarding the message. */
        PLAYER_USE_CONTROL,
        /** Phase 11 #31.4 item-on-entity -- a single {@code Entity.interact(Player, InteractionHand)}
         *  (plain right-click, {@code atPos == null}) or {@code Entity.interactAt(Player, Vec3,
         *  InteractionHand)} (precise-hit-location right-click, e.g. armor stand/item frame,
         *  {@code atPos != null}) call, routed to the TARGET ENTITY's owner-by-CURRENT-OWNERSHIP
         *  region -- {@code WorldGrid.findOwningRegion(target.getUUID())}, NOT position -- for a
         *  per-player opt-in set ({@link PlayerEntityInteractExperiment}), gated behind {@link
         *  NestworldTuning#PLAYER_ENTITY_INTERACT_REGION_EXECUTION}.
         *
         *  <p>Deliberately routed by the TARGET's ownership, unlike {@link Type#PLAYER_ATTACK}
         *  (routed by the ATTACKER's position): {@code Player.attack()} pushes every individual
         *  target mutation through {@link EntityMutationHelper}, so running the whole call on the
         *  attacker's region is safe. {@code Animal.mobInteract}/{@code AbstractHorse.mobInteract}/
         *  {@code ArmorStand.interactAt}/{@code ItemFrame.interact} (breeding, taming, milking,
         *  equipment-slot swap, held-item/rotation) all mutate the target entity's fields DIRECTLY
         *  with no such helper -- so the call must run on the thread that actually owns the
         *  target, not the caller.
         *
         *  <p>Same synchronous-result-channel shape as {@link Type#PLAYER_BLOCK_INTERACTION} (the
         *  packet handler's vanilla flow needs the real {@code InteractionResult} back to decide
         *  {@code CriteriaTriggers}/arm-swing) -- {@code latch}/{@code resultRef}/{@code claimed},
         *  bounded wait, main-thread fallback (unmodified vanilla call) on timeout or an ownership
         *  mismatch re-checked fresh at apply time (the target may have transferred to a different
         *  region between dispatch and apply -- caught by re-resolving {@code findOwningRegion}
         *  inside {@code applyQueued}, per the standing "never trust the mailbox destination as
         *  ground truth" discipline). Deliberately does NOT decompose into a closed
         *  {@code EntityMutationOp}-style op set (`BREED`/`TAME`/`MILK`/`FRAME_ROTATE`/...) -- that
         *  would need a new bespoke op for every vanilla AND MODDED interact-branch, a maintenance
         *  trap given 420-mod real-pack usage. Instead the whole vanilla/modded call runs, verbatim
         *  and unmodified, on the correct owner thread -- any mod overriding {@code interact()}/
         *  {@code interactAt()} is automatically covered with zero NestworldCore-side awareness of
         *  its specific mutation shape. */
        PLAYER_ENTITY_INTERACT
    }

    /** Op for {@link Type#PLAYER_USE_CONTROL} -- which void vanilla method to call. */
    public enum UseControlOp { RELEASE, STOP }

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

    /** Payload for {@link Type#ENTITY_MUTATE}. {@code rerouteHops} — Phase 9.2
     *  (EntityMutationDispatcher): 0 for a fresh send; incremented by 1 each time {@link
     *  EntityMutationDispatcher#applyQueued} detects the applying region no longer owns the
     *  target and re-routes to the entity's actual current owner. Bounded by {@link
     *  NestworldTuning#ENTITY_MUTATE_MAX_REROUTE_HOPS} — a target ping-ponging ownership faster
     *  than the mailbox drains must not turn into an unbounded A→B→C→A… loop. */
    public record EntityMutate(UUID entityId, EntityMutationOp op, int rerouteHops) {
        public EntityMutate(UUID entityId, EntityMutationOp op) {
            this(entityId, op, 0);
        }
    }

    public static RegionMessage<EntityMutate> entityMutate(WorldRegion source, WorldRegion destination,
            long logicalTick, EntityMutate mutate) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.ENTITY_MUTATE, mutate, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_INPUT} — a captured, sequenced player-input snapshot.
     *  {@code sequence} is monotonically increasing PER PLAYER, assigned by {@code
     *  PlayerInputDispatcher} on the main thread at capture time (never by the applying region —
     *  sequence order must reflect arrival order at the server, not apply order). {@code
     *  rerouteHops} mirrors {@link EntityMutate}'s field — 0 fresh, incremented on each stale-
     *  ownership re-route, bounded by {@link NestworldTuning#PLAYER_INPUT_MAX_REROUTE_HOPS}. */
    public record PlayerInput(UUID playerId, long sequence, double x, double y, double z,
                               float yRot, float xRot, int rerouteHops) {
        public PlayerInput(UUID playerId, long sequence, double x, double y, double z, float yRot, float xRot) {
            this(playerId, sequence, x, y, z, yRot, xRot, 0);
        }
    }

    public static RegionMessage<PlayerInput> playerInput(WorldRegion source, WorldRegion destination,
            long logicalTick, PlayerInput input) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_INPUT, input, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_ATTACK}. {@code rerouteHops} mirrors {@link
     *  EntityMutate}'s field — the attacker may have crossed a region boundary between dispatch
     *  (main thread, packet arrival) and apply (the destination region's own mailbox drain);
     *  bounded by {@link NestworldTuning#PLAYER_INPUT_MAX_REROUTE_HOPS} (same bound reused, no
     *  need for a third separate constant for what's the same failure mode). */
    public record PlayerAttack(UUID attackerId, UUID targetId, int rerouteHops) {
        public PlayerAttack(UUID attackerId, UUID targetId) {
            this(attackerId, targetId, 0);
        }
    }

    public static RegionMessage<PlayerAttack> playerAttack(WorldRegion source, WorldRegion destination,
            long logicalTick, PlayerAttack attack) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_ATTACK, attack, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_BLOCK_INTERACTION}. {@code latch}/{@code resultRef} are
     *  the synchronous result channel: the applying region thread computes the real {@code
     *  InteractionResult}, stores it in {@code resultRef}, then counts {@code latch} down;
     *  {@code resultRef} left {@code null} at that point (region-side ownership mismatch, missing
     *  player, or an exception) signals "fall back to main-thread execution" to the waiting
     *  dispatcher -- see {@link PlayerInteractionDispatcher#dispatch}.
     *
     *  <p>{@code claimed} exists specifically for the timeout race: if the dispatcher's bounded
     *  wait times out and it falls back to running the interaction directly on main, this
     *  message is STILL sitting in the region's mailbox and will eventually get drained regardless
     *  -- without {@code claimed}, that would call {@code BlockState.use()} a SECOND time (a real
     *  bug found live-testing this: a timed-out lever flip got silently un-flipped moments later
     *  by the delayed region-side apply). Whichever side wins the {@code
     *  compareAndSet(false, true)} race actually executes the interaction; the loser no-ops. */
    public record BlockInteraction(
            net.minecraft.core.BlockPos pos, UUID playerId,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit,
            java.util.concurrent.CountDownLatch latch,
            java.util.concurrent.atomic.AtomicReference<net.minecraft.world.InteractionResult> resultRef,
            java.util.concurrent.atomic.AtomicBoolean claimed) {
    }

    public static RegionMessage<BlockInteraction> blockInteraction(WorldRegion source, WorldRegion destination,
            long logicalTick, BlockInteraction interaction) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_BLOCK_INTERACTION, interaction, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_USE_ITEM}. Same shape/semantics as {@link
     *  BlockInteraction}'s {@code latch}/{@code resultRef}/{@code claimed} -- see that record's
     *  javadoc. {@code hand} identifies which hand's item to use; the region thread re-reads the
     *  player's CURRENT held item fresh at apply time rather than trusting a snapshot passed
     *  here, so it always acts on whatever is actually in that hand when it runs. */
    public record UseItem(
            UUID playerId, net.minecraft.world.InteractionHand hand,
            java.util.concurrent.CountDownLatch latch,
            java.util.concurrent.atomic.AtomicReference<net.minecraft.world.InteractionResult> resultRef,
            java.util.concurrent.atomic.AtomicBoolean claimed) {
    }

    public static RegionMessage<UseItem> useItem(WorldRegion source, WorldRegion destination,
            long logicalTick, UseItem useItem) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_USE_ITEM, useItem, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_USE_ITEM_ON_BLOCK}. Same shape/semantics as {@link
     *  BlockInteraction}'s {@code latch}/{@code resultRef}/{@code claimed} -- see that record's
     *  javadoc. {@code hand}/{@code hit} identify which hand and where/how the click landed --
     *  the region thread re-reads the player's CURRENT held item fresh at apply time rather than
     *  trusting a snapshot passed here. */
    public record UseItemOnBlock(
            UUID playerId, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit,
            java.util.concurrent.CountDownLatch latch,
            java.util.concurrent.atomic.AtomicReference<net.minecraft.world.InteractionResult> resultRef,
            java.util.concurrent.atomic.AtomicBoolean claimed) {
    }

    public static RegionMessage<UseItemOnBlock> useItemOnBlock(WorldRegion source, WorldRegion destination,
            long logicalTick, UseItemOnBlock useItemOnBlock) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_USE_ITEM_ON_BLOCK, useItemOnBlock, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_USE_CONTROL} -- see that type's javadoc. No latch/resultRef/
     *  claimed: fire-and-forget, nothing waits on this. {@code rerouteHops} mirrors {@link
     *  PlayerAttack}'s field -- see the type javadoc for why this reroutes rather than drops. */
    public record UseControl(UUID playerId, UseControlOp op, int rerouteHops) {
        public UseControl(UUID playerId, UseControlOp op) {
            this(playerId, op, 0);
        }
    }

    public static RegionMessage<UseControl> useControl(WorldRegion source, WorldRegion destination,
            long logicalTick, UseControl control) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_USE_CONTROL, control, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Payload for {@link Type#PLAYER_ENTITY_INTERACT}. Same shape/semantics as {@link
     *  BlockInteraction}'s {@code latch}/{@code resultRef}/{@code claimed} -- see that record's
     *  javadoc. {@code atPos} is {@code null} for the plain {@code interact()} branch, non-null
     *  (the exact hit location) for the {@code interactAt()} branch -- the region thread
     *  re-reads the player's CURRENT held item fresh at apply time rather than trusting a
     *  snapshot passed here. {@code rerouteHops} exists for symmetry/future use but this type
     *  does NOT reroute on an ownership mismatch (unlike {@link PlayerAttack}/{@link UseControl})
     *  -- it falls back to main instead, matching every other synchronous-result dispatcher here,
     *  since a stale-then-rerouted interact could apply against a target the player can no longer
     *  even reach/see the current state of by the time a second hop lands. */
    public record EntityInteract(
            UUID playerId, UUID targetId, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.Vec3 atPos,
            java.util.concurrent.CountDownLatch latch,
            java.util.concurrent.atomic.AtomicReference<net.minecraft.world.InteractionResult> resultRef,
            java.util.concurrent.atomic.AtomicBoolean claimed) {
    }

    public static RegionMessage<EntityInteract> entityInteract(WorldRegion source, WorldRegion destination,
            long logicalTick, EntityInteract interact) {
        return new RegionMessage<>(source, destination, logicalTick, 0,
                Type.PLAYER_ENTITY_INTERACT, interact, System.nanoTime(), MailboxAudit.nextId());
    }

    /** Wall-clock age of this message in nanoseconds — Stage 4's "mailbox latency" metric. */
    public long ageNanos() {
        return System.nanoTime() - createdNanos;
    }
}
