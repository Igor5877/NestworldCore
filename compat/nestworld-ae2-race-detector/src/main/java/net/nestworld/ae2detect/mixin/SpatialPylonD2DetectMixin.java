package net.nestworld.ae2detect.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.ae2detect.D2Detect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observation only (AE2-PIN-LEAK-001 D2): execution site of pylon neighbour-driven MBCalculator work. */
@Mixin(value = appeng.blockentity.spatial.SpatialPylonBlockEntity.class, remap = false)
public abstract class SpatialPylonD2DetectMixin {
    @WrapOperation(method = "neighborChanged", at = @At(value = "INVOKE",
            target = "Lappeng/me/cluster/implementations/SpatialPylonCalculator;updateMultiblockAfterNeighborUpdate(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
    private void nwd$d2(appeng.me.cluster.implementations.SpatialPylonCalculator calc, ServerLevel level, BlockPos loc, BlockPos changed, Operation<Void> op) {
        D2Detect.executed("pylon", (BlockEntity) (Object) this);
        op.call(calc, level, loc, changed);
    }
}
