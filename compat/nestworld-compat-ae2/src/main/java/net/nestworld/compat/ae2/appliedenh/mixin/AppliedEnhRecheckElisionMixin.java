package net.nestworld.compat.ae2.appliedenh.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.nestworld.compat.ae2.glue.AppliedEnhRecheckElision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * APPLIEDENH-PERF-001 (docs/compat/APPLIEDENH-PERF-001-audit.md §4): wraps the same call site appliedenhancements'
 * {@code recheckExtractionType} wraps, as the OUTER wrapper (config priority 1500 &gt; 1000). Only the first
 * {@code wrapHandlerExtract} call of one {@code extractFromHandler} invocation (R1, back-to-back with AE2's own
 * {@code matches(getStackInSlot)} check) may be routed straight to AE2's raw {@code wrapHandlerExtract}; every other
 * call, and R1 whenever a proof obligation is not met, goes through {@code original} unchanged.
 * {@code require = 0}: if AE2 changes the target, nothing is injected and the game is unaffected.
 */
@Mixin(targets = "appeng.me.storage.ExternalStorageFacade$ItemHandlerFacade", remap = false)
public abstract class AppliedEnhRecheckElisionMixin {
    @Shadow
    private static int wrapHandlerExtract(IItemHandler handler, int slot, int maxExtract, boolean simulate) {
        throw new AssertionError();
    }

    @WrapOperation(method = "extractFromHandler", require = 0, at = @At(value = "INVOKE",
            target = "Lappeng/me/storage/ExternalStorageFacade$ItemHandlerFacade;wrapHandlerExtract(Lnet/minecraftforge/items/IItemHandler;IIZ)I"))
    private static int nestworld$elideFirstRecheck(IItemHandler handler, int slot, int maxExtract, boolean simulate,
            Operation<Integer> original, @Local(ordinal = 0) ItemStack checkedStack,
            @Share("nestworld$r1Done") LocalBooleanRef r1Done) {
        boolean first = !r1Done.get();
        r1Done.set(true);
        boolean elide = AppliedEnhRecheckElision.mayElide(first, handler, checkedStack);
        int r = elide ? wrapHandlerExtract(handler, slot, maxExtract, simulate)
                : original.call(handler, slot, maxExtract, simulate);
        if (AppliedEnhRecheckElision.tracing) AppliedEnhRecheckElision.trace(handler, slot, maxExtract, simulate, first, elide, r);
        return r;
    }
}
