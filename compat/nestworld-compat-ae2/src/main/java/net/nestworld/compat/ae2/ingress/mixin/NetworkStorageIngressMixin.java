package net.nestworld.compat.ae2.ingress.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.NetworkStorage;
import net.nestworld.compat.ae2.glue.IngressCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2-GRID-DOMAIN-001 D0 diagnostics (opt-in config). Observes only; never cancels or changes arguments. */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageIngressMixin {
    @Inject(method = "insert", at = @At("HEAD"))
    private void nw$ingressInsert(AEKey what, long amount, Actionable type, IActionSource src, CallbackInfoReturnable<Long> cir) {
        IngressCensus.call(type == Actionable.SIMULATE ? "insert/sim" : "insert");
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void nw$ingressExtract(AEKey what, long amount, Actionable mode, IActionSource source, CallbackInfoReturnable<Long> cir) {
        IngressCensus.call(mode == Actionable.SIMULATE ? "extract/sim" : "extract");
    }
}
