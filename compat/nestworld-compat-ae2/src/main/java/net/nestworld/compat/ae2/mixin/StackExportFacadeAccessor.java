package net.nestworld.compat.ae2.mixin;

import appeng.api.behaviors.StackExportStrategy;
import appeng.parts.automation.StackExportFacade;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WP3-B eligibility: the strategy classes inside an export facade (exact allowlist). */
@Mixin(value = StackExportFacade.class, remap = false)
public interface StackExportFacadeAccessor {
    @Accessor("strategies")
    List<StackExportStrategy> nw$strategies();
}
