package net.nestworld.ae2detect.mixin;

import net.nestworld.ae2detect.PhaseDetect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only: the BE tick list runs on main (band) or on a region thread (interior work round). Targets a
 *  Core class by name (no compile dependency on the Core jar); HEAD/RETURN only count windows. */
@Mixin(targets = "net.nestworld.region.NestworldDimensionRegion", remap = false)
public abstract class BePhaseDetectMixin {
    @Unique
    private static final ThreadLocal<PhaseDetect.W> nwd$w = new ThreadLocal<>();

    @Inject(method = "tickBlockEntityList", at = @At("HEAD"))
    private void nwd$head(CallbackInfo ci) {
        PhaseDetect.W w = PhaseDetect.beWindow();
        nwd$w.set(w);
        PhaseDetect.enter(w);
    }

    @Inject(method = "tickBlockEntityList", at = @At("RETURN"))
    private void nwd$ret(CallbackInfo ci) {
        PhaseDetect.W w = nwd$w.get();
        if (w != null) PhaseDetect.exit(w);
    }
}
