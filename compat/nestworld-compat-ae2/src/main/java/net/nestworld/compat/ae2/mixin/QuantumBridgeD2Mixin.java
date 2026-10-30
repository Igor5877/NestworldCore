package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.nestworld.compat.ae2.glue.BlockCallbacks;
import org.spongepowered.asm.mixin.Mixin;

/** D2 / I3: QuantumBaseBlock.neighborChanged → QuantumBridgeBlockEntity.neighborUpdate (MBCalculator). */
@Mixin(value = QuantumBridgeBlockEntity.class, remap = false)
public abstract class QuantumBridgeD2Mixin {
    @WrapMethod(method = "neighborUpdate")
    private void nw$d2(BlockPos fromPos, Operation<Void> op) {
        BlockCallbacks.route((QuantumBridgeBlockEntity) (Object) this, "qnb.neighborUpdate", () -> op.call(fromPos));
    }
}
