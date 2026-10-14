package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.AEBaseBlockEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = AEBaseBlockEntity.class, remap = false)
public interface AEBaseBlockEntityInvoker {
    @Invoker("setChangedAtEndOfTick")
    Object nw$setChangedAtEndOfTick(Level level);
}
