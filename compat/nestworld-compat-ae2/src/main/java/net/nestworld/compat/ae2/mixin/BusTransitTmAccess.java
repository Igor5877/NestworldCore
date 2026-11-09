package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** WP3-B deferred modulation: AE2's tick counter and queue re-positioning (heap order kept by AE2's own methods). */
@Mixin(value = TickManagerService.class, remap = false)
public interface BusTransitTmAccess {
    @Accessor("currentTick")
    long nw$currentTick();

    @Invoker("removeFromQueue")
    void nw$removeFromQueue(IGridNode node, TickTracker tt);

    @Invoker("addToQueue")
    void nw$addToQueueT(IGridNode node, TickTracker tt);
}
