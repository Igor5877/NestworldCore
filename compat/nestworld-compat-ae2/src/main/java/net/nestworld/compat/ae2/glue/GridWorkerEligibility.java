package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * AE2-GRID-DOMAIN-001 G1: which grids may run their phases on the grid worker (model B). Fail-closed: a grid is
 * eligible only when none of its machine classes is on the deny list — classes whose grid-tick work touches ENTITIES,
 * the CHUNK SYSTEM or WORLD BLOCKS (planes, spatial IO, growth accelerators …), which MAIN may touch concurrently in its
 * non-BE phases (TZ §3′ "Risks to close in G1 audit"). Re-evaluated when the grid's node count changes or every
 * {@link #RECHECK_TICKS} server ticks. MAIN only (called from {@link GridWorker#submit}).
 */
final class GridWorkerEligibility {
    static final int RECHECK_TICKS = 200;
    /** Exact class-name fragments that keep a grid inline (entity / chunk / world-block access from the grid tick). */
    private static final Set<String> DENY = Set.of(
            "AnnihilationPlanePart", "FormationPlanePart", "IdentityAnnihilationPlanePart",
            "SpatialIOPortBlockEntity", "SpatialPylonBlockEntity", "SpatialAnchorBlockEntity",
            "PartActiveFormationPlane", "PartExFormationPlane", "PartExAnnihilationPlane"); // ExtendedAE
    // Deliberately NOT denied (G1 decision, TZ §3′.1): Charger / Inscriber (own inventory only), GrowthAccelerator
    // (world random-ticks hop to MAIN, GrowthAcceleratorMainHopMixin), crazyae2addons EntropyCradleControllerBE (writes
    // only its own multiblock's cap block states — the same exposure to the owning region thread as on MAIN today).
    private static final Map<Grid, long[]> CACHE = new WeakHashMap<>();   // {nodeCount, checkedAtTick, eligible}
    static final LongAdder eligibleChecks = new LongAdder(), denied = new LongAdder();
    private static volatile String lastDenied = "-";
    private GridWorkerEligibility() {}

    static boolean eligible(Grid grid) {
        int nodes = grid.size();
        long[] c = CACHE.get(grid);
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        int tick = server == null ? 0 : server.getTickCount();
        if (c != null && c[0] == nodes && tick - c[1] < RECHECK_TICKS) return c[2] == 1;
        boolean ok = true;
        for (Class<?> cls : grid.getMachineClasses()) {
            String n = cls.getSimpleName();
            if (DENY.contains(n)) { ok = false; lastDenied = cls.getName(); break; }
        }
        CACHE.put(grid, new long[] { nodes, tick, ok ? 1 : 0 });
        eligibleChecks.increment();
        if (!ok) denied.increment();
        return ok;
    }

    static String status() {
        int on = 0, off = 0;
        for (long[] c : CACHE.values()) if (c[2] == 1) on++; else off++;
        return "gridWorker eligibility: grids eligible=" + on + " inline=" + off + " checks=" + eligibleChecks.sum()
                + " lastDenied=" + lastDenied;
    }

    /** Unused helper kept for the audit command: does this node's owner class appear on the deny list? */
    static boolean denies(IGridNode node) {
        Object o = node.getOwner();
        return o != null && DENY.contains(o.getClass().getSimpleName());
    }
}
