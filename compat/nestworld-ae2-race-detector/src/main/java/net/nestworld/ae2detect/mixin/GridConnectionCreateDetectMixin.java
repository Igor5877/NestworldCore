package net.nestworld.ae2detect.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.GridConnection;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.nestworld.ae2detect.BeReadyDetect;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: S3 duplicate connection at its throw site. */
@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionCreateDetectMixin {
    @WrapMethod(method = "create")
    private static GridConnection nwd$create(IGridNode a, IGridNode b, Direction fromAtoB, Operation<GridConnection> op) {
        net.nestworld.ae2detect.FailureAttribution.enter(net.nestworld.ae2detect.FailureAttribution.Kind.TOPO, "create");
        try {
            return op.call(a, b, fromAtoB);
        } catch (IllegalStateException e) {
            if (BeReadyDetect.isS3(e)) BeReadyDetect.record(BeReadyDetect.Symptom.S3_DUPLICATE_CONNECTION, e, false);
            throw e;
        } finally {
            net.nestworld.ae2detect.FailureAttribution.exit(net.nestworld.ae2detect.FailureAttribution.Kind.TOPO, "create");
        }
    }

    @WrapMethod(method = "destroy")
    private void nwd$destroy(Operation<Void> op) {
        net.nestworld.ae2detect.FailureAttribution.enter(net.nestworld.ae2detect.FailureAttribution.Kind.TOPO, "destroy");
        try {
            op.call();
        } finally {
            net.nestworld.ae2detect.FailureAttribution.exit(net.nestworld.ae2detect.FailureAttribution.Kind.TOPO, "destroy");
        }
    }
}
