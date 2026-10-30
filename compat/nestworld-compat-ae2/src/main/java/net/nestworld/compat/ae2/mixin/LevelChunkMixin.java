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
    @org.spongepowered.asm.mixin.Shadow private boolean loaded;

    /**
     * LEGACY-ORIENTATION-001.1 (corrected): the regular disk path creates block entities in
     * ChunkSerializer.postLoadChunk (run by runPostLoad inside protoChunkToFullChunk, before
     * setLoaded(true)): loadStatic → setBlockEntity. While {@code loaded} is false the block entity is
     * not yet published and the chunk is neither loaded into the level nor ticking. Live placements
     * reach setBlockEntity with {@code loaded == true} and are not migrated here.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void nw$migrateLegacyOnLoad(BlockEntity be, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!loaded && be instanceof NwAeBlockEntity ae) {
            net.nestworld.compat.ae2.glue.LegacyOrientation.migratePrePublication((LevelChunk) (Object) this, be.getBlockPos(), be, ae);
        }
    }

    /** LEGACY-ORIENTATION-001.1: pre-publication migration of AE2's legacy orientation (disk path). */
    @WrapOperation(method = "promotePendingBlockEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadStatic(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity nw$migrateLegacy(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                         net.minecraft.nbt.CompoundTag tag, Operation<BlockEntity> op) {
        BlockEntity be = op.call(pos, state, tag);
        if (be instanceof NwAeBlockEntity ae) {
            net.nestworld.compat.ae2.glue.LegacyOrientation.migratePrePublication((LevelChunk) (Object) this, pos, be, ae);
        }
        return be;
    }

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
        net.nestworld.compat.ae2.glue.LifeAdmission.enterLife();
        try {
            life.nw$markRemoved();
            op.call(be);
        } finally {
            net.nestworld.compat.ae2.glue.LifeAdmission.exitLife();
            life.nw$life().unlock();
        }
    }

    /**
     * AE2-PIN-LEAK-001 D1: admission around the whole block write — for a pinned AE2 host being removed/replaced from
     * a thread that is not its execution domain, the host's nw$life is taken BEFORE the section write and held through
     * onRemove and setRemoved (see {@link net.nestworld.compat.ae2.glue.LifeAdmission}).
     */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "setBlockState")
    private net.minecraft.world.level.block.state.BlockState nw$lifeAdmission(net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state, boolean isMoving,
            Operation<net.minecraft.world.level.block.state.BlockState> op) {
        return net.nestworld.compat.ae2.glue.LifeAdmission.around((LevelChunk) (Object) this, pos, state, () -> op.call(pos, state, isMoving));
    }

    /** Fixture point (no-op in production): the section already holds the new state, onRemove has not run yet. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "setBlockState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onRemove(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private void nw$afterSectionWrite(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
            boolean isMoving, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> cir) {
        net.nestworld.compat.ae2.glue.LifeAdmission.afterSectionWrite(pos);
    }
}
