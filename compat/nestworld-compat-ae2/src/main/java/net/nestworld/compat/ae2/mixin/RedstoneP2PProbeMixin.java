package net.nestworld.compat.ae2.mixin;

import appeng.parts.p2p.RedstoneP2PTunnelPart;
import net.nestworld.compat.ae2.glue.P2PFixtures;
import net.nestworld.compat.ae2.glue.T2Fixtures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fixture-only probe for AE2-COMPAT-P2P-001 P2P-10 (Redstone P2P neighbour notification boundary);
 *  a no-op unless fixtures are enabled and the probe is armed. Changes no behaviour. */
@Mixin(value = RedstoneP2PTunnelPart.class, remap = false)
public abstract class RedstoneP2PProbeMixin {
    @Inject(method = "notifyNeighbors", at = @At("HEAD"))
    private void nw$probeHead(CallbackInfo ci) {
        if (T2Fixtures.ENABLED) P2PFixtures.rsNotify((RedstoneP2PTunnelPart) (Object) this, true);
    }

    @Inject(method = "notifyNeighbors", at = @At("RETURN"))
    private void nw$probeReturn(CallbackInfo ci) {
        if (T2Fixtures.ENABLED) P2PFixtures.rsNotify((RedstoneP2PTunnelPart) (Object) this, false);
    }
}
