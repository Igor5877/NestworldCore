package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minecraft.world.level.Level;
import net.nestworld.compat.ae2.glue.GridCensus;
import net.nestworld.compat.ae2.glue.GridPhaseGate;
import net.nestworld.compat.ae2.glue.GridStaging;
import net.nestworld.compat.ae2.glue.NwGrid;
import net.nestworld.compat.ae2.glue.NwGridBound;
import net.nestworld.compat.ae2.glue.NwTickManager;
import net.nestworld.compat.ae2.glue.Specs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grid: the GRID-PHASE gate P(G) (phase XOR structural topology), the outer Grid.on*Tick phase
 * boundary (so every IGridServiceProvider, addons included, is covered), and grid back-references.
 */
@Mixin(value = Grid.class, remap = false)
public abstract class GridMixin implements NwGrid {
    @Unique private final ReentrantLock nw$p = new ReentrantLock(true);
    @Unique private int nw$phaseDepth;
    @Unique private final GridStaging nw$staging = new GridStaging();

    @Override
    public ReentrantLock nw$p() {
        return nw$p;
    }

    @Override
    public int nw$phaseDepth() {
        return nw$phaseDepth;
    }

    @Override
    public void nw$phaseDepth(int depth) {
        nw$phaseDepth = depth;
    }

    @Override
    public GridStaging nw$staging() {
        return nw$staging;
    }

    /** Services AE2 constructs without a grid get a back-reference (T1/T2 and staging resolve through it). */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nw$bind(GridNode center, CallbackInfo ci) {
        Grid self = (Grid) (Object) this;
        ((NwTickManager) self.getTickManager()).nw$setGrid(self);
        Object storage = self.getStorageService(), crafting = self.getCraftingService();
        ((NwGridBound) storage).nw$bindGrid(self);
        ((NwGridBound) ((StorageServiceAccessor) storage).nw$interestManager()).nw$bindGrid(self);
        ((NwGridBound) crafting).nw$bindGrid(self);
        // AE2-GRID-DOMAIN-001 G1: NetworkStorage knows its grid for P(G) ingress (mixin present only with the worker on)
        if (self.getStorageService().getInventory() instanceof NwGridBound ns) ns.nw$bindGrid(self);
        ((NwGridBound) ((CraftingServiceAccessor) crafting).nw$interestManager()).nw$bindGrid(self);
    }

    // ---------------------------------------------------------------- phases (main)

    @WrapMethod(method = "onServerStartTick")
    private void nw$serverStart(Operation<Void> op) {
        Grid self = (Grid) (Object) this;
        if (nw$startOnWorker(self, () -> op.call())) return;
        net.nestworld.compat.ae2.glue.GridWorker.awaitAll();   // AE2-GRID-DOMAIN-001: previous tick's phases first
        GridPhaseGate.phase(self, false, () -> op.call());
    }

    @WrapMethod(method = "onLevelStartTick")
    private void nw$levelStart(Level level, Operation<Void> op) {
        Grid self = (Grid) (Object) this;
        if (nw$startOnWorker(self, () -> op.call(level))) return;
        net.nestworld.compat.ae2.glue.GridWorker.awaitAll();
        GridPhaseGate.phase(self, false, () -> op.call(level));
    }

    /** MVP-001 overlapStart: the unchanged START phase on the domain (FIFO), MAIN does not wait. false ⇒ inline as before. */
    private static boolean nw$startOnWorker(Grid self, Runnable call) {
        if (!net.nestworld.compat.ae2.glue.GridWorker.OVERLAP_START) return false;
        Runnable body = () -> {
            net.nestworld.compat.ae2.glue.MainService.enter(self);
            try {
                GridPhaseGate.phase(self, false, () -> { call.run(); return null; });
            } finally {
                net.nestworld.compat.ae2.glue.MainService.exit();
            }
        };
        if (!net.nestworld.compat.ae2.glue.GridWorker.submit(self, body)) return false;
        net.nestworld.compat.ae2.glue.GridWorker.startSubmitted.increment();
        return true;
    }

    @WrapMethod(method = "onLevelEndTick")
    private void nw$levelEnd(Level level, Operation<Void> op) {
        Grid self = (Grid) (Object) this;
        Runnable body = () -> {
            long t0 = GridCensus.on ? System.nanoTime() : 0;
            boolean worker = net.nestworld.compat.ae2.glue.GridWorker.isWorkerThread();
            if (worker) net.nestworld.compat.ae2.glue.MainService.enter(self);
            try {
                GridPhaseGate.phase(self, false, () -> {
                    if (worker) net.nestworld.compat.ae2.glue.BusTransit.drainOwnedGrid(self, level.getServer().getTickCount());
                    return op.call(level);
                });
            } finally {
                if (worker) net.nestworld.compat.ae2.glue.MainService.exit();
            }
            if (t0 != 0) GridCensus.record(self, false, System.nanoTime() - t0);
        };
        net.nestworld.compat.ae2.glue.GridWorker.endOfTickBarrier();   // MVP-001 overlapStart: one barrier per tick
        // AE2-GRID-DOMAIN-001 G1: eligible grid ⇒ the unchanged phase runs on the Core "ae2" domain (FIFO)
        if (!net.nestworld.compat.ae2.glue.GridWorker.submit(self, body)) {
            net.nestworld.compat.ae2.glue.GridWorker.awaitAll();   // AE2 never ticks two grids at once (shared hosts)
            body.run();
        }
    }

    /** Server END runs Storage/Crafting phases: staged service mutations are drained first (the cut). */
    @WrapMethod(method = "onServerEndTick")
    private void nw$serverEnd(Operation<Void> op) {
        Grid self = (Grid) (Object) this;
        Runnable body = () -> {
            long t0 = GridCensus.on ? System.nanoTime() : 0;
            boolean worker = net.nestworld.compat.ae2.glue.GridWorker.isWorkerThread();
            if (worker) net.nestworld.compat.ae2.glue.MainService.enter(self);
            try {
                GridPhaseGate.phase(self, true, () -> {
                    if (worker) {
                        var srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (srv != null) net.nestworld.compat.ae2.glue.BusTransit.drainOwnedGrid(self, srv.getTickCount());
                    }
                    return op.call();
                });
            } finally {
                if (worker) net.nestworld.compat.ae2.glue.MainService.exit();
            }
            if (t0 != 0) GridCensus.record(self, true, System.nanoTime() - t0);
        };
        net.nestworld.compat.ae2.glue.GridWorker.endOfTickBarrier();
        if (!net.nestworld.compat.ae2.glue.GridWorker.submit(self, body)) {
            net.nestworld.compat.ae2.glue.GridWorker.awaitAll();   // AE2 never ticks two grids at once (shared hosts)
            body.run();
        }
    }

    // ---------------------------------------------------------------- topology (G1)

    /** G1: grid creation (P → T0 → domains; the centre's current grid, if any, is part of the move). */
    @WrapMethod(method = "create")
    private static Grid nw$create(GridNode center, Operation<Grid> op) {
        Supplier<List<Grid>> grids = () -> {
            List<Grid> l = new ArrayList<>();
            l.add(Specs.myGrid(center));
            return l;
        };
        return GridPhaseGate.topology(grids, () -> KERNEL.guardedStrictUnchecked(Specs.topology(grids), () -> op.call(center)));
    }
}
