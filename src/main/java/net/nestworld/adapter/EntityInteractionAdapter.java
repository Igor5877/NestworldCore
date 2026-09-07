package net.nestworld.adapter;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * §41.C split (see {@link PlayerAttackAdapter} for the pattern this repeats). The vanilla/Forge
 * call that used to live inline (as the private {@code runInteraction} helper) in
 * {@link net.nestworld.region.EntityInteractionDispatcher}. Note this is the loader-specific case
 * flagged in docs/DEPENDENCY_DIRECTION_AUDIT.md's priority list: {@code ForgeHooks} is Forge API,
 * not vanilla, so isolating it here is exactly the kind of boundary that would need a real
 * NeoForge/Fabric counterpart, not a mechanical port.
 */
public final class EntityInteractionAdapter {
    private EntityInteractionAdapter() {}

    /**
     * Replicates the vanilla/Forge call verbatim: {@code atPos == null} -> {@code
     * player.interactOn(target, hand)} ({@code Player::interactOn}); {@code atPos != null} -> the
     * Forge hook then {@code target.interactAt(player, atPos, hand)} -- matches {@code
     * ServerGamePacketListenerImpl.handleInteract()}'s two branches exactly.
     */
    public static InteractionResult invoke(ServerPlayer player, Entity target, InteractionHand hand, Vec3 atPos) {
        if (atPos == null) {
            return player.interactOn(target, hand);
        }
        InteractionResult forgeResult = net.minecraftforge.common.ForgeHooks.onInteractEntityAt(player, target, atPos, hand);
        if (forgeResult != null) {
            return forgeResult;
        }
        return target.interactAt(player, atPos, hand);
    }
}
