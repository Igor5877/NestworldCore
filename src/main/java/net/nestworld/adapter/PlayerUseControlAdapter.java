package net.nestworld.adapter;

import net.minecraft.server.level.ServerPlayer;

/**
 * §41.C split (see {@link PlayerAttackAdapter} for the pattern this repeats). The two Minecraft
 * 1.20.1 vanilla calls that used to live inline in
 * {@link net.nestworld.region.PlayerUseControlDispatcher#applyQueued}'s switch, one per
 * {@code RegionMessage.UseControlOp} value (a Core-defined enum, not Minecraft's).
 */
public final class PlayerUseControlAdapter {
    private PlayerUseControlAdapter() {}

    /** Executes vanilla {@code Player.releaseUsingItem()}. */
    public static void release(ServerPlayer player) {
        player.releaseUsingItem();
    }

    /** Executes vanilla {@code LivingEntity.stopUsingItem()}. */
    public static void stop(ServerPlayer player) {
        player.stopUsingItem();
    }
}
