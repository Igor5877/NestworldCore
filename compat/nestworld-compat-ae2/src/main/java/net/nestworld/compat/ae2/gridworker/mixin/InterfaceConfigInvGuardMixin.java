package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Mixin;

/** AE2-GRID-DOMAIN-MVP-001: ConfigInventory overrides (getStack/getKey may call setStack ⇒ mutating reads; keySet). Cross-grid miss ⇒ neutral answer; mutators use {@link ChestGuard#enterMutation}. */
@Mixin(value = appeng.util.ConfigInventory.class, remap = false)
public abstract class InterfaceConfigInvGuardMixin {

    @WrapMethod(method = "getStack(I)Lappeng/api/stacks/GenericStack;")
    private appeng.api.stacks.GenericStack nw$g0(int a, Operation<appeng.api.stacks.GenericStack> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "getKey(I)Lappeng/api/stacks/AEKey;")
    private appeng.api.stacks.AEKey nw$g1(int a, Operation<appeng.api.stacks.AEKey> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call(a);
        } finally {
            ChestGuard.exit(p);
        }
    }

    @WrapMethod(method = "keySet()Ljava/util/Set;")
    private java.util.Set nw$g2(Operation<java.util.Set> op) {
        ReentrantLock p = ChestGuard.enterMutation((Object) this);
        try {
            return op.call();
        } finally {
            ChestGuard.exit(p);
        }
    }

}
