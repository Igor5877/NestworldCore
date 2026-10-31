package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import appeng.me.cluster.implementations.QuantumCluster;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** D4a member mutation intents: center and ring {@code updateStatus(null, -1, affectWorld)} in {@code QuantumCluster.destroy}. */
@Mixin(value = QuantumCluster.class, remap = false)
public abstract class QuantumClusterD4aMixin {
    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
            target = "Lappeng/blockentity/qnb/QuantumBridgeBlockEntity;updateStatus(Lappeng/me/cluster/implementations/QuantumCluster;BZ)V"))
    private void nw$member(QuantumBridgeBlockEntity r, QuantumCluster c, byte flags, boolean affectWorld, Operation<Void> op) {
        ClusterTeardownCoordinator.member(this, r, "qnb.member.updateStatus", r::getCluster, () -> op.call(r, c, flags, affectWorld));
    }
}
