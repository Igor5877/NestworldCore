package net.nestworld.compat.ae2.mixin;

import appeng.blockentity.crafting.CraftingBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.nestworld.compat.ae2.glue.BlockCallbacks;
import org.spongepowered.asm.mixin.Mixin;

/** D2 / I3: AbstractCraftingUnitBlock.neighborChanged → CraftingBlockEntity.updateMultiBlock (MBCalculator). */
@Mixin(value = CraftingBlockEntity.class, remap = false)
public abstract class CraftingBlockEntityD2Mixin {
    @WrapMethod(method = "updateMultiBlock")
    private void nw$d2(BlockPos changedPos, Operation<Void> op) {
        BlockCallbacks.route((CraftingBlockEntity) (Object) this, "crafting.updateMultiBlock", () -> op.call(changedPos));
    }
}
