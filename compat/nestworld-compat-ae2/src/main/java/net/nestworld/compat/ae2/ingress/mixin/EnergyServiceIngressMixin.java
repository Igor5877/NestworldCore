package net.nestworld.compat.ae2.ingress.mixin;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.me.service.EnergyService;
import net.nestworld.compat.ae2.glue.IngressCensus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2-GRID-DOMAIN-001 D0 diagnostics (opt-in config). Observes only. */
@Mixin(value = EnergyService.class, remap = false)
public abstract class EnergyServiceIngressMixin {
    @Inject(method = "extractAEPower(DLappeng/api/config/Actionable;Lappeng/api/config/PowerMultiplier;)D", at = @At("HEAD"))
    private void nw$ingressPower(double amt, Actionable mode, PowerMultiplier pm, CallbackInfoReturnable<Double> cir) {
        IngressCensus.call("power");
    }
}
