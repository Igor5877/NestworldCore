package net.nestworld.mekgatefix.mixin;

import mekanism.common.lib.transmitter.DynamicNetwork;
import org.spongepowered.asm.mixin.Mixin;

/** Empty mixin on a neighbouring Mekanism class: NOT a protected target. */
@Mixin(value = DynamicNetwork.class, remap = false)
public abstract class UnrelatedMekMixin {
}
