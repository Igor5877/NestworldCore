package net.nestworld.ae2detect.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.PhaseDetect;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: free-running region entity tick window. */
@Mixin(targets = "net.nestworld.region.RegionThread", remap = false)
public abstract class RegionEntityPhaseDetectMixin {
    @WrapMethod(method = "tickEntities")
    private void nwd$entities(Operation<Void> op) {
        PhaseDetect.enter(PhaseDetect.W.REGION_ENTITY);
        try { op.call(); } finally { PhaseDetect.exit(PhaseDetect.W.REGION_ENTITY); }
    }
}
