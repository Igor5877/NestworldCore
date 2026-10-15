package net.nestworld.compat.ae2.mixin;

import appeng.parts.p2p.CapabilityP2PTunnelPart;
import net.nestworld.compat.ae2.glue.NwCapTunnel;
import net.nestworld.compat.ae2.glue.P2PDepth;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** P2P-ACCESS-DEPTH-001 (H2): accessDepth becomes per (thread × tunnel instance). */
@Mixin(value = CapabilityP2PTunnelPart.class, remap = false)
public abstract class CapabilityP2PTunnelPartMixin implements NwCapTunnel {
    @Shadow private int accessDepth;

    @Unique private final ThreadLocal<int[]> nw$depthTl = new ThreadLocal<>();

    @Override
    public int nw$depth() {
        if (!P2PDepth.PROTECT) return accessDepth;
        int[] d = nw$depthTl.get();
        return d == null ? 0 : d[0];
    }

    @Override
    public void nw$depth(int value) {
        P2PDepth.stored(value);
        if (!P2PDepth.PROTECT) {
            accessDepth = value;
            return;
        }
        if (value == 0) {
            nw$depthTl.remove();                    // no per-thread residue at depth 0
            return;
        }
        int[] d = nw$depthTl.get();
        if (d == null) nw$depthTl.set(new int[]{value});
        else d[0] = value;
    }

    @Override
    public boolean nw$hasEntry() {
        return nw$depthTl.get() != null;
    }

    @Redirect(method = "getAdjacentCapability",
            at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I", opcode = Opcodes.GETFIELD))
    private int nw$getDepth(CapabilityP2PTunnelPart<?, ?> self) {
        return ((NwCapTunnel) self).nw$depth();
    }

    @Redirect(method = "getAdjacentCapability",
            at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I", opcode = Opcodes.PUTFIELD))
    private void nw$putDepth(CapabilityP2PTunnelPart<?, ?> self, int value) {
        ((NwCapTunnel) self).nw$depth(value);
    }
}
