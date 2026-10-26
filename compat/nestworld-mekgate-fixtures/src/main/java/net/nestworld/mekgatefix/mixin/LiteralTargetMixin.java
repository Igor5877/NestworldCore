package net.nestworld.mekgatefix.mixin;

import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;

/** Empty mixin naming the protected target as a class literal. */
@Mixin(value = TransmitterNetworkRegistry.class, remap = false)
public abstract class LiteralTargetMixin {
}
