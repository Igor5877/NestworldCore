package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.MainService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AE2-GRID-DOMAIN-MVP-001: the crafting CPU's calls into providers (foreign hosts: pattern providers, crafters) run on
 *  MAIN as one synchronous call each when the grid runs on the AE2 worker. Refused ⇒ AE2's own "busy / not pushed". */
@Mixin(targets = "appeng.crafting.execution.CraftingCpuLogic", remap = false)
public abstract class CraftingCpuMainServiceMixin {
    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;isBusy()Z"))
    private boolean nw$isBusy(ICraftingProvider provider, Operation<Boolean> op) {
        try {
            return MainService.call("craft isBusy", () -> op.call(provider));
        } catch (MainService.Refused r) {
            net.nestworld.compat.ae2.glue.MainServicedCounters.refused();
            return true;
        }
    }

    @WrapOperation(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean nw$push(ICraftingProvider provider, IPatternDetails details, KeyCounter[] inputs, Operation<Boolean> op) {
        try {
            net.nestworld.compat.ae2.glue.MainServicedCounters.crafting();
            return MainService.call("craft pushPattern", () -> op.call(provider, details, inputs));
        } catch (MainService.Refused r) {
            net.nestworld.compat.ae2.glue.MainServicedCounters.refused();
            return false;
        }
    }
}
