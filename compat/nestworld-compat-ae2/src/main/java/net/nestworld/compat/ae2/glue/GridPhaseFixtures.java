package net.nestworld.compat.ae2.glue;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.IStorageProvider;
import appeng.me.Grid;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.nestworld.compat.ae2.mixin.StorageServiceAccessor;

/**
 * GRID-PHASE-001 fixtures (owner gate list): drain-cut boundary, coalescing, no immediate mutation while
 * the phase is active (even from main), watcher FIFO, destroyed-before-drain, and a region-thread
 * topology stress against main grid phases. Fixture mode only.
 */
public final class GridPhaseFixtures {
    private static volatile String status = "idle";
    private static volatile Runnable pending;
    private static volatile int evaluateAt = Integer.MAX_VALUE;

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        Runnable p = pending;
        if (p == null || tick < evaluateAt) return;
        pending = null;
        evaluateAt = Integer.MAX_VALUE;
        p.run();
    }

    private static void foreign(Runnable r) {
        Thread t = new Thread(r, "nw-gridphase-foreign");
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void finish(String name, List<String> f, String detail) {
        status = name + (f.isEmpty() ? ": PASS" : ": FAIL " + f) + " " + detail;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] gridphase fixture {}", status);
    }

    // ---------------------------------------------------------------- staging cases (need a drive)

    public static String staging(String kind, ServerLevel level, BlockPos drivePos) {
        if (pending != null) return "a fixture is running: " + status;
        if (!(level.getBlockEntity(drivePos) instanceof IGridConnectedBlockEntity drive) || drive.getMainNode().getNode() == null)
            return "no AE2 grid node at " + drivePos;
        IGridNode node = drive.getMainNode().getNode();
        Grid grid = (Grid) node.getGrid();
        var c = Ae2Compat.COUNTERS;
        long staged0 = c.stagedRebuild.sum(), coal0 = c.coalescedRebuild.sum(), drained0 = c.drainedRebuild.sum(),
                wStaged0 = c.stagedWatcherOps.sum(), wDrained0 = c.drainedWatcherOps.sum(), dropped0 = c.droppedStaged.sum();
        List<String> f = new ArrayList<>();
        int[] drainTick = {-1, -1};
        AtomicInteger cuts = new AtomicInteger();
        AEKey keyA = AEItemKey.of(Items.STONE), keyB = AEItemKey.of(Items.DIRT);
        IStackWatcher[] watcher = {null};
        IManagedGridNode[] watcherNode = {null};
        if (kind.startsWith("watcher") || kind.equals("activephase")) {
            watcherNode[0] = GridHelper.createManagedNode(new Object(), (IGridNodeListener<Object>) (o, n) -> { })
                    .setInWorldNode(false)
                    .addService(IStorageWatcherNode.class, new IStorageWatcherNode() {
                        public void updateWatcher(IStackWatcher w) {
                            watcher[0] = w;
                        }

                        public void onStackChange(AEKey what, long amount) {
                        }
                    });
            watcherNode[0].create(level, null);
            GridHelper.createConnection(watcherNode[0].getNode(), node);         // main, outside a phase
            if (watcher[0] == null) return "watcher was not handed out by StorageService.addNode";
        }
        var im = ((StorageServiceAccessor) grid.getStorageService()).nw$interestManager();
        Runnable cleanup = () -> {
            Staging.hook = null;
            if (watcherNode[0] != null) watcherNode[0].destroy();
        };
        switch (kind) {
            case "boundary" -> Staging.hook = (pt, g) -> {
                if (g != grid) return;
                int t = level.getServer().getTickCount();
                if (pt.equals("drain-cut") && cuts.getAndIncrement() == 0) {
                    foreign(() -> IStorageProvider.requestUpdate(drive.getMainNode()));   // before the cut point's drain
                    drainTick[0] = t;
                } else if (pt.equals("drain-done") && cuts.get() == 1 && drainTick[1] < 0) {
                    drainTick[1] = t;
                    if (c.drainedRebuild.sum() - drained0 != 1) f.add("request published before the cut was not applied in this phase");
                    foreign(() -> IStorageProvider.requestUpdate(drive.getMainNode()));   // after the cut
                    if (((NwGrid) grid).nw$staging().rebuilds.size() != 1) f.add("request published after the cut was not left for the next phase");
                    if (c.drainedRebuild.sum() - drained0 != 1) f.add("request published after the cut was applied in this phase");
                }
            };
            case "coalesce" -> foreign(() -> {
                for (int i = 0; i < 3; i++) IStorageProvider.requestUpdate(drive.getMainNode());
            });
            case "activephase" -> Staging.hook = (pt, g) -> {
                if (g != grid || !pt.equals("phase-active") || cuts.getAndIncrement() != 0) return;
                watcher[0].add(keyA);                                    // main, phase ACTIVE → must stage
                IStorageProvider.requestUpdate(drive.getMainNode());     // main, phase ACTIVE → must stage
                if (im.containsKey(keyA)) f.add("watcher add applied immediately inside the active phase");
            };
            case "watcherfifo" -> foreign(() -> {
                watcher[0].add(keyB);
                watcher[0].reset();
                watcher[0].add(keyA);
                watcher[0].add(keyB);
            });
            case "watcherdestroyed" -> {
                foreign(() -> watcher[0].add(keyA));
                watcherNode[0].destroy();                                // main topology: watcher destroyed before drain
                watcherNode[0] = null;
            }
            default -> {
                return "unknown kind " + kind;
            }
        }
        evaluateAt = level.getServer().getTickCount() + 5;
        pending = () -> {
            boolean hasA = im.containsKey(keyA), hasB = im.containsKey(keyB);   // before cleanup destroys the watcher
            cleanup.run();
            long staged = c.stagedRebuild.sum() - staged0, coal = c.coalescedRebuild.sum() - coal0, drained = c.drainedRebuild.sum() - drained0,
                    wStaged = c.stagedWatcherOps.sum() - wStaged0, wDrained = c.drainedWatcherOps.sum() - wDrained0, dropped = c.droppedStaged.sum() - dropped0;
            switch (kind) {
                case "boundary" -> {
                    if (staged != 2 || drained != 2) f.add("staged " + staged + " drained " + drained + " (expected 2/2)");
                    if (drainTick[0] < 0 || drainTick[1] < 0) f.add("cut hooks not reached");
                }
                case "coalesce" -> {
                    if (staged != 1 || coal != 2 || drained != 1) f.add("staged " + staged + " coalesced " + coal + " drained " + drained + " (expected 1/2/1)");
                }
                case "activephase" -> {
                    if (wStaged != 1 || staged != 1) f.add("in-phase mutations not staged (watcherStaged " + wStaged + ", rebuildStaged " + staged + ")");
                    if (wDrained < 1 || drained < 1) f.add("not applied at the next boundary");
                }
                case "watcherfifo" -> {
                    if (wStaged != 4 || wDrained != 4) f.add("watcher ops staged " + wStaged + " drained " + wDrained + " (expected 4/4)");
                    if (!(hasA && hasB)) f.add("FIFO order violated: final membership A=" + hasA + " B=" + hasB + " (expected both)");
                }
                case "watcherdestroyed" -> {
                    if (hasA) f.add("operation on a destroyed watcher was applied");
                    if (wStaged != 1) f.add("expected 1 staged op, got " + wStaged);
                }
                default -> {
                }
            }
            if (c.stagingErrors.sum() > 0) f.add("stagingErrors=" + c.stagingErrors.sum());
            finish("STAGING-" + kind, f, "staged=" + staged + " coalesced=" + coal + " drained=" + drained + " watcherStaged=" + wStaged
                    + " watcherDrained=" + wDrained + " dropped=" + dropped + " cutTicks=" + drainTick[0] + "/" + drainTick[1]);
        };
        status = "STAGING-" + kind + ": running";
        return status;
    }

    // ---------------------------------------------------------------- region-thread topology stress

    public static String stress(ServerLevel level, BlockPos target, int seconds) {
        if (pending != null) return "a fixture is running: " + status;
        var hosts = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                new net.minecraft.world.phys.AABB(target).inflate(8));
        if (hosts.isEmpty()) return "no armor stand host near " + target;
        UUID host = hosts.get(0).getUUID();
        Block inscriber = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "inscriber"));
        AtomicInteger toggles = new AtomicInteger();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        var c = Ae2Compat.COUNTERS;
        long fail0 = c.pTryFailures.sum(), retry0 = c.pRetryCount.sum(), starve0 = c.pWriterStarvationWarning.sum();
        String[] thread = {null};
        Runnable[] step = {null};
        step[0] = () -> {
            thread[0] = Thread.currentThread().getName();
            if (level.getBlockState(target).isAir()) level.setBlock(target, inscriber.defaultBlockState(), 3);
            else level.removeBlock(target, false);                     // single-node grid dies on the region thread
            toggles.incrementAndGet();
            if (System.nanoTime() < end) RegionTasks.submit(host, step[0]);
        };
        RegionTasks.submit(host, step[0]);
        evaluateAt = level.getServer().getTickCount() + seconds * 20 + 40;
        pending = () -> finish("STRESS", new ArrayList<>(), "toggles=" + toggles.get() + " thread=" + thread[0]
                + " pTryFailures+" + (c.pTryFailures.sum() - fail0) + " pRetryCount+" + (c.pRetryCount.sum() - retry0)
                + " pMaxRetry=" + c.pMaxRetry.get() + " starvation+" + (c.pWriterStarvationWarning.sum() - starve0));
        status = "STRESS: running";
        return status;
    }

    /**
     * Deterministic overlap: main parks inside an ACTIVE server-END phase of the drive's grid G while a
     * RegionThread (armor-stand host) toggles a node block adjacent to the drive (topology on G).
     * With P(G) the region cannot complete during the phase; without P it does (negative control).
     */
    public static String overlap(ServerLevel level, BlockPos drivePos, BlockPos target) {
        if (pending != null) return "a fixture is running: " + status;
        if (!(level.getBlockEntity(drivePos) instanceof IGridConnectedBlockEntity drive) || drive.getMainNode().getNode() == null)
            return "no AE2 grid node at " + drivePos;
        Grid grid = (Grid) drive.getMainNode().getNode().getGrid();
        var hosts = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                new net.minecraft.world.phys.AABB(target).inflate(8));
        if (hosts.isEmpty()) return "no armor stand host near " + target;
        UUID host = hosts.get(0).getUUID();
        if (!(level.getBlockEntity(target) instanceof IGridConnectedBlockEntity t) || t.getMainNode().getNode() == null
                || t.getMainNode().getNode().getGrid() != grid) return "target must be an AE2 node already joined to the drive's grid";
        var c = Ae2Compat.COUNTERS;
        long fail0 = c.pTryFailures.sum();
        boolean[] done = {false};
        boolean[] completedDuringPhase = {false};
        boolean[] routed = {false};
        String[] thread = {null};
        AtomicInteger hits = new AtomicInteger();
        Staging.hook = (pt, g) -> {
            if (g != grid || !pt.equals("phase-active") || hits.getAndIncrement() != 0) return;
            RegionTasks.submit(host, () -> {
                thread[0] = Thread.currentThread().getName();
                // topology on G without a world write (block writes may be routed by Core): destroy the node
                t.getMainNode().destroy();
                routed[0] = t.getMainNode().getNode() != null;     // not destroyed synchronously here
                done[0] = true;
            });
            long deadline = System.nanoTime() + 2_000_000_000L;      // main stays inside the active phase
            while (System.nanoTime() < deadline && !done[0]) Thread.onSpinWait();
            completedDuringPhase[0] = done[0];
        };
        evaluateAt = level.getServer().getTickCount() + 60;
        pending = () -> {
            Staging.hook = null;
            List<String> f = new ArrayList<>();
            if (thread[0] == null) f.add("region task never ran");
            if (routed[0]) {
                status = "OVERLAP: INVALID (node destroy did not complete synchronously on the region thread) thread=" + thread[0];
                Ae2Compat.LOG.info("[nestworld-compat-ae2] gridphase fixture {}", status);
                return;
            }
            if (completedDuringPhase[0]) f.add("OVERLAP: region topology on G completed while main's phase of G was active");
            if (!done[0]) f.add("region topology never completed after the phase (progress)");
            finish("OVERLAP", f, "thread=" + thread[0] + " completedDuringPhase=" + completedDuringPhase[0]
                    + " completedAfter=" + done[0] + " pTryFailures+" + (c.pTryFailures.sum() - fail0));
        };
        status = "OVERLAP: running";
        return status;
    }

    private GridPhaseFixtures() {
    }
}
