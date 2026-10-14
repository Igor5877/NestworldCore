package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.config.Actionable;
import appeng.blockentity.networking.EnergyAcceptorBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.Specs;
import net.nestworld.compat.ae2.kernel.NestedRefusal;
import org.spongepowered.asm.mixin.Mixin;

/** Entry validation (001 §3): never inject into a service whose grid was merged away meanwhile. */
@Mixin(value = EnergyAcceptorBlockEntity.class, remap = false)
public abstract class EnergyAcceptorBlockEntityMixin {
    @WrapMethod(method = "funnelPowerIntoStorage")
    private double nw$funnel(double power, Actionable mode, Operation<Double> op) {
        IGridConnectedBlockEntity self = (IGridConnectedBlockEntity) this;
        try {
            return KERNEL.guardedUnchecked(Specs.node(() -> self.getMainNode().getNode()), () -> op.call(power, mode));
        } catch (NestedRefusal r) {
            return power;
        }
    }
}
