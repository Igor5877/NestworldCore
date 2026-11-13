package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.MainService;
import net.nestworld.compat.ae2.glue.MainServicedCounters;
import org.spongepowered.asm.mixin.Mixin;

/** AE2-GRID-DOMAIN-MVP-001 (owner 2026-11-12): chemical storage-bus facades (Applied-Mekanistics; on grid #1 only the
 *  radioactive waste barrels, ~0.1 op/tick) never run on the AE2 worker — each op is ONE synchronous MAIN call (the
 *  same thread that runs them today without the worker). Refused ⇒ nothing moved / nothing listed this tick. */
@Mixin(targets = "me.ramidzkh.mekae2.ae2.stack.ChemicalHandlerFacade", remap = false)
public abstract class MekanismChemicalFacadeMainServiceMixin {
    @WrapMethod(method = "insert")
    private long nw$insert(AEKey what, long amount, Actionable mode, IActionSource src, Operation<Long> op) {
        try { return MainService.call("chemical storage insert", () -> op.call(what, amount, mode, src)); }
        catch (MainService.Refused r) { MainServicedCounters.refused(); return 0L; }
    }

    @WrapMethod(method = "extract")
    private long nw$extract(AEKey what, long amount, Actionable mode, IActionSource src, Operation<Long> op) {
        try { return MainService.call("chemical storage extract", () -> op.call(what, amount, mode, src)); }
        catch (MainService.Refused r) { MainServicedCounters.refused(); return 0L; }
    }

    @WrapMethod(method = "getAvailableStacks")
    private void nw$list(KeyCounter out, Operation<Void> op) {
        try { MainService.call("chemical storage list", () -> { op.call(out); return null; }); }
        catch (MainService.Refused r) { MainServicedCounters.refused(); }
    }
}
