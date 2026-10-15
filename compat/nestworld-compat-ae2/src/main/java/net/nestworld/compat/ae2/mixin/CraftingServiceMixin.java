package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.NwGridBound;
import net.nestworld.compat.ae2.glue.Staging;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Crafting provider rebuild requests: phase-deferred, coalesced per (provider, kind) — owner-approved. */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin implements NwGridBound {
    @Unique private volatile Grid nw$grid;

    @Override
    public Grid nw$boundGrid() {
        return nw$grid;
    }

    @Override
    public void nw$bindGrid(Grid grid) {
        nw$grid = grid;
    }

    @WrapMethod(method = "refreshNodeCraftingProvider")
    private void nw$refreshNode(IGridNode node, Operation<Void> op) {
        CraftingService self = (CraftingService) (Object) this;
        Grid g = nw$grid;
        Staging.apply(g, node, () -> op.call(node),
                () -> Staging.stageRebuild(g, node, "crafting-node", () -> {
                    // refreshNodeCraftingProvider would re-ADD a provider of a node that already left: drop instead
                    if (node.getGrid() == g) self.refreshNodeCraftingProvider(node);
                    else Staging.dropStale();
                }));
    }
}
