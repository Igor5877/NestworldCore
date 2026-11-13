package net.nestworld.compat.ae2.cost.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraftforge.items.IItemHandler;
import net.nestworld.compat.ae2.glue.SophExtractIndex;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * AE2-COST-001-FAST candidate 1 (see {@link SophExtractIndex}). Priority 900 &lt; appliedenhancements' default 1000 ⇒ this
 * @WrapMethod is applied first and is therefore the INNERMOST wrapper: it replaces only AE2's original slot loop, while
 * appliedenhancements' extractFromIndexedSlots (observed-slot / slot-index fast path) stays outside and unchanged.
 * Nesting is re-verified at runtime on the first call ({@code CostMixinPlugin.verifyNesting}).
 */
@Mixin(targets = "appeng.me.storage.ExternalStorageFacade$ItemHandlerFacade", remap = false, priority = 900)
public abstract class ItemHandlerFacadeIndexMixin {
    @Shadow @Final private IItemHandler handler;

    @Shadow
    private static int extractFromHandler(IItemHandler handler, int slot, AEItemKey itemKey, int maxExtract, Actionable actionable) {
        throw new AssertionError();
    }

    @WrapMethod(method = "extractExternal")
    private int nestworld$indexedExtract(AEKey what, int amount, Actionable mode, Operation<Integer> original) {
        if (what instanceof AEItemKey key && SophExtractIndex.active() && net.nestworld.compat.ae2.glue.CostMixinPlugin.nestingOk()) {
            int r = SophExtractIndex.extract(handler, key, amount, mode, ItemHandlerFacadeIndexMixin::extractFromHandler,
                    () -> original.call(what, amount, mode));
            if (r >= 0) return r;
        }
        return original.call(what, amount, mode);
    }
}
