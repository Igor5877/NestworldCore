package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;
import net.minecraft.server.MinecraftServer;

/** `/nwcompatae2 gridcensus coverage`: run the model-A coverage analysis once over every loaded grid (MAIN, read-only)
 *  and print the uncovered class-2 paths ranked, plus the largest grids' verdicts. */
public final class GridCoverageCommand {
    private GridCoverageCommand() {}

    /** Benchmark window start: reset Core's per-tick domain metrics (MSPT / worker / await / worldOps rings). */
    public static String tickReset() {
        try {
            Class.forName("net.nestworld.region.DomainTickMetrics", true, GridCoverageCommand.class.getClassLoader()).getMethod("reset").invoke(null);
            return "domain tick metrics reset";
        } catch (ReflectiveOperationException e) {
            return "domain tick metrics unavailable: " + e;
        }
    }

    public static String run(MinecraftServer server) {
        GridOwnership.COVERAGE.clear();
        var grids = new java.util.ArrayList<Grid>();
        for (Grid g : appeng.hooks.ticking.TickHandler.instance().getGridList()) grids.add(g);
        grids.sort((a, b) -> Integer.compare(b.size(), a.size()));
        StringBuilder sb = new StringBuilder("grids=" + grids.size());
        int shown = 0;
        for (Grid g : grids) {
            var p = GridOwnership.coverageOf(g);
            if (shown++ < 6) sb.append("\n grid nodes=").append(g.size()).append(" verdict=").append(p.refusal() == null ? "CLAIMABLE (A)" : "B: " + p.refusal());
        }
        return sb + "\n" + GridOwnership.coverage(40);
    }

    /** AE2-GRID-DOMAIN-MVP-001 census of the hottest (largest) grid only: verdict, uncovered paths as DISTINCT host/side
     *  pairs (one computation), and the per-class device cost of THIS grid since `gridcensus start` (impact). */
    public static String grid1(MinecraftServer server) {
        Grid top = null;
        for (Grid g : appeng.hooks.ticking.TickHandler.instance().getGridList()) if (top == null || g.size() > top.size()) top = g;
        if (top == null) return "no grids";
        GridOwnership.COVERAGE.clear();
        var p = GridOwnership.coverageOf(top);
        StringBuilder sb = new StringBuilder("grid1 nodes=" + top.size() + " verdict=" + (p.refusal() == null ? "CLAIMABLE (A)" : "B: " + p.refusal()));
        sb.append("\n").append(GridOwnership.coverage(80).replace("count = occurrences in computations", "count = distinct host/side pairs, grid #1 only"));
        long ticks = GridCensus.ticksSinceStart(server.getTickCount());
        var dev = new java.util.ArrayList<>(GridCensus.devicesOf(top).entrySet());
        dev.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        double total = 0;
        for (var e : dev) total += e.getValue()[0];
        sb.append(String.format("%ngrid1 device cost over %d ticks: total %.3f ms/tick", ticks, total / 1e6 / ticks));
        for (int i = 0; i < Math.min(40, dev.size()); i++) {
            long[] c = dev.get(i).getValue();
            sb.append(String.format("%n  %.3f ms/tick  calls/tick=%.1f  %s", c[0] / 1e6 / ticks, (double) c[1] / ticks, dev.get(i).getKey()));
        }
        return sb.toString();
    }
}
