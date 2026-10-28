package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import net.nestworld.compat.ae2.glue.GridPhaseGate;
import net.nestworld.compat.ae2.glue.Specs;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;

/** G1 node moves and destruction; G2 listener calls after commit (001.1 §3/§6). */
@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeMixin {
    @WrapMethod(method = "setGrid")
    private void nw$setGrid(Grid grid, Operation<Void> op) {
        GridNode self = (GridNode) (Object) this;
        Supplier<List<Grid>> grids = () -> {
            List<Grid> l = new ArrayList<>(2);
            l.add(Specs.myGrid(self));
            l.add(grid);
            return l;
        };
        GridPhaseGate.topology(grids, () -> KERNEL.guardedStrictUnchecked(Specs.topology(grids), () -> op.call(grid)));
    }

    @WrapMethod(method = "destroy")
    private void nw$destroy(Operation<Void> op) {
        GridNode self = (GridNode) (Object) this;
        Supplier<List<Grid>> grids = () -> Specs.around(self);
        GridPhaseGate.topology(grids, () -> KERNEL.guardedStrictUnchecked(Specs.topology(grids), () -> op.call()));
    }

    /**
     * R1-a scope 3: every node-listener callback fired inside a guarded section becomes an intent (deferred to the
     * outermost exit, after unlock, FIFO) delivered in the host's current BE execution domain (TopologyCallbacks).
     * Replaces the setGrid-only M1 deferral (onGridChanged / POWER status); now also covers addConnection /
     * removeConnection (onInWorldConnectionChanged), which previously ran under T0. notifyStatusChange goes through
     * callListener and is covered by the same path.
     */
    @WrapMethod(method = "callListener")
    private void nw$callListener(GridNode.ListenerCallback<?> cb, Operation<Void> op) {
        GridNode self = (GridNode) (Object) this;
        if (!net.nestworld.compat.ae2.glue.TopologyCallbacks.record(self, () -> op.call(cb))) op.call(cb);
    }
}
