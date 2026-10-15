package net.nestworld.compat.ae2.mixin;

import appeng.me.service.P2PService;
import appeng.parts.p2p.P2PTunnelPart;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.compat.ae2.glue.NwP2PService;
import net.nestworld.compat.ae2.glue.P2PState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P2P-MAP-STATE-001 (H1): the memory-card call site "candidate = newFrequency(); updateFreq(this,
 * candidate)" becomes one transaction (allocateAndBind). newFrequency()'s own contract is unchanged.
 */
@Mixin(value = P2PTunnelPart.class, remap = false)
public abstract class P2PTunnelPartMixin {
    @Unique private static final ThreadLocal<Short> NW_CANDIDATE = new ThreadLocal<>();

    @WrapOperation(method = "onPartShiftActivate", at = @At(value = "INVOKE", target = "Lappeng/me/service/P2PService;newFrequency()S"))
    private short nw$candidate(P2PService p2p, Operation<Short> op) {
        short c = op.call(p2p);
        NW_CANDIDATE.set(c);
        Runnable hook = P2PState.hookAfterCandidate;
        if (hook != null) hook.run();
        return c;
    }

    @WrapOperation(method = "onPartShiftActivate",
            at = @At(value = "INVOKE", target = "Lappeng/me/service/P2PService;updateFreq(Lappeng/parts/p2p/P2PTunnelPart;S)V"))
    private void nw$bind(P2PService p2p, P2PTunnelPart t, short freq, Operation<Void> op) {
        Short candidate = NW_CANDIDATE.get();
        NW_CANDIDATE.remove();
        if (P2PState.PROTECT && candidate != null && candidate == freq) {
            ((NwP2PService) p2p).nw$allocateAndBind(t, freq);
        } else {
            op.call(p2p, t, freq);
        }
    }
}
