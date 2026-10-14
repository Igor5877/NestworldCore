package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.Grid;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.nestworld.compat.ae2.glue.NwTickManager;
import net.nestworld.compat.ae2.glue.NwTickTracker;
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * T1 (per-grid lock) and T2 (device ticks suspended out of the section) — AE2-COMPAT-DESIGN-001.4 §2,
 * 001.5 §3 (owner-approved). The old tick survives a suspension only as an identity token
 * (tracker, generation, thread); AE2's {@code currentlyTicking} suppression does not.
 */
@Mixin(value = TickManagerService.class, remap = false)
public abstract class TickManagerServiceMixin implements NwTickManager {
    @Shadow @Final private Map<IGridNode, TickTracker> awake;
    @Shadow @Final private Map<Level, PriorityQueue<TickTracker>> upcomingTicks;
    @Shadow private IGridNode currentlyTicking;
    @Shadow private long currentTick;

    @Unique private Grid nw$grid;
    @Unique private TickTracker nw$suspTt;
    @Unique private long nw$suspGen;
    @Unique private Thread nw$suspThread;
    @Unique private boolean nw$t2Valid = true;

    @Override
    public Grid nw$grid() {
        return nw$grid;
    }

    @Override
    public void nw$setGrid(Grid grid) {
        this.nw$grid = grid;
    }

    @Override
    public TickTracker nw$suspendedTt() {
        return nw$suspTt;
    }

    @Unique
    private <T> T nw$strict(Operation<T> op, Object... args) {
        return KERNEL.guardedStrictUnchecked(Specs.grid(nw$grid), () -> op.call(args));
    }

    /** Self-call: the suspended node, from the device callback's own extent (main runs only that). */
    @Unique
    private boolean nw$isSelf(IGridNode node) {
        TickTracker t = nw$suspTt;
        return t != null && node == t.getNode() && Thread.currentThread() == nw$suspThread;
    }

    @Unique
    private boolean nw$isSuspendedNode(IGridNode node) {
        TickTracker t = nw$suspTt;
        return t != null && node == t.getNode();
    }

    // ---------------------------------------------------------------- T1

    @WrapMethod(method = "alertDevice")
    private boolean nw$alertDevice(IGridNode node, Operation<Boolean> op) {
        return KERNEL.guardedStrictUnchecked(Specs.grid(nw$grid), () -> {
            if (nw$isSelf(node)) {
                COUNTERS.t2SelfCall.increment();
                return false;                             // vanilla self-call semantics
            }
            if (nw$isSuspendedNode(node)) COUNTERS.t2ForeignAlert.increment();
            return op.call(node);                         // addToQueue hook schedules for the next tick
        });
    }

    @WrapMethod(method = "sleepDevice")
    private boolean nw$sleepDevice(IGridNode node, Operation<Boolean> op) {
        return KERNEL.guardedStrictUnchecked(Specs.grid(nw$grid), () -> {
            if (nw$isSelf(node)) {
                COUNTERS.t2SelfCall.increment();
                return false;
            }
            if (nw$isSuspendedNode(node)) COUNTERS.t2ForeignSleep.increment();
            return op.call(node);
        });
    }

    /**
     * Foreign wake of the node whose tick is suspended (001.5 §3.3, approved): vanilla would return
     * {@code false} because the polled node is still in {@code awake} — a lost wake. Instead the node is
     * really scheduled (generation changes, the old continuation is invalidated) and only then
     * {@code true} is returned.
     */
    @WrapMethod(method = "wakeDevice")
    private boolean nw$wakeDevice(IGridNode node, Operation<Boolean> op) {
        return KERNEL.guardedStrictUnchecked(Specs.grid(nw$grid), () -> {
            if (nw$isSelf(node)) {
                COUNTERS.t2SelfCall.increment();
                return false;
            }
            TickTracker t = nw$suspTt;
            if (t != null && node == t.getNode() && awake.get(node) == t && ((NwTickTracker) t).nw$gen() == nw$suspGen) {
                ((TickManagerServiceInvoker) this).nw$addToQueue(node, t);
                COUNTERS.t2ForeignWake.increment();
                return true;
            }
            return op.call(node);
        });
    }

    @WrapMethod(method = "addNode")
    private void nw$addNode(IGridNode node, CompoundTag data, Operation<Void> op) {
        nw$strict(op, node, data);
    }

    @WrapMethod(method = "removeNode")
    private void nw$removeNode(IGridNode node, Operation<Void> op) {
        nw$strict(op, node);
    }

    @WrapMethod(method = "onServerStartTick")
    private void nw$onServerStartTick(Operation<Void> op) {
        nw$strict(op);
    }

    @WrapMethod(method = "onLevelEndTick")
    private void nw$onLevelEndTick(Level level, Operation<Void> op) {
        nw$strict(op, level);
    }

    @WrapMethod(method = "onServerEndTick")
    private void nw$onServerEndTick(Operation<Void> op) {
        nw$strict(op);
    }

    // ---------------------------------------------------------------- scheduling generation

    @Inject(method = "addToQueue", at = @At("HEAD"))
    private void nw$onAddToQueue(IGridNode node, TickTracker tt, CallbackInfo ci) {
        if (tt == null) return;
        ((NwTickTracker) tt).nw$bump();
        // a reschedule of the node whose tick is in progress is eligible from the next server tick only
        if (tt == nw$suspTt) tt.setLastTick(currentTick);
    }

    @Inject(method = "removeFromQueue", at = @At("HEAD"))
    private void nw$onRemoveFromQueue(IGridNode node, TickTracker tt, CallbackInfo ci) {
        if (tt != null) ((NwTickTracker) tt).nw$bump();
    }

    // ---------------------------------------------------------------- T2 suspension and continuation gate

    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Lappeng/me/service/TickManagerService;unsafeTickingRequest(Lappeng/me/service/helpers/TickTracker;I)Lappeng/api/networking/ticking/TickRateModulation;"))
    private TickRateModulation nw$suspend(TickManagerService self, TickTracker tt, int diff,
                                          Operation<TickRateModulation> op,
                                          @Local(argsOnly = true) PriorityQueue<TickTracker> queue) {
        nw$suspTt = tt;
        nw$suspGen = ((NwTickTracker) tt).nw$gen();
        nw$suspThread = Thread.currentThread();
        currentlyTicking = null;                          // suppression must not survive the suspension
        COUNTERS.t2Suspensions.increment();
        TickRateModulation mod;
        try {
            mod = KERNEL.suspendedUnchecked(Specs.grid(nw$grid), () -> op.call(self, tt, diff));
        } catch (Throwable t) {
            nw$suspTt = null;
            nw$suspThread = null;
            throw t;
        }
        // re-entered under the current mode: one decision for the whole continuation
        IGridNode node = tt.getNode();
        boolean live = nw$grid == null || nw$grid.getPivot() != null;
        boolean same = awake.get(node) == tt && ((NwTickTracker) tt).nw$gen() == nw$suspGen;
        boolean sameQueue = upcomingTicks.get(node.getLevel()) == queue;
        nw$suspTt = null;
        nw$suspThread = null;
        if (live && same && !sameQueue) throw KERNEL.unaudited("T2 queue identity changed during suspension");
        nw$t2Valid = live && same;
        if (!nw$t2Valid) COUNTERS.t2StaleTracker.increment();
        return mod;
    }

    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Lappeng/me/service/helpers/TickTracker;setLastTick(J)V"))
    private void nw$gateLastTick(TickTracker tt, long tick, Operation<Void> op) {
        if (nw$t2Valid) op.call(tt, tick);
    }

    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Lappeng/me/service/helpers/TickTracker;setCurrentRate(I)V"))
    private void nw$gateRate(TickTracker tt, int rate, Operation<Void> op) {
        if (nw$t2Valid) op.call(tt, rate);
    }

    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Lappeng/me/service/TickManagerService;sleepDevice(Lappeng/api/networking/IGridNode;)Z"))
    private boolean nw$gateSleep(TickManagerService self, IGridNode node, Operation<Boolean> op) {
        return nw$t2Valid && op.call(self, node);
    }

    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z"))
    private boolean nw$gateRequeue(Map<?, ?> map, Object key, Operation<Boolean> op) {
        return nw$t2Valid && op.call(map, key);
    }
}
