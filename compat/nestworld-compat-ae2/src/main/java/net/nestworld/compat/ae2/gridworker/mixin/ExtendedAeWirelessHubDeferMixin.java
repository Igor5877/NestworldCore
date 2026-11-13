package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.blockentity.grid.AENetworkBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.GridWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Same as {@link ExtendedAeWirelessConnectorDeferMixin} for the multi-link TileWirelessHub (per-slot flags). */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileWirelessHub", remap = false)
public abstract class ExtendedAeWirelessHubDeferMixin {
    @Shadow private boolean[] updateStatus;

    @WrapMethod(method = "serverTick")
    private void nw$deferTopology(Operation<Void> op) {
        if (GridWorker.isWorkerThread()) {
            for (boolean pending : updateStatus) {
                if (pending) {
                    net.nestworld.compat.ae2.glue.HostEscape.request((net.minecraft.world.level.block.entity.BlockEntity) (Object) this);
                    return;
                }
            }
        }
        op.call();
    }
}
