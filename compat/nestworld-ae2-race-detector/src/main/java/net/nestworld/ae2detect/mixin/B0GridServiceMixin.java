package net.nestworld.ae2detect.mixin;

import appeng.api.networking.IGridServiceProvider;
import appeng.me.Grid;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** B0 (observation only): per-service split of a grid's end-of-tick work. */
@Mixin(value = Grid.class, remap = false)
public abstract class B0GridServiceMixin {
    @WrapOperation(method = "onServerEndTick", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/IGridServiceProvider;onServerEndTick()V"))
    private void nwd$serverEnd(IGridServiceProvider svc, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(svc); } finally { if (t != 0) B0Detect.end("svc.serverEnd." + svc.getClass().getSimpleName(), t); }
    }

    @WrapOperation(method = "onLevelEndTick", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/IGridServiceProvider;onLevelEndTick(Lnet/minecraft/world/level/Level;)V"))
    private void nwd$levelEnd(IGridServiceProvider svc, Level level, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(svc, level); } finally { if (t != 0) B0Detect.end("svc.levelEnd." + svc.getClass().getSimpleName(), t); }
    }
}
