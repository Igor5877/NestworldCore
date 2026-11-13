package net.nestworld.compat.ae2.ingress.mixin;

import net.minecraft.world.item.ItemStack;
import net.nestworld.compat.ae2.glue.ChestTouchCensus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2-GRID-DOMAIN-MVP-001 storage-bus touch census (diagnostics only; ingress-census config, flag-gated): who reads /
 *  writes the Sophisticated chests that grid #1's storage buses mount. No behaviour change. */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class SophisticatedInventoryCensusMixin {
    @Shadow @Final protected net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper storageWrapper;

    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void nw$insert(int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        ChestTouchCensus.touch(storageWrapper, simulate ? "insert-sim" : "insert");
    }

    @Inject(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void nw$extract(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        ChestTouchCensus.touch(storageWrapper, simulate ? "extract-sim" : "extract");
    }

    @Inject(method = "setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void nw$set(int slot, ItemStack stack, CallbackInfo ci) {
        ChestTouchCensus.touch(storageWrapper, "set");
    }

    @Inject(method = "getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private void nw$get(int slot, CallbackInfoReturnable<ItemStack> cir) {
        ChestTouchCensus.touch(storageWrapper, "read");
    }
}
