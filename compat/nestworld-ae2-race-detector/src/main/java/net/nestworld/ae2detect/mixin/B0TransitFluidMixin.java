package net.nestworld.ae2detect.mixin;

import appeng.helpers.externalstorage.GenericStackFluidStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;

/** B0 (observation only): foreign Forge fluid-handler callers into AE2 generic inventories (B5 transit proxy). */
@Mixin(value = GenericStackFluidStorage.class, remap = false)
public abstract class B0TransitFluidMixin {
    @WrapMethod(method = "fill")
    private int nwd$fill(FluidStack res, IFluidHandler.FluidAction action, Operation<Integer> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("transit.fluid.fill");
        try { return op.call(res, action); } finally { B0Detect.end("transit.fluid.fill", t); }
    }

    @WrapMethod(method = "drain(Lnet/minecraftforge/fluids/FluidStack;Lnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nwd$drain(FluidStack res, IFluidHandler.FluidAction action, Operation<FluidStack> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("transit.fluid.drain");
        try { return op.call(res, action); } finally { B0Detect.end("transit.fluid.drain", t); }
    }

    @WrapMethod(method = "drain(ILnet/minecraftforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/minecraftforge/fluids/FluidStack;")
    private FluidStack nwd$drainN(int max, IFluidHandler.FluidAction action, Operation<FluidStack> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("transit.fluid.drain");
        try { return op.call(max, action); } finally { B0Detect.end("transit.fluid.drain", t); }
    }
}
