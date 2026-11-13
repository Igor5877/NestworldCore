package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.GridWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * AE2-GRID-DOMAIN-001 model A: AE2 15.4.x QuantumBridgeBlockEntity.serverTick does work ONLY while {@code updateStatus}
 * is set: QuantumCluster.updateStatus (global Locatables registry, remote bridge lookup in another dimension,
 * grid connection create/destroy = topology) + neighborUpdate (multiblock recalculation reading neighbours). Never on
 * the AE2 domain thread: leave the flag set; HOST_ESCAPE_MAIN runs the unchanged original once on MAIN at the next barrier (grid stays model A).
 */
@Mixin(value = QuantumBridgeBlockEntity.class, remap = false)
public abstract class QuantumBridgeDeferMixin {
    @Shadow private boolean updateStatus;

    @WrapMethod(method = "serverTick")
    private void nw$deferTopology(Operation<Void> op) {
        if (updateStatus && GridWorker.isWorkerThread()) {
            net.nestworld.compat.ae2.glue.HostEscape.request((net.minecraft.world.level.block.entity.BlockEntity) (Object) this);
            return;
        }
        op.call();
    }
}
