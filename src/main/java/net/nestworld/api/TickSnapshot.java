package net.nestworld.api;

/**
 * Server-wide tick-time snapshot, sourced from vanilla's own {@code
 * MinecraftServer.tickTimes} ring buffer (same source {@code /nestworld mspt} reads) —
 * NOT NestworldCore-specific, but included here so a single API call gives a metrics
 * exporter both halves of the picture (core internals + overall tick health) without
 * a second round-trip.
 */
public record TickSnapshot(
        double msptAvg,
        double msptP50,
        double msptP95,
        double msptP99,
        double msptMax,
        double tps
) {
}
