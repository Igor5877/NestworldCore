package net.nestworld.modapi.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The "ordinary Forge, no NestWorldCore" peer execution semantics for setVelocity. Status:
 * IMPLEMENTED (API-1A.6), NOT YET VERIFIED (API-1A.7 pending). Full contract:
 * {@code docs/contracts/SetVelocityPrimitive.md}'s "Forge threading contract" section.
 *
 * <pre>
 * Responsibility:
 *   Apply a setVelocity mutation correctly when no NestWorldCore backend is installed --
 *   NOT a fallback for the NestWorldCore peer, a normal implementation of the same contract.
 *
 * Does:
 *   - apply directly if the caller is already on the server's own thread
 *   - otherwise capture (dimension, uuid), marshal to the server's task executor
 *     (MinecraftServer.execute), and re-acquire the entity fresh by identity before applying
 *
 * Does NOT:
 *   - ever call entity.setDeltaMovement(...) from a thread other than the server's own thread
 *     -- this was an explicit correction: an earlier draft's bare direct call would have let
 *     an ordinary Forge mod mutate vanilla state off-thread from an arbitrary worker thread,
 *     defeating the purpose of this abstraction layer
 *   - carry the caller's live Entity reference across the server.execute(...) marshal --
 *     only (dimension, uuid) crosses it, matching this whole API's fresh-reacquisition
 *     discipline even on this non-region-routed path
 *
 * Threading:
 *   Callable from ANY thread. Internally branches on MinecraftServer.isSameThread().
 *
 * Invariant:
 *   No live Entity reference crosses the server.execute(...) boundary. Verified directly
 *   against this repo's real vanilla source before writing this class: Entity.getServer(),
 *   MinecraftServer.isSameThread()/.execute(Runnable)/.getLevel(ResourceKey) all confirmed to
 *   exist with these exact signatures (projects/forge/src/main/java/net/minecraft/...).
 *
 * Failure (outcome-only, never an exception to the caller):
 *   entity.getServer() == null (not currently associated with a running server, e.g.
 *     mid-transfer) -> no-op, nothing safe to marshal to
 *   level for the captured dimension no longer exists at apply time -> no-op
 *   entity not found or removed at apply time (post-marshal re-acquisition) -> no-op
 *   API-1A.8: intentVersion loses arbitration to an already-applied newer version ->
 *     STALE_INTENT_DROPPED, silent no-op (checked via SetVelocityIntentTracker immediately
 *     before both apply points below, same version domain the NestWorld peer uses)
 *
 * Lifecycle:
 *   Stateless. No install/uninstall -- this class is always available; it is simply not
 *   consulted when a VelocityExecutionBackend is installed (see NestWorldEntities.setVelocity).
 *
 * Evidence / tests:
 *   Compiles and links correctly against confirmed-real vanilla API. The same-thread vs.
 *   foreign-thread branches and the post-marshal re-acquisition no-op path are NOT YET
 *   exercised by a live test -- API-1A.7's job.
 * </pre>
 */
final class ForgeVelocityExecution {
    private ForgeVelocityExecution() {}

    static void apply(Entity entity, Vec3 velocity, long intentVersion) {
        long nwT0 = ForgeVelocityTelemetry.ENABLED ? ForgeVelocityTelemetry.onAccepted() : 0L;
        MinecraftServer server = entity.getServer();
        if (server == null) {
            if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.noServer, nwT0);
            return;
        }
        SetVelocityIntentTracker.TargetKey key =
                new SetVelocityIntentTracker.TargetKey(entity.level().dimension(), entity.getUUID());
        if (server.isSameThread()) {
            if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.pathSameThread.increment();
            if (SetVelocityIntentTracker.tryAccept(key, intentVersion)) {
                entity.setDeltaMovement(velocity);
                if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.applied, nwT0);
            } else if (ForgeVelocityTelemetry.ENABLED) { // STALE_INTENT_DROPPED -- silent, same posture as target-not-found
                ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.staleDropped, nwT0);
            }
            return;
        }
        ResourceKey<Level> dimension = entity.level().dimension();
        UUID id = entity.getUUID();
        if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.pathMarshal.increment();
        server.execute(() -> applyByIdentity(server, dimension, id, velocity, key, intentVersion, nwT0));
    }

    private static void applyByIdentity(MinecraftServer server, ResourceKey<Level> dimension,
            UUID id, Vec3 velocity, SetVelocityIntentTracker.TargetKey key, long intentVersion, long nwT0) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.otherTerminal, nwT0);
            return;
        }
        Entity fresh = level.getEntity(id);
        if (fresh == null || fresh.isRemoved()) {
            if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.targetNotFound, nwT0);
            return;
        }
        if (!SetVelocityIntentTracker.tryAccept(key, intentVersion)) {
            if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.staleDropped, nwT0);
            return; // STALE_INTENT_DROPPED
        }
        fresh.setDeltaMovement(velocity);
        if (ForgeVelocityTelemetry.ENABLED) ForgeVelocityTelemetry.onTerminal(ForgeVelocityTelemetry.applied, nwT0);
    }
}
