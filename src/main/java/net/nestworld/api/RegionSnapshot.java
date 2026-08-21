package net.nestworld.api;

/**
 * Read-only snapshot of one active {@code WorldRegion} at the moment it was taken.
 * Stable, public API surface for external consumers (metrics exporters, future mods)
 * that want to observe NestworldCore's live region state without depending on
 * {@code net.nestworld.region}'s internal classes directly.
 */
public record RegionSnapshot(
        int id,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ,
        double avgTickMs,
        int entityCount,
        boolean freeRunning
) {
}
