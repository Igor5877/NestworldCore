package net.nestworld.ae2detect.mixin;

import appeng.helpers.externalstorage.GenericStackItemStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.ItemStack;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;

/** B0 (observation only): foreign Forge item-handler callers into AE2 generic inventories (B5 transit proxy). */
@Mixin(value = GenericStackItemStorage.class, remap = false)
public abstract class B0TransitMixin {
    @WrapMethod(method = "insertItem")
    private ItemStack nwd$insert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("transit.item.insert");
        try { return op.call(slot, stack, simulate); } finally { B0Detect.end("transit.item.insert", t); }
    }

    @WrapMethod(method = "extractItem")
    private ItemStack nwd$extract(int slot, int amount, boolean simulate, Operation<ItemStack> op) {
        long t = B0Detect.begin();
        if (t != 0) B0Detect.caller("transit.item.extract");
        try { return op.call(slot, amount, simulate); } finally { B0Detect.end("transit.item.extract", t); }
    }
}
