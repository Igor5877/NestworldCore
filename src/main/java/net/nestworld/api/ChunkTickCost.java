package net.nestworld.api;

/**
 * "В яких чанках": measured tick cost (entity + block-entity combined) for one chunk,
 * over the current decayed window (~last few seconds — see
 * {@code net.nestworld.region.TickAttribution#decayChunks()}). Exact instrumentation,
 * not a density estimate (contrast with {@code /nestworld hotspots}, which is a fresh
 * entity-density scan, not accumulated measured time).
 *
 * @param dimensionKey dimension this chunk belongs to, e.g. "minecraft:overworld".
 * @param chunkX chunk X coordinate.
 * @param chunkZ chunk Z coordinate.
 * @param totalMs measured tick cost in this chunk over the current window, in milliseconds.
 */
public record ChunkTickCost(String dimensionKey, int chunkX, int chunkZ, double totalMs) {
}
