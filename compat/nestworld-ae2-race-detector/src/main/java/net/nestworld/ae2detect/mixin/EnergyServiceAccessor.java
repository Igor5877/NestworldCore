package net.nestworld.ae2detect.mixin;

import appeng.me.service.EnergyService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EnergyService.class, remap = false)
public interface EnergyServiceAccessor {
    @Accessor("ongoingInjectOperation")
    boolean nwd$injecting();

    @Accessor("ongoingExtractOperation")
    boolean nwd$extracting();
}
