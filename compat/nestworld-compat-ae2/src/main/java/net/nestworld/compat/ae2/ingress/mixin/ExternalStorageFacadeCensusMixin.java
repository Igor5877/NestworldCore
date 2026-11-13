package net.nestworld.compat.ae2.ingress.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.nestworld.compat.ae2.glue.ChestTouchCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MVP-001 storage-bus sizing (census only): facade-level insert/extract counts by thread and mode, and
 *  getAvailableStacks calls (inherited — counted on the abstract facade). No behaviour change. */
@Mixin(value = appeng.me.storage.ExternalStorageFacade.class, remap = false)
public abstract class ExternalStorageFacadeCensusMixin {
    @Inject(method = "insert", at = @At("HEAD"))
    private void nw$insert(AEKey what, long amount, Actionable mode, IActionSource src, CallbackInfoReturnable<Long> cir) {
        ChestTouchCensus.facade(((Object) this).getClass().getSimpleName() + " insert-" + mode);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void nw$extract(AEKey what, long amount, Actionable mode, IActionSource src, CallbackInfoReturnable<Long> cir) {
        ChestTouchCensus.facade(((Object) this).getClass().getSimpleName() + " extract-" + mode);
    }
}
