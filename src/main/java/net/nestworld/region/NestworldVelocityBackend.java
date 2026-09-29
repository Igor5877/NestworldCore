package net.nestworld.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nestworld.modapi.entity.VelocityExecutionBackend;

/**
 * NestWorld's "region execution semantics" peer implementation of the setVelocity Bounded
 * Mutation Primitive. Status: IMPLEMENTED (API-1A.6), NOT YET VERIFIED (API-1A.7 pending).
 * Full contract: {@code docs/contracts/SetVelocityPrimitive.md}.
 *
 * <pre>
 * Responsibility: one-line delegate into EntityMutationDispatcher -- exactly the same shape
 *   as the already-shipped Ignite/ExtinguishFire ops, no new routing logic.
 * Does: forward (entity, velocity) to EntityMutationDispatcher.dispatch as a SetVelocity op.
 * Does NOT: retain the entity reference, do any capture/reacquisition itself -- that is
 *   entirely EntityMutationDispatcher's job, unchanged by this class.
 * Threading: whatever thread called NestWorldEntities.setVelocity -- dispatch() itself
 *   handles same-thread vs. cross-region marshaling.
 * Invariant: inherits EntityMutationDispatcher's own already-audited capture discipline.
 * Failure: inherits EntityMutationDispatcher's documented failure/delivery semantics
 *   (best-effort, at-most-once, NOT guaranteed -- see docs/contracts/SetVelocityPrimitive.md).
 * Lifecycle: stateless singleton, installed/uninstalled via VelocityBackendRegistry by
 *   NestworldRegionSystem's server-lifecycle hooks -- this class itself has no lifecycle.
 * Evidence / tests: inherits EntityMutationDispatcher's existing evidence for Ignite-shaped
 *   ops; SetVelocity-specific evidence is API-1A.7's job, NOT YET RUN.
 * </pre>
 */
final class NestworldVelocityBackend implements VelocityExecutionBackend {
    static final NestworldVelocityBackend INSTANCE = new NestworldVelocityBackend();

    private NestworldVelocityBackend() {}

    @Override
    public void setVelocity(Entity entity, Vec3 velocity, long intentVersion) {
        // API-1A.7 TEMP INSTRUMENTATION (route-trace evidence gate, 2026-09-29).
        org.apache.logging.log4j.LogManager.getLogger("NestWorld/DIAG").info(
                "[API1A7] NestworldVelocityBackend.setVelocity() target={} velocity={} intentVersion={} thread={}",
                entity.getUUID(), velocity, intentVersion, Thread.currentThread().getName());
        EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(velocity, intentVersion));
    }
}
