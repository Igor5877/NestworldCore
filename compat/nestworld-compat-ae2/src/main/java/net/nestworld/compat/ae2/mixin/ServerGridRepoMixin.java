package net.nestworld.compat.ae2.mixin;

import net.nestworld.compat.ae2.glue.Ae2Compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The only mutation of the traversed registry set; main-only by audit (001.4 §3.1). */
@Mixin(targets = "appeng.hooks.ticking.ServerGridRepo", remap = false)
public abstract class ServerGridRepoMixin {
    @Inject(method = "updateNetworks", at = @At("HEAD"))
    private void nw$main(CallbackInfo ci) {
        Ae2Compat.assertMain("ServerGridRepo.updateNetworks");
    }
}
