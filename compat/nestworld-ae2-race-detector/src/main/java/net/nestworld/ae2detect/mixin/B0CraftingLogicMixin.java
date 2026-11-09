package net.nestworld.ae2detect.mixin;

import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;

/** B0 (observation only): crafting CPU job time. */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class B0CraftingLogicMixin {
    @WrapMethod(method = "tickCraftingLogic")
    private void nwd$tick(IEnergyService eg, CraftingService cc, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(eg, cc); } finally { B0Detect.end("crafting.tickLogic", t); }
    }
}
