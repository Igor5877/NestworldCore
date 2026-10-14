package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.hooks.ticking.TickHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.level.Level;
import net.nestworld.compat.ae2.glue.NwAeBlockEntity;
import net.nestworld.compat.ae2.glue.SetChangedProtocol;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** QUEUE-001: four-state setChanged protocol and the per-block-entity lifecycle lock. */
@Mixin(value = AEBaseBlockEntity.class, remap = false)
public abstract class AEBaseBlockEntityMixin implements NwAeBlockEntity {
    @Shadow private appeng.api.orientation.BlockOrientation pendingOrientationChange;

    @Unique private final AtomicLong nw$sc = new AtomicLong();
    @Unique private volatile int nw$cbGen;
    @Unique private final ReentrantLock nw$life = new ReentrantLock();
    @Unique private volatile boolean nw$removed;

    @Override
    public AtomicLong nw$sc() {
        return nw$sc;
    }

    @Override
    public int nw$cbGen() {
        return nw$cbGen;
    }

    @Override
    public int nw$nextCbGen() {
        return ++nw$cbGen;                                // main only (the consumer)
    }

    @Override
    public ReentrantLock nw$life() {
        return nw$life;
    }

    @Override
    public boolean nw$removed() {
        return nw$removed;
    }

    @Override
    public void nw$markRemoved() {
        nw$removed = true;
    }

    @Override
    public appeng.api.orientation.BlockOrientation nw$pendingOrientation() {
        return pendingOrientationChange;
    }

    @Override
    public void nw$clearPendingOrientation() {
        pendingOrientationChange = null;
    }

    /**
     * LEGACY-ORIENTATION-001 live-world case (owner: loadTag/onReady world mutation REJECTED; fail-closed
     * until an owner-routed bounded migration exists): the disk path is migrated before publication
     * (LegacyOrientation), so a pending orientation reaching onReady comes from NBT loaded into a live
     * world. Do not write the world; record it.
     */
    @WrapOperation(method = "onReady", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean nw$noLiveWorldOrientationWrite(Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, Operation<Boolean> op) {
        net.nestworld.compat.ae2.glue.LegacyOrientation.liveWorldSkipped(this, pos, state);
        return false;
    }

    @Override
    public void nw$requeueSetChanged() {
        AEBaseBlockEntityInvoker self = (AEBaseBlockEntityInvoker) this;
        TickHandler.instance().addCallable(null, (Level level) -> self.nw$setChangedAtEndOfTick(level));
    }

    /** Producer: the read of {@code setChangedQueued} becomes the protocol's CAS (P-LP). */
    @WrapOperation(method = "saveChanges", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lappeng/blockentity/AEBaseBlockEntity;setChangedQueued:Z"))
    private boolean nw$produce(AEBaseBlockEntity self, Operation<Boolean> op) {
        return SetChangedProtocol.produce(this);
    }

    @WrapOperation(method = "saveChanges", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lappeng/blockentity/AEBaseBlockEntity;setChangedQueued:Z"))
    private void nw$producerFlagWrite(AEBaseBlockEntity self, boolean value, Operation<Void> op) {
        // the protocol word replaces the plain flag
    }

    /** Consumer: claim before setChanged reads state; finish with exact CAS rules (QUEUE-001.1 §4.2). */
    @WrapMethod(method = "setChangedAtEndOfTick")
    private Object nw$consume(Level level, Operation<Object> op) {
        return SetChangedProtocol.run(this, () -> op.call(level));
    }

    @WrapOperation(method = "setChangedAtEndOfTick", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lappeng/blockentity/AEBaseBlockEntity;setChangedQueued:Z"))
    private void nw$consumerFlagWrite(AEBaseBlockEntity self, boolean value, Operation<Void> op) {
        // finish is done by the protocol after the body
    }
}
