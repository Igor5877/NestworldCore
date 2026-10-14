package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.storage.IStorageProvider;
import appeng.me.Grid;
import appeng.me.service.StorageService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.NwGridBound;
import net.nestworld.compat.ae2.glue.Staging;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Storage provider rebuild requests: phase-deferred, coalesced per (provider, kind) — owner-approved. */
@Mixin(value = StorageService.class, remap = false)
public abstract class StorageServiceMixin implements NwGridBound {
    @Unique private volatile Grid nw$grid;

    @Override
    public Grid nw$boundGrid() {
        return nw$grid;
    }

    @Override
    public void nw$bindGrid(Grid grid) {
        nw$grid = grid;
    }

    @WrapMethod(method = "refreshNodeStorageProvider")
    private void nw$refreshNode(IGridNode node, Operation<Void> op) {
        StorageService self = (StorageService) (Object) this;
        Staging.apply(nw$grid, () -> op.call(node),
                () -> Staging.stageRebuild(nw$grid, node, "storage-node", () -> self.refreshNodeStorageProvider(node)));
    }

    @WrapMethod(method = "refreshGlobalStorageProvider")
    private void nw$refreshGlobal(IStorageProvider provider, Operation<Void> op) {
        StorageService self = (StorageService) (Object) this;
        Staging.apply(nw$grid, () -> op.call(provider),
                () -> Staging.stageRebuild(nw$grid, provider, "storage-global", () -> self.refreshGlobalStorageProvider(provider)));
    }
}
