package net.nestworld.ae2detect.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache;
import net.minecraft.core.Direction;
import net.nestworld.ae2detect.MekDetect;
import net.nestworld.ae2detect.MekDetect.Op;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: every wrapper calls the original exactly once. */
@Mixin(value = NetworkAcceptorCache.class, remap = false)
public abstract class MekAcceptorCacheDetectMixin {
    @WrapMethod(method = "acceptorChanged")
    private void nwd$changed(Transmitter t, Direction d, Operation<Void> op) {
        MekDetect.producerEnter(Op.ACCEPTOR_CHANGED, this);
        try {
            op.call(t, d);
        } finally {
            MekDetect.producerExit();
        }
    }

    @WrapMethod(method = "adoptAcceptors")
    private void nwd$adopt(NetworkAcceptorCache other, Operation<Void> op) {
        MekDetect.producerEnter(Op.ADOPT, this);
        try {
            op.call(other);
        } finally {
            MekDetect.producerExit();
        }
    }

    @WrapMethod(method = "deregister")
    private void nwd$deregister(Operation<Void> op) {
        MekDetect.producerEnter(Op.DEREGISTER, this);
        try {
            op.call();
        } finally {
            MekDetect.producerExit();
        }
    }

    @WrapMethod(method = "commit")
    private void nwd$commit(Operation<Void> op) {
        MekDetect.commitEnter(this);
        try {
            op.call();
        } finally {
            MekDetect.commitExit(this);
        }
    }
}
