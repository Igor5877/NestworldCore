package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 11 #31.2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md, docs/
 * PHASE11_1_PLAYER_EXECUTION_CONTRACT.md) — the player-input mailbox transport. SHADOW MODE ONLY:
 * this class captures, sequences, and delivers player input to a position-resolved "owner"
 * region for apply-time validation, but does NOT mutate the player or anything else. The old
 * main-thread movement path (unchanged, in {@code ServerGamePacketListenerImpl.handleMovePlayer})
 * keeps applying 100% of real movement regardless of what happens here.
 *
 * <p>"Owner" at this stage is resolved PURELY by position ({@link WorldGrid#getRegionForChunk})
 * — players are not registered in any region's owned-entity set today (they tick on main,
 * unconditionally, per the long-standing invariant #31.3 is the first phase to actually
 * challenge). This is a deliberate, narrower notion of ownership than {@link
 * EntityMutationDispatcher}'s (which resolves via {@code WorldGrid.findOwningRegion(UUID)} for
 * entities that ARE region-tracked) — for a player, "which region owns the next tick of input"
 * is naturally "whichever region's chunk bounds currently contain the player," not a persistent
 * assignment, since nothing about a player's region membership is tracked/committed anywhere yet.
 */
public final class PlayerInputDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerInputDispatcher() {}

    private static WorldRegion resolveByPosition(ServerLevel level, double x, double z) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
    }

    /**
     * Called from {@code ServerGamePacketListenerImpl.handleMovePlayer} (main thread) for every
     * accepted movement packet, when {@link NestworldTuning#PLAYER_INPUT_REGION_EXECUTION} is
     * enabled. {@code x}/{@code y}/{@code z}/{@code yRot}/{@code xRot} are the ALREADY-VALIDATED
     * (clamped/wrapped) target values the vanilla path is about to apply — captured, not
     * recomputed, so the shadow pipeline observes exactly what the real path saw.
     */
    public static void dispatch(ServerPlayer player, double x, double y, double z, float yRot, float xRot) {
        if (!NestworldTuning.PLAYER_INPUT_REGION_EXECUTION) {
            return;
        }
        WorldRegion owner = resolveByPosition(player.serverLevel(), x, z);
        if (owner == null) {
            return; // unmanaged dimension or grid not ready -- nothing to shadow-test here
        }
        java.util.UUID id = player.getUUID();
        // Sequence is assigned unconditionally at capture time -- it represents "this input
        // arrived at this point in the server-observed stream," independent of whether the
        // bounded queue below actually accepts it. A dropped-for-queue-full input still
        // consumes a sequence number, which correctly shows up as a detected GAP at apply time
        // rather than silently vanishing from the sequence entirely.
        long seq = PlayerInputDiagnostics.nextSequence(id);
        if (!PlayerInputDiagnostics.tryReserveSlot(id)) {
            return; // queue-full drop already counted by tryReserveSlot
        }
        PlayerInputDiagnostics.recordSent();
        owner.nestworldPostMessage(RegionMessage.playerInput(null, owner,
                player.serverLevel().getServer().getTickCount(),
                new RegionMessage.PlayerInput(id, seq, x, y, z, yRot, xRot)));
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_INPUT} message. Re-resolves ownership FRESH by the player's CURRENT position (not
     * the position the input was captured at) — same "never trust the mailbox destination as
     * ground truth" discipline as {@link EntityMutationDispatcher#applyQueued}. SHADOW MODE:
     * "apply" here means "record the sequence-order outcome," nothing else — no gameplay state
     * changes as a result of this call.
     */
    public static void applyQueued(RegionMessage.PlayerInput input, WorldRegion applyingRegion, ServerLevel level) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(input.playerId());
        if (player == null) {
            PlayerInputDiagnostics.releaseSlot(input.playerId());
            PlayerInputDiagnostics.recordDroppedPlayerGone();
            return;
        }
        WorldRegion currentOwner = resolveByPosition(player.serverLevel(), player.getX(), player.getZ());
        if (currentOwner != applyingRegion) {
            PlayerInputDiagnostics.releaseSlot(input.playerId());
            if (currentOwner == null || input.rerouteHops() >= NestworldTuning.PLAYER_INPUT_MAX_REROUTE_HOPS) {
                if (currentOwner != null) {
                    LOGGER.warn("PlayerInputDispatcher: dropping input seq={} for {} after {} re-route hops "
                            + "(player keeps changing region faster than the mailbox drains)",
                            input.sequence(), input.playerId(), input.rerouteHops());
                }
                PlayerInputDiagnostics.recordDroppedRerouteLimit();
                return;
            }
            if (!PlayerInputDiagnostics.tryReserveSlot(input.playerId())) {
                return; // queue full at the reroute target too -- already counted
            }
            PlayerInputDiagnostics.recordRerouted();
            applyingRegion.nestworldSendOrQueue(currentOwner, RegionMessage.playerInput(applyingRegion, currentOwner,
                    level.getServer().getTickCount(),
                    new RegionMessage.PlayerInput(input.playerId(), input.sequence(), input.x(), input.y(), input.z(),
                            input.yRot(), input.xRot(), input.rerouteHops() + 1)));
            return;
        }
        PlayerInputDiagnostics.recordApply(input.playerId(), input.sequence());
        PlayerInputDiagnostics.releaseSlot(input.playerId());
    }
}
