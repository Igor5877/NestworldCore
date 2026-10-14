package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registry mutation under T0 ({@code Grid.nextSerial} and the registry side buffers are touched only
 * by topology entries), and the main-affinity premise of 001.4 §3 asserted at every traversal.
 */
@Mixin(value = TickHandler.class, remap = false)
public abstract class TickHandlerMixin {
    @WrapMethod(method = "addNetwork")
    private void nw$addNetwork(Grid grid, Operation<Void> op) {
        KERNEL.guardedStrictUnchecked(Specs.topology(List::of), () -> op.call(grid));
    }

    @WrapMethod(method = "removeNetwork")
    private void nw$removeNetwork(Grid grid, Operation<Void> op) {
        KERNEL.guardedStrictUnchecked(Specs.topology(List::of), () -> op.call(grid));
    }

    @Inject(method = "onServerTickStart", at = @At("HEAD"))
    private void nw$mainStart(CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerTickStart");
    }

    @Inject(method = "onServerTickEnd", at = @At("HEAD"))
    private void nw$mainEnd(CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerTickEnd");
    }

    @Inject(method = "onServerLevelTickStart", at = @At("HEAD"))
    private void nw$mainLevelStart(ServerLevel level, CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerLevelTickStart");
    }

    @Inject(method = "onServerLevelTickEnd", at = @At("HEAD"))
    private void nw$mainLevelEnd(ServerLevel level, CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerLevelTickEnd");
    }
}
