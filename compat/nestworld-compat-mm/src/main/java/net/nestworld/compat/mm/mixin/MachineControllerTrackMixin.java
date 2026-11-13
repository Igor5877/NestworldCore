package net.nestworld.compat.mm.mixin;

import io.ticticboom.mods.mm.controller.machine.register.MachineControllerBlockEntity;
import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.mm.glue.MmControllers;
import net.nestworld.compat.mm.glue.NwMmController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers each ticking controller once (cheap flag check per tick). No MM behaviour is changed. */
@Mixin(value = MachineControllerBlockEntity.class, remap = false)
public abstract class MachineControllerTrackMixin implements NwMmController {
    @Shadow private List<BlockEntity> portBlockEntities;
    @Unique private volatile boolean nw$tracked;

    @Inject(method = "tick", at = @At("HEAD"))
    private void nw$track(CallbackInfo ci) {
        if (!nw$tracked) {
            nw$tracked = true;
            MmControllers.track((BlockEntity) (Object) this);
        }
    }

    @Override
    public List<BlockEntity> nw$ports() {
        return portBlockEntities;
    }

    @Override
    public void nw$untracked() {
        nw$tracked = false;
    }
}
