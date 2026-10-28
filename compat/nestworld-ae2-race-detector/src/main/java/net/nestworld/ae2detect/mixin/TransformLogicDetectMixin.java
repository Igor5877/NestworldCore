package net.nestworld.ae2detect.mixin;

import appeng.recipes.transform.TransformCircumstance;
import appeng.recipes.transform.TransformLogic;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Predicate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.material.FluidState;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: TransformLogic's static caches are read/filled by these entry points. */
@Mixin(value = TransformLogic.class, remap = false)
public abstract class TransformLogicDetectMixin {
    @WrapMethod(method = "canTransformInFluid")
    private static boolean nwd$fluid(ItemEntity e, FluidState f, Operation<Boolean> op) {
        StaticDetect.enter(Site.TRANSFORM_CACHE);
        try { return op.call(e, f); } finally { StaticDetect.exit(Site.TRANSFORM_CACHE); }
    }

    @WrapMethod(method = "canTransformInAnyFluid")
    private static boolean nwd$anyFluid(ItemEntity e, Operation<Boolean> op) {
        StaticDetect.enter(Site.TRANSFORM_CACHE);
        try { return op.call(e); } finally { StaticDetect.exit(Site.TRANSFORM_CACHE); }
    }

    @WrapMethod(method = "canTransformInExplosion")
    private static boolean nwd$explosion(ItemEntity e, Operation<Boolean> op) {
        StaticDetect.enter(Site.TRANSFORM_CACHE);
        try { return op.call(e); } finally { StaticDetect.exit(Site.TRANSFORM_CACHE); }
    }

    @WrapMethod(method = "tryTransform")
    private static boolean nwd$try(ItemEntity e, Predicate<TransformCircumstance> p, Operation<Boolean> op) {
        StaticDetect.enter(Site.TRANSFORM_CACHE);
        try { return op.call(e, p); } finally { StaticDetect.exit(Site.TRANSFORM_CACHE); }
    }
}
