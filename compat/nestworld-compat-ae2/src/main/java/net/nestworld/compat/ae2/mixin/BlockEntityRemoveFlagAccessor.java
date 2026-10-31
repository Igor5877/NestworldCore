package net.nestworld.compat.ae2.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The vanilla removed flag, as AE2's QNB breakClusterOnRemove toggles it around its cluster destroy (D4a port). */
@Mixin(BlockEntity.class)
public interface BlockEntityRemoveFlagAccessor {
    @Accessor("remove")
    boolean nw$removeFlag();

    @Accessor("remove")
    void nw$setRemoveFlag(boolean remove);
}
