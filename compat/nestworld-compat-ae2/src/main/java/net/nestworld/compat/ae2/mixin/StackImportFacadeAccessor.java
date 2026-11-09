package net.nestworld.compat.ae2.mixin;

import appeng.api.behaviors.StackImportStrategy;
import appeng.parts.automation.StackImportFacade;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WP3-B eligibility: the strategy classes inside an import facade (exact allowlist). */
@Mixin(value = StackImportFacade.class, remap = false)
public interface StackImportFacadeAccessor {
    @Accessor("strategies")
    List<StackImportStrategy> nw$strategies();
}
