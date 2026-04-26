/*
 * NestworldCore - Island lifecycle state machine
 */
package net.nestworld.island;

/**
 * Lifecycle states for a SkyBlock island (maps 1-to-1 with a Forge process).
 *
 * Transitions:
 *   COLD ──warmup──▶ WARMING ──loaded──▶ WARM ──player joins──▶ ACTIVE
 *   ACTIVE ──idle 5min──▶ COOLING ──saved──▶ COLD
 *   ACTIVE ──lag emergency──▶ COOLING ──saved──▶ COLD (quarantine 15min)
 */
public enum IslandState {
    /** On S3/disk. No process running. Zero RAM. */
    COLD,

    /** Process is starting, world loading. ~30-60s with 100+ mods. */
    WARMING,

    /** Process running, world loaded, no players. Ready in ~1s. */
    WARM,

    /** Players online, ticking normally. */
    ACTIVE,

    /** Saving to disk, players kicked or migrating. Process will stop. */
    COOLING;

    public boolean isRunning() {
        return this == WARMING || this == WARM || this == ACTIVE || this == COOLING;
    }

    public boolean acceptsPlayers() {
        return this == WARM || this == ACTIVE;
    }
}
