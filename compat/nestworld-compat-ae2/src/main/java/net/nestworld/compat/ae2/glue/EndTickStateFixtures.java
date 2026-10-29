package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.api.networking.IGridNode;
import appeng.me.GridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-NODE-LISTENER-ENDTICK-001 fixture (fixtures flag only; TEST CONFIG ae2 unpinned): interface A (REGION domain),
 * interface B (MAIN, border band), creative energy cell C (MAIN) powering the grid. Phases: POWER toggles, pathing
 * reboot (third interface D added/removed), stale (A removed in its domain before its queued state callback), negative
 * control (routing off). Per host the observer records the exact emission sequence and each delivery / stale outcome
 * with thread and in-domain flag; acceptance per owner decisions 2026-10-29 (13).
 */
public final class EndTickStateFixtures {
    private static final String IFACE = "ae2:interface";
    private static volatile String status = "idle";
    private static final List<Runnable[]> steps = new ArrayList<>();       // {action} + wait in waits
    private static final List<Integer> waits = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static volatile int idx = -1;
    private static volatile boolean started;
    private static volatile int evaluateAt = Integer.MAX_VALUE;
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    /** Per host position: emitted labels (in order), delivered labels (in order), stale labels, off-domain deliveries. */
    static final class Rec {
        final List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        final List<String> stale = Collections.synchronizedList(new ArrayList<>());
        final List<String> deliveredThreads = Collections.synchronizedList(new ArrayList<>());
        volatile int offDomain, deliveredAfterRemoval;
    }

    static final Map<BlockPos, Rec> REC = Collections.synchronizedMap(new LinkedHashMap<>());
    static volatile BlockPos removedHost;
    /** Stale case: A's removal, enqueued from the emission of A's first deferred state callback. */
    static volatile Runnable staleArm;
    static volatile BlockPos staleHost;

    private EndTickStateFixtures() {
    }

    public static String status() {
        return status;
    }

    /** Step engine: each step runs at a tick end, the next one {@code wait} ticks later. */
    public static void onServerTickEnd(int tick) {
        if (!started || tick < evaluateAt) return;
        idx++;
        if (idx >= steps.size()) {
            started = false;
            HostDomainCallbackDelivery.observer = null;
            EndTickStateCallbacks.ROUTING = true;
            status = "ENDTICK" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " counters{st emitted="
                    + COUNTERS.stEmitted.sum() + " inDomain=" + COUNTERS.stInDomain.sum() + " deferred=" + COUNTERS.stDeferred.sum()
                    + " ranInDomain=" + COUNTERS.stRanInDomain.sum() + " stale=" + COUNTERS.stStale.sum() + " dropped="
                    + COUNTERS.stDropped.sum() + " failed=" + COUNTERS.stFailed.sum() + " noHost=" + COUNTERS.stNoHost.sum() + "}";
            Ae2Compat.LOG.info("[nestworld-compat-ae2] endtick state fixture {}", status);
            return;
        }
        status = "ENDTICK: running " + names.get(idx) + " (" + (idx + 1) + "/" + steps.size() + ")";
        try {
            steps.get(idx)[0].run();
        } catch (Throwable t) {
            failures.add(names.get(idx) + ": threw " + t);
        }
        evaluateAt = tick + waits.get(idx);
    }

    private static void step(String name, int wait, Runnable r) {
        names.add(name);
        waits.add(wait);
        steps.add(new Runnable[]{r});
    }

    private static final HostDomainCallbackDelivery.Observer OBSERVER = new HostDomainCallbackDelivery.Observer() {
        @Override
        public void emitted(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host, boolean deferred) {
            if (ch != HostDomainCallbackDelivery.Channel.STATE || host == null) return;
            Rec r = REC.get(host.getBlockPos());
            if (r != null) r.emitted.add(label);
            Runnable arm = staleArm;
            if (arm != null && deferred && host.getBlockPos().equals(staleHost)) {
                staleArm = null;
                removedHost = staleHost;
                arm.run();                               // enqueued BEFORE this callback (per-domain FIFO) → callback stale
            }
        }

        @Override
        public void delivered(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host) {
            if (ch != HostDomainCallbackDelivery.Channel.STATE || host == null) return;
            Rec r = REC.get(host.getBlockPos());
            if (r == null) return;
            r.delivered.add(label);
            r.deliveredThreads.add(Thread.currentThread().getName());
            if (host.getLevel() instanceof ServerLevel sl
                    && !BeDomainReady.currentThreadIn(sl, host.getBlockPos(), BeDomainReady.typeOf(host))) r.offDomain++;
            if (host.getBlockPos().equals(removedHost) && host.isRemoved()) r.deliveredAfterRemoval++;
        }

        @Override
        public void stale(HostDomainCallbackDelivery.Channel ch, GridNode node, String label, BlockEntity host) {
            if (ch != HostDomainCallbackDelivery.Channel.STATE || host == null) return;
            Rec r = REC.get(host.getBlockPos());
            if (r != null) r.stale.add(label);
        }
    };

    public static String run(ServerLevel level, BlockPos a) {
        if (started) return "running: " + status;
        BlockPos b = a.west(), c = b.west(), d = b.above();
        String da = BeDomainReady.domainName(level, a, IFACE), db = BeDomainReady.domainName(level, b, IFACE);
        if (!da.startsWith("REGION#") || !"MAIN".equals(db) || !"MAIN".equals(BeDomainReady.domainName(level, c, "ae2:creative_energy_cell"))) {
            return "need A=REGION, B=MAIN, C=MAIN (got A=" + da + " B=" + db + "); run with ae2 unpinned at a region's first interior column";
        }
        for (BlockPos p : new BlockPos[]{a, b, c, d}) if (!level.getBlockState(p).isAir()) return p + " must be air";
        Block iface = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "interface"));
        Block cell = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "creative_energy_cell"));
        steps.clear(); waits.clear(); names.clear(); failures.clear(); REC.clear(); removedHost = null; staleArm = null;
        long[] base = new long[4];
        Runnable placeA = () -> inDomain(level, a, () -> level.setBlockAndUpdate(a, iface.defaultBlockState()));
        Runnable removeA = () -> inDomain(level, a, () -> level.setBlockAndUpdate(a, Blocks.AIR.defaultBlockState()));
        Runnable cOn = () -> level.setBlockAndUpdate(c, cell.defaultBlockState());
        Runnable cOff = () -> level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
        Runnable record = () -> {
            REC.clear();
            REC.put(a, new Rec());
            REC.put(b, new Rec());
            HostDomainCallbackDelivery.observer = OBSERVER;
            base[0] = COUNTERS.stFailed.sum();
            base[1] = COUNTERS.stDropped.sum();
        };
        step("forceload", 40, () -> {
            for (BlockPos p : new BlockPos[]{a, b, c}) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
        });
        step("place", 100, () -> {
            cOn.run();
            level.setBlockAndUpdate(b, iface.defaultBlockState());
            placeA.run();
        });
        step("record", 1, () -> {
            for (BlockPos p : new BlockPos[]{a, b, c}) {
                if (level.getBlockState(p).isAir()) failures.add("setup: nothing placed at " + p);
            }
            record.run();
        });
        step("power-off-1", 30, cOff);
        step("power-on-1", 70, cOn);
        step("power-off-2", 30, cOff);
        step("power-on-2", 70, cOn);
        step("check-power", 1, () -> {
            checkSequences("POWER", a, b);
            expectState(level, a, b, true, "POWER");
        });
        step("record-pathing", 1, record);
        step("pathing-add-D", 60, () -> level.setBlockAndUpdate(d, iface.defaultBlockState()));
        step("pathing-remove-D", 60, () -> level.setBlockAndUpdate(d, Blocks.AIR.defaultBlockState()));
        step("check-pathing", 1, () -> {
            checkSequences("PATHING", a, b);
            for (String host : new String[]{"A", "B"}) {
                Rec r = REC.get(host.equals("A") ? a : b);
                if (r.emitted.stream().noneMatch(l -> l.equals("GRID_BOOT"))) failures.add("PATHING: no GRID_BOOT emitted for " + host);
            }
            expectState(level, a, b, true, "PATHING");
        });
        step("record-stale", 1, record);
        step("stale-arm-then-power-off", 40, () -> {
            staleHost = a;
            staleArm = removeA;                           // fires at A's emission, ahead of the callback in A's FIFO
            cOff.run();                                   // POWER emitted at this tick's end
        });
        step("check-stale", 1, () -> {
            Rec ra = REC.get(a);
            if (ra.stale.isEmpty()) failures.add("STALE: no stale-cancelled callback for removed A (emitted " + ra.emitted + ")");
            if (ra.deliveredAfterRemoval != 0) failures.add("STALE: " + ra.deliveredAfterRemoval + " deliveries to removed A");
            if (ra.emitted.size() != ra.delivered.size() + ra.stale.size()) failures.add("STALE: emitted " + ra.emitted + " != delivered " + ra.delivered + " + stale " + ra.stale);
        });
        step("restore", 100, () -> {
            removedHost = null;
            staleArm = null;
            placeA.run();
            cOn.run();
        });
        step("negative-control-routing-off", 1, () -> {
            record.run();
            EndTickStateCallbacks.ROUTING = false;
        });
        step("neg-power-off", 30, cOff);
        step("neg-power-on", 70, cOn);
        step("check-negative", 1, () -> {
            Rec ra = REC.get(a);
            if (ra.delivered.isEmpty()) failures.add("NEG: no state delivery observed for A");
            else if (ra.offDomain == 0) failures.add("NEG: routing off but A's listener still ran in its domain " + ra.deliveredThreads);
            EndTickStateCallbacks.ROUTING = true;
            REC.get(a).offDomain = 0;                    // the negative phase is not part of the ON acceptance
        });
        step("cleanup", 20, () -> {
            removeA.run();
            level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
            cOff.run();
            for (BlockPos p : new BlockPos[]{a, b, c}) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
            if (COUNTERS.stFailed.sum() != base[0] || COUNTERS.stDropped.sum() != base[1]) failures.add("failed/dropped changed");
        });
        idx = -1;
        evaluateAt = level.getServer().getTickCount();
        started = true;                                   // step 0 runs at this tick's end
        return "ENDTICK: started A=" + a + " (" + da + ") B=" + b + " (" + db + ") steps=" + steps.size();
    }

    /** Per host: delivered (in order) + stale = emitted (exact, FIFO); no off-domain delivery. */
    private static void checkSequences(String phase, BlockPos a, BlockPos b) {
        for (BlockPos p : new BlockPos[]{a, b}) {
            Rec r = REC.get(p);
            String h = p.equals(a) ? "A" : "B";
            List<String> em = new ArrayList<>(r.emitted), dl = new ArrayList<>(r.delivered);
            if (em.isEmpty()) failures.add(phase + ": no state callback emitted for " + h);
            if (!r.stale.isEmpty()) failures.add(phase + ": unexpected stale for " + h + " " + r.stale);
            if (!em.equals(dl)) failures.add(phase + ": FIFO/exactness " + h + " emitted=" + em + " delivered=" + dl);
            if (r.offDomain != 0) failures.add(phase + ": " + r.offDomain + " off-domain deliveries for " + h + " " + r.deliveredThreads);
        }
    }

    private static void expectState(ServerLevel level, BlockPos a, BlockPos b, boolean powered, String phase) {
        for (BlockPos p : new BlockPos[]{a, b}) {
            BlockEntity be = level.getBlockEntity(p);
            IGridNode n = be instanceof IGridConnectedBlockEntity h ? h.getMainNode().getNode() : null;
            String h = p.equals(a) ? "A" : "B";
            if (n == null) { failures.add(phase + ": no node for " + h); continue; }
            if (n.isPowered() != powered) failures.add(phase + ": " + h + " powered=" + n.isPowered() + " expected " + powered);
            if (!n.meetsChannelRequirements()) failures.add(phase + ": " + h + " has no channel");
            if (n.getGrid().getPathingService().isNetworkBooting()) failures.add(phase + ": grid still booting");
        }
    }

    private static void inDomain(ServerLevel level, BlockPos pos, Runnable op) {
        try {
            BeDomainReady.enqueueTo(level, pos, IFACE, op, k -> failures.add("domain task at " + pos + " dropped: " + k));
        } catch (Throwable t) {
            failures.add("enqueue failed at " + pos + ": " + t);
        }
    }
}
