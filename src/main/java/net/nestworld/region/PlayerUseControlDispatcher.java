package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 11 #31.3/#31.4 player-action-ownership hardening -- routes {@code releaseUsingItem()}/
 * {@code stopUsingItem()} to a player's owner region when (and only when) that player's own tick
 * -- and therefore the use-item countdown and {@code completeUsingItem()} -- already runs there
 * via {@link PlayerTickExperiment}. See {@link RegionMessage.Type#PLAYER_USE_CONTROL}'s javadoc
 * for the full rationale (fire-and-forget, no fallback -- a fallback here would BE the race this
 * exists to eliminate, not a safety net).
 *
 * <p>Deliberately gated on {@link PlayerTickExperiment#isEnabled}, NOT a separate opt-in set like
 * every other #31.4 dispatcher: this is not a new experiment with its own scope decision -- it is
 * a correctness fix for whatever scope #31.3 already covers. A player who isn't tick-ticking on a
 * region has no race to fix (their {@code completeUsingItem()} already runs on the same thread
 * -- main -- as these packet handlers), so the vanilla direct call remains exactly correct for
 * them, unchanged.
 */
public final class PlayerUseControlDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerUseControlDispatcher() {}

    private static WorldRegion resolveByPosition(ServerLevel level, double x, double z) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
    }

    /**
     * Called from {@code ServerGamePacketListenerImpl} (main thread), before the vanilla direct
     * {@code releaseUsingItem()}/{@code stopUsingItem()} call. Returns {@code true} if posted to a
     * region's mailbox (caller must NOT also call the vanilla method itself -- doing so would be
     * exactly the double-execution/race this type exists to prevent) or {@code false} if the
     * caller should fall through to the unchanged direct main-thread call (player's tick isn't
     * region-owned, or owner resolution failed).
     */
    public static boolean dispatch(ServerPlayer player, RegionMessage.UseControlOp op) {
        if (!NestworldTuning.PLAYER_TICK_REGION_EXECUTION || !PlayerTickExperiment.isEnabled(player.getUUID())) {
            return false;
        }
        WorldRegion owner = resolveByPosition(player.serverLevel(), player.getX(), player.getZ());
        if (owner == null) {
            return false;
        }
        owner.nestworldPostMessage(RegionMessage.useControl(null, owner,
                player.serverLevel().getServer().getTickCount(),
                new RegionMessage.UseControl(player.getUUID(), op)));
        PlayerUseControlTiming.recordEnqueued();
        return true;
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_USE_CONTROL} message. Re-resolves the player's ownership FRESH by their CURRENT
     * position -- same "never trust the mailbox destination as ground truth" discipline as {@link
     * PlayerAttackDispatcher#applyQueued}. Reroutes (bounded by {@link
     * NestworldTuning#PLAYER_INPUT_MAX_REROUTE_HOPS}) if the player crossed a region boundary
     * between dispatch and apply; drops (logged) past the hop limit, if the player has
     * disconnected, or if they've since left {@link PlayerTickExperiment} entirely (their tick --
     * and this control mutation along with it -- belongs back on main at that point; a stale
     * region-side release/stop would race the main thread exactly like the case this type exists
     * to prevent, just in the opposite direction).
     */
    public static void applyQueued(RegionMessage.UseControl control, WorldRegion applyingRegion, ServerLevel level, long queueLatencyNanos) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(control.playerId());
        if (player == null) {
            return;
        }
        if (!PlayerTickExperiment.isEnabled(control.playerId())) {
            LOGGER.warn("PlayerUseControlDispatcher: dropping {} for {} -- player left the tick-experiment "
                    + "opt-in set between dispatch and apply (their tick, and this control op, now belong on main)",
                    control.op(), control.playerId());
            PlayerUseControlTiming.recordDropped();
            return;
        }
        WorldRegion currentOwner = resolveByPosition(player.serverLevel(), player.getX(), player.getZ());
        if (currentOwner != applyingRegion) {
            if (currentOwner == null || control.rerouteHops() >= NestworldTuning.PLAYER_INPUT_MAX_REROUTE_HOPS) {
                if (currentOwner != null) {
                    LOGGER.warn("PlayerUseControlDispatcher: dropping {} for {} after {} re-route hops "
                            + "(player keeps changing region faster than the mailbox drains)",
                            control.op(), control.playerId(), control.rerouteHops());
                }
                PlayerUseControlTiming.recordDropped();
                return;
            }
            applyingRegion.nestworldSendOrQueue(currentOwner, RegionMessage.useControl(applyingRegion, currentOwner,
                    level.getServer().getTickCount(),
                    new RegionMessage.UseControl(control.playerId(), control.op(), control.rerouteHops() + 1)));
            PlayerUseControlTiming.recordRerouted();
            return;
        }
        try {
            switch (control.op()) {
                case RELEASE -> player.releaseUsingItem();
                case STOP -> player.stopUsingItem();
            }
            PlayerUseControlTiming.recordExecuted(queueLatencyNanos);
        } catch (Throwable t) {
            LOGGER.warn("[#31.3/#31.4 player-action-ownership] {} for {} region-thread error: {}",
                    control.op(), player.getGameProfile().getName(), t.getMessage());
        }
    }
}
