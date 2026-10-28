package net.nestworld.ae2detect.mixin;

import appeng.api.networking.IGridNodeListener;
import appeng.me.Grid;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: Grid.ITERATION_BUFFER is used inside notifyAllNodes. */
@Mixin(value = Grid.class, remap = false)
public abstract class GridNotifyDetectMixin {
    @WrapMethod(method = "notifyAllNodes")
    private void nwd$notify(IGridNodeListener.State state, Operation<Void> op) {
        StaticDetect.enter(Site.ITERATION_BUFFER);
        try { op.call(state); } finally { StaticDetect.exit(Site.ITERATION_BUFFER); }
    }
}
