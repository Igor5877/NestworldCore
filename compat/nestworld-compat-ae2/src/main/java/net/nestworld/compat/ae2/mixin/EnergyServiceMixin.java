package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeService;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.networking.events.GridEvent;
import appeng.api.networking.events.GridPowerIdleChange;
import appeng.api.networking.events.GridPowerStorageStateChanged;
import appeng.api.networking.IGridNodeListener;
import appeng.me.Grid;
import appeng.me.energy.EnergyThreshold;
import appeng.me.energy.EnergyWatcher;
import appeng.me.service.EnergyService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.NwEnergyService;
import net.nestworld.compat.ae2.glue.Specs;
import net.nestworld.compat.ae2.kernel.DomainLock;
import net.nestworld.compat.ae2.kernel.DomainSpec;
import net.nestworld.compat.ae2.kernel.NestedRefusal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * E1–E11 (AE2-COMPAT-DESIGN-001.1 §3/§6). E1–E4 are refusable (M2: a nested lower-order entry returns
 * the contract-valid "nothing moved" result); every other entry is strict. E8's three callbacks are
 * deferred past the section (CALLBACK_AFTER_COMMIT). E6 carries the M5c runtime barrier.
 */
@Mixin(value = EnergyService.class, remap = false)
public abstract class EnergyServiceMixin implements NwEnergyService {
    @Shadow @Final Grid grid;
    @Shadow private double globalAvailablePower;
    @Shadow private double providerPowerSum;

    @Shadow
    private void addProvider(IAEPowerStorage provider) {
        throw new AssertionError();
    }

    @Shadow
    private void addRequester(IAEPowerStorage requester) {
        throw new AssertionError();
    }

    @Unique private final DomainLock nw$lock = new DomainLock();
    @Unique private volatile double nw$pubStored;
    @Unique private volatile double nw$pubMaxStored;

    @Override
    public DomainLock nw$lock() {
        return nw$lock;
    }

    @Override
    public Grid nw$grid() {
        return grid;
    }

    @Override
    public double nw$publishedStored() {
        return nw$pubStored;
    }

    @Override
    public double nw$publishedMaxStored() {
        return nw$pubMaxStored;
    }

    @Unique
    private DomainSpec nw$spec() {
        return Specs.energy((EnergyService) (Object) this);
    }

    @Unique
    private <T> T nw$strict(Operation<T> op, Object... args) {
        return KERNEL.guardedStrictUnchecked(nw$spec(), () -> op.call(args));
    }

    // ---------------------------------------------------------------- E1–E4 (refusable, M2)

    @WrapMethod(method = "injectPower")
    private double nw$injectPower(double amt, Actionable mode, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(amt, mode));
        } catch (NestedRefusal r) {
            return amt;                                   // nothing injected: full leftover
        }
    }

    @WrapMethod(method = "injectProviderPower")
    private double nw$injectProviderPower(double amt, Actionable mode, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(amt, mode));
        } catch (NestedRefusal r) {
            return amt;
        }
    }

    @WrapMethod(method = "extractAEPower")
    private double nw$extractAEPower(double amt, Actionable mode, PowerMultiplier pm, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(amt, mode, pm));
        } catch (NestedRefusal r) {
            return 0.0;                                   // nothing extracted
        }
    }

    @WrapMethod(method = "extractProviderPower")
    private double nw$extractProviderPower(double amt, Actionable mode, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(amt, mode));
        } catch (NestedRefusal r) {
            return 0.0;
        }
    }

    @WrapMethod(method = "getEnergyDemand")
    private double nw$getEnergyDemand(double max, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(max));
        } catch (NestedRefusal r) {
            return 0.0;
        }
    }

    @WrapMethod(method = "getProviderEnergyDemand")
    private double nw$getProviderEnergyDemand(double max, Operation<Double> op) {
        try {
            return KERNEL.guardedUnchecked(nw$spec(), () -> op.call(max));
        } catch (NestedRefusal r) {
            return 0.0;
        }
    }

    @WrapMethod(method = "getStoredPower")
    private double nw$getStoredPower(Operation<Double> op) {
        try {
            double v = KERNEL.guardedUnchecked(nw$spec(), () -> op.call());
            nw$pubStored = v;
            return v;
        } catch (NestedRefusal r) {
            return nw$pubStored;                          // last value published at a completed read
        }
    }

    @WrapMethod(method = "getMaxStoredPower")
    private double nw$getMaxStoredPower(Operation<Double> op) {
        try {
            double v = KERNEL.guardedUnchecked(nw$spec(), () -> op.call());
            nw$pubMaxStored = v;
            return v;
        } catch (NestedRefusal r) {
            return nw$pubMaxStored;
        }
    }

    @WrapMethod(method = "refreshPower")
    private void nw$refreshPower(Operation<Void> op) {
        try {
            KERNEL.guardedUnchecked(nw$spec(), () -> op.call());
        } catch (NestedRefusal r) {
            // nothing refreshed
        }
    }

    // ---------------------------------------------------------------- E5–E11 (strict)

    @WrapMethod(method = "nodeIdlePowerChangeHandler")
    private void nw$idleChange(GridPowerIdleChange ev, Operation<Void> op) {
        nw$strict(op, ev);
    }

    @WrapMethod(method = "storagePowerChangeHandler")
    private void nw$storageChange(GridPowerStorageStateChanged ev, Operation<Void> op) {
        nw$strict(op, ev);
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

    @WrapMethod(method = "onServerEndTick")
    private void nw$onServerEndTick(Operation<Void> op) {
        nw$strict(op);
    }

    @WrapMethod(method = "registerEnergyInterest")
    private boolean nw$registerInterest(EnergyThreshold t, Operation<Boolean> op) {
        return nw$strict(op, t);
    }

    @WrapMethod(method = "unregisterEnergyInterest")
    private boolean nw$unregisterInterest(EnergyThreshold t, Operation<Boolean> op) {
        return nw$strict(op, t);
    }

    @WrapMethod(method = "invalidateOverlayEnergyGrid")
    private void nw$invalidateOverlay(Operation<Void> op) {
        nw$strict(op);
    }

    @WrapMethod(method = "saveNodeData")
    private void nw$saveNodeData(IGridNode node, CompoundTag data, Operation<Void> op) {
        nw$strict(op, node, data);
    }

    // ---------------------------------------------------------------- E8 callbacks after commit

    @WrapOperation(method = "onServerEndTick", at = @At(value = "INVOKE",
            target = "Lappeng/me/energy/EnergyWatcher;post(Lappeng/me/service/EnergyService;)V"))
    private void nw$deferWatcherPost(EnergyWatcher watcher, EnergyService service, Operation<Void> op) {
        if (!KERNEL.defer(() -> op.call(watcher, service))) op.call(watcher, service);
    }

    @WrapOperation(method = "publicPowerState", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/IGrid;postEvent(Lappeng/api/networking/events/GridEvent;)Lappeng/api/networking/events/GridEvent;"))
    private GridEvent nw$deferPowerStatusEvent(IGrid g, GridEvent ev, Operation<GridEvent> op) {
        if (!KERNEL.defer(() -> op.call(g, ev))) return op.call(g, ev);
        return ev;                                        // postEvent returns its argument
    }

    @WrapOperation(method = "publicPowerState", at = @At(value = "INVOKE",
            target = "Lappeng/me/Grid;notifyAllNodes(Lappeng/api/networking/IGridNodeListener$State;)V"))
    private void nw$deferNotifyAll(Grid g, IGridNodeListener.State state, Operation<Void> op) {
        if (!KERNEL.defer(() -> op.call(g, state))) op.call(g, state);
    }

    // ---------------------------------------------------------------- M5c runtime barrier (E6)

    /**
     * At {@code addNode}'s {@code getService(IAEPowerStorage.class)}: an unaudited participant class is
     * admitted only through the DEGRADED handover (001.3). In NORMAL the branch is skipped now and its
     * exact continuation (accounting + addProvider + addRequester) runs under G before this call's
     * outermost entry returns.
     */
    @WrapOperation(method = "addNode", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/IGridNode;getService(Ljava/lang/Class;)Lappeng/api/networking/IGridNodeService;"))
    private IGridNodeService nw$admission(IGridNode node, Class<?> type, Operation<IGridNodeService> op) {
        IGridNodeService service = op.call(node, type);
        if (type != IAEPowerStorage.class || service == null || Ae2Compat.isAllowedParticipant(service)) return service;
        if (KERNEL.inDegradedSection()) return service;
        IAEPowerStorage ps = (IAEPowerStorage) service;
        KERNEL.admitUnknown(() -> {
            if (ps.isAEPublicPowerStorage()) {
                AccessRestriction flow = ps.getPowerFlow();
                if (flow.isAllowExtraction()) {
                    globalAvailablePower += ps.getAECurrentPower();
                    providerPowerSum += ps.getAEMaxPower();
                }
                addProvider(ps);
                addRequester(ps);
            }
        }, "AE2 compat: unaudited IAEPowerStorage participant " + service.getClass().getName()
                + " at " + node + " (thread " + Thread.currentThread().getName() + ")");
        return null;
    }
}
