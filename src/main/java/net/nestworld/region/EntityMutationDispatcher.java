package net.nestworld.region;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nestworld.modapi.entity.SetVelocityIntentTracker;
import net.nestworld.modapi.inventory.InventorySlotVersionTracker;
import net.nestworld.modapi.inventory.InventoryTargetKey;
import net.nestworld.modapi.inventory.PlayerIncarnationRegistry;

/**
 * Phase 9.2 (docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md §4/§6/§7) — the consolidated entity-
 * mutation dispatch API. First-stage scope, per explicit user instruction (2026-08-27), kept
 * deliberately narrow so this doesn't grow into a universal world-mutation bus:
 * {@code Player.attack()} target mutation, hurt/damage, kill, discard/remove, projectile→entity,
 * AoE→entities — the same surface {@link EntityMutationOp}'s closed set already covers, plus the
 * two new ops ({@link EntityMutationOp.Kill}/{@link EntityMutationOp.Discard}) added alongside
 * this class.
 *
 * <p><b>What this does NOT change</b> (explicit non-goals, same list the user gave): no {@code
 * synchronized(entity)}/entity locks, no Netty changes, no {@code ensureRunningOnSameThread}
 * changes, no Player Region Execution, no handover protocol, no {@code CrossRegionBlockMutation}
 * (block/block-entity mutation stays out of scope — see {@link
 * NestworldRegionSystem#nestworldDeferForeignBlockWrite} and the still-DEFERRED task for that).
 * Existing call sites ({@code Player.attack()}, AoE loops, projectile hits) keep calling {@link
 * EntityMutationHelper#redirectIfForeign}/{@link EntityMutationHelper#redirectFromMainThread}
 * exactly as before — this class does NOT touch a single line of vanilla attack/damage/knockback
 * calculation, critical-hit rolls, enchantment application, sweeping, hurt events, death handling,
 * advancement/stat triggers, Forge events, sound/particles, cooldowns, or invulnerability frames.
 * It only ever sits at the mutation BOUNDARY those call sites already redirect through — the
 * {@code entity.hurt(...)}/{@code entity.kill()}/etc. call itself, nothing upstream of it.
 *
 * <p><b>The mandatory algorithm</b> (user's own diagram, 2026-08-27): {@code dispatch()} resolves
 * the CURRENT owner fresh and either applies directly (same-thread) or posts to that owner's
 * mailbox (foreign). Critically, {@link #applyQueued} — called when a region thread drains a
 * queued {@code ENTITY_MUTATE} message — resolves ownership AGAIN, from scratch, at apply time,
 * rather than trusting the mailbox destination as ground truth. This is the piece that did NOT
 * exist before this class: the pre-existing {@code RegionThread.applyPendingEntityMutations()}
 * applied a drained message unconditionally once dequeued, with no re-check that the applying
 * region still actually owns the target — a message queued to region B, followed by a split/
 * merge/handover moving the target to region C before B drains it, used to apply on B's thread
 * regardless. {@link #applyQueued} now re-routes to the entity's actual current owner instead
 * (best-effort, not loop-proof against a target ping-ponging ownership faster than the mailbox
 * drains — an edge case, not the common path this is built for), or silently NO-OPs if the target
 * was removed/died before the message was applied.
 */
public final class EntityMutationDispatcher {
    private EntityMutationDispatcher() {}

    /**
     * Single entry point for a NEW entity-mutation call site (existing {@code redirectIfForeign}/
     * {@code redirectFromMainThread} call sites are left as-is — see class javadoc). Works from
     * ANY calling thread: auto-detects whether the caller is a region thread (behaves like {@code
     * redirectIfForeign}, no {@code self} parameter needed — the calling region IS {@code
     * Thread.currentThread()}'s region) or not (behaves like {@code redirectFromMainThread}).
     * Applies directly and returns if already on the correct thread or the system isn't sharded;
     * otherwise posts to the owner's mailbox for later application via {@link #applyQueued}.
     */
    public static void dispatch(Entity target, EntityMutationOp op) {
        dispatch(target, op, MailboxAudit.nextId());
    }

    /**
     * API-1A.7 TEMP INSTRUMENTATION (2026-09-29): {@code opId}-carrying overload for the
     * verification pass's phase-trace logging. {@code opId} is diagnostic-only -- it never
     * participates in routing/scheduling/dedup/ordering; the public {@link #dispatch(Entity,
     * EntityMutationOp)} overload above generates a fresh one per call and delegates here.
     */
    static void dispatch(Entity target, EntityMutationOp op, long opId) {
        final org.apache.logging.log4j.Logger DIAG = org.apache.logging.log4j.LogManager.getLogger("NestWorld/DIAG");
        DIAG.info("[API1A7] opId={} phase=SUBMIT op={} target={} callerThread={}",
                opId, op.getClass().getSimpleName(), target.getUUID(), Thread.currentThread().getName());
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(target.level())) {
            DIAG.info("[API1A7] opId={} phase=APPLY owner=unmanaged target={}", opId, target.getUUID());
            ApplyOutcome outcome = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, outcome);
            return;
        }
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(target.level())
                .getGrid().findOwningRegion(target.getUUID());
        DIAG.info("[API1A7] opId={} phase=OWNER_RESOLVED owner={} target={}",
                opId, owner == null ? "null" : owner.getId(), target.getUUID());
        EntityMutationTestHooks.barrierA(op);
        if (owner == null) {
            // Not currently owned by any region (mid-spawn, or a genuinely unsharded moment) --
            // nothing to race against, apply directly, same as EntityMutationHelper's own
            // null-owner handling.
            ApplyOutcome outcome = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={} (owner=null direct)", opId, outcome);
            return;
        }
        if (Thread.currentThread() instanceof RegionThread rt) {
            WorldRegion self = rt.getRegion();
            if (self == owner) {
                NestworldOwnershipAssertions.assertEntityOwner(target, "EntityMutationDispatcher.dispatch.directFallthrough");
                DIAG.info("[API1A7] opId={} phase=APPLY owner={} target={} (same-region direct)", opId, self.getId(), target.getUUID());
                ApplyOutcome outcome = applyDirect(target, op);
                DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, outcome);
                return;
            }
            DIAG.info("[API1A7] opId={} phase=ENQUEUE from={} to={} target={}", opId, self.getId(), owner.getId(), target.getUUID());
            self.nestworldSendOrQueue(owner, RegionMessage.entityMutate(self, owner,
                    target.level().getServer().getTickCount(), new RegionMessage.EntityMutate(target.getUUID(), op, 0, opId)));
            return;
        }
        // Main thread (or any non-region caller) -- same backpressure-fallback precedent as
        // EntityMutationHelper.redirectFromMainThread (no "own region" to hold a sender-side
        // pending-retry queue for; falling back to a direct call is strictly no worse than the
        // pre-dispatcher behavior in that one rare, already-degraded case).
        if (owner.nestworldMailboxDepthFast() >= NestworldTuning.MAILBOX_BACKPRESSURE_THRESHOLD) {
            DIAG.info("[API1A7] opId={} phase=APPLY owner={} target={} (backpressure fallback direct)", opId, owner.getId(), target.getUUID());
            ApplyOutcome outcome = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, outcome);
            return;
        }
        DIAG.info("[API1A7] opId={} phase=ENQUEUE from=main to={} target={}", opId, owner.getId(), target.getUUID());
        owner.nestworldPostMessage(RegionMessage.entityMutate(null, owner,
                target.level().getServer().getTickCount(), new RegionMessage.EntityMutate(target.getUUID(), op, 0, opId)));
    }

    /**
     * Called by {@code RegionThread.applyPendingEntityMutations()} when draining a queued
     * ENTITY_MUTATE message on {@code applyingRegion}'s own thread. Re-validates target existence
     * AND ownership fresh — see class javadoc's "mandatory algorithm" section for why. NO-OP if
     * the target is gone; re-routes (does not apply here, does not drop) if ownership moved on to
     * a different region since the message was sent.
     */
    public static void applyQueued(UUID targetId, EntityMutationOp op, int rerouteHops, WorldRegion applyingRegion, ServerLevel level) {
        applyQueued(targetId, op, rerouteHops, MailboxAudit.nextId(), applyingRegion, level);
    }

    /**
     * API-1A.7 TEMP INSTRUMENTATION (2026-09-29): {@code opId}-carrying overload. Same
     * diagnostic-only status as {@code dispatch}'s overload above -- the 5-arg legacy overload
     * (used by any caller not yet updated to pass an opId) generates a fresh, uncorrelated one.
     */
    static void applyQueued(UUID targetId, EntityMutationOp op, int rerouteHops, long opId, WorldRegion applyingRegion, ServerLevel level) {
        final org.apache.logging.log4j.Logger DIAG = org.apache.logging.log4j.LogManager.getLogger("NestWorld/DIAG");
        EntityMutationTestHooks.barrierB(op);
        Entity target = level.getEntity(targetId);
        if (target == null || target.isRemoved()) {
            DIAG.info("[API1A7] opId={} phase=TERMINAL result=TARGET_NOT_FOUND target={} applyingRegion={} thread={}",
                    opId, targetId, applyingRegion.getId(), Thread.currentThread().getName());
            if (op instanceof EntityMutationOp.SetInventorySlot) InventorySetTelemetry.targetNotFound.incrementAndGet();
            MailboxLifecycleTestHooks.onEntityTerminal(opId, "TARGET_NOT_FOUND");
            return; // target died/despawned/unloaded between send and apply -- NO-OP, not an error
        }
        WorldRegion currentOwner = NestworldRegionSystem.isInitialised() && NestworldRegionSystem.get().isManagedLevel(level)
                ? NestworldRegionSystem.get().getDimensionRegion(level).getGrid().findOwningRegion(targetId)
                : null;
        if (currentOwner != null && currentOwner != applyingRegion) {
            if (rerouteHops >= NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS) {
                // Target is ping-ponging ownership faster than the mailbox drains (or a genuine
                // bug is looping it) -- drop rather than retry forever. Graceful degradation, not
                // silent infinite retry -- same philosophy as this project's backpressure handling.
                DIAG.warn("[API1A7] opId={} phase=TERMINAL result=REROUTE_LIMIT_DROPPED target={} hops={}",
                        opId, targetId, rerouteHops);
                if (op instanceof EntityMutationOp.SetInventorySlot) InventorySetTelemetry.rerouteLimitDropped.incrementAndGet();
                MailboxLifecycleTestHooks.onEntityTerminal(opId, "REROUTE_LIMIT_DROPPED");
                return;
            }
            // Stale: ownership moved on (split/merge/handover) between send and apply. Re-route
            // to the entity's REAL current owner -- that region's own next drain re-validates
            // again from scratch, same as this call did. opId carried through UNCHANGED.
            if (op instanceof EntityMutationOp.SetInventorySlot) InventorySetTelemetry.maxRerouteHop.accumulateAndGet(rerouteHops + 1, Math::max);
            DIAG.info("[API1A7] opId={} phase=REROUTE from={} to={} hop={} target={}",
                    opId, applyingRegion.getId(), currentOwner.getId(), rerouteHops + 1, targetId);
            applyingRegion.nestworldSendOrQueue(currentOwner, RegionMessage.entityMutate(applyingRegion, currentOwner,
                    level.getServer().getTickCount(), new RegionMessage.EntityMutate(targetId, op, rerouteHops + 1, opId)));
            return;
        }
        EntityMutationTestHooks.barrierC(op);
        MailboxLifecycleTestHooks.onEntityApplied(opId, applyingRegion);
        DIAG.info("[API1A7] opId={} phase=APPLY owner={} target={} thread={}",
                opId, applyingRegion.getId(), targetId, Thread.currentThread().getName());
        ApplyOutcome outcome = applyDirect(target, op);
        DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, outcome);
    }

    /** Terminal outcome of {@link #applyDirect}. API-1B.7: split out of the former boolean
     *  return so the phase-trace TERMINAL line distinguishes an incarnation rejection from a
     *  version rejection -- they are different axes and must be observable separately. */
    enum ApplyOutcome { APPLIED, STALE_INTENT_DROPPED, INCARNATION_MISMATCH, TARGET_REMOVED, TARGET_NOT_PLAYER }

    /** The actual mutation switch — same set {@code RegionThread.applyPendingEntityMutations()}
     *  already had, plus {@link EntityMutationOp.Kill}/{@link EntityMutationOp.Discard}. Every op
     *  type other than {@link EntityMutationOp.SetVelocity}/{@link EntityMutationOp.SetInventorySlot}
     *  always mutates and returns {@link ApplyOutcome#APPLIED}. */
    static ApplyOutcome applyDirect(Entity entity, EntityMutationOp op) {
        if (op instanceof EntityMutationOp.Damage d) {
            entity.hurt(d.source(), d.amount());
        } else if (op instanceof EntityMutationOp.AddEffect e) {
            if (entity instanceof LivingEntity le) {
                le.addEffect(e.effect(), e.source());
            }
        } else if (op instanceof EntityMutationOp.Ignite i) {
            entity.setSecondsOnFire(i.seconds());
        } else if (op instanceof EntityMutationOp.ExtinguishFire) {
            entity.extinguishFire();
        } else if (op instanceof EntityMutationOp.Knockback k) {
            if (entity instanceof LivingEntity le) {
                le.knockback(k.strength(), k.x(), k.z());
            }
        } else if (op instanceof EntityMutationOp.Kill) {
            entity.kill();
        } else if (op instanceof EntityMutationOp.Discard) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        } else if (op instanceof EntityMutationOp.SetVelocity sv) {
            SetVelocityIntentTracker.TargetKey key =
                    new SetVelocityIntentTracker.TargetKey(entity.level().dimension(), entity.getUUID());
            if (!SetVelocityIntentTracker.tryAccept(key, sv.intentVersion())) {
                return ApplyOutcome.STALE_INTENT_DROPPED; // a newer intent for this target already won
            }
            entity.setDeltaMovement(sv.velocity());
        } else if (op instanceof EntityMutationOp.SetInventorySlot s) {
            return applySetInventorySlot(entity, s);
        }
        return ApplyOutcome.APPLIED;
    }

    private static ApplyOutcome applySetInventorySlot(Entity entity, EntityMutationOp.SetInventorySlot s) {
        // Per-op trace lines only while a fixture has PER_INTENT on: the first API-1B.7 stress run
        // showed synchronous per-op INFO logging can stall the main thread under load.
        final org.apache.logging.log4j.Logger DIAG = org.apache.logging.log4j.LogManager.getLogger("NestWorld/DIAG");
        if (!(entity instanceof ServerPlayer player)) {
            InventorySetTelemetry.notPlayer.incrementAndGet();
            return ApplyOutcome.TARGET_NOT_PLAYER; // inventory slots only exist on players
        }
        java.util.UUID id = player.getUUID();
        String obj = Integer.toHexString(System.identityHashCode(player));
        // Execution validity = object identity: the object about to be mutated must be the very
        // object the request was stamped against (PlayerIncarnationRegistry's == lookup). No Forge
        // lifecycle event is consulted. Checked BEFORE the version tracker, so a stale-incarnation
        // request leaves no trace there.
        long incNow = PlayerIncarnationRegistry.incarnationOf(player);
        if (incNow != s.playerIncarnation()) {
            InventorySetTelemetry.incarnationMismatch.incrementAndGet();
            if (InventorySetTelemetry.PER_INTENT) DIAG.info("[API1B6] SETSLOT outcome=INCARNATION_MISMATCH player={} slot={} intentVersion={} requestInc={} objectInc={} versionTrackerTouched=NO playerObj={} thread={}",
                    id, s.slot(), s.intentVersion(), s.playerIncarnation(), incNow, obj, Thread.currentThread().getName());
            return ApplyOutcome.INCARNATION_MISMATCH;
        }
        if (player.isRemoved()) {
            // The targeted object itself is dead (e.g. a direct-path apply against a reference
            // captured before respawn/logout). Not a cross-incarnation hazard -- identity already
            // matched -- but writing into a discarded object is pointless; drop it observably.
            InventorySetTelemetry.targetRemoved.incrementAndGet();
            if (InventorySetTelemetry.PER_INTENT) DIAG.info("[API1B6] SETSLOT outcome=TARGET_REMOVED player={} slot={} intentVersion={} requestInc={} versionTrackerTouched=NO playerObj={} thread={}",
                    id, s.slot(), s.intentVersion(), s.playerIncarnation(), obj, Thread.currentThread().getName());
            return ApplyOutcome.TARGET_REMOVED;
        }
        InventoryTargetKey key = new InventoryTargetKey(id, s.slot());
        InventorySetTelemetry.onTryAccept(id, s.slot(), s.playerIncarnation(), s.intentVersion());
        if (!InventorySlotVersionTracker.tryAccept(key, s.playerIncarnation(), s.intentVersion())) {
            InventorySetTelemetry.staleDropped.incrementAndGet();
            if (InventorySetTelemetry.PER_INTENT) DIAG.info("[API1B6] SETSLOT outcome=STALE_INTENT_DROPPED player={} slot={} intentVersion={} requestInc={} versionTrackerTouched=YES lastApplied={} thread={}",
                    id, s.slot(), s.intentVersion(), s.playerIncarnation(),
                    InventorySlotVersionTracker.peekLastApplied(key, s.playerIncarnation()), Thread.currentThread().getName());
            return ApplyOutcome.STALE_INTENT_DROPPED;
        }
        player.getInventory().setItem(s.slot(), s.snapshot());
        long incAfter = PlayerIncarnationRegistry.incarnationOf(player);
        if (Thread.currentThread() instanceof RegionThread rt && NestworldRegionSystem.isInitialised()
                && NestworldRegionSystem.get().isManagedLevel(player.level())) {
            WorldRegion ownerNow = NestworldRegionSystem.get().getDimensionRegion(player.level()).getGrid().findOwningRegion(id);
            if (ownerNow != null && ownerNow != rt.getRegion()) InventorySetTelemetry.wrongOwnerMutation.incrementAndGet();
        }
        InventorySetTelemetry.onApplied(id, s.slot(), s.playerIncarnation(), s.intentVersion(), incAfter, player, player.getInventory().getItem(s.slot()));
        if (InventorySetTelemetry.PER_INTENT) DIAG.info("[API1B6] SETSLOT outcome=APPLIED player={} slot={} intentVersion={} requestInc={} objectInc={} versionTrackerTouched=YES item={}x{} playerObj={} thread={}",
                id, s.slot(), s.intentVersion(), s.playerIncarnation(), incNow,
                s.snapshot().getItem(), s.snapshot().getCount(), obj, Thread.currentThread().getName());
        return ApplyOutcome.APPLIED;
    }
}
