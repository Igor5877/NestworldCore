package net.nestworld.compat.ae2.mixin;

import appeng.api.behaviors.StackTransferContext;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.parts.automation.StorageExportStrategy;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.nestworld.compat.ae2.glue.BusTransitPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = StorageExportStrategy.class, remap = false)
public abstract class BusExportPerfMixin {
    @ModifyReturnValue(method = "transfer", at = @At("RETURN"))
    private long nw$accepted(long inserted, StackTransferContext context, AEKey key, long amount) {
        if (key instanceof AEItemKey) BusTransitPerf.inlineItems(true, inserted);
        return inserted;
    }
}
