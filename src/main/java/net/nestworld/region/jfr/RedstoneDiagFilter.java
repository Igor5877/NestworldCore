package net.nestworld.region.jfr;

import net.minecraft.core.BlockPos;

/**
 * Diagnostic-only (2026-09-03, "vanilla scheduling boundary" follow-up to the PROMOTE
 * investigation -- see project memory). Narrow, position-filtered gate for the
 * SCHEDULE/COLLECT/ROUTE instrumentation added to {@code LevelTicks} and
 * {@code NestworldDimensionRegion#routeScheduledTick}.
 *
 * <p>Deliberately NOT a global "log every scheduled tick" filter -- that would reproduce the
 * same server-wide noise problem the PROMOTE investigation already hit once with unconditional
 * per-scheduled-tick JFR events. Instead this matches only the specific repeater positions
 * under test, so the cost of a miss is one {@code long[]} scan (no allocation, no lock) and the
 * cost of a hit is one log line -- everywhere else on the server, this instrumentation is inert.
 */
public final class RedstoneDiagFilter {

    private RedstoneDiagFilter() {}

    /** Repeaters at (-9,103,116) facing south and (-10,103,116) facing north -- the only two
     *  positions in the test circuit that vanilla ever calls {@code scheduleTick} for; plain
     *  redstone wire propagates via immediate neighbor updates, not the scheduled-tick queue.
     *  Plus one Test-A control position (-9,110,115), well clear of the circuit, used for a
     *  falling-sand scheduled tick that is unrelated to redstone entirely -- see project memory
     *  "vanilla scheduling boundary" follow-up: confirms whether NestWorld's scheduled-tick
     *  transport (schedule -&gt; collect -&gt; route -&gt; POST -&gt; POLL -&gt; EXECUTE) works at all before
     *  asking whether redstone specifically ever reaches {@code scheduleTick}. */
    private static final long[] TARGET = {
            BlockPos.asLong(-9, 103, 116),
            BlockPos.asLong(-10, 103, 116),
            BlockPos.asLong(-9, 110, 115),
    };

    public static boolean matches(BlockPos pos) {
        long l = pos.asLong();
        for (long t : TARGET) {
            if (t == l) return true;
        }
        return false;
    }
}
