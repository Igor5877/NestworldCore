package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.config.Actionable;
import appeng.api.parts.IPart;
import appeng.parts.networking.EnergyAcceptorPart;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.Specs;
import net.nestworld.compat.ae2.kernel.NestedRefusal;
import org.spongepowered.asm.mixin.Mixin;

/** Entry validation (001 §3) for the cable-part acceptor. */
@Mixin(value = EnergyAcceptorPart.class, remap = false)
public abstract class EnergyAcceptorPartMixin {
    @WrapMethod(method = "funnelPowerIntoStorage")
    private double nw$funnel(double power, Actionable mode, Operation<Double> op) {
        IPart self = (IPart) this;
        try {
            return KERNEL.guardedUnchecked(Specs.node(self::getGridNode), () -> op.call(power, mode));
        } catch (NestedRefusal r) {
            return power;
        }
    }
}
