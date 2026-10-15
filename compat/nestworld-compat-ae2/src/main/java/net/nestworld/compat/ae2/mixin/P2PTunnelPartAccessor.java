package net.nestworld.compat.ae2.mixin;

import appeng.parts.p2p.P2PTunnelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = P2PTunnelPart.class, remap = false)
public interface P2PTunnelPartAccessor {
    @Accessor("freq")
    void nw$setFreqField(short freq);
}
