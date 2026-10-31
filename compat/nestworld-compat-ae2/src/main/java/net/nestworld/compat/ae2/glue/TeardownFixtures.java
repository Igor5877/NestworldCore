package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * AE2-PIN-LEAK-001 D4a fixture (fixtures flag; production pins): crafting CPUs of 1k storages in a region-interior chunk
 * (S0 at P, members along +x). Cases: CONCURRENT (two members removed — one on the chunk-owning region thread, one on main —
 * while the first teardown is held: claimed 1, duplicate 1, drops exactly once, no loss); REBUILD (old cluster re-formed
 * before the held teardown is delivered: new cluster untouched, old teardown exactly once); NEGATIVE (coordinator off:
 * AE2's teardown runs off the anchor's domain). Item accounting is checked against the ItemEntities actually in the world.
 */
public final class TeardownFixtures {
    private static final String STORAGE = "ae2:crafting_storage";
    private static volatile String status = "idle";
    private static final List<Runnable> steps = new ArrayList<>();
    private static final List<Integer> waits = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static volatile int idx = -1, waitOverride = -1;
    private static volatile boolean started;
    private static volatile int evaluateAt = Integer.MAX_VALUE;
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    private static volatile Object holdCluster;
    private static volatile Runnable held;
    private static volatile boolean regionDone;
    private static volatile CraftingCPUCluster oldCluster, newCluster;
    private static final long[] base = new long[12];
    /** request log: identity of cluster → [requests, claims] */
    private static final java.util.Map<Object, int[]> CLAIMS = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static final List<String> claimLog = Collections.synchronizedList(new ArrayList<>());

    private static String claimsOf(Object cluster) {
        int[] v = CLAIMS.get(cluster);
        return v == null ? "0/0" : v[0] + "/" + v[1];
    }

    private TeardownFixtures() {
    }

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        if (!started || tick < evaluateAt) return;
        idx++;
        if (idx >= steps.size()) {
            started = false;
            ClusterTeardownCoordinator.hold = null;
            ClusterTeardownCoordinator.claimObserver = null;
            ClusterTeardownCoordinator.ENABLED = true;
            status = "TEARDOWN" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " " + String.join(" | ", notes);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] D4a teardown fixture {}", status);
            return;
        }
        status = "TEARDOWN: running " + names.get(idx) + " (" + (idx + 1) + "/" + steps.size() + ")";
        waitOverride = -1;
        try {
            steps.get(idx).run();
        } catch (Throwable t) {
            failures.add(names.get(idx) + ": threw " + t);
        }
        evaluateAt = tick + (waitOverride >= 0 ? waitOverride : waits.get(idx));
    }

    private static void step(String name, int wait, Runnable r) {
        names.add(name);
        waits.add(wait);
        steps.add(r);
    }

    private static void snapshot() {
        var k = COUNTERS;
        long[] v = {k.tdClaimed.sum(), k.tdDuplicate.sum(), k.drItemsCaptured.sum(), k.drItemsDelivered.sum(),
                k.drItemsRetained.sum(), k.tdOffDomain.sum(), k.tmOffDomain.sum(), k.tmSkippedReassigned.sum(),
                k.tdBypassedOffDomain.sum(), k.drIntents.sum(), k.tdFallback.sum(), k.drFallback.sum()};
        System.arraycopy(v, 0, base, 0, v.length);
    }

    private static long d(int i) {
        var k = COUNTERS;
        long[] v = {k.tdClaimed.sum(), k.tdDuplicate.sum(), k.drItemsCaptured.sum(), k.drItemsDelivered.sum(),
                k.drItemsRetained.sum(), k.tdOffDomain.sum(), k.tmOffDomain.sum(), k.tmSkippedReassigned.sum(),
                k.tdBypassedOffDomain.sum(), k.drIntents.sum(), k.tdFallback.sum(), k.drFallback.sum()};
        return v[i] - base[i];
    }

    /** Compact caller chain (appeng + addon frames only, outermost last) for claim diagnostics. */
    private static String shortStack() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.contains("TeardownFixtures") || c.contains("ClusterTeardownCoordinator")) continue;
            if (!c.startsWith("appeng.") && !c.startsWith("net.nestworld.compat") && !c.startsWith("net.minecraft.world.level")) continue;
            if (n++ > 0) b.append('<');
            b.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (n >= 12) break;
        }
        return b.toString();
    }

    private static String id(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static CraftingBlockEntity be(ServerLevel level, BlockPos p) {
        return level.getBlockEntity(p) instanceof CraftingBlockEntity c ? c : null;
    }

    private static long diamondsAround(ServerLevel level, BlockPos p, boolean discard) {
        long n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(6), e -> e.getItem().is(Items.DIAMOND))) {
            n += e.getItem().getCount();
            if (discard) e.discard();
        }
        return n;
    }

    private static void removeOnRegion(ServerLevel level, BlockPos target) {
        regionDone = false;
        try {
            // P's chunk-owning region thread: the null-type domain of the target position (interior → REGION)
            BeDomainReady.enqueueTo(level, target, null, () -> {
                level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                regionDone = true;
            }, k -> failures.add("region removal dropped " + k));
        } catch (Throwable t) {
            failures.add("enqueue failed " + t);
        }
    }

    public static String run(ServerLevel level, BlockPos p) {
        if (started) return "running: " + status;
        BlockPos p1 = p.east(), p2 = p1.east();
        String dp = BeDomainReady.domainName(level, p, STORAGE), dn = BeDomainReady.domainName(level, p1, null);
        if (!"MAIN".equals(dp) || !dn.startsWith("REGION#") || !BeDomainReady.domainName(level, p2, null).startsWith("REGION#")) {
            return "need storages pinned (MAIN) and the member positions' null-type domain a REGION (got S=" + dp + " N=" + dn + ")";
        }
        for (BlockPos q : new BlockPos[]{p, p1, p2}) if (!level.getBlockState(q).isAir()) return q + " must be air";
        Block storage = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "1k_crafting_storage"));
        steps.clear(); waits.clear(); names.clear(); failures.clear(); notes.clear();
        CLAIMS.clear();
        claimLog.clear();
        ClusterTeardownCoordinator.claimObserver = (cluster, claimed, label) -> {
            int[] v = CLAIMS.computeIfAbsent(cluster, k -> new int[2]);
            v[0]++;
            if (claimed) v[1]++;
            if (claimLog.size() < 60) claimLog.add(label + "@" + Integer.toHexString(System.identityHashCode(cluster)) + (claimed ? "+claim" : "-dup")
                    + "@" + Thread.currentThread().getName() + (claimed ? " via " + shortStack() : ""));
        };
        ClusterTeardownCoordinator.hold = (cluster, deliver) -> {
            if (cluster != holdCluster) return false;
            held = deliver;
            return true;
        };

        step("forceload", 40, () -> level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true));
        diamondsAround(level, p, true);

        // ---------------- CONCURRENT: two members removed while the first teardown is held
        step("C-place", 60, () -> {
            level.setBlockAndUpdate(p, storage.defaultBlockState());
            level.setBlockAndUpdate(p1, storage.defaultBlockState());
        });
        step("C-seed", 1, () -> {
            CraftingBlockEntity a = be(level, p), b = be(level, p1);
            if (a == null || b == null || a.getCluster() == null || a.getCluster() != b.getCluster()) {
                failures.add("CONCURRENT: 2-storage CPU not formed");
                return;
            }
            oldCluster = a.getCluster();
            oldCluster.craftingLogic.getInventory().insert(AEItemKey.of(Items.DIAMOND), 37, Actionable.MODULATE);
            holdCluster = oldCluster;
            held = null;
            snapshot();
            // both members removed in the same window: S1 on the chunk-owning region thread, S0 on main right now —
            // whichever reaches the claim first owns the (held) teardown, the other must be the duplicate
            removeOnRegion(level, p1);
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        });
        for (int k = 0; k < 40; k++) {
            step("C-await-region-" + k, 1, () -> {
                if (!regionDone || held == null || holdCluster == null) return;
                holdCluster = null;
                Runnable r = held;
                held = null;
                r.run();                                        // deliver the held teardown after both removals
                waitOverride = 0;
            });
        }
        step("C-check", 40, () -> { });
        step("C-verify", 1, () -> {
            long world = diamondsAround(level, p, true);
            notes.add("CONCURRENT claimed+" + d(0) + " duplicate+" + d(1) + " captured+" + d(2) + " delivered+" + d(3)
                    + " retained+" + d(4) + " dropIntents+" + d(9) + " world=" + world + " tdOffDomain+" + d(5) + " tmOffDomain+" + d(6));
            notes.add("CONCURRENT oldCluster requests/claims=" + claimsOf(oldCluster));
            int[] v = CLAIMS.get(oldCluster);
            if (v == null || v[1] != 1) failures.add("CONCURRENT: old cluster claimed " + (v == null ? 0 : v[1]) + " times (expected exactly 1)");
            if (v == null || v[0] < 2) failures.add("CONCURRENT: old cluster requested " + (v == null ? 0 : v[0]) + " times (both removals must reach it)");
            if (d(2) != 37 || d(3) != 37 || d(4) != 0) failures.add("CONCURRENT: items captured/delivered/retained " + d(2) + "/" + d(3) + "/" + d(4));
            if (world != 37) failures.add("CONCURRENT: " + world + " diamonds in the world (expected 37: lost or duplicated)");
            if (d(5) != 0 || d(6) != 0) failures.add("CONCURRENT: off-domain teardown/member " + d(5) + "/" + d(6));
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p1).isAir()) failures.add("CONCURRENT: members not removed");
        });

        // ---------------- REBUILD: old cluster re-formed before its held teardown is delivered
        step("R-place", 60, () -> {
            level.setBlockAndUpdate(p, storage.defaultBlockState());
            level.setBlockAndUpdate(p1, storage.defaultBlockState());
            level.setBlockAndUpdate(p2, storage.defaultBlockState());
        });
        step("R-seed", 1, () -> {
            CraftingBlockEntity a = be(level, p), c = be(level, p2);
            if (a == null || c == null || a.getCluster() == null || a.getCluster() != c.getCluster()) {
                failures.add("REBUILD: 3-storage CPU not formed");
                return;
            }
            oldCluster = a.getCluster();
            oldCluster.craftingLogic.getInventory().insert(AEItemKey.of(Items.DIAMOND), 11, Actionable.MODULATE);
            holdCluster = oldCluster;
            held = null;
            snapshot();
            removeOnRegion(level, p2);                          // end member removed on the region thread → teardown held
        });
        for (int k = 0; k < 40; k++) {
            step("R-await-region-" + k, 1, () -> {
                if (!regionDone || held == null || holdCluster == null) return;
                holdCluster = null;
                CraftingBlockEntity b = be(level, p1);
                CraftingBlockEntity a0 = be(level, p);
                notes.add("REBUILD before-forced: S0.cluster=" + id(a0 == null ? null : a0.getCluster()) + " S1.cluster="
                        + id(b == null ? null : b.getCluster()) + " old=" + id(oldCluster) + " old.destroyed=" + oldCluster.isDestroyed()
                        + " mbInProgress=" + appeng.me.cluster.MBCalculator.isModificationInProgress());
                // S2's neighbour callback (D2 → S1's domain) normally re-forms {S0,S1} already; the forced recalculation is an
                // idempotent recheck that the re-formation exists BEFORE the held teardown is delivered
                if (b != null) b.updateMultiBlock(p2);
                CraftingBlockEntity a = be(level, p);
                newCluster = a == null ? null : a.getCluster();
                notes.add("REBUILD after-forced: S0.cluster=" + id(newCluster) + " S1.cluster=" + id(b == null ? null : b.getCluster()));
                if (newCluster == null || newCluster == oldCluster || be(level, p1).getCluster() != newCluster) {
                    failures.add("REBUILD: cluster not re-formed before delivery (" + newCluster + ")");
                }
                Runnable r = held;
                held = null;
                r.run();
                waitOverride = 0;
            });
        }
        step("R-check", 40, () -> { });
        step("R-verify", 1, () -> {
            long world = diamondsAround(level, p, true);
            CraftingBlockEntity a = be(level, p), b = be(level, p1);
            boolean untouched = a != null && b != null && a.getCluster() == newCluster && b.getCluster() == newCluster
                    && newCluster != null && !newCluster.isDestroyed();
            notes.add("REBUILD claimed+" + d(0) + " duplicate+" + d(1) + " captured+" + d(2) + " delivered+" + d(3)
                    + " world=" + world + " skippedReassigned+" + d(7) + " newClusterUntouched=" + untouched);
            notes.add("REBUILD oldCluster requests/claims=" + claimsOf(oldCluster) + " log=" + claimLog);
            int[] v = CLAIMS.get(oldCluster);
            if (v == null || v[1] != 1) failures.add("REBUILD: old teardown claimed " + (v == null ? 0 : v[1]) + " times (expected 1)");
            if (d(2) != 11 || d(3) != 11 || world != 11) failures.add("REBUILD: items captured/delivered/world " + d(2) + "/" + d(3) + "/" + world);
            if (!untouched) failures.add("REBUILD: the re-formed cluster was touched by the old teardown");
            if (d(7) < 2) failures.add("REBUILD: reassigned members not skipped (" + d(7) + ")");
        });
        step("R-clean", 20, () -> {
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(p1, Blocks.AIR.defaultBlockState());
        });
        step("R-clean-drops", 1, () -> diamondsAround(level, p, true));

        // ---------------- NEGATIVE: coordinator off → AE2's teardown runs off the anchor's domain
        step("N-place", 60, () -> {
            level.setBlockAndUpdate(p, storage.defaultBlockState());
            level.setBlockAndUpdate(p1, storage.defaultBlockState());
        });
        step("N-remove", 40, () -> {
            snapshot();
            ClusterTeardownCoordinator.ENABLED = false;
            removeOnRegion(level, p1);
        });
        step("N-verify", 1, () -> {
            notes.add("NEGATIVE bypassedOffDomain+" + d(8) + " claimed+" + d(0));
            if (d(8) < 1) failures.add("NEGATIVE: coordinator off but no off-domain teardown observed");
            ClusterTeardownCoordinator.ENABLED = true;
        });
        step("cleanup", 20, () -> {
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            diamondsAround(level, p, true);
            level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
            if (d(10) != 0 || d(11) != 0) notes.add("fallbacks td/dr " + d(10) + "/" + d(11));
        });
        idx = -1;
        evaluateAt = level.getServer().getTickCount();
        started = true;
        return "TEARDOWN: started S0=" + p + " (" + dp + ") N=" + p1 + " (" + dn + ") steps=" + steps.size();
    }
}
