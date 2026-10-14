package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import net.nestworld.compat.ae2.glue.NwTickManager;
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Grid.class, remap = false)
public abstract class GridMixin {
    /** TickManagerService has no grid reference of its own; T1/T2 resolve the grid's lock through it. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nw$bindTickManager(GridNode center, CallbackInfo ci) {
        Grid self = (Grid) (Object) this;
        ((NwTickManager) self.getTickManager()).nw$setGrid(self);
    }

    /** G1: grid creation (T0; the centre's current grid, if any, is part of the move). */
    @WrapMethod(method = "create")
    private static Grid nw$create(GridNode center, Operation<Grid> op) {
        return KERNEL.guardedStrictUnchecked(Specs.topology(() -> {
            List<Grid> l = new ArrayList<>();
            l.add(Specs.myGrid(center));
            return l;
        }), () -> op.call(center));
    }
}
