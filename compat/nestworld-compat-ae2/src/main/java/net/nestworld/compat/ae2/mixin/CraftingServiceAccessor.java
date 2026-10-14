package net.nestworld.compat.ae2.mixin;

import appeng.me.service.CraftingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingService.class, remap = false)
public interface CraftingServiceAccessor {
    @Accessor("interestManager")
    @SuppressWarnings("rawtypes")
    appeng.me.helpers.InterestManager nw$interestManager();
}
