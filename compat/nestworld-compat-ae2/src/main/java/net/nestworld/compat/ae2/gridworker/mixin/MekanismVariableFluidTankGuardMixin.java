package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;

/** AE2-GRID-DOMAIN-MVP-001: VariableCapacityFluidTank's own setStackSize override (see MekanismFluidTankGuardMixin). Cross-grid miss ({@link ChestGuard#MISS}) ⇒ neutral answer (nothing moved); mutators that cannot
 *  answer neutrally use {@link ChestGuard#enterMutation}. */
@Mixin(value = mekanism.common.capabilities.fluid.VariableCapacityFluidTank.class, remap = false)
public abstract class MekanismVariableFluidTankGuardMixin {

    @WrapMethod(method = "setStackSize(ILmekanism/api/Action;)I")
    private int nw$g0(int a, mekanism.api.Action b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
