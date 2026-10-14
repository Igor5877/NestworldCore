package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.nestworld.compat.ae2.glue.NwAeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * QUEUE-001.1 §4.3 remover side: the two cross-thread removal sites (replacement in setBlockEntity,
 * removeBlockEntity) take the AE2 block entity's lifecycle lock around the VIRTUAL setRemoved call, so
 * the most-derived override (AE2 classes and addons: mainNode.destroy) is covered. Chunk unload
 * (clearAllBlockEntities) runs on main, the initializer's own thread, and needs no lock.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @WrapOperation(method = {"setBlockEntity", "removeBlockEntity"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
    private void nw$lockedRemoval(BlockEntity be, Operation<Void> op) {
        if (!(be instanceof NwAeBlockEntity life)) {
            op.call(be);
            return;
        }
        if (KERNEL.inSection()) {
            // taking nw$life here would invert nw$life → T0/G; fail-safe: serialize, remove unlocked
            COUNTERS.lifeOrderViolation.increment();
            KERNEL.unaudited("AE2 block-entity removal inside a guarded section");
            life.nw$markRemoved();
            op.call(be);
            return;
        }
        life.nw$life().lock();
        try {
            life.nw$markRemoved();
            op.call(be);
        } finally {
            life.nw$life().unlock();
        }
    }
}
