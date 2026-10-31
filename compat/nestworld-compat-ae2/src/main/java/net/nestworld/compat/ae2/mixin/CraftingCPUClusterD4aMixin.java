package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator;
import net.nestworld.compat.ae2.glue.ClusterTeardownPorts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * D4a, crafting CPU cluster side: member mutation intents (each member's {@code updateStatus(null)} in
 * {@code CraftingCPUCluster.destroy}) and the cluster-identity entry adapter for {@code CraftingCPUCluster.breakCluster}.
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterD4aMixin {
    /**
     * AE2 delegates {@code cluster.breakCluster()} to {@code core.breakCluster()}, which tears down whatever cluster the
     * core points to NOW. With the teardown deferred, the re-formation path ({@code member.updateStatus(new)} →
     * {@code old.breakCluster()}) reaches a core that already belongs to the NEW cluster → the new cluster would be torn
     * down. The request is therefore made for THIS cluster (identity of the call), anchored at its core.
     */
    @WrapMethod(method = "breakCluster")
    private void nw$breakCluster(Operation<Void> op) {
        CraftingCPUCluster self = (CraftingCPUCluster) (Object) this;
        CraftingBlockEntity core = ((CraftingCPUClusterAccessor) (Object) self).nw$getCore();
        if (!ClusterTeardownCoordinator.ENABLED || core == null) {
            op.call();
            return;
        }
        ClusterTeardownCoordinator.request(self, core, "crafting.cluster.breakCluster",
                () -> ClusterTeardownPorts.craftingBreakCluster(core, self));
    }

    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
            target = "Lappeng/blockentity/crafting/CraftingBlockEntity;updateStatus(Lappeng/me/cluster/implementations/CraftingCPUCluster;)V"))
    private void nw$member(CraftingBlockEntity r, CraftingCPUCluster c, Operation<Void> op) {
        ClusterTeardownCoordinator.member(this, r, "crafting.member.updateStatus", r::getCluster, () -> op.call(r, c));
    }
}
