package net.nestworld.ae2detect.mixin;

import appeng.me.cluster.MBCalculator;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: calculateMultiblock reads and sets the static MBCalculator.modificationInProgress. */
@Mixin(value = MBCalculator.class, remap = false)
public abstract class MBCalculatorDetectMixin {
    @WrapMethod(method = "calculateMultiblock")
    private void nwd$calc(ServerLevel level, BlockPos loc, Operation<Void> op) {
        StaticDetect.enter(Site.MB_MODIFICATION);
        String host = "mb@" + loc.toShortString();
        net.nestworld.ae2detect.FailureAttribution.enter(net.nestworld.ae2detect.FailureAttribution.Kind.MB, host);
        try { op.call(level, loc); } finally {
            StaticDetect.exit(Site.MB_MODIFICATION);
            net.nestworld.ae2detect.FailureAttribution.exit(net.nestworld.ae2detect.FailureAttribution.Kind.MB, host);
        }
    }
}
