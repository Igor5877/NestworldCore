package net.nestworld.ae2detect.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.me.service.EnergyService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.Detect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only: each wrapper calls the original exactly once; HEAD injects only count. */
@Mixin(value = EnergyService.class, remap = false)
public abstract class EnergyServiceDetectMixin {
    @WrapMethod(method = "injectProviderPower")
    private double nwd$inject(double amt, Actionable mode, Operation<Double> op) {
        Detect.injectCalls.increment();
        if (Detect.foreign(Detect.INJECTING, this)) Detect.injectWhileForeignInject.increment();
        if (Detect.foreign(Detect.EXTRACTING, this)) Detect.injectWhileForeignExtract.increment();
        Detect.enter(Detect.INJECTING, this);
        try {
            return op.call(amt, mode);
        } finally {
            Detect.exit(Detect.INJECTING, this);
        }
    }

    @WrapMethod(method = "extractProviderPower")
    private double nwd$extract(double amt, Actionable mode, Operation<Double> op) {
        Detect.extractCalls.increment();
        if (Detect.foreign(Detect.INJECTING, this)) Detect.extractWhileForeignInject.increment();
        Detect.enter(Detect.EXTRACTING, this);
        try {
            return op.call(amt, mode);
        } finally {
            Detect.exit(Detect.EXTRACTING, this);
        }
    }

    private void nwd$requester(String what) {
        if (((EnergyServiceAccessor) this).nwd$injecting()) {
            if (Detect.foreign(Detect.INJECTING, this)) {
                Detect.requesterModifyForeignInject.increment();
                Detect.noteFirst(what);
            } else {
                Detect.requesterModifySameThread.increment();
            }
        }
    }

    private void nwd$provider(String what) {
        if (((EnergyServiceAccessor) this).nwd$extracting()) {
            if (Detect.foreign(Detect.EXTRACTING, this)) {
                Detect.providerModifyForeignExtract.increment();
                Detect.noteFirst(what);
            } else {
                Detect.providerModifySameThread.increment();
            }
        }
    }

    @Inject(method = "addRequester", at = @At("HEAD"))
    private void nwd$addRequester(IAEPowerStorage node, CallbackInfo ci) {
        nwd$requester("addRequester");
    }

    @Inject(method = "removeRequester", at = @At("HEAD"))
    private void nwd$removeRequester(IAEPowerStorage node, CallbackInfo ci) {
        nwd$requester("removeRequester");
    }

    @Inject(method = "addProvider", at = @At("HEAD"))
    private void nwd$addProvider(IAEPowerStorage node, CallbackInfo ci) {
        nwd$provider("addProvider");
    }

    @Inject(method = "removeProvider", at = @At("HEAD"))
    private void nwd$removeProvider(IAEPowerStorage node, CallbackInfo ci) {
        nwd$provider("removeProvider");
    }
}
