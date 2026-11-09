package net.nestworld.ae2detect.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.cluster.implementations.QuantumCluster;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.MbDetect;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: MBCalculator.modificationInProgress is set/cleared inside each cluster's destroy(). */
@Mixin(value = {QuantumCluster.class, CraftingCPUCluster.class, SpatialPylonCluster.class}, remap = false)
public abstract class ClusterDestroyDetectMixin {
    @WrapMethod(method = "destroy")
    private void nwd$destroy(Operation<Void> op) {
        StaticDetect.enter(Site.MB_MODIFICATION);
        net.nestworld.ae2detect.D2Detect.mb(true);
        boolean outer = MbDetect.destroyEnter();
        long b0 = net.nestworld.ae2detect.B0Detect.begin();
        try { op.call(); } finally {
            MbDetect.destroyExit(outer);
            net.nestworld.ae2detect.B0Detect.end("mb.destroy", b0);
            StaticDetect.exit(Site.MB_MODIFICATION);
        }
    }
}
