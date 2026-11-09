package net.nestworld.compat.ae2.mixin;

import appeng.me.cluster.IAEMultiBlock;
import appeng.me.cluster.MBCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AE2-DEMAIN-001 B1: the calculator's target (the block entity whose structure is recalculated). */
@Mixin(value = MBCalculator.class, remap = false)
public interface MBCalculatorAccessor {
    @Accessor("target")
    IAEMultiBlock<?> nw$target();
}
