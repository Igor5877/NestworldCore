package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.4 step 4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the per-player opt-in
 * set that gates whether {@code ItemStack.useOn(UseOnContext)} (item-on-block: axe strip/scrape/
 * wax-off first, then future item-on-block cases like {@code BlockItem} placement) runs on the
 * CLICKED BLOCK's owner region instead of main. Independent of every other #31 opt-in set.
 */
public final class PlayerUseItemOnBlockExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerUseItemOnBlockExperiment() {}

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
