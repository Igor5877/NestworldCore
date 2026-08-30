package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.4 step 2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the per-player opt-in
 * set that gates whether a right-click block interaction (specifically {@code
 * BlockState.use()} — lever/button/door-class "flip state" interactions, NOT item-use-on-block
 * or container opening, which are separate future steps) runs on the block's owner region
 * instead of main. Independent of {@link PlayerAttackExperiment}/{@link PlayerTickExperiment} —
 * separate flag ({@link NestworldTuning#PLAYER_INTERACTION_REGION_EXECUTION}), separate opt-in
 * set — same isolation discipline every #31 sub-mechanism has used so far.
 */
public final class PlayerInteractionExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerInteractionExperiment() {}

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
