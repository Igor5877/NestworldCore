package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraftforge.fluids.FluidStack;

/** AE2-GRID-DOMAIN-MVP-001: Forge FluidTank (Titanium FluidTankComponent base; IF fluid laser base) behind a hot-grid storage bus — every access serialised by P(G) (identity-registered tanks only). Cross-grid miss ({@link ChestGuard#MISS}) ⇒ neutral answer (nothing moved); mutators that cannot
 *  answer neutrally use {@link ChestGuard#enterMutation}. */
@Mixin(value = net.minecraftforge.fluids.capability.templates.FluidTank.class, remap = false)
public abstract class ForgeFluidTankGuardMixin {

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

    @WrapMethod(method = "getFluid()Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g3(Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getFluidAmount()I")
    private int nw$g4(Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setFluid(Lnet/minecraftforge/fluids/FluidStack;)V")
    private void nw$g5(FluidStack a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "isFluidValid(Lnet/minecraftforge/fluids/FluidStack;)Z")
    private boolean nw$g6(FluidStack a, Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return false;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
