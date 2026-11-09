package net.nestworld.compat.ae2.mixin;

import appeng.parts.AEBasePart;
import appeng.parts.networking.CablePart;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.NeighborAffinity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AE2-NEIGHBOR-AFFINITY-001: every non-cable part pins the block it faces while it is in the world. */
@Mixin(value = AEBasePart.class, remap = false)
public abstract class PartNeighborAffinityMixin {
    @Unique private long[] nw$affinity;
    @Unique private ServerLevel nw$affinityLevel;

    @Inject(method = "addToWorld", at = @At("TAIL"))
    private void nw$pinFacing(CallbackInfo ci) {
        AEBasePart self = (AEBasePart) (Object) this;
        if (net.nestworld.compat.ae2.glue.BusTransit.manages(self)) {
            // WP3-B: the two exact bus classes pin / release their neighbour per operation (eligibility-driven)
            net.nestworld.compat.ae2.glue.BusTransit.onAddToWorld((appeng.parts.automation.IOBusPart) self);
            return;
        }
        if (nw$affinity != null || self instanceof CablePart || self.getHost() == null) return;
        Direction side = self.getSide();
        BlockEntity host = self.getHost().getBlockEntity();
        if (side == null || host == null || !(host.getLevel() instanceof ServerLevel level)) return;
        long[] positions = {host.getBlockPos().relative(side).asLong()};
        if (NeighborAffinity.pin(level, positions)) {
            nw$affinity = positions;
            nw$affinityLevel = level;
        }
    }

    @Inject(method = "removeFromWorld", at = @At("HEAD"))
    private void nw$releaseFacing(CallbackInfo ci) {
        AEBasePart self = (AEBasePart) (Object) this;
        if (self instanceof appeng.parts.automation.IOBusPart bus && net.nestworld.compat.ae2.glue.BusTransit.FLAG)
            net.nestworld.compat.ae2.glue.BusTransit.onRemoveFromWorld(bus);
        if (nw$affinity == null) return;
        NeighborAffinity.release(nw$affinityLevel, nw$affinity);
        nw$affinity = null;
        nw$affinityLevel = null;
    }
}
