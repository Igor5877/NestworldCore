package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import appeng.util.ILevelRunnable;
import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.level.LevelEvent;
import net.nestworld.compat.ae2.glue.QueueStaging;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registry mutation under T0 ({@code Grid.nextSerial} and the registry side buffers are touched only
 * by topology entries), and the main-affinity premise of 001.4 §3 asserted at every traversal.
 */
@Mixin(value = TickHandler.class, remap = false)
public abstract class TickHandlerMixin {
    @Shadow @Final private Queue<ILevelRunnable> serverQueue;
    @Shadow @Final private Map<LevelAccessor, Queue<ILevelRunnable>> callQueue;

    // ---------------------------------------------------------------- QUEUE-001 producer→main handoff

    /** Off-main producers are staged; main keeps AE2's exact path (QUEUE-001 §3). */
    @WrapMethod(method = "addCallable(Lnet/minecraft/world/level/LevelAccessor;Lappeng/util/ILevelRunnable;)V")
    private void nw$addCallable(LevelAccessor level, ILevelRunnable c, Operation<Void> op) {
        if (!QueueStaging.offMain()) {
            op.call(level, c);
            return;
        }
        Preconditions.checkArgument(level == null || !level.isClientSide(), "Can only register serverside callbacks");
        QueueStaging.stage(level, c);
    }

    /** Server-phase drain LP: right before AE2 processes serverQueue. */
    @Inject(method = "onServerTickEnd", at = @At(value = "INVOKE",
            target = "Lappeng/hooks/ticking/TickHandler;processQueue(Ljava/util/Queue;Lnet/minecraft/world/level/Level;)I"))
    private void nw$drainServer(CallbackInfo ci) {
        QueueStaging.drainServer(serverQueue);
    }

    /** Level-phase drain LP: before AE2's remove → process → put → merge of callQueue[level]. */
    @Inject(method = "onServerLevelTickStart", at = @At("HEAD"))
    private void nw$drainLevel(ServerLevel level, CallbackInfo ci) {
        QueueStaging.drainLevel(level, callQueue);
    }

    @Inject(method = "onUnloadLevel", at = @At("HEAD"))
    private void nw$dropLevel(LevelEvent.Unload ev, CallbackInfo ci) {
        if (!ev.getLevel().isClientSide()) QueueStaging.dropLevel(ev.getLevel());
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void nw$clearStaging(CallbackInfo ci) {
        QueueStaging.clear();
    }

    // ---------------------------------------------------------------- registry (T0) and main affinity
    @WrapMethod(method = "addNetwork")
    private void nw$addNetwork(Grid grid, Operation<Void> op) {
        KERNEL.guardedStrictUnchecked(Specs.topology(List::of), () -> op.call(grid));
    }

    @WrapMethod(method = "removeNetwork")
    private void nw$removeNetwork(Grid grid, Operation<Void> op) {
        KERNEL.guardedStrictUnchecked(Specs.topology(List::of), () -> op.call(grid));
    }

    @Inject(method = "onServerTickStart", at = @At("HEAD"))
    private void nw$mainStart(CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerTickStart");
    }

    @Inject(method = "onServerTickEnd", at = @At("HEAD"))
    private void nw$mainEnd(CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerTickEnd");
    }

    @Inject(method = "onServerLevelTickStart", at = @At("HEAD"))
    private void nw$mainLevelStart(ServerLevel level, CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerLevelTickStart");
    }

    @Inject(method = "onServerLevelTickEnd", at = @At("HEAD"))
    private void nw$mainLevelEnd(ServerLevel level, CallbackInfo ci) {
        Ae2Compat.assertMain("TickHandler.onServerLevelTickEnd");
    }
}
