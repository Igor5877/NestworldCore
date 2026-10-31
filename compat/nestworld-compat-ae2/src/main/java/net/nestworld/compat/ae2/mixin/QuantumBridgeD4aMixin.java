package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import appeng.me.cluster.implementations.QuantumCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import org.spongepowered.asm.mixin.Mixin;

/** D4a entry adapters, QNB: {@code breakClusterOnRemove} (block removal) and {@code disconnect} (setRemoved / unload / MBCalculator). */
@Mixin(value = QuantumBridgeBlockEntity.class, remap = false)
public abstract class QuantumBridgeD4aMixin {
    @WrapMethod(method = "breakClusterOnRemove")
    private void nw$breakClusterOnRemove(Operation<Void> op) {
        QuantumBridgeBlockEntity self = (QuantumBridgeBlockEntity) (Object) this;
        QuantumCluster cluster = self.getCluster();
        if (!ClusterTeardownCoordinator.ENABLED && cluster != null) ClusterTeardownCoordinator.bypassed(self);
        if (!ClusterTeardownCoordinator.ENABLED || cluster == null) {
            op.call();
            return;
        }
        BlockEntity center = cluster.getCenter();
        // port: AE2 marks the removed bridge as removed while its cluster is destroyed (no block-state restore)
        ClusterTeardownCoordinator.request(cluster, center != null ? center : self, "qnb.breakClusterOnRemove", () -> {
            BlockEntityRemoveFlagAccessor flag = (BlockEntityRemoveFlagAccessor) (Object) self;
            boolean prev = flag.nw$removeFlag();
            flag.nw$setRemoveFlag(true);
            try {
                cluster.destroy();
            } finally {
                flag.nw$setRemoveFlag(prev);
            }
        });
    }

    @WrapMethod(method = "disconnect")
    private void nw$disconnect(boolean affectWorld, Operation<Void> op) {
        QuantumBridgeBlockEntity self = (QuantumBridgeBlockEntity) (Object) this;
        QuantumCluster cluster = self.getCluster();
        if (!ClusterTeardownCoordinator.ENABLED && cluster != null) ClusterTeardownCoordinator.bypassed(self);
        if (!ClusterTeardownCoordinator.ENABLED || cluster == null) {
            op.call(affectWorld);
            return;
        }
        BlockEntity center = cluster.getCenter();
        ClusterTeardownCoordinator.request(cluster, center != null ? center : self, "qnb.disconnect", () -> {
            if (!affectWorld) cluster.setUpdateStatus(false);
            cluster.destroy();
        });
    }
}
