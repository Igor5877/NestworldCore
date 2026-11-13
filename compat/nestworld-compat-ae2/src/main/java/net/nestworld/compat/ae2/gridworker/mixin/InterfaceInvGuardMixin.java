package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;

/** AE2-GRID-DOMAIN-MVP-001 (owner 2026-11-13): AE2 / ExtendedAE interface inventories (InterfaceLogic storage + config, identity-registered) — the interface device tick runs DIRECTLY on the AE2 worker, so every outside accessor (pipes via item/fluid/chemical wrappers, other grids' storage buses via STORAGE, GUI) enters the same P(G). Unregistered GenericStackInv: one map miss. Cross-grid miss ⇒ neutral answer; mutators use {@link ChestGuard#enterMutation}. */
@Mixin(value = appeng.helpers.externalstorage.GenericStackInv.class, remap = false)
public abstract class InterfaceInvGuardMixin {

    @WrapMethod(method = "isEmpty()Z")
    private boolean nw$g0(Operation<Boolean> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return true;
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getStack(I)Lappeng/api/stacks/GenericStack;")
    private appeng.api.stacks.GenericStack nw$g1(int a, Operation<appeng.api.stacks.GenericStack> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return null;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getKey(I)Lappeng/api/stacks/AEKey;")
    private appeng.api.stacks.AEKey nw$g2(int a, Operation<appeng.api.stacks.AEKey> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return null;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getAmount(I)J")
    private long nw$g3(int a, Operation<Long> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0L;
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "setStack(ILappeng/api/stacks/GenericStack;)V")
    private void nw$g4(int a, appeng.api.stacks.GenericStack b, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a, b);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "insert(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J")
    private long nw$g5(int a, appeng.api.stacks.AEKey b, long c, appeng.api.config.Actionable d, Operation<Long> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0L;
        try {
            return op.call(a, b, c, d);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extract(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J")
    private long nw$g6(int a, appeng.api.stacks.AEKey b, long c, appeng.api.config.Actionable d, Operation<Long> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0L;
        try {
            return op.call(a, b, c, d);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "writeToTag()Lnet/minecraft/nbt/ListTag;")
    private net.minecraft.nbt.ListTag nw$g7(Operation<net.minecraft.nbt.ListTag> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "readFromTag(Lnet/minecraft/nbt/ListTag;)V")
    private void nw$g8(net.minecraft.nbt.ListTag a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "clear()V")
    private void nw$g9(Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "beginBatch()V")
    private void nw$g10(Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "endBatch()V")
    private void nw$g11(Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "endBatchSuppressed()V")
    private void nw$g12(Operation<Void> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J")
    private long nw$g13(appeng.api.stacks.AEKey a, long b, appeng.api.config.Actionable c, appeng.api.networking.security.IActionSource d, Operation<Long> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0L;
        try {
            return op.call(a, b, c, d);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J")
    private long nw$g14(appeng.api.stacks.AEKey a, long b, appeng.api.config.Actionable c, appeng.api.networking.security.IActionSource d, Operation<Long> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return 0L;
        try {
            return op.call(a, b, c, d);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V")
    private void nw$g15(appeng.api.stacks.KeyCounter a, Operation<Void> op) {
        ReentrantLock p = ChestGuard.enter((Object) this);
        if (p == ChestGuard.MISS) return;
        try {
            op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

}
