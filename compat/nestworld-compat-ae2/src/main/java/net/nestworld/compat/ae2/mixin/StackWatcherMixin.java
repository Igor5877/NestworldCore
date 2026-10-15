package net.nestworld.compat.ae2.mixin;

import appeng.api.stacks.AEKey;
import appeng.me.Grid;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.NwGridBound;
import net.nestworld.compat.ae2.glue.Staging;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * SERVICE-MUTATION-001 (owner-approved): watcher membership is consumed only by the Storage/Crafting
 * phases, so operations are phase-deferred in FIFO order (no coalescing). Never applied while the
 * grid's phase is active; a watcher destroyed before the drain makes its operations no-ops (AE2's own
 * {@code destroyed} check).
 */
@Mixin(value = StackWatcher.class, remap = false)
public abstract class StackWatcherMixin {
    @Shadow @Final private InterestManager<?> interestManager;

    private Grid nw$grid() {
        return ((NwGridBound) interestManager).nw$boundGrid();
    }

    @WrapMethod(method = "add")
    private void nw$add(AEKey key, Operation<Void> op) {
        StackWatcher<?> self = (StackWatcher<?>) (Object) this;
        Grid g = nw$grid();
        Staging.apply(g, self, () -> op.call(key), () -> Staging.stageWatcher(g, self, () -> self.add(key)));
    }

    @WrapMethod(method = "remove")
    private void nw$remove(AEKey key, Operation<Void> op) {
        StackWatcher<?> self = (StackWatcher<?>) (Object) this;
        Grid g = nw$grid();
        Staging.apply(g, self, () -> op.call(key), () -> Staging.stageWatcher(g, self, () -> self.remove(key)));
    }

    @WrapMethod(method = "reset")
    private void nw$reset(Operation<Void> op) {
        StackWatcher<?> self = (StackWatcher<?>) (Object) this;
        Grid g = nw$grid();
        Staging.apply(g, self, () -> op.call(), () -> Staging.stageWatcher(g, self, self::reset));
    }

    @WrapMethod(method = "setWatchAll")
    private void nw$watchAll(boolean all, Operation<Void> op) {
        StackWatcher<?> self = (StackWatcher<?>) (Object) this;
        Grid g = nw$grid();
        Staging.apply(g, self, () -> op.call(all), () -> Staging.stageWatcher(g, self, () -> self.setWatchAll(all)));
    }
}
