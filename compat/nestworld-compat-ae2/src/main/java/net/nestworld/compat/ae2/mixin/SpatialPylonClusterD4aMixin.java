package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.spatial.SpatialPylonBlockEntity;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** D4a member mutation intents: each line member's {@code updateStatus(null)} in {@code SpatialPylonCluster.destroy}. */
@Mixin(value = SpatialPylonCluster.class, remap = false)
public abstract class SpatialPylonClusterD4aMixin {
    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
            target = "Lappeng/blockentity/spatial/SpatialPylonBlockEntity;updateStatus(Lappeng/me/cluster/implementations/SpatialPylonCluster;)V"))
    private void nw$member(SpatialPylonBlockEntity r, SpatialPylonCluster c, Operation<Void> op) {
        ClusterTeardownCoordinator.member(this, r, "pylon.member.updateStatus", r::getCluster, () -> op.call(r, c));
    }
}
