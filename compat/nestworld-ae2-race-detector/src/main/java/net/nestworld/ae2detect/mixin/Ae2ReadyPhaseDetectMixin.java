package net.nestworld.ae2detect.mixin;

import appeng.hooks.ticking.TickHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.ae2detect.PhaseDetect;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: AE2 first-tick readiness window (server thread). */
@Mixin(value = TickHandler.class, remap = false)
public abstract class Ae2ReadyPhaseDetectMixin {
    @WrapMethod(method = "readyBlockEntities")
    private void nwd$ready(ServerLevel level, Operation<Void> op) {
        PhaseDetect.enter(PhaseDetect.W.AE2_READY);
        try { op.call(level); } finally { PhaseDetect.exit(PhaseDetect.W.AE2_READY); }
    }
}
