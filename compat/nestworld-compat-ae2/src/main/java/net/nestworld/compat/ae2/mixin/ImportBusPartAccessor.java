package net.nestworld.compat.ae2.mixin;

import appeng.api.behaviors.StackImportStrategy;
import appeng.parts.automation.ImportBusPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WP3-B: the bus's own (lazily created) import facade, reused by the region half. */
@Mixin(value = ImportBusPart.class, remap = false)
public interface ImportBusPartAccessor {
    @Accessor("importStrategy")
    StackImportStrategy nw$importStrategy();

    @Accessor("importStrategy")
    void nw$setImportStrategy(StackImportStrategy s);
}
