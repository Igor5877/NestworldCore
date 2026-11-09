package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.parts.automation.IOBusPart;
import appeng.util.prioritylist.IPartitionList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.nestworld.compat.ae2.glue.BusTransit;
import net.nestworld.compat.ae2.glue.NwTransitBus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2-DEMAIN-001 B4–B6 (WP3-B bus transit): the scheduled operation of an AE2 import / export bus stays in AE2's
 * TickManagerService pass; only {@code doBusWork} is replaced, for the exact classes {@code ImportBusPart} /
 * {@code ExportBusPart} and only when {@link BusTransit} accepts it. A transit operation returns SAME (its FASTER /
 * SLOWER is applied by the commit scheduler when the transfer result is known); SLEEP / IDLE are AE2's own.
 */
@Mixin(value = IOBusPart.class, remap = false)
public abstract class IOBusPartTransitMixin implements NwTransitBus {
    @Shadow @Final protected IActionSource source;

    @Shadow
    protected abstract int getOperationsPerTick();

    @Shadow
    protected abstract int availableSlots();

    @Shadow
    protected abstract IPartitionList getFilter();

    @Unique private BusTransit.BusState nw$transit;
    @Unique private boolean nw$deferredOp;

    @Override
    public BusTransit.BusState nw$transit() {
        BusTransit.BusState s = nw$transit;
        if (s == null) nw$transit = s = new BusTransit.BusState();
        return s;
    }

    @Override
    public int nw$opsPerTick() {
        return getOperationsPerTick();
    }

    @Override
    public int nw$availableSlots() {
        return availableSlots();
    }

    @Override
    public IPartitionList nw$filter() {
        return getFilter();
    }

    @Override
    public IActionSource nw$source() {
        return source;
    }

    @WrapOperation(method = "tickingRequest", at = @At(value = "INVOKE",
            target = "Lappeng/parts/automation/IOBusPart;doBusWork(Lappeng/api/networking/IGrid;)Z"))
    private boolean nw$busWork(IOBusPart self, IGrid grid, Operation<Boolean> op) {
        nw$deferredOp = false;
        if (!BusTransit.manages(self)) return op.call(self, grid);
        int r = BusTransit.onBusWork(self, grid, () -> op.call(self, grid));
        if (r == BusTransit.DEFERRED) {
            nw$deferredOp = true;
            return false;
        }
        return r == BusTransit.INLINE_WORK;
    }

    @ModifyReturnValue(method = "tickingRequest", at = @At("RETURN"))
    private TickRateModulation nw$deferModulation(TickRateModulation mod, IGridNode node, int ticksSinceLastCall) {
        if (!nw$deferredOp) return mod;
        nw$deferredOp = false;
        return mod == TickRateModulation.FASTER || mod == TickRateModulation.SLOWER ? TickRateModulation.SAME : mod;
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void nw$writeTransit(CompoundTag extra, CallbackInfo ci) {
        if (nw$transit != null) BusTransit.write((IOBusPart) (Object) this, extra);
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void nw$readTransit(CompoundTag extra, CallbackInfo ci) {
        if (extra.contains(BusTransit.NBT_KEY)) BusTransit.read((IOBusPart) (Object) this, extra);
    }
}
