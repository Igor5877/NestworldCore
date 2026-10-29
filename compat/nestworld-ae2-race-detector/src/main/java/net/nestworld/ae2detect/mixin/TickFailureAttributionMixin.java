package net.nestworld.ae2detect.mixin;

import net.minecraft.server.MinecraftServer;
import net.nestworld.ae2detect.FailureAttribution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observation only: every tick failure NestWorld Core handles (skip or breaker) gets one attribution record. */
@Mixin(MinecraftServer.class)
public abstract class TickFailureAttributionMixin {
    @Inject(method = "nestworldOnTickFailure", at = @At("HEAD"), remap = false)
    private void nwd$attr(Throwable failure, CallbackInfoReturnable<Boolean> cir) {
        try {
            FailureAttribution.tickFailed(failure);
        } catch (Throwable ignored) {
            // attribution must never change failure handling
        }
    }
}
