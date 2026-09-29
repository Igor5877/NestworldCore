package net.nestworld.region;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nestworld.modapi.entity.SetVelocityIntentTracker;

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
            boolean applied = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, applied ? "APPLIED" : "STALE_INTENT_DROPPED");
            return;
        }
        WorldRegion owner = NestworldRegionSystem.get().getDimensionRegion(target.level())
                .getGrid().findOwningRegion(target.getUUID());
        DIAG.info("[API1A7] opId={} phase=OWNER_RESOLVED owner={} target={}",
                opId, owner == null ? "null" : owner.getId(), target.getUUID());
        EntityMutationTestHooks.barrierA();
        if (owner == null) {
            // Not currently owned by any region (mid-spawn, or a genuinely unsharded moment) --
            // nothing to race against, apply directly, same as EntityMutationHelper's own
            // null-owner handling.
            boolean applied = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={} (owner=null direct)", opId, applied ? "APPLIED" : "STALE_INTENT_DROPPED");
            return;
        }
        if (Thread.currentThread() instanceof RegionThread rt) {
            WorldRegion self = rt.getRegion();
            if (self == owner) {
                NestworldOwnershipAssertions.assertEntityOwner(target, "EntityMutationDispatcher.dispatch.directFallthrough");
                DIAG.info("[API1A7] opId={} phase=APPLY owner={} target={} (same-region direct)", opId, self.getId(), target.getUUID());
                boolean applied = applyDirect(target, op);
                DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, applied ? "APPLIED" : "STALE_INTENT_DROPPED");
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
            boolean applied = applyDirect(target, op);
            DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, applied ? "APPLIED" : "STALE_INTENT_DROPPED");
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
        EntityMutationTestHooks.barrierB();
        Entity target = level.getEntity(targetId);
        if (target == null || target.isRemoved()) {
            DIAG.info("[API1A7] opId={} phase=TERMINAL result=TARGET_NOT_FOUND target={} applyingRegion={} thread={}",
                    opId, targetId, applyingRegion.getId(), Thread.currentThread().getName());
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
                return;
            }
            // Stale: ownership moved on (split/merge/handover) between send and apply. Re-route
            // to the entity's REAL current owner -- that region's own next drain re-validates
            // again from scratch, same as this call did. opId carried through UNCHANGED.
            DIAG.info("[API1A7] opId={} phase=REROUTE from={} to={} hop={} target={}",
                    opId, applyingRegion.getId(), currentOwner.getId(), rerouteHops + 1, targetId);
            applyingRegion.nestworldSendOrQueue(currentOwner, RegionMessage.entityMutate(applyingRegion, currentOwner,
                    level.getServer().getTickCount(), new RegionMessage.EntityMutate(targetId, op, rerouteHops + 1, opId)));
            return;
        }
        EntityMutationTestHooks.barrierC();
        DIAG.info("[API1A7] opId={} phase=APPLY owner={} target={} thread={}",
                opId, applyingRegion.getId(), targetId, Thread.currentThread().getName());
        boolean applied = applyDirect(target, op);
        DIAG.info("[API1A7] opId={} phase=TERMINAL result={}", opId, applied ? "APPLIED" : "STALE_INTENT_DROPPED");
    }

    /** The actual mutation switch — same set {@code RegionThread.applyPendingEntityMutations()}
     *  already had, plus {@link EntityMutationOp.Kill}/{@link EntityMutationOp.Discard}. Returns
     *  {@code false} only for API-1A.8's STALE_INTENT_DROPPED outcome ({@link
     *  EntityMutationOp.SetVelocity} whose {@code intentVersion} lost arbitration to a newer one
     *  already applied) -- every other op type always mutates and returns {@code true}. */
    static boolean applyDirect(Entity entity, EntityMutationOp op) {
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
                return false; // STALE_INTENT_DROPPED -- a newer intent for this target already won
            }
            entity.setDeltaMovement(sv.velocity());
        }
        return true;
    }
}
