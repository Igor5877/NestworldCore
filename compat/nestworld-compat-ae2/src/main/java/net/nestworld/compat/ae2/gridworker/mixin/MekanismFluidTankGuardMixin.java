package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraftforge.fluids.FluidStack;

/** AE2-GRID-DOMAIN-MVP-001: Mekanism fluid tank behind a hot-grid storage bus (thermal evaporation valve) — every access serialised by P(G) (identity-registered tanks only). Cross-grid miss ({@link ChestGuard#MISS}) ⇒ neutral answer (nothing moved); mutators that cannot
 *  answer neutrally use {@link ChestGuard#enterMutation}. */
@Mixin(value = mekanism.common.capabilities.fluid.BasicFluidTank.class, remap = false)
public abstract class MekanismFluidTankGuardMixin {

    @WrapMethod(method = "getFluid()Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g0(Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setStack(Lnet/minecraftforge/fluids/FluidStack;)V")
    private void nw$g1(FluidStack a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setStackUnchecked(Lnet/minecraftforge/fluids/FluidStack;)V")
    private void nw$g2(FluidStack a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "insert(Lnet/minecraftforge/fluids/FluidStack;Lmekanism/api/Action;Lmekanism/api/AutomationType;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g3(FluidStack a, mekanism.api.Action b, mekanism.api.AutomationType c, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return a;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extract(ILmekanism/api/Action;Lmekanism/api/AutomationType;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nw$g4(int a, mekanism.api.Action b, mekanism.api.AutomationType c, Operation<FluidStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return FluidStack.EMPTY;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "isFluidValid(Lnet/minecraftforge/fluids/FluidStack;)Z")
    private boolean nw$g5(FluidStack a, Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return false;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setStackSize(ILmekanism/api/Action;)I")
    private int nw$g6(int a, mekanism.api.Action b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "growStack(ILmekanism/api/Action;)I")
    private int nw$g7(int a, mekanism.api.Action b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "isEmpty()Z")
    private boolean nw$g8(Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return true;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getFluidAmount()I")
    private int nw$g9(Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;")
    private net.minecraft.nbt.CompoundTag nw$g10(Operation<net.minecraft.nbt.CompoundTag> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V")
    private void nw$g11(net.minecraft.nbt.CompoundTag a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
