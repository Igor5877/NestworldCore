package net.nestworld.ae2detect.mixin;

import net.nestworld.ae2detect.BeReadyDetect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observation only: S2 "The node has already been initialized" — fires only on getInitData's throw path (the
 *  single NEW IllegalStateException), just before AE2 throws. The return type (private InitData) is not referenced. */
@Mixin(targets = "appeng.me.ManagedGridNode", remap = false)
public abstract class ManagedGridNodeInitDetectMixin {
    @Inject(method = "getInitData", at = @At(value = "NEW", target = "java/lang/IllegalStateException"))
    private void nwd$initDataThrow(CallbackInfoReturnable<Object> cir) {
        BeReadyDetect.record(BeReadyDetect.Symptom.S2_ALREADY_INITIALIZED,
                new IllegalStateException("The node has already been initialized (observed at throw site)"), false);
    }
}
