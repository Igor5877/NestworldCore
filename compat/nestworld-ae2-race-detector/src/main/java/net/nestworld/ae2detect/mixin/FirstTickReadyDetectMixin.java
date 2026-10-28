package net.nestworld.ae2detect.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Consumer;
import net.nestworld.ae2detect.BeReadyDetect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observation only: the real readiness call ({@code initFunction.accept(be)}) inside FirstTickInfo.callInit —
 *  innermost, so it sees the thread that actually runs onReady even if an addon wraps/defers callInit. */
@Mixin(targets = "appeng.hooks.ticking.ServerBlockEntityRepo$FirstTickInfo", remap = false)
public abstract class FirstTickReadyDetectMixin {
    @WrapOperation(method = "callInit", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void nwd$ready(Consumer<Object> init, Object be, Operation<Void> op) {
        BeReadyDetect.beforeReady(be);
        Throwable failure = null;
        try {
            op.call(init, be);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            BeReadyDetect.afterReady(be, failure);
        }
    }
}
