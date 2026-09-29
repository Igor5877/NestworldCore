package net.nestworld.modapi.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Public NestWorld Mod API facade. Status: IMPLEMENTED (API-1A.6), NOT YET VERIFIED
 * (API-1A.7's compatibility/concurrency test gate has not run) -- do not read this class or
 * its javadoc as "production-proven." Full normative contract:
 * {@code docs/contracts/SetVelocityPrimitive.md} -- this class's javadoc is part of that
 * contract, not a separate description of it; if the two ever disagree, that is a bug in one
 * of them to be fixed, not a reason to trust the code over the doc or vice versa.
 *
 * <pre>
 * Responsibility:
 *   The one public entry point for the setVelocity Bounded Mutation Primitive. Mods call
 *   this class; nothing else in net.nestworld.modapi.entity is public-API surface.
 *
 * Does:
 *   - accept a live Entity + immutable Vec3 for mod-author ergonomics
 *   - select whichever of two peer execution semantics is currently active (NestWorldCore
 *     region-aware backend, or ordinary-Forge threading-contract execution) and delegate
 *   - guarantee the entity mutated is exactly the one identified by the caller's Entity
 *     reference at call time (subject to normal reroute/reacquisition below)
 *
 * Does NOT:
 *   - accept arbitrary caller logic, a Runnable, or any generic "submit" escape hatch
 *   - return a result (fire-and-forget, matching EntityMutationDispatcher's existing ops)
 *   - guarantee delivery (see Failure below)
 *   - retain the caller's Entity reference past this call, in either peer
 *
 * Threading:
 *   Callable from ANY thread. Internally marshals to the correct execution context itself --
 *   callers never need to pre-marshal.
 *
 * Invariant:
 *   No live Entity reference supplied by the caller crosses an asynchronous handoff. The
 *   NestWorldCore peer inherits this from EntityMutationDispatcher's already-audited capture
 *   discipline (UUID only crosses the mailbox); the ordinary-Forge peer
 *   (ForgeVelocityExecution) implements it directly (captures (dimension, uuid) before any
 *   thread marshal, re-acquires fresh on the server thread).
 *
 * Failure (outcome-only, no exception thrown to the caller in any documented case):
 *   target removed/not found at apply time -> silent no-op
 *   NestWorldCore peer, hop limit exceeded during reroute -> dropped, Core-internal WARN log
 *   NestWorldCore peer, server shutdown mid-flight -> silent no-op (mailbox not drained;
 *     see docs/RACE_CLASSIFICATION.md item 15 -- a Core-wide gap, not introduced here)
 *   ordinary-Forge peer, entity has no current server (entity.getServer() == null) -> no-op
 *   API-1A.8: an older call's intent loses arbitration to a newer call's already-applied
 *     intent for the SAME entity -> STALE_INTENT_DROPPED, silent no-op (see
 *     {@link SetVelocityIntentTracker} -- LATEST_INTENT_WINS, not FIFO: a stale intent is
 *     dropped rather than applied out of order over a newer state)
 *   Delivery semantics overall: best-effort, at-most-once, NOT guaranteed-delivered. This is
 *   not softened language -- both silent-loss paths above are confirmed by direct trace of the
 *   mechanisms this primitive reuses/implements, not theoretical. LATEST_INTENT_WINS is
 *   orthogonal to delivery, not an upgrade to it -- a winning intent can still be lost to
 *   target removal or shutdown, same as before.
 *
 * Lifecycle:
 *   This class itself is stateless. Which peer answers a call is entirely determined by
 *   {@link VelocityBackendRegistry#current()} at the moment of the call -- see that class for
 *   the install/uninstall state machine.
 *
 * Evidence / tests:
 *   Compiled standalone against this repo's real build output; jdeps-verified zero
 *   net.nestworld.region/adapter/EntityMutationDispatcher dependency in this package
 *   (API-1A.6 landing evidence). Full dedicated/integrated x Forge/NestWorld live-server and
 *   concurrency evidence is API-1A.7's job -- NOT YET RUN. Do not cite this class as proof of
 *   correctness beyond the compile+jdeps evidence actually gathered so far.
 * </pre>
 */
public final class NestWorldEntities {
    private NestWorldEntities() {}

    /**
     * Sets {@code entity}'s velocity. Fire-and-forget, no return value, best-effort/at-most-once
     * delivery -- NOT guaranteed-delivered (see the class-level contract above and
     * {@code docs/contracts/SetVelocityPrimitive.md}'s "Delivery semantics"). The {@code entity}
     * argument is read only for identity/dispatch at this call site -- never retained past this
     * method, never stored inside any queued operation, in either peer implementation.
     *
     * <p>Two peer execution semantics apply depending on what's present at runtime -- neither is
     * a fallback for the other, both are normal implementations of one contract:
     * <ul>
     *   <li>NestWorldCore present: routes through the region-aware backend (fresh reacquisition,
     *       ownership revalidation, bounded reroute -- see {@code EntityMutationDispatcher}).</li>
     *   <li>Ordinary Forge, no Core: applies on the server's own thread, marshaling through the
     *       server's standard task executor and re-acquiring the entity fresh by identity when
     *       called from a foreign thread -- see {@link ForgeVelocityExecution}. Never mutates
     *       vanilla state from a foreign thread under any circumstance.</li>
     * </ul>
     *
     * <p>Silent-loss cases are not hidden by this javadoc: the target may have been removed, may
     * have changed dimension (currently indistinguishable from removed on the NestWorldCore
     * peer), or the server may be shutting down with this call still in flight (mailbox not
     * drained). All three result in a silent no-op, never an exception.
     */
    public static void setVelocity(Entity entity, Vec3 velocity) {
        // API-1A.8: assign the intent version HERE, at submission -- before any thread/region
        // handoff -- so it reflects caller intent order, not scheduling order. Same version
        // domain regardless of which peer ends up executing the call.
        SetVelocityIntentTracker.TargetKey key =
                new SetVelocityIntentTracker.TargetKey(entity.level().dimension(), entity.getUUID());
        long intentVersion = SetVelocityIntentTracker.assignVersion(key);

        VelocityExecutionBackend backend = VelocityBackendRegistry.current();
        if (backend != null) {
            backend.setVelocity(entity, velocity, intentVersion);
        } else {
            ForgeVelocityExecution.apply(entity, velocity, intentVersion);
        }
    }
}
