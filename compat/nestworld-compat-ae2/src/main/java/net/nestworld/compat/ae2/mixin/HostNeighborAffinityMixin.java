package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.glue.NeighborAffinity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AE2-NEIGHBOR-AFFINITY-001: full-block interfaces and pattern providers pin their six neighbours while ready. */
@Mixin(value = AENetworkBlockEntity.class, remap = false)
public abstract class HostNeighborAffinityMixin {
    @Unique private long[] nw$affinity;
    @Unique private ServerLevel nw$affinityLevel;

    @Inject(method = "onReady", at = @At("TAIL"))
    private void nw$pinNeighbours(CallbackInfo ci) {
        Object self = this;
        if (nw$affinity != null || !(self instanceof InterfaceLogicHost || self instanceof PatternProviderLogicHost)) return;
        AENetworkBlockEntity be = (AENetworkBlockEntity) self;
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        Direction[] dirs = Direction.values();
        long[] positions = new long[dirs.length];
        for (int i = 0; i < dirs.length; i++) positions[i] = be.getBlockPos().relative(dirs[i]).asLong();
        if (NeighborAffinity.pin(level, positions)) {
            nw$affinity = positions;
            nw$affinityLevel = level;
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"), remap = true)
    private void nw$releaseOnRemoved(CallbackInfo ci) {
        nw$release();
    }

    @Inject(method = "onChunkUnloaded", at = @At("HEAD"))
    private void nw$releaseOnUnload(CallbackInfo ci) {
        nw$release();
    }

    @Unique
    private void nw$release() {
        if (nw$affinity == null) return;
        NeighborAffinity.release(nw$affinityLevel, nw$affinity);
        nw$affinity = null;
        nw$affinityLevel = null;
    }
}
