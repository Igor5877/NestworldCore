package net.nestworld.compat.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.parts.automation.StorageImportStrategy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.BusTransitPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Counts actual accepted baseline items only inside a measured, managed bus operation. */
@Mixin(value = StorageImportStrategy.class, remap = false)
public abstract class BusImportPerfMixin {
    @WrapOperation(method = "transfer", at = @At(value = "INVOKE", target =
            "Lappeng/api/storage/MEStorage;insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J"))
    private long nw$accepted(MEStorage storage, AEKey key, long amount, Actionable mode, IActionSource source, Operation<Long> original) {
        long inserted = original.call(storage, key, amount, mode, source);
        if (mode == Actionable.MODULATE && key instanceof AEItemKey) BusTransitPerf.inlineItems(false, inserted);
        return inserted;
    }
}
