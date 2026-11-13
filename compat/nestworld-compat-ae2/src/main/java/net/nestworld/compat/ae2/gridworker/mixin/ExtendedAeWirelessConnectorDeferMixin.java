package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.blockentity.grid.AENetworkBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.GridWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * AE2-GRID-DOMAIN-001 model A: ExtendedAE 1.4.21 TileWirelessConnector.serverTick does work ONLY while its
 * {@code updateStatus} flag is set, and that work (WirelessConnect.updateStatus) reads the remote connector, mutates the
 * global Locatables registry and creates/destroys grid connections (topology). Never run it on the AE2 domain thread:
 * leave the flag set; HOST_ESCAPE_MAIN runs the unchanged original once on MAIN at the next barrier (grid stays model A).
 * With the flag clear the original returns immediately, so nothing is skipped.
 */
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileWirelessConnector", remap = false)
public abstract class ExtendedAeWirelessConnectorDeferMixin {
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
