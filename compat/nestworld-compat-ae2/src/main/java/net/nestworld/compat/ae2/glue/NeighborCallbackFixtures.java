package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.me.GridNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-PIN-LEAK-001 D2 fixture (fixtures flag; production pins): crafting storage S at P (pinned → MAIN domain) in a
 * region-interior chunk; neighbour updates triggered from the region thread that owns P's east neighbour N (stone
 * placed / removed in N's domain). Checks: S's handler deferred and executed in S's domain, exactly once per emission
 * and in order; stale (S removed in its domain ahead of the queued callback); negative control (routing off → the
 * handler runs on the region thread).
 */
public final class NeighborCallbackFixtures {
    private static final String STORAGE = "ae2:crafting_storage";
    private static volatile String status = "idle";
    private static final List<Runnable> steps = new ArrayList<>();
    private static final List<Integer> waits = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static volatile int idx = -1;
    private static volatile boolean started;
    private static volatile int evaluateAt = Integer.MAX_VALUE;
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    static final List<String> emitted = Collections.synchronizedList(new ArrayList<>());
    static final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    static final List<String> stale = Collections.synchronizedList(new ArrayList<>());
    static final List<String> threads = Collections.synchronizedList(new ArrayList<>());
    static volatile int offDomain, deliveredAfterRemoval;
    static volatile BlockPos hostPos, removedHost;
    static volatile Runnable staleArm;

    private NeighborCallbackFixtures() {
    }

    public static String status() {
        return status;
    }

    private static final HostDomainCallbackDelivery.Observer OBSERVER = new HostDomainCallbackDelivery.Observer() {
        @Override
        public void emitted(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host, boolean deferred) {
            if (ch != HostDomainCallbackDelivery.Channel.BLOCK || host == null || !host.getBlockPos().equals(hostPos)) return;
            emitted.add(label);
            Runnable arm = staleArm;
            if (arm != null && deferred) {
                staleArm = null;
                removedHost = hostPos;
                arm.run();                               // enqueued in S's domain BEFORE this callback (FIFO)
            }
        }

        @Override
        public void delivered(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host) {
            if (ch != HostDomainCallbackDelivery.Channel.BLOCK || host == null || !host.getBlockPos().equals(hostPos)) return;
            delivered.add(label);
            threads.add(Thread.currentThread().getName());
            if (host.getLevel() instanceof ServerLevel sl && !BeDomainReady.currentThreadIn(sl, host.getBlockPos(), STORAGE)) offDomain++;
            if (host.getBlockPos().equals(removedHost) && host.isRemoved()) deliveredAfterRemoval++;
        }

        @Override
        public void stale(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host) {
            if (ch != HostDomainCallbackDelivery.Channel.BLOCK || host == null || !host.getBlockPos().equals(hostPos)) return;
            stale.add(label);
        }
    };

    public static void onServerTickEnd(int tick) {
        if (!started || tick < evaluateAt) return;
        idx++;
        if (idx >= steps.size()) {
            started = false;
            HostDomainCallbackDelivery.observer = null;
            BlockCallbacks.ROUTING = true;
            status = "NEIGHBOR" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " counters{bk emitted="
                    + COUNTERS.bkEmitted.sum() + " inDomain=" + COUNTERS.bkInDomain.sum() + " deferred=" + COUNTERS.bkDeferred.sum()
                    + " ranInDomain=" + COUNTERS.bkRanInDomain.sum() + " stale=" + COUNTERS.bkStale.sum() + " dropped="
                    + COUNTERS.bkDropped.sum() + " failed=" + COUNTERS.bkFailed.sum() + " noHost=" + COUNTERS.bkNoHost.sum() + "}";
            Ae2Compat.LOG.info("[nestworld-compat-ae2] neighbor callback fixture {}", status);
            return;
        }
        status = "NEIGHBOR: running " + names.get(idx) + " (" + (idx + 1) + "/" + steps.size() + ")";
        try {
            steps.get(idx).run();
        } catch (Throwable t) {
            failures.add(names.get(idx) + ": threw " + t);
        }
        evaluateAt = tick + waits.get(idx);
    }

    private static void step(String name, int wait, Runnable r) {
        names.add(name);
        waits.add(wait);
        steps.add(r);
    }

    private static void reset() {
        emitted.clear(); delivered.clear(); stale.clear(); threads.clear();
        offDomain = 0; deliveredAfterRemoval = 0;
    }

    public static String run(ServerLevel level, BlockPos p) {
        if (started) return "running: " + status;
        BlockPos n = p.east();
        String ds = BeDomainReady.domainName(level, p, STORAGE), dn = BeDomainReady.domainName(level, n, null);
        if (!"MAIN".equals(ds) || !dn.startsWith("REGION#")) {
            return "need S's domain MAIN (ae2 pinned) and its east neighbour's domain a REGION (got S=" + ds + " N=" + dn + ")";
        }
        if (!level.getBlockState(p).isAir() || !level.getBlockState(n).isAir()) return "P and its east neighbour must be air";
        Block storage = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "1k_crafting_storage"));
        steps.clear(); waits.clear(); names.clear(); failures.clear(); reset();
        hostPos = p; removedHost = null; staleArm = null;
        long[] base = new long[2];
        Runnable trigger = () -> inDomain(level, n, null, () -> {        // on N's region thread: two neighbour updates of S
            level.setBlockAndUpdate(n, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(n, Blocks.AIR.defaultBlockState());
        });
        Runnable placeS = () -> level.setBlockAndUpdate(p, storage.defaultBlockState());   // S's domain is MAIN
        Runnable removeS = () -> level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());

        step("forceload", 40, () -> level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true));
        step("place-S", 40, placeS);
        step("record", 1, () -> {
            if (level.getBlockState(p).isAir()) failures.add("setup: S not placed");
            reset();
            HostDomainCallbackDelivery.observer = OBSERVER;
            base[0] = COUNTERS.bkFailed.sum();
            base[1] = COUNTERS.bkDropped.sum();
        });
        step("trigger-1", 20, trigger);
        step("trigger-2", 20, trigger);
        step("check-routed", 1, () -> {
            List<String> em = new ArrayList<>(emitted), dl = new ArrayList<>(delivered);
            if (em.isEmpty()) failures.add("ROUTED: no neighbour callback emitted for S");
            if (!em.equals(dl)) failures.add("ROUTED: emitted " + em + " != delivered " + dl);
            if (offDomain != 0) failures.add("ROUTED: " + offDomain + " off-domain executions " + threads);
            if (!stale.isEmpty()) failures.add("ROUTED: unexpected stale " + stale);
            if (threads.stream().anyMatch(t -> !t.equals(level.getServer().getRunningThread().getName()))) failures.add("ROUTED: not on MAIN " + threads);
        });
        step("record-stale", 1, () -> {
            reset();
            staleArm = () -> {
                try {
                    BeDomainReady.enqueueTo(level, p, STORAGE, removeS, k -> failures.add("STALE: removal dropped " + k));
                } catch (Throwable t) {
                    failures.add("STALE: enqueue failed " + t);
                }
            };
        });
        step("stale-trigger", 30, trigger);
        step("check-stale", 1, () -> {
            if (stale.isEmpty()) failures.add("STALE: no stale-cancelled callback (emitted " + emitted + ")");
            if (deliveredAfterRemoval != 0) failures.add("STALE: " + deliveredAfterRemoval + " deliveries to removed S");
            if (emitted.size() != delivered.size() + stale.size()) failures.add("STALE: emitted " + emitted + " != delivered " + delivered + " + stale " + stale);
        });
        step("restore-S", 30, () -> {
            staleArm = null;
            removedHost = null;
            placeS.run();
        });
        step("negative-control-routing-off", 1, () -> {
            reset();
            BlockCallbacks.ROUTING = false;
        });
        step("neg-trigger", 20, trigger);
        step("check-negative", 1, () -> {
            if (delivered.isEmpty()) failures.add("NEG: no neighbour handler observed");
            else if (offDomain == 0) failures.add("NEG: routing off but the handler still ran in S's domain " + threads);
            BlockCallbacks.ROUTING = true;
        });
        step("cleanup", 20, () -> {
            removeS.run();
            level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
            if (COUNTERS.bkFailed.sum() != base[0] || COUNTERS.bkDropped.sum() != base[1]) failures.add("failed/dropped changed");
        });
        idx = -1;
        evaluateAt = level.getServer().getTickCount();
        started = true;
        return "NEIGHBOR: started S=" + p + " (" + ds + ") N=" + n + " (" + dn + ") steps=" + steps.size();
    }

    private static void inDomain(ServerLevel level, BlockPos pos, String type, Runnable op) {
        try {
            BeDomainReady.enqueueTo(level, pos, type, op, k -> failures.add("domain task at " + pos + " dropped: " + k));
        } catch (Throwable t) {
            failures.add("enqueue failed at " + pos + ": " + t);
        }
    }
}
