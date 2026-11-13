package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.Level;
import net.nestworld.compat.ae2.glue.MainService;
import net.nestworld.compat.ae2.glue.MainServicedCounters;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** AE2-GRID-DOMAIN-MVP-001 (interface audit 2026-11-13): AppliedFlux EnergyDistributeService.onLevelEndTick runs inside
 *  the grid's level-end phase (= on the AE2 worker) and its induction-card distributors push FE into NEIGHBOUR block
 *  entities' energy capabilities. Grids with active distributors: the call is one synchronous MAIN call (MAIN acts for
 *  the parked worker; neighbours are MAIN-pinned as today). Refused ⇒ no distribution this tick (counted). */
@Mixin(targets = "com.glodblock.github.appflux.common.me.service.EnergyDistributeService", remap = false)
public abstract class AppFluxDistributeMainServiceMixin {
    @Shadow @Final private java.util.Set<?> activeNodes;

    @WrapMethod(method = "onLevelEndTick")
    private void nw$distribute(Level level, Operation<Void> op) {
        if (activeNodes.isEmpty()) return;   // AE2/AppliedFlux: nothing to distribute (loop body never runs)
        try {
            MainService.call("appflux distribute", () -> { op.call(level); return null; });
        } catch (MainService.Refused r) {
            MainServicedCounters.refused();
        }
    }
}
