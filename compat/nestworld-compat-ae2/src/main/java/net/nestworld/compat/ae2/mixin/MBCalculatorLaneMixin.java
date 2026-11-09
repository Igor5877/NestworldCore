package net.nestworld.compat.ae2.mixin;

import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.glue.MbLane;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * AE2-DEMAIN-001 B1 (D1–D5): ONE shared semantic entrypoint for every multiblock calculator — AE2's own (crafting CPU,
 * QNB, spatial pylon) and every addon calculator that extends {@code MBCalculator} (AdvancedAE, ExtendedAE, neoecoae,
 * molecularmanipulator; megacells / mae2 / ae2omnicells / extendedae_plus / crazyae2addons crafting blocks use AE2's
 * CraftingBlockEntity + CraftingCPUCalculator). The base method body is the only implementation (subclass overrides call
 * super), so wrapping it covers them all. {@code setModificationInProgress} (static, one target for every writer)
 * adopts / verifies unknown writers. Lane off = AE2 unchanged.
 */
@Mixin(value = MBCalculator.class, remap = false)
public abstract class MBCalculatorLaneMixin {
    @WrapMethod(method = "calculateMultiblock")
    private void nw$lane(ServerLevel level, BlockPos loc, Operation<Void> op) {
        MbLane.calc((MBCalculator<?, ?>) (Object) this, level, loc, () -> op.call(level, loc));
    }

    @WrapMethod(method = "setModificationInProgress")
    private static void nw$set(IAECluster cluster, Operation<Void> op) {
        MbLane.set(cluster, () -> op.call(cluster));
    }

    /** D2 fence: bounds known, nothing mutated yet (MBCalculator:119). */
    @WrapOperation(method = "calculateMultiblock", at = @At(value = "INVOKE",
            target = "Lappeng/me/cluster/MBCalculator;checkMultiblockScale(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean nw$scale(MBCalculator<?, ?> self, BlockPos min, BlockPos max, Operation<Boolean> op,
                             @Local(argsOnly = true) ServerLevel level) {
        boolean ok = op.call(self, min, max);
        if (ok) MbLane.fence(self, level, min, max);
        return ok;
    }

    /** Fixture probe point only (MBCalculator:143, flag set); production hook is null. */
    @WrapOperation(method = "calculateMultiblock", at = @At(value = "INVOKE",
            target = "Lappeng/me/cluster/IAECluster;updateStatus(Z)V"))
    private void nw$status(IAECluster cluster, boolean updateGrid, Operation<Void> op) {
        MbLane.statusHook((MBCalculator<?, ?>) (Object) this);
        op.call(cluster, updateGrid);
    }
}
