package net.nestworld.region;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nestworld.modapi.inventory.InventorySetBackend;
import net.nestworld.modapi.inventory.SetSlotRequest;

/**
 * NestWorld's "region execution semantics" peer implementation of the setSlot Bounded Mutation
 * Primitive (API-1B.6). Full contract: {@code docs/contracts/InventorySetSlotPrimitive.md}.
 *
 * <p><b>Not a copy-paste of {@link NestworldVelocityBackend}'s shape</b> -- deliberately
 * different, for an evidence-based reason: {@code VelocityExecutionBackend.setVelocity} receives
 * an already-resolved live {@code Entity} from its caller ({@code NestWorldEntities.setVelocity}),
 * so its Core peer never needs to look a target up. {@link InventorySetBackend#setSlot} receives
 * only a {@link SetSlotRequest} carrying a player {@code UUID} -- turning that UUID into a live
 * {@code ServerPlayer} means reading {@code MinecraftServer.getPlayerList()}'s backing map
 * ({@code PlayerList.playersByUUID}, confirmed by direct source read to be a plain
 * {@code HashMap}, mutated on the main thread on join/leave -- NOT concurrent-safe). Since
 * {@code NestWorldInventory.setSlot} is contractually callable from ANY thread, that resolution
 * step MUST happen on the main server thread, not the caller's thread -- same marshal-first
 * discipline {@link net.nestworld.modapi.inventory.ForgeInventorySetExecution} already uses for
 * exactly this reason. {@code EntityMutationDispatcher.dispatch(Entity, EntityMutationOp)} itself
 * remains safe to call from any thread once a live target is in hand (confirmed by existing
 * call sites, e.g. {@code Api1A7DeterministicTests}'s spawned-thread dispatch) -- the hazard this
 * class guards against is strictly the UUID-to-Entity resolution step, not {@code dispatch()}
 * itself.
 *
 * <pre>
 * Responsibility: resolve (playerId -> live ServerPlayer) on the main thread, then delegate into
 *   EntityMutationDispatcher exactly like the already-shipped SetVelocity peer.
 * Does: marshal to main thread if needed -> resolve live ServerPlayer -> EntityMutationDispatcher
 *   .dispatch as a SetInventorySlot op.
 * Does NOT: check incarnation or version itself -- both authoritative checks happen inside
 *   EntityMutationDispatcher.applyDirect's SetInventorySlot case, immediately before the
 *   mutation, same "authoritative check right before mutation" discipline as SetVelocity.
 * Threading: setSlot() itself is callable from any thread (mirrors NestWorldInventory's own
 *   contract); player resolution and the dispatch call are marshaled onto the main thread.
 * Lifecycle: one instance per MinecraftServer, constructed and installed by
 *   NestworldRegionSystem.onServerStarting, uninstalled (token-checked) on onServerStopping --
 *   see InventorySetBackendRegistry for the state machine.
 * Evidence / tests: API-1B.7's job, NOT YET RUN.
 * </pre>
 */
final class NestworldInventorySetBackend implements InventorySetBackend {
    private final MinecraftServer server;

    NestworldInventorySetBackend(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void setSlot(SetSlotRequest request) {
        // No early incarnation check: validity is object identity, decided only against the
        // object actually being mutated (EntityMutationDispatcher.applyDirect).
        if (server.isSameThread()) {
            applyByIdentity(request);
            return;
        }
        server.execute(() -> applyByIdentity(request));
    }

    private void applyByIdentity(SetSlotRequest request) {
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null) {
            InventorySetTelemetry.backendTargetNotFound.incrementAndGet(); // API-1B.7 temp instrumentation
            return; // TARGET_NOT_FOUND -- not connected
        }
        EntityMutationDispatcher.dispatch(player, new EntityMutationOp.SetInventorySlot(
                request.slot(), request.snapshot(), request.intentVersion(), request.playerIncarnation()));
    }
}
