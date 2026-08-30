package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the per-player opt-in set that
 * gates whether {@code Player.attack()}, invoked from {@code
 * ServerGamePacketListenerImpl}'s {@code onAttack()} choke point, runs on the attacker's
 * position-resolved owner region instead of main. Deliberately independent of {@link
 * PlayerTickExperiment} — separate flag ({@link NestworldTuning#PLAYER_ATTACK_REGION_EXECUTION}),
 * separate opt-in set — so a test result can be attributed to attack-execution specifically, not
 * conflated with tick-execution's own effects, per the same isolation discipline #31.3 used.
 *
 * <p>{@link #enabled} is a {@code ConcurrentHashMap.newKeySet()} — same B-GLOBAL shape as {@link
 * PlayerTickExperiment}: read on every attack packet (main thread), written only from the admin
 * command (main thread).
 */
public final class PlayerAttackExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerAttackExperiment() {}

    public static boolean isEnabled(UUID playerId) {
        return !enabled.isEmpty() && enabled.contains(playerId);
    }

    public static boolean add(UUID playerId) {
        return enabled.add(playerId);
    }

    public static boolean remove(UUID playerId) {
        return enabled.remove(playerId);
    }

    public static Set<UUID> snapshot() {
        return Set.copyOf(enabled);
    }

    public static int size() {
        return enabled.size();
    }
}
