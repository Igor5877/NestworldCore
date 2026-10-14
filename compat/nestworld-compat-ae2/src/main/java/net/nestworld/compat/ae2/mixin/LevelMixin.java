package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M1 (001.2 §2, 001.3 §6, 001.4 §4): the only vanilla-class mixin. Outside an AE2 section it costs one
 * thread-local read. In an energy section the output-signal notification is deferred (same-thread FIFO,
 * delivered at the outermost exit, stop at the first exception); in a topology section it is counted
 * and runs inline. Other notification entry points are detectors: reached from an energy section they
 * are an unaudited path (switch to serialization), from a topology section only counted.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @WrapMethod(method = "updateNeighbourForOutputSignal")
    private void nw$outputSignal(BlockPos pos, Block block, Operation<Void> op) {
        if (KERNEL.inSection()) {
            if (KERNEL.inTopologySection()) {
                COUNTERS.m1InTopology.increment();
            } else if (KERNEL.defer(() -> op.call(pos, block))) {
                COUNTERS.m1Deferred.increment();
                return;
            }
        }
        op.call(pos, block);
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void nw$detectNeighborChanged(BlockPos pos, Block block, BlockPos from, CallbackInfo ci) {
        nw$detect("Level.neighborChanged");
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
    private void nw$detectUpdateNeighborsAt(BlockPos pos, Block block, CallbackInfo ci) {
        nw$detect("Level.updateNeighborsAt");
    }

    private static void nw$detect(String site) {
        if (!KERNEL.inSection()) return;
        if (KERNEL.inTopologySection()) {
            COUNTERS.m1InTopology.increment();
            return;
        }
        COUNTERS.m1Unaudited.increment();
        KERNEL.unaudited(site + " reached inside an AE2 energy section");
    }
}
