package net.nestworld.adapter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * §41.C split (see {@link PlayerAttackAdapter} for the pattern this repeats). The single
 * Minecraft 1.20.1 vanilla call that used to live inline in
 * {@link net.nestworld.region.PlayerUseItemDispatcher#applyQueued}.
 */
public final class PlayerUseItemAdapter {
    private PlayerUseItemAdapter() {}

    /** Executes vanilla {@code ServerPlayerGameMode.useItem(Player, Level, ItemStack, InteractionHand)}. */
    public static InteractionResult invoke(ServerPlayer player, ServerLevel level, ItemStack itemstack, InteractionHand hand) {
        return player.gameMode.useItem(player, level, itemstack, hand);
    }
}
