package net.nestworld.ae2detect.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import appeng.api.networking.ticking.TickRateModulation;
import org.spongepowered.asm.mixin.injection.At;
import java.util.PriorityQueue;
import net.minecraft.nbt.CompoundTag;
import net.nestworld.ae2detect.TickDetect;
import net.nestworld.ae2detect.TickDetect.Op;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: every wrapper calls the original exactly once. */
@Mixin(value = TickManagerService.class, remap = false)
public abstract class TickManagerDetectMixin {
    @WrapMethod(method = "tickQueue")
    private void nwd$tickQueue(PriorityQueue<TickTracker> q, Operation<Void> op) {
        TickDetect.tickEnter(this);
        try { op.call(q); } finally { TickDetect.tickExit(this); }
    }

    /** marks the device callback inside tickQueue: queue operations are NOT in progress during it */
    @WrapOperation(method = "tickQueue", at = @At(value = "INVOKE",
            target = "Lappeng/me/service/TickManagerService;unsafeTickingRequest(Lappeng/me/service/helpers/TickTracker;I)Lappeng/api/networking/ticking/TickRateModulation;"))
    private TickRateModulation nwd$callback(TickManagerService self, TickTracker tt, int diff, Operation<TickRateModulation> op) {
        TickDetect.callbackEnter(this);
        try { return op.call(self, tt, diff); } finally { TickDetect.callbackExit(this); }
    }

    @WrapMethod(method = "addToQueue")
    private void nwd$add(IGridNode n, TickTracker t, Operation<Void> op) {
        TickDetect.producerEnter(Op.ADD_TO_QUEUE, this);
        try { op.call(n, t); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "removeFromQueue")
    private void nwd$remove(IGridNode n, TickTracker t, Operation<Void> op) {
        TickDetect.producerEnter(Op.REMOVE_FROM_QUEUE, this);
        try { op.call(n, t); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "updateQueuePosition")
    private void nwd$update(IGridNode n, TickTracker t, Operation<Void> op) {
        TickDetect.producerEnter(Op.UPDATE_POSITION, this);
        try { op.call(n, t); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "wakeDevice")
    private boolean nwd$wake(IGridNode n, Operation<Boolean> op) {
        TickDetect.producerEnter(Op.WAKE, this);
        try { return op.call(n); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "alertDevice")
    private boolean nwd$alert(IGridNode n, Operation<Boolean> op) {
        TickDetect.producerEnter(Op.ALERT, this);
        try { return op.call(n); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "sleepDevice")
    private boolean nwd$sleep(IGridNode n, Operation<Boolean> op) {
        TickDetect.producerEnter(Op.SLEEP, this);
        try { return op.call(n); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "addNode")
    private void nwd$addNode(IGridNode n, CompoundTag d, Operation<Void> op) {
        TickDetect.producerEnter(Op.ADD_NODE, this);
        try { op.call(n, d); } finally { TickDetect.producerExit(this); }
    }

    @WrapMethod(method = "removeNode")
    private void nwd$removeNode(IGridNode n, Operation<Void> op) {
        TickDetect.producerEnter(Op.REMOVE_NODE, this);
        try { op.call(n); } finally { TickDetect.producerExit(this); }
    }
}
