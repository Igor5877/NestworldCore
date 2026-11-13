package net.nestworld.compat.ae2.ingress.mixin;

import appeng.me.Grid;
import net.nestworld.compat.ae2.glue.IngressCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2-GRID-DOMAIN-001 D0 diagnostics (opt-in config): every external code path reaches grid services through
 *  Grid.getService(Class) — sample who asks for which service from outside the grid tick. Observes only. */
@Mixin(value = Grid.class, remap = false)
public abstract class GridServiceIngressMixin {
    @Inject(method = "getService", at = @At("HEAD"))
    private void nw$ingressService(Class<?> iface, CallbackInfoReturnable<Object> cir) {
        if (IngressCensus.on) IngressCensus.call("service:" + iface.getSimpleName());
    }
}
