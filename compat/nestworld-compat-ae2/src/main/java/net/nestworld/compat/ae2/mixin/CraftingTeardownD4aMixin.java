package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import net.nestworld.compat.ae2.glue.ClusterTeardownPorts;
import net.nestworld.compat.ae2.glue.HostDomainCallbackDelivery;
import org.spongepowered.asm.mixin.Mixin;

/**
 * D4a entry adapters, crafting CPU: {@code breakCluster} (block removal and AE2's re-formation path) and
 * {@code disconnect} (MBCalculator disassembly). The cluster is captured at the call; the teardown runs once through the
 * coordinator in the core's domain. Coordinator off → AE2's original method.
 */
@Mixin(value = CraftingBlockEntity.class, remap = false)
public abstract class CraftingTeardownD4aMixin {
    @WrapMethod(method = "breakCluster")
    private void nw$breakCluster(Operation<Void> op) {
        CraftingBlockEntity self = (CraftingBlockEntity) (Object) this;
        CraftingCPUCluster cluster = self.getCluster();
        if (!ClusterTeardownCoordinator.ENABLED && cluster != null) ClusterTeardownCoordinator.bypassed(self);
        if (!ClusterTeardownCoordinator.ENABLED || cluster == null) {
            op.call();
            return;
        }
        BlockEntity core = ((CraftingCPUClusterAccessor) (Object) cluster).nw$getCore();
        ClusterTeardownCoordinator.request(cluster, core != null ? core : self, "crafting.breakCluster",
                () -> ClusterTeardownPorts.craftingBreakCluster(self, cluster));
    }

    @WrapMethod(method = "disconnect")
    private void nw$disconnect(boolean update, Operation<Void> op) {
        CraftingBlockEntity self = (CraftingBlockEntity) (Object) this;
        CraftingCPUCluster cluster = self.getCluster();
        if (!ClusterTeardownCoordinator.ENABLED && cluster != null) ClusterTeardownCoordinator.bypassed(self);
        if (!ClusterTeardownCoordinator.ENABLED || cluster == null) {
            op.call(update);
            return;
        }
        BlockEntity core = ((CraftingCPUClusterAccessor) (Object) cluster).nw$getCore();
        // port of CraftingBlockEntity.disconnect: destroy the captured cluster; the requester's own updateSubType in ITS domain
        ClusterTeardownCoordinator.request(cluster, core != null ? core : self, "crafting.disconnect", () -> {
            cluster.destroy();
            if (update) {
                HostDomainCallbackDelivery.deliverForHost(HostDomainCallbackDelivery.Channel.CLUSTER, null, self,
                        "crafting.disconnect.updateSubType", () -> self.updateSubType(true));
            }
        });
    }
}
