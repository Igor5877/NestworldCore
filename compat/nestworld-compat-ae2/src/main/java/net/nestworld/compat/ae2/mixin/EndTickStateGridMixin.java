package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNodeListener;
import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.EndTickStateCallbacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AE2-NODE-LISTENER-ENDTICK-001: GRID_BOOT / POWER from Grid.notifyAllNodes. */
@Mixin(value = Grid.class, remap = false)
public abstract class EndTickStateGridMixin {
    @WrapOperation(method = "notifyAllNodes", at = @At(value = "INVOKE",
            target = "Lappeng/me/GridNode;notifyStatusChange(Lappeng/api/networking/IGridNodeListener$State;)V"))
    private void nw$routeState(GridNode node, IGridNodeListener.State state, Operation<Void> op) {
        EndTickStateCallbacks.route(node, state, () -> op.call(node, state));
    }
}
