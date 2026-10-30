package net.nestworld.compat.ae2.mixin;

import appeng.api.parts.IPartHost;
import appeng.parts.CableBusContainer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.nestworld.compat.ae2.glue.BlockCallbacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * D2 / I4 + I5: CableBusBlock.neighborChanged and Forge onNeighborChange → CableBusContainer.onNeighborChanged;
 * CableBusBlock.updateShape's side effect → CableBusContainer.onUpdateShape (the BlockState is still returned by
 * CableBusBlock synchronously — only this container call is routed).
 */
@Mixin(value = CableBusContainer.class, remap = false)
public abstract class CableBusContainerD2Mixin {
    @Shadow private IPartHost tcb;

    @WrapMethod(method = "onNeighborChanged")
    private void nw$d2Neighbor(BlockGetter level, BlockPos pos, BlockPos neighbor, Operation<Void> op) {
        BlockCallbacks.route(tcb == null ? null : tcb.getBlockEntity(), "cablebus.onNeighborChanged", () -> op.call(level, pos, neighbor));
    }

    @WrapMethod(method = "onUpdateShape")
    private void nw$d2Shape(LevelAccessor level, BlockPos pos, Direction side, Operation<Void> op) {
        BlockCallbacks.route(tcb == null ? null : tcb.getBlockEntity(), "cablebus.onUpdateShape", () -> op.call(level, pos, side));
    }
}
