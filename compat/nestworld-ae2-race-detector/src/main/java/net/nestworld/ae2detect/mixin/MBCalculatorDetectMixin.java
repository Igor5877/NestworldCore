package net.nestworld.ae2detect.mixin;

import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.ae2detect.MbDetect;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Observation only: calculateMultiblock reads and sets the static MBCalculator.modificationInProgress; D8 counters
 * (MbDetect) at calculateMultiblock and setModificationInProgress. Priority 100 (rig run2: 2000 made it the OUTER
 * wrapper, counting entries the lane only queued): applied inside a compat
 * layer's wrapper of the same method, so the counters see AE2's own executions only (checked by the B1 fixture).
 */
@Mixin(value = MBCalculator.class, remap = false, priority = 100)
public abstract class MBCalculatorDetectMixin {
    @WrapMethod(method = "calculateMultiblock")
    private void nwd$calc(ServerLevel level, BlockPos loc, Operation<Void> op) {
        StaticDetect.enter(Site.MB_MODIFICATION);
        net.nestworld.ae2detect.D2Detect.mb(false);
        String host = "mb@" + loc.toShortString();
        net.nestworld.ae2detect.FailureAttribution.enter(net.nestworld.ae2detect.FailureAttribution.Kind.MB, host);
        boolean skipped = MBCalculator.isModificationInProgress();
        boolean outer = MbDetect.calcEnter(this, skipped);
        long t0 = System.nanoTime(), b0 = net.nestworld.ae2detect.B0Detect.begin();
        Throwable err = null;
        try {
            op.call(level, loc);
        } catch (Throwable t) {
            err = t;
            throw t;
        } finally {
            MbDetect.calcExit(outer, skipped, t0, err);
            net.nestworld.ae2detect.B0Detect.end("mb.calc", b0);
            StaticDetect.exit(Site.MB_MODIFICATION);
            net.nestworld.ae2detect.FailureAttribution.exit(net.nestworld.ae2detect.FailureAttribution.Kind.MB, host);
        }
    }

    @WrapMethod(method = "setModificationInProgress")
    private static void nwd$set(IAECluster cluster, Operation<Void> op) {
        MbDetect.set(cluster, MBCalculator.isModificationInProgress(), () -> op.call(cluster));
    }
}
