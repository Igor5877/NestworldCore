package net.nestworld.compat.ae2.mixin;

import net.minecraft.world.entity.decoration.ArmorStand;
import net.nestworld.compat.ae2.glue.RegionTasks;
import net.nestworld.compat.ae2.glue.T2Fixtures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fixture-only host for region-thread tasks (see RegionTasks); a no-op unless fixtures are enabled. */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void nw$regionTask(CallbackInfo ci) {
        if (T2Fixtures.ENABLED) RegionTasks.runFor(((ArmorStand) (Object) this).getUUID());
    }
}
