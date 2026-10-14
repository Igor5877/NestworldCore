package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.networking.IGridNodeListener;
import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.List;
import net.nestworld.compat.ae2.glue.GridPhaseGate;
import net.nestworld.compat.ae2.glue.Specs;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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

    @WrapOperation(method = "setGrid", at = @At(value = "INVOKE",
            target = "Lappeng/me/GridNode;callListener(Lappeng/me/GridNode$ListenerCallback;)V"))
    private void nw$deferListener(GridNode node, GridNode.ListenerCallback<?> cb, Operation<Void> op) {
        if (!KERNEL.defer(() -> op.call(node, cb))) op.call(node, cb);
    }

    @WrapOperation(method = "setGrid", at = @At(value = "INVOKE",
            target = "Lappeng/me/GridNode;notifyStatusChange(Lappeng/api/networking/IGridNodeListener$State;)V"))
    private void nw$deferStatus(GridNode node, IGridNodeListener.State state, Operation<Void> op) {
        if (!KERNEL.defer(() -> op.call(node, state))) op.call(node, state);
    }
}
