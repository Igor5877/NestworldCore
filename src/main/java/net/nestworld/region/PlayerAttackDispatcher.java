package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Phase 11 #31.4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the minimal choke-point
 * experiment for {@code Player.attack()}. Unlike #31.2's input mailbox, this is NOT shadow mode:
 * when a dispatch succeeds, the caller ({@code ServerGamePacketListenerImpl}'s {@code onAttack()})
 * skips its own main-thread {@code attack()} call entirely -- the whole call (cooldown scale,
 * critical-hit roll, enchantment lookup, sweep-target scan, and every target mutation inside it)
 * runs once, on the attacking player's position-resolved owner region thread, via a real mailbox
 * message ({@link RegionMessage.Type#PLAYER_ATTACK}) drained per-tick by {@code RegionThread},
 * NOT {@code RegionThreadPool.runWorkRound()} -- see that message type's javadoc for why
 * {@code runWorkRound()}'s free-running-region path (a single-slot overwrite, fine for #31.3's
 * once-per-main-tick tick-experiment batches) would silently drop an attack dispatched per
 * packet if two landed in the same region before it drained.
 *
 * <p>No new mutation-boundary work is needed for the target-hit side: {@link
 * EntityMutationHelper#redirectFromMainThread} (used throughout {@code Player.attack()} for
 * every target hit) already detects "caller is a region thread that already owns this target"
 * and falls through to a direct, cheaper mutation in that case -- see its own javadoc.
 */
public final class PlayerAttackDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerAttackDispatcher() {}

    private static WorldRegion resolveByPosition(ServerLevel level, double x, double z) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
    }

    /**
     * Called from {@code ServerGamePacketListenerImpl}'s {@code onAttack()} (main thread), before
     * the vanilla direct {@code player.attack(entity)} call. Returns {@code true} if the attack
     * was posted to a region's mailbox (caller must NOT also call {@code attack()} itself) or
     * {@code false} if it should fall through to the unchanged main-thread path (flag off,
     * attacker not opted in, or owner resolution failed -- same "safe direct fallback" precedent
     * as every other Phase 9/10/11 dispatcher).
     */
    public static boolean dispatch(ServerPlayer attacker, Entity target) {
        if (!NestworldTuning.PLAYER_ATTACK_REGION_EXECUTION || !PlayerAttackExperiment.isEnabled(attacker.getUUID())) {
            return false;
        }
        WorldRegion owner = resolveByPosition(attacker.serverLevel(), attacker.getX(), attacker.getZ());
        if (owner == null) {
            return false;
        }
        owner.nestworldPostMessage(RegionMessage.playerAttack(null, owner,
                attacker.serverLevel().getServer().getTickCount(),
                new RegionMessage.PlayerAttack(attacker.getUUID(), target.getUUID())));
        return true;
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_ATTACK} message. Re-resolves the attacker's ownership FRESH by their CURRENT
     * position (not the position at dispatch time) -- same "never trust the mailbox destination
     * as ground truth" discipline as {@link EntityMutationDispatcher#applyQueued}/{@link
     * PlayerInputDispatcher#applyQueued}. Reroutes (bounded by {@link
     * NestworldTuning#PLAYER_INPUT_MAX_REROUTE_HOPS}) if the attacker crossed a region boundary
     * between dispatch and apply; drops (logged) past the hop limit or if either the attacker or
     * the target has disconnected/despawned since dispatch.
     */
    public static void applyQueued(RegionMessage.PlayerAttack attack, WorldRegion applyingRegion, ServerLevel level) {
        ServerPlayer attacker = level.getServer().getPlayerList().getPlayer(attack.attackerId());
        if (attacker == null) {
            return;
        }
        Entity target = level.getEntity(attack.targetId());
        if (target == null) {
            return;
        }
        WorldRegion currentOwner = resolveByPosition(attacker.serverLevel(), attacker.getX(), attacker.getZ());
        if (currentOwner != applyingRegion) {
            if (currentOwner == null || attack.rerouteHops() >= NestworldTuning.PLAYER_INPUT_MAX_REROUTE_HOPS) {
                if (currentOwner != null) {
                    LOGGER.warn("PlayerAttackDispatcher: dropping attack by {} on {} after {} re-route hops "
                            + "(attacker keeps changing region faster than the mailbox drains)",
                            attack.attackerId(), attack.targetId(), attack.rerouteHops());
                }
                return;
            }
            applyingRegion.nestworldSendOrQueue(currentOwner, RegionMessage.playerAttack(applyingRegion, currentOwner,
                    level.getServer().getTickCount(),
                    new RegionMessage.PlayerAttack(attack.attackerId(), attack.targetId(), attack.rerouteHops() + 1)));
            return;
        }
        try {
            long t0 = System.nanoTime();
            attacker.attack(target);
            PlayerAttackTiming.recordRegion(System.nanoTime() - t0);
        } catch (Throwable t) {
            LOGGER.warn("[#31.4 experiment] Player {} region-thread attack error: {}",
                    attacker.getGameProfile().getName(), t.getMessage());
        }
    }
}
