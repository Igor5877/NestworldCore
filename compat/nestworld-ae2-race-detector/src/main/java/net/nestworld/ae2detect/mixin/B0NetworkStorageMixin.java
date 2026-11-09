package net.nestworld.ae2detect.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.NetworkStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;

/** B0 (observation only): grid storage ops per thread class; off-MAIN callers sampled (transit proxy). */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class B0NetworkStorageMixin {
    @WrapMethod(method = "insert")
    private long nwd$insert(AEKey what, long amount, Actionable mode, IActionSource src, Operation<Long> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("storage.insert");
        try { return op.call(what, amount, mode, src); } finally {
            if (t != 0) B0Detect.end(mode == Actionable.MODULATE ? "storage.insert.MOD" : "storage.insert.SIM", t);
        }
    }

    @WrapMethod(method = "extract")
    private long nwd$extract(AEKey what, long amount, Actionable mode, IActionSource src, Operation<Long> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("storage.extract");
        try { return op.call(what, amount, mode, src); } finally {
            if (t != 0) B0Detect.end(mode == Actionable.MODULATE ? "storage.extract.MOD" : "storage.extract.SIM", t);
        }
    }
}
