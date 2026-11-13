package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.item.ItemStack;

/** AE2-GRID-DOMAIN-MVP-001 option (1): every data-surface access of a GUARDED Sophisticated handler (storage-bus target of the hot grid) is serialised by that grid's P(G) — see {@link ChestGuard}. Cross-grid miss ({@link ChestGuard#MISS}) ⇒ neutral answer (nothing moved); mutators that cannot
 *  answer neutrally use {@link ChestGuard#enterMutation}. */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class SophisticatedChestGuardMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final protected net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper storageWrapper;

    @WrapMethod(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g0(int a, ItemStack b, boolean c, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return b;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "insertItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g1(ItemStack a, boolean b, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return a;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "insertItemOnlyToSlot(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g2(int a, ItemStack b, boolean c, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return b;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g3(int a, int b, boolean c, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return ItemStack.EMPTY;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extractItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g4(ItemStack a, boolean b, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return ItemStack.EMPTY;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extractItemInternal(IIZ)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g5(int a, int b, boolean c, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return ItemStack.EMPTY;
        try {
            return op.call(a, b, c);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V")
    private void nw$g6(int a, ItemStack b, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g7(int a, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return ItemStack.EMPTY;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getSlotStack(I)Lnet/minecraft/world/item/ItemStack;")
    private ItemStack nw$g8(int a, Operation<ItemStack> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return ItemStack.EMPTY;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setSlotStack(ILnet/minecraft/world/item/ItemStack;)V")
    private void nw$g9(int a, ItemStack b, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "isItemValid(ILnet/minecraft/world/item/ItemStack;)Z")
    private boolean nw$g10(int a, ItemStack b, Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return false;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getSlotLimit(I)I")
    private int nw$g11(int a, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getStackLimit(ILnet/minecraft/world/item/ItemStack;)I")
    private int nw$g12(int a, ItemStack b, Operation<Integer> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return 0;
        try {
            return op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "hasEmptySlots()Z")
    private boolean nw$g13(Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return false;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getTrackedStacks()Ljava/util/Set;")
    private java.util.Set nw$g14(Operation<java.util.Set> op) {
        ReentrantLock p = ChestGuard.enter(storageWrapper);
        if (p == ChestGuard.MISS) return java.util.Set.of();
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "copyStacksTo(Lnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;)V")
    private void nw$g15(net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "changeSlots(I)V")
    private void nw$g16(int a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setSize(I)V")
    private void nw$g17(int a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;")
    private net.minecraft.nbt.CompoundTag nw$g18(Operation<net.minecraft.nbt.CompoundTag> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V")
    private void nw$g19(net.minecraft.nbt.CompoundTag a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation(storageWrapper);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
