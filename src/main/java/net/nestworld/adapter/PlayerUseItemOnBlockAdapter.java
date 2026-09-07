package net.nestworld.adapter;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/**
 * §41.C split (see {@link PlayerAttackAdapter} for the pattern this repeats). The Minecraft
 * 1.20.1 vanilla call (plus its creative-mode count-preservation quirk) that used to live inline
 * in {@link net.nestworld.region.PlayerUseItemOnBlockDispatcher#applyQueued}.
 */
public final class PlayerUseItemOnBlockAdapter {
    private PlayerUseItemOnBlockAdapter() {}

    /**
     * Executes vanilla {@code ItemStack.useOn(UseOnContext)}, replicating
     * {@code ServerPlayerGameMode.useItemOn()}'s creative-mode count-preservation wrapper verbatim
     * -- Minecraft 1.20.1 game-mode behavior, not a Core decision.
     */
    public static InteractionResult invoke(ServerPlayer player, ItemStack itemstack, UseOnContext useoncontext) {
        if (player.gameMode.isCreative()) {
            int i = itemstack.getCount();
            InteractionResult result = itemstack.useOn(useoncontext);
            itemstack.setCount(i);
            return result;
        }
        return itemstack.useOn(useoncontext);
    }
}
