package net.nestworld.compat.ae2.glue;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Integration fixtures on real RegionThreads (owner gates, AE2-COMPAT-GATE-STATUS):
 * QUEUE-LIFE-001 — a RegionThread removes an AE2 block entity while main initializes it;
 * QUEUE-STAGING-001 — a real AE2 path from a RegionThread publishes setChanged callbacks through the
 * staged serverQueue and each is consumed exactly once. Enabled with -Dnestworld.compatAe2.fixtures=true.
 */
public final class IntegrationFixtures {
    public enum Life { DURING, BEFORE }

    private static volatile String status = "idle";
    private static volatile Runnable pending;
    private static volatile int evaluateAt = Integer.MAX_VALUE;

    public static String status() {
        return status;
    }

    // ------------------------------------------------------------------ QUEUE-LIFE-001

    public static String life(Life kind, ServerLevel level, BlockPos target) {
        if (pending != null) return "a fixture is running: " + status;
        if (!level.getBlockState(target).isAir()) return "target " + target + " must be air";
        List<net.minecraft.world.entity.decoration.ArmorStand> hosts = level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ArmorStand.class, new net.minecraft.world.phys.AABB(target).inflate(8));
        if (hosts.isEmpty()) return "no armor stand within 8 blocks of " + target + " (region-thread task host)";
        java.util.UUID host = hosts.get(0).getUUID();
        String mainName = level.getServer().getRunningThread().getName();
        List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());
        String[] removerThread = {null};
        long[] t = new long[3];                           // 0 removeStart, 1 removeEnd, 2 initDone
        boolean[] flags = new boolean[3];                 // 0 removerBlocked, 1 removedBeforeLock, 2 cancelled
        String[] cancelReason = {null};
        boolean[] routed = {false};
        Runnable remover = () -> {
            removerThread[0] = Thread.currentThread().getName();
            t[0] = System.nanoTime();
            level.removeBlock(target, false);
            t[1] = System.nanoTime();
            routed[0] = !level.getBlockState(target).isAir();   // Core routed/deferred the write: not a same-thread removal
        };
        LifeHooks.hook = (point, be) -> {
            if (!be.getBlockPos().equals(target)) return;
            if (kind == Life.BEFORE && point.equals("init-prelock")) {
                CountDownLatch done = new CountDownLatch(1);
                RegionTasks.submit(host, () -> {
                    remover.run();
                    done.countDown();
                });
                try {
                    flags[1] = done.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (kind == Life.DURING && point.equals("init-locked")) {
                RegionTasks.submit(host, remover);
                NwAeBlockEntity life = (NwAeBlockEntity) be;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !life.nw$life().hasQueuedThreads()) Thread.onSpinWait();
                flags[0] = life.nw$life().hasQueuedThreads();
            }
            if (point.equals("init-done")) t[2] = System.nanoTime();
            if (point.startsWith("init-cancelled")) {
                flags[2] = true;
                cancelReason[0] = point;
            }
        };
        long cancelledBefore = Ae2Compat.COUNTERS.initCancelled.sum();
        Block inscriber = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "inscriber"));
        level.setBlockAndUpdate(target, inscriber.defaultBlockState());
        if (!(level.getBlockEntity(target) instanceof AEBaseBlockEntity be0)) {
            LifeHooks.hook = null;
            return "no AE2 block entity was created at " + target;
        }
        evaluateAt = level.getServer().getTickCount() + 100;
        pending = () -> {
            LifeHooks.hook = null;
            if (routed[0]) {
                status = "LIFE " + kind + ": INVALID (removal was routed/deferred by Core, not performed on the region thread) remover=" + removerThread[0];
                Ae2Compat.LOG.info("[nestworld-compat-ae2] integration fixture {}", status);
                return;
            }
            List<String> f = new ArrayList<>(notes);
            boolean air = level.getBlockState(target).isAir();
            boolean ghost = be0 instanceof IGridConnectedBlockEntity g && g.getMainNode().getNode() != null;
            int ready = be0.getReadyInvoked();
            if (removerThread[0] == null) f.add("region task never ran (hook not reached or host not ticking)");
            else if (removerThread[0].equals(mainName)) f.add("remover ran on main, not a region thread");
            if (!air) f.add("block at target is " + level.getBlockState(target) + " (resurrection?)");
            if (!be0.isRemoved()) f.add("block entity not removed");
            if (ghost) f.add("GHOST: removed block entity still owns a grid node");
            if (kind == Life.DURING) {
                if (!flags[0]) f.add("remover was not blocked on nw$life while main was inside callInit");
                if (ready != 1) f.add("onReady ran " + ready + " times (expected 1)");
                if (t[1] != 0 && t[2] != 0 && t[1] < t[2]) f.add("removal completed before initialization finished");
            } else {
                if (!flags[1]) f.add("region removal did not complete before main took nw$life");
                if (!flags[2]) f.add("initialization was not cancelled by revalidation");
                if (ready != 0) f.add("onReady ran on a removed block entity");
                if (Ae2Compat.COUNTERS.initCancelled.sum() - cancelledBefore != 1) f.add("initCancelled not counted once");
            }
            status = "LIFE " + kind + (f.isEmpty() ? ": PASS" : ": FAIL " + f) + " remover=" + removerThread[0]
                    + " blocked=" + flags[0] + " cancelled=" + cancelReason[0] + " readyInvoked=" + ready + " ghost=" + ghost;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] integration fixture {}", status);
        };
        status = "LIFE " + kind + ": running";
        return status;
    }

    // ------------------------------------------------------------------ QUEUE-STAGING-001

    public static String staging(ServerLevel level, BlockPos target, int seconds) {
        if (pending != null) return "a fixture is running: " + status;
        if (!(level.getBlockEntity(target) instanceof AEBaseBlockEntity be)) return "no AE2 block entity at " + target;
        String mainName = level.getServer().getRunningThread().getName();
        AtomicInteger publishes = new AtomicInteger(), regionPublishes = new AtomicInteger(),
                requeues = new AtomicInteger(), claims = new AtomicInteger(), covered = new AtomicInteger();
        Set<String> producerThreads = ConcurrentHashMap.newKeySet();
        long stagedBefore = Ae2Compat.COUNTERS.queueStagedServer.sum();
        SetChangedProtocol.hook = (point, b) -> {
            if (b != be) return;
            switch (point) {
                case "P-publish" -> {
                    publishes.incrementAndGet();
                    String n = Thread.currentThread().getName();
                    producerThreads.add(n);
                    if (!n.equals(mainName)) regionPublishes.incrementAndGet();
                }
                case "P-LP" -> covered.incrementAndGet();
                case "C-requeue" -> requeues.incrementAndGet();
                case "C-claim" -> claims.incrementAndGet();
                default -> {
                }
            }
        };
        evaluateAt = level.getServer().getTickCount() + seconds * 20;
        pending = () -> {
            SetChangedProtocol.hook = null;
            List<String> f = new ArrayList<>();
            long staged = Ae2Compat.COUNTERS.queueStagedServer.sum() - stagedBefore;
            long phase = ((NwAeBlockEntity) be).nw$sc().get() & SetChangedProtocol.PHASE;
            if (regionPublishes.get() == 0) {
                status = "STAGING: INVALID (no publish came from a region thread: the hopper did not tick on one; path not exercised) publishes=" + publishes.get();
                Ae2Compat.LOG.info("[nestworld-compat-ae2] integration fixture {}", status);
                return;
            }
            if (claims.get() != publishes.get() + requeues.get())
                f.add("claims " + claims.get() + " != publishes " + publishes.get() + " + requeues " + requeues.get());
            if (staged < regionPublishes.get()) f.add("staged " + staged + " < region publishes " + regionPublishes.get());
            if (phase != SetChangedProtocol.IDLE) f.add("final phase " + phase + " (expected IDLE after settle)");
            status = "STAGING" + (f.isEmpty() ? ": PASS" : ": FAIL " + f) + " publishes=" + publishes.get()
                    + " regionPublishes=" + regionPublishes.get() + " covered=" + covered.get() + " requeues=" + requeues.get()
                    + " claims=" + claims.get() + " staged=" + staged + " threads=" + producerThreads;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] integration fixture {}", status);
        };
        status = "STAGING: running";
        return status;
    }

    /** Main thread, end of every server tick. */
    public static void onServerTickEnd(int tick) {
        Runnable p = pending;
        if (p == null || tick < evaluateAt) return;
        pending = null;
        evaluateAt = Integer.MAX_VALUE;
        p.run();
    }

    private IntegrationFixtures() {
    }
}
