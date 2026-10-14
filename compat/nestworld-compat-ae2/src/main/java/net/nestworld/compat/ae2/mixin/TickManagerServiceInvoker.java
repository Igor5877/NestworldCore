package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = TickManagerService.class, remap = false)
public interface TickManagerServiceInvoker {
    @Invoker("addToQueue")
    void nw$addToQueue(IGridNode node, TickTracker tt);
}
