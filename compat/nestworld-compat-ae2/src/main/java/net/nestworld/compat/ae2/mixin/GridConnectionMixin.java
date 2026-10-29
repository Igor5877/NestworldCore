package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.nestworld.compat.ae2.glue.GridPhaseGate;
import net.nestworld.compat.ae2.glue.Specs;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** G1: connection create (merge) and destroy (split). T0 plus both sides' overlays. */
@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionMixin {
    @Shadow private GridNode sideA;
    @Shadow private GridNode sideB;

    @WrapMethod(method = "create")
    private static GridConnection nw$create(IGridNode a, IGridNode b, Direction dir, Operation<GridConnection> op) {
        Supplier<List<Grid>> grids = () -> {
            List<Grid> l = new ArrayList<>(2);
            if (a instanceof GridNode ga) l.add(Specs.myGrid(ga));
            if (b instanceof GridNode gb) l.add(Specs.myGrid(gb));
            return l;
        };
        return GridPhaseGate.topology(grids, () -> KERNEL.guardedStrictUnchecked(Specs.topology(grids), () -> nw$ensure(a, b, dir, op)));
    }

    /**
     * R1-a scope 3 ensureConnection(A, B, direction), under T0 (owner decisions 2026-10-28 (5)). In-world connections
     * only (direction != null: InWorldGridNode.findInWorldConnections, whose existing-connection check runs before
     * T0 — the TOCTOU window). Revalidates both nodes live; exact existing A↔B with this direction → EXISTING (the
     * existing connection is returned; no IllegalStateException is ever caught and turned into success);
     * inconsistent connection state → invariant failure (thrown, counted, never masked); a node destroyed between
     * discovery and T0 → REJECTED_STALE (nothing created); else created exactly once. direction == null (cable bus,
     * P2P, toggle bus, quantum): AE2's own semantics, unchanged.
     */
    private static GridConnection nw$ensure(IGridNode a, IGridNode b, Direction dir, Operation<GridConnection> op) {
        if (dir == null || !(a instanceof GridNode ga) || !(b instanceof GridNode gb) || a == b) {
            return op.call(a, b, dir);
        }
        if (!((GridNodeAccessor) ga).nw$ready() || !((GridNodeAccessor) gb).nw$ready()) {
            COUNTERS.topoRejectedStale.increment();
            return null;                                  // InWorldGridNode ignores the result
        }
        GridConnection ab = nw$between(ga, gb), ba = nw$between(gb, ga);
        if (ab == null && ba == null) {
            GridConnection created = op.call(a, b, dir);
            COUNTERS.topoCreated.increment();
            return created;
        }
        if (ab != null && ab == ba && ab.isInWorld() && ab.getDirection(ga) == dir) {
            COUNTERS.topoExisting.increment();
            return ab;
        }
        COUNTERS.topoInvariantFailure.increment();
        throw new IllegalStateException("[nestworld-compat-ae2] AE2 topology invariant violated: inconsistent connection "
                + ga + " <-> " + gb + " (" + dir + "): a->b=" + ab + " b->a=" + ba);
    }

    private static GridConnection nw$between(GridNode from, GridNode to) {
        for (var c : from.getConnections()) {
            if (c.getOtherSide(from) == to) return (GridConnection) c;
        }
        return null;
    }

    @WrapMethod(method = "destroy")
    private void nw$destroy(Operation<Void> op) {
        Supplier<List<Grid>> grids = () -> {
            List<Grid> l = new ArrayList<>(2);
            l.add(Specs.myGrid(sideA));
            l.add(Specs.myGrid(sideB));
            return l;
        };
        GridPhaseGate.topology(grids, () -> KERNEL.guardedStrictUnchecked(Specs.topology(grids), () -> op.call()));
    }

    /** AE2-NODE-LISTENER-ENDTICK-001: CHANNEL from GridConnection.finalizeChannels (both sides). */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "finalizeChannels", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lappeng/me/GridNode;notifyStatusChange(Lappeng/api/networking/IGridNodeListener$State;)V"))
    private void nw$routeChannelState(GridNode node, appeng.api.networking.IGridNodeListener.State state, Operation<Void> op) {
        net.nestworld.compat.ae2.glue.EndTickStateCallbacks.route(node, state, () -> op.call(node, state));
    }
}
