package net.nestworld.compat.ae2.mixin;

import appeng.me.service.StorageService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = StorageService.class, remap = false)
public interface StorageServiceAccessor {
    @Accessor("interestManager")
    @SuppressWarnings("rawtypes")
    appeng.me.helpers.InterestManager nw$interestManager();
}
