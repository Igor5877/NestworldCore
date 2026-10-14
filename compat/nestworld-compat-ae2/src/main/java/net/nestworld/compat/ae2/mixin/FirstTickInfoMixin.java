package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.NwAeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * QUEUE-001.1 §4.3: first-tick initialization of an AE2 block entity runs under its lifecycle lock and
 * revalidates INSIDE it (never check-then-lock). Removal takes the same lock (LevelChunkMixin), so
 * onReady/create and setRemoved/destroy are mutually exclusive per block entity.
 */
@Mixin(targets = "appeng.hooks.ticking.ServerBlockEntityRepo$FirstTickInfo", remap = false)
public abstract class FirstTickInfoMixin {
    @Shadow
    public abstract BlockEntity blockEntity();

    @WrapMethod(method = "callInit")
    private void nw$callInit(Operation<Void> op) {
        BlockEntity be = blockEntity();
        if (!(be instanceof NwAeBlockEntity life)) {
            op.call();
            return;
        }
        if (KERNEL.inSection()) {                         // order is nw$life → T0 → domains
            COUNTERS.lifeOrderViolation.increment();
            throw KERNEL.unaudited("AE2 block-entity initialization inside a guarded section");
        }
        life.nw$life().lock();
        try {
            String reason = nw$invalid(be, life);
            if (reason != null) {
                COUNTERS.initCancelled.increment();
                return;
            }
            op.call();
        } finally {
            life.nw$life().unlock();
        }
    }

    private static String nw$invalid(BlockEntity be, NwAeBlockEntity life) {
        if (life.nw$removed() || be.isRemoved()) return "removed";
        Level level = be.getLevel();
        if (level == null) return "no level";
        BlockPos pos = be.getBlockPos();
        if (!level.isLoaded(pos)) return "chunk not loaded";
        if (level.getBlockEntity(pos) != be) return "not the block entity at its position";
        if (level.getBlockState(pos).getBlock() != be.getBlockState().getBlock()) return "block changed";
        return null;
    }
}
