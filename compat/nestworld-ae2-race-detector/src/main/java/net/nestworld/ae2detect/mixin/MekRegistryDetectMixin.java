package net.nestworld.ae2detect.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.DynamicNetwork;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.nestworld.ae2detect.MekDetect;
import net.nestworld.ae2detect.MekDetect.Op;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: every wrapper calls the original exactly once. */
@Mixin(value = TransmitterNetworkRegistry.class, remap = false)
public abstract class MekRegistryDetectMixin {
    private static void nwd$produce(Op op, Runnable original) {
        MekDetect.producerEnter(op, null);
        try {
            original.run();
        } finally {
            MekDetect.producerExit();
        }
    }

    @WrapMethod(method = "trackTransmitter")
    private static void nwd$track(Transmitter t, Operation<Void> op) { nwd$produce(Op.TRACK, () -> op.call(t)); }

    @WrapMethod(method = "untrackTransmitter")
    private static void nwd$untrack(Transmitter t, Operation<Void> op) { nwd$produce(Op.UNTRACK, () -> op.call(t)); }

    @WrapMethod(method = "invalidateTransmitter")
    private static void nwd$invalidate(Transmitter t, Operation<Void> op) { nwd$produce(Op.INVALIDATE, () -> op.call(t)); }

    @WrapMethod(method = "registerOrphanTransmitter")
    private static void nwd$orphan(Transmitter t, Operation<Void> op) { nwd$produce(Op.ORPHAN, () -> op.call(t)); }

    @WrapMethod(method = "registerChangedNetwork")
    private static void nwd$changed(DynamicNetwork n, Operation<Void> op) { nwd$produce(Op.CHANGED_NETWORK, () -> op.call(n)); }

    @WrapMethod(method = "registerNetwork")
    private void nwd$netAdd(DynamicNetwork n, Operation<Void> op) { nwd$produce(Op.NETWORK_ADD, () -> op.call(n)); }

    @WrapMethod(method = "removeNetwork")
    private void nwd$netRemove(DynamicNetwork n, Operation<Void> op) { nwd$produce(Op.NETWORK_REMOVE, () -> op.call(n)); }

    @WrapMethod(method = "onTicketLevelChange")
    private void nwd$ticket(ChunkTicketLevelUpdatedEvent e, Operation<Void> op) { nwd$produce(Op.TICKET_CHANGE, () -> op.call(e)); }

    @WrapMethod(method = "onTick")
    private void nwd$tick(TickEvent.ServerTickEvent e, Operation<Void> op) {
        MekDetect.tickEnter();
        try {
            op.call(e);
        } finally {
            MekDetect.tickExit();
        }
    }
}
