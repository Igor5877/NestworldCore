package net.nestworld.compat.ae2.ingress.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.nestworld.compat.ae2.glue.ChestTouchCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MVP-001 sizing (census only): Applied-Mekanistics chemical storage-bus facade ops by thread and mode. */
@Mixin(targets = "me.ramidzkh.mekae2.ae2.stack.ChemicalHandlerFacade", remap = false)
public abstract class ChemicalFacadeCensusMixin {
    @Inject(method = "insert", at = @At("HEAD"))
    private void nw$insert(AEKey what, long amount, Actionable mode, IActionSource src, CallbackInfoReturnable<Long> cir) {
        ChestTouchCensus.facade("ChemicalHandlerFacade insert-" + mode);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void nw$extract(AEKey what, long amount, Actionable mode, IActionSource src, CallbackInfoReturnable<Long> cir) {
        ChestTouchCensus.facade("ChemicalHandlerFacade extract-" + mode);
    }

    @Inject(method = "getAvailableStacks", at = @At("HEAD"))
    private void nw$list(appeng.api.stacks.KeyCounter out, CallbackInfo ci) {
        ChestTouchCensus.facade("ChemicalHandlerFacade getAvailableStacks");
    }
}
