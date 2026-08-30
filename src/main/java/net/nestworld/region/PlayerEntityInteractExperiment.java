package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.4 item-on-entity — the per-player opt-in set that gates whether {@code
 * Entity.interact()}/{@code interactAt()} runs on the TARGET ENTITY's owner region instead of
 * main. Independent of every other #31 opt-in set.
 */
public final class PlayerEntityInteractExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerEntityInteractExperiment() {}

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
