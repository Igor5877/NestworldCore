package net.nestworld.modapi.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Core-internal SPI. Status: IMPLEMENTED (API-1A.6), NOT YET VERIFIED (API-1A.7 pending).
 * Full contract: {@code docs/contracts/SetVelocityPrimitive.md}.
 *
 * <pre>
 * Responsibility:
 *   One peer execution semantics for the setVelocity Bounded Mutation Primitive -- the
 *   region-aware side. Implemented by net.nestworld.region.NestworldVelocityBackend when
 *   NestWorldCore is present, installed via {@link VelocityBackendRegistry}. The OTHER peer
 *   (ordinary Forge, no Core) is {@link ForgeVelocityExecution} -- not an implementation of
 *   this interface, a separate code path selected when no backend is installed. Neither peer
 *   is a fallback for the other.
 *
 * Does:
 *   - receive a live Entity + Vec3 + an already-assigned intentVersion, synchronously, from
 *     NestWorldEntities.setVelocity (API-1A.8: the version is assigned at the public call site,
 *     before this method is invoked -- implementations must NOT reassign or ignore it)
 *   - apply the velocity mutation via whatever mechanism the implementation trusts to do so
 *     correctly across region ownership (the shipped implementation delegates to
 *     EntityMutationDispatcher, which itself checks the version against
 *     SetVelocityIntentTracker immediately before the actual mutation)
 *
 * Does NOT:
 *   - retain the Entity reference past the call (implementations must not do this; the
 *     shipped implementation satisfies it by inheriting EntityMutationDispatcher's own
 *     already-audited capture discipline, not by re-implementing one here)
 *   - get called directly by mods -- mods call NestWorldEntities only
 *
 * Threading:
 *   Called synchronously from whatever thread invoked NestWorldEntities.setVelocity. An
 *   implementation is responsible for its OWN correct handoff to wherever the mutation must
 *   actually execute -- this interface makes no threading guarantee by itself.
 *
 * Invariant:
 *   No live Entity reference reachable from this call may cross an asynchronous ownership
 *   handoff unchanged -- see NestWorldEntities' class-level Invariant for the full statement.
 *
 * Failure:
 *   Implementation-defined, but must be outcome-only (no exception back to the caller) to
 *   match NestWorldEntities' own documented failure semantics.
 *
 * Lifecycle:
 *   Instances are installed/uninstalled via {@link VelocityBackendRegistry} -- see that
 *   class's own contract for the state machine. This interface itself carries no lifecycle.
 *
 * Evidence / tests:
 *   See NestWorldEntities' class-level Evidence/tests -- same status (compile+jdeps only,
 *   API-1A.7 pending).
 * </pre>
 */
public interface VelocityExecutionBackend {
    void setVelocity(Entity entity, Vec3 velocity, long intentVersion);
}
