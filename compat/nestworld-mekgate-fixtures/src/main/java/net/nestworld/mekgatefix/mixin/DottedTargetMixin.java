package net.nestworld.mekgatefix.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Empty mixin naming the protected target as a dotted string. */
@Mixin(targets = "mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache", remap = false)
public abstract class DottedTargetMixin {
}
