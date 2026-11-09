package net.nestworld.compat.ae2.mixin;

import appeng.api.behaviors.StackExportStrategy;
import appeng.parts.automation.ExportBusPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** WP3-B: export facade and the round-robin cursor of an AE2 export bus. */
@Mixin(value = ExportBusPart.class, remap = false)
public interface ExportBusPartAccessor {
    @Invoker("getExportStrategy")
    StackExportStrategy nw$exportStrategy();

    @Accessor("nextSlot")
    int nw$nextSlot();

    @Accessor("nextSlot")
    void nw$setNextSlot(int slot);
}
