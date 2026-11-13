package net.nestworld.compat.ae2.gridworker.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.Grid;
import appeng.me.storage.NetworkStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.nestworld.compat.ae2.glue.GridWorker;
import net.nestworld.compat.ae2.glue.NwGridBound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * AE2-GRID-DOMAIN-001 G1: NetworkStorage knows its grid; a caller that is not the grid's current owner (MAIN while
 * the worker may run this grid, or any other thread) takes P(G) around insert/extract (census: Fluix Pool,
 * botanicalextramachinery ME export). Owner calls (inside the grid phase, P(G) held) pass straight through.
 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageGridMixin implements NwGridBound {
    @Unique private volatile Grid nw$grid;

    @Override public Grid nw$boundGrid() { return nw$grid; }
    @Override public void nw$bindGrid(Grid grid) { nw$grid = grid; }

    @WrapMethod(method = "insert")
    private long nw$insert(AEKey what, long amount, Actionable type, IActionSource src, Operation<Long> op) {
        return GridWorker.ingress(nw$grid, () -> op.call(what, amount, type, src));
    }

    @WrapMethod(method = "extract")
    private long nw$extract(AEKey what, long amount, Actionable mode, IActionSource source, Operation<Long> op) {
        return GridWorker.ingress(nw$grid, () -> op.call(what, amount, mode, source));
    }
}
