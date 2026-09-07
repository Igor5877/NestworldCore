package net.nestworld.adapter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * §41.C split (see {@link PlayerAttackAdapter} for the pattern this repeats). The single
 * Minecraft 1.20.1 vanilla call that used to live inline in
 * {@link net.nestworld.region.PlayerInteractionDispatcher#applyQueued}.
 */
public final class PlayerInteractionAdapter {
    private PlayerInteractionAdapter() {}

    /** Executes vanilla {@code BlockState.use(Level, Player, InteractionHand, BlockHitResult)}. */
    public static InteractionResult invoke(BlockState state, ServerLevel level, ServerPlayer player,
            InteractionHand hand, BlockHitResult hit) {
        return state.use(level, player, hand, hit);
    }
}
