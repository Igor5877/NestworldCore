package net.nestworld.ae2detect.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.cluster.implementations.QuantumCluster;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: MBCalculator.modificationInProgress is set/cleared inside each cluster's destroy(). */
@Mixin(value = {QuantumCluster.class, CraftingCPUCluster.class, SpatialPylonCluster.class}, remap = false)
public abstract class ClusterDestroyDetectMixin {
    @WrapMethod(method = "destroy")
    private void nwd$destroy(Operation<Void> op) {
        StaticDetect.enter(Site.MB_MODIFICATION);
        try { op.call(); } finally { StaticDetect.exit(Site.MB_MODIFICATION); }
    }
}
