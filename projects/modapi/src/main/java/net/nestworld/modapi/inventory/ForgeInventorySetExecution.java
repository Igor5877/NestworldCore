package net.nestworld.modapi.inventory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The "ordinary Forge, no NestWorldCore" peer for {@code setSlot} -- a normal implementation of
 * the same contract, not a fallback. Same threading shape as
 * {@code net.nestworld.modapi.entity.ForgeVelocityExecution}: apply on the server thread, after
 * re-acquiring the player by UUID.
 *
 * <p>Incarnation validity is object identity ({@link PlayerIncarnationRegistry}): the player
 * re-acquired at apply time must be the very object the request was stamped against. This needs
 * no lifecycle event, which is why this peer now has the same respawn/relogin protection as the
 * NestWorld peer on stock Forge (closing the earlier stock-Forge OPEN BLOCKER by construction --
 * still to be confirmed on a real stock-Forge runtime).
 */
final class ForgeInventorySetExecution {
    private ForgeInventorySetExecution() {}

    static void setSlot(ServerPlayer player, SetSlotRequest request) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return; // not associated with a running server -- nothing safe to marshal to
        }
        if (server.isSameThread()) {
            applyByIdentity(server, request);
            return;
        }
        server.execute(() -> applyByIdentity(server, request));
    }

    private static void applyByIdentity(MinecraftServer server, SetSlotRequest request) {
        ServerPlayer fresh = server.getPlayerList().getPlayer(request.playerId());
        if (fresh == null) {
            return; // not connected -- TARGET_NOT_FOUND equivalent
        }
        if (PlayerIncarnationRegistry.incarnationOf(fresh) != request.playerIncarnation()) {
            return; // INCARNATION_MISMATCH -- a different ServerPlayer object than the one targeted
        }
        InventoryTargetKey key = new InventoryTargetKey(request.playerId(), request.slot());
        if (!InventorySlotVersionTracker.tryAccept(key, request.playerIncarnation(), request.intentVersion())) {
            return; // STALE_INTENT_DROPPED
        }
        fresh.getInventory().setItem(request.slot(), request.snapshot());
    }
}
