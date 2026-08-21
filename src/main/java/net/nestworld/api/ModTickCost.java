package net.nestworld.api;

/**
 * "Хто винен": cumulative measured tick time for one mod, one kind
 * ("entity" or "blockentity"), since server boot. Exact instrumentation
 * (every real tick call, not sampled) — see {@code net.nestworld.region.TickAttribution}.
 *
 * @param modId cumulative measured tick time for one mod, one kind ("entity" or "blockentity"),
 *              since server boot, uniquely identified by this namespace (e.g. "minecraft", "create").
 * @param kind  "entity" or "blockentity".
 * @param totalMs sum of measured wall-clock tick time for this mod+kind, in milliseconds.
 * @param count cumulative number of ticks measured for this mod+kind.
 */
public record ModTickCost(String modId, String kind, double totalMs, long count) {
}
