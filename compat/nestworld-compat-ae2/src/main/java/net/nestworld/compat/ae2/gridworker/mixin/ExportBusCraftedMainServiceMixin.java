package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.AEKey;
import appeng.parts.automation.ExportBusPart;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.MainService;
import org.spongepowered.asm.mixin.Mixin;

/** AE2-GRID-DOMAIN-MVP-001: crafted items pushed by the crafting CPU into an export bus's target (foreign inventory)
 *  run on MAIN as one synchronous call. Refused ⇒ nothing inserted (the CPU keeps the items, AE2 semantics). */
@Mixin(value = ExportBusPart.class, remap = false)
public abstract class ExportBusCraftedMainServiceMixin {
    @WrapMethod(method = "insertCraftedItems")
    private long nw$crafted(ICraftingLink link, AEKey what, long amount, Actionable mode, Operation<Long> op) {
        try {
            net.nestworld.compat.ae2.glue.MainServicedCounters.crafting();
            return MainService.call("craft insertCraftedItems", () -> op.call(link, what, amount, mode));
        } catch (MainService.Refused r) {
            net.nestworld.compat.ae2.glue.MainServicedCounters.refused();
            return 0L;
        }
    }
}
