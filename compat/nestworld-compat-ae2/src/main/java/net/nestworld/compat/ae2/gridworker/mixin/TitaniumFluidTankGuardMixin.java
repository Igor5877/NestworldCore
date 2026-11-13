package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraftforge.fluids.FluidStack;

/** AE2-GRID-DOMAIN-MVP-001: Titanium FluidTankComponent overrides (fill/drain/forced/setFluidStack) — see ForgeFluidTankGuardMixin. Cross-grid miss ({@link ChestGuard#MISS}) ⇒ neutral answer (nothing moved); mutators that cannot
 *  answer neutrally use {@link ChestGuard#enterMutation}. */
@Mixin(targets = "com.hrznstudio.titanium.component.fluid.FluidTankComponent", remap = false)
public abstract class TitaniumFluidTankGuardMixin {

    @WrapMethod(method = "fill(Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)I")
    private int nw$g0(FluidStack a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "drain(Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g1(FluidStack a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "drain(ILnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g2(int a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "fillForced(Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)I")
    private int nw$g3(FluidStack a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "drainForced(Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g4(FluidStack a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "drainForced(ILnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g5(int a, net.minecraftforge.fluids.capability.IFluidHandler.FluidAction b, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setFluidStack(Lnet/minecraftforge/fluids/FluidStack;)V")
    private void nw$g6(FluidStack a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
