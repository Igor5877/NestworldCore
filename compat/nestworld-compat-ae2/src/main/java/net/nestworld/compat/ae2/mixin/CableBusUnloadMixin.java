package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.networking.CableBusBlockEntity;
import net.nestworld.compat.ae2.glue.BusTransit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** WP3-B persistence: a cable bus that leaves the world by chunk unload hands its bus transit to the saved NBT. */
@Mixin(value = CableBusBlockEntity.class, remap = false)
public abstract class CableBusUnloadMixin {
    @Inject(method = "onChunkUnloaded", at = @At("HEAD"))
    private void nw$markUnloading(CallbackInfo ci) {
        if (BusTransit.FLAG) BusTransit.markUnloading((CableBusBlockEntity) (Object) this);
    }
}
