package net.nestworld.compat.ae2.mixin;

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
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** G1: connection create (merge) and destroy (split). T0 plus both sides' overlays. */
@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionMixin {
    @Shadow private GridNode sideA;
    @Shadow private GridNode sideB;

    @WrapMethod(method = "create")
    private static GridConnection nw$create(IGridNode a, IGridNode b, Direction dir, Operation<GridConnection> op) {
        return KERNEL.guardedStrictUnchecked(Specs.topology(() -> {
            List<Grid> l = new ArrayList<>(2);
            if (a instanceof GridNode ga) l.add(Specs.myGrid(ga));
            if (b instanceof GridNode gb) l.add(Specs.myGrid(gb));
            return l;
        }), () -> op.call(a, b, dir));
    }

    @WrapMethod(method = "destroy")
    private void nw$destroy(Operation<Void> op) {
        KERNEL.guardedStrictUnchecked(Specs.topology(() -> {
            List<Grid> l = new ArrayList<>(2);
            l.add(Specs.myGrid(sideA));
            l.add(Specs.myGrid(sideB));
            return l;
        }), () -> op.call());
    }
}
