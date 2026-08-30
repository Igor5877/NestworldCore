package net.nestworld.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 #31.3 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the per-player opt-in set that
 * actually gates whether an individual player's tick runs on their position-resolved owner
 * region instead of main. Deliberately separate from the {@link
 * NestworldTuning#PLAYER_TICK_REGION_EXECUTION} master switch (which alone changes nothing) so
 * the staged rollout (Stage A: 1 player -> Stage B: 2 players/2 regions -> Stage C: boundary
 * crossing -> Stage D: 10/25/50/100/200) can grow this set incrementally via {@code /nestworld
 * playertickexperiment add|remove|list}, never a blanket flip for every player at once.
 *
 * <p>{@link #enabled} is a {@code ConcurrentHashMap.newKeySet()} — B-GLOBAL shape (server-wide,
 * not owned by any one region), read every main-tick (the choke point in {@code
 * NestworldDimensionRegion.tickAllRegions}) and written only from the admin command (main
 * thread) — same correctly-matched-concurrent-collection reasoning already applied to {@code
 * NestworldPins} and {@code ServerLevel.players} (Phase 10 audit, finding G1).
 */
public final class PlayerTickExperiment {
    private static final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private PlayerTickExperiment() {}

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
