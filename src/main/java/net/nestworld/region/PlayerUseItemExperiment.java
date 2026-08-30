package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.4 step 3 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the per-player opt-in
 * set that gates whether {@code ServerPlayerGameMode.useItem()} (no-target item use: eating,
 * drinking, bow draw, ender pearl throw, ...) runs on the player's position-resolved owner
 * region instead of main. Independent of every other #31 opt-in set -- separate flag ({@link
 * NestworldTuning#PLAYER_USE_ITEM_REGION_EXECUTION}), separate set -- same isolation discipline
 * every #31 sub-mechanism has used so far.
 */
public final class PlayerUseItemExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerUseItemExperiment() {}

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
