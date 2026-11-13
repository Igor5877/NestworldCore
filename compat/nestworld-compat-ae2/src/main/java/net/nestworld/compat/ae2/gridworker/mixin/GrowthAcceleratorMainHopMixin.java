package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.blockentity.misc.GrowthAcceleratorBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.GridWorker;
import org.spongepowered.asm.mixin.Mixin;

/**
 * AE2-GRID-DOMAIN-001 G1: the Growth Accelerator's device tick random-ticks ADJACENT WORLD BLOCKS (crop growth =
 * setBlock in chunks other threads own). When its grid runs on the AE2 domain, that body hops to MAIN
 * ({@code server.execute}, same server tick's task pump) instead of writing the world from the domain thread. The
 * device's tick schedule is unchanged: AE2's {@code tickingRequest} returns SAME unconditionally.
 */
@Mixin(value = GrowthAcceleratorBlockEntity.class, remap = false)
public abstract class GrowthAcceleratorMainHopMixin {
    @WrapMethod(method = "onTick")
    private void nw$mainHop(int ticksSinceLastCall, Operation<Void> op) {
        if (!GridWorker.isWorkerThread()) {
            op.call(ticksSinceLastCall);
            return;
        }
        var be = (GrowthAcceleratorBlockEntity) (Object) this;
        var server = be.getLevel() == null ? null : be.getLevel().getServer();
        if (server == null) return;
        GridWorker.mainHops.increment();
        server.execute(() -> {
            if (!be.isRemoved()) op.call(ticksSinceLastCall);
        });
    }
}
