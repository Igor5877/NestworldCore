package net.nestworld.compat.ae2.mixin;

import appeng.parts.p2p.CapabilityP2PTunnelPart;
import net.nestworld.compat.ae2.glue.NwCapTunnel;
import net.nestworld.compat.ae2.glue.P2PDepth;
import net.nestworld.compat.ae2.glue.P2POwnership;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** P2P-ACCESS-DEPTH-001 (H2): the guard's reads / writes of the outer tunnel's accessDepth. */
@Mixin(targets = "appeng.parts.p2p.CapabilityP2PTunnelPart$CapabilityGuard", remap = false)
public abstract class CapabilityGuardMixin {
    /** get(): first read, the "== 0 → closed" check. Fixture hook point (inside the transfer). */
    @Redirect(method = "get", at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private int nw$getClosedCheck(CapabilityP2PTunnelPart<?, ?> tunnel) {
        int d = ((NwCapTunnel) tunnel).nw$depth();
        var hook = P2PDepth.hookInGet;
        if (hook != null) hook.accept(tunnel, d);
        return d;
    }

    /** get(): second read, the "== 1 → real handler, else empty (recursion)" decision. */
    @Redirect(method = "get", at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I",
            opcode = Opcodes.GETFIELD, ordinal = 1))
    private int nw$getRecursionCheck(CapabilityP2PTunnelPart<?, ?> tunnel) {
        int d = ((NwCapTunnel) tunnel).nw$depth();
        if (d > 1) P2PDepth.rejected(tunnel);
        return d;
    }

    /**
     * P2P-FORWARDING-FAIL-CLOSED-001 (H3 safety gate): the neighbour lookup of the OTHER tunnel end is
     * the forward. If the current thread does not own that position, it never happens: AE2 sees no
     * block entity and returns its empty handler (0 / unchanged stack). Ghost copies and live foreign
     * block entities are not touched.
     */
    @WrapOperation(method = "get", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity nw$ownedNeighbourOnly(Level level, BlockPos pos, Operation<BlockEntity> op) {
        return P2POwnership.mayForward(level, pos) ? op.call(level, pos) : null;
    }

    @Redirect(method = "close", at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I", opcode = Opcodes.GETFIELD))
    private int nw$closeGet(CapabilityP2PTunnelPart<?, ?> tunnel) {
        return ((NwCapTunnel) tunnel).nw$depth();
    }

    @Redirect(method = "close", at = @At(value = "FIELD", target = "Lappeng/parts/p2p/CapabilityP2PTunnelPart;accessDepth:I", opcode = Opcodes.PUTFIELD))
    private void nw$closePut(CapabilityP2PTunnelPart<?, ?> tunnel, int value) {
        ((NwCapTunnel) tunnel).nw$depth(value);
    }
}
