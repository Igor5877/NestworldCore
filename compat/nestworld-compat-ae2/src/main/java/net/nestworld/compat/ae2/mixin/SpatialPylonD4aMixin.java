package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.spatial.SpatialPylonBlockEntity;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import org.spongepowered.asm.mixin.Mixin;

/**
 * D4a entry adapter, spatial pylon: {@code disconnect} (setRemoved / unload / MBCalculator). Anchor = the line member
 * with the lowest position (pylons have no core). Port: destroy the captured cluster; every line member — the requester
 * included — is updated by the member intents of {@code destroy} (AE2's extra {@code this.updateStatus(null)} is the same
 * update again).
 */
@Mixin(value = SpatialPylonBlockEntity.class, remap = false)
public abstract class SpatialPylonD4aMixin {
    @WrapMethod(method = "disconnect")
    private void nw$disconnect(boolean b, Operation<Void> op) {
        SpatialPylonBlockEntity self = (SpatialPylonBlockEntity) (Object) this;
        SpatialPylonCluster cluster = self.getCluster();
        if (!ClusterTeardownCoordinator.ENABLED && cluster != null) ClusterTeardownCoordinator.bypassed(self);
        if (!ClusterTeardownCoordinator.ENABLED || cluster == null) {
            op.call(b);
            return;
        }
        BlockEntity anchor = self;
        for (var it = cluster.getBlockEntities(); it.hasNext(); ) {
            SpatialPylonBlockEntity m = it.next();
            if (m.getBlockPos().compareTo(anchor.getBlockPos()) < 0) anchor = m;
        }
        ClusterTeardownCoordinator.request(cluster, anchor, "pylon.disconnect", cluster::destroy);
    }
}
