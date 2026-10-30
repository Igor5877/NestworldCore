package net.nestworld.ae2detect.mixin;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.CableBusContainer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.nestworld.ae2detect.D2Detect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Observation only (AE2-PIN-LEAK-001 D2): execution site of cable bus neighbour (per part) and shape updates. */
@Mixin(value = CableBusContainer.class, remap = false)
public abstract class CableBusContainerD2DetectMixin {
    @Shadow private IPartHost tcb;

    @WrapOperation(method = "onNeighborChanged", at = @At(value = "INVOKE",
            target = "Lappeng/api/parts/IPart;onNeighborChanged(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
    private void nwd$neighbor(IPart part, BlockGetter level, BlockPos pos, BlockPos neighbor, Operation<Void> op) {
        D2Detect.executed("cablebus.neighbor", tcb == null ? null : tcb.getBlockEntity());
        op.call(part, level, pos, neighbor);
    }

    @WrapOperation(method = "onUpdateShape", at = @At(value = "INVOKE", target = "Lappeng/parts/CableBusContainer;invalidateShapes()V"))
    private void nwd$shape(CableBusContainer self, Operation<Void> op) {
        D2Detect.executed("cablebus.shape", tcb == null ? null : tcb.getBlockEntity());
        op.call(self);
    }
}
