package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.spatial.SpatialPylonBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.nestworld.compat.ae2.glue.BlockCallbacks;
import org.spongepowered.asm.mixin.Mixin;

/** D2 / I3: SpatialPylonBlock.neighborChanged → SpatialPylonBlockEntity.neighborChanged(BlockPos) (MBCalculator). */
@Mixin(value = SpatialPylonBlockEntity.class, remap = false)
public abstract class SpatialPylonD2Mixin {
    @WrapMethod(method = "neighborChanged")
    private void nw$d2(BlockPos changedPos, Operation<Void> op) {
        BlockCallbacks.route((SpatialPylonBlockEntity) (Object) this, "pylon.neighborChanged", () -> op.call(changedPos));
    }
}
