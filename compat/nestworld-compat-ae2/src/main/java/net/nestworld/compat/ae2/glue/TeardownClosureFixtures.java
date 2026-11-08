package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.nestworld.compat.ae2.mixin.CraftingCPUClusterAccessor;

/**
 * AE2-COMPAT-CLOSURE-001 final gate (owner 2026-11-09): the four D4a scenarios not covered by {@link TeardownFixtures}
 * (CONCURRENT = F3 and REBUILD = F4 are accepted evidence and are not repeated here).
 * <ul>
 * <li>F1 normal — cluster in one domain, member removed in that domain: teardown exactly once, drops exact;</li>
 * <li>F2 cross-domain — members in MAIN border band and REGION interior (needs AE2 unpinned; N/A otherwise): every
 * host mutation in its own domain;</li>
 * <li>F5 split / merge while the claimed teardown is pending: delivery re-resolves the domain, exactly once;</li>
 * <li>F6 host unload / reload while the claimed teardown is pending (a: removed member is not the core, b: it is):
 * no ghost mutation of an unloaded host, items conserved across unload / reload (world + reloaded cluster inventory).</li>
 * </ul>
 * Argument p = the MAIN border-band position; p+1.. are interior. Fixtures flag only.
 */
public final class TeardownClosureFixtures {
    private static final String STORAGE = "ae2:crafting_storage";
    private static final int ATTEMPTS = 10;
    private static volatile String status = "idle";
    private static final List<Runnable> steps = new ArrayList<>();
    private static final List<Integer> waits = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static volatile int idx = -1, waitOverride = -1, evaluateAt = Integer.MAX_VALUE;
    private static volatile boolean started;
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    private static volatile Object holdCluster;
    private static volatile Runnable held;
    private static volatile boolean regionDone;
    private static volatile CraftingCPUCluster oldCluster;
    private static volatile CraftingBlockEntity ghostBe;
    private static volatile CraftingCPUCluster ghostClusterBefore;
    private static volatile String domainBefore;
    private static final Map<Object, int[]> CLAIMS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final long[] base = new long[10];

    private TeardownClosureFixtures() {
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
            status = "TEARDOWN-CLOSURE" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " " + String.join(" | ", notes);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] D4a closure fixture {}", status);
            return;
        }
        status = "TEARDOWN-CLOSURE: running " + names.get(idx) + " (" + (idx + 1) + "/" + steps.size() + ")";
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

    private static long[] now() {
        var k = COUNTERS;
        return new long[]{k.tdClaimed.sum(), k.tdDuplicate.sum(), k.drItemsCaptured.sum(), k.drItemsDelivered.sum(),
                k.drItemsRetained.sum(), k.tdOffDomain.sum(), k.tmOffDomain.sum(), k.tmIntents.sum(), k.tdFallback.sum(),
                k.drFallback.sum()};
    }

    private static void snapshot() {
        System.arraycopy(now(), 0, base, 0, base.length);
    }

    private static long d(int i) {
        return now()[i] - base[i];
    }

    private static String counters() {
        return "claimed+" + d(0) + " duplicate+" + d(1) + " captured+" + d(2) + " delivered+" + d(3) + " retained+" + d(4)
                + " tdOffDomain+" + d(5) + " tmOffDomain+" + d(6) + " memberIntents+" + d(7) + " fallback td/dr+" + d(8) + "/" + d(9);
    }

    private static CraftingBlockEntity be(ServerLevel level, BlockPos p) {
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return null;
        return level.getBlockEntity(p) instanceof CraftingBlockEntity c ? c : null;
    }

    private static long diamondsAround(ServerLevel level, BlockPos p, boolean discard) {
        long n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(8, 260, 8), e -> e.getItem().is(Items.DIAMOND))) {
            n += e.getItem().getCount();
            if (discard) e.discard();
        }
        return n;
    }

    private static long diamondsIn(CraftingCPUCluster c) {
        return c == null ? 0 : c.craftingLogic.getInventory().list.get(AEItemKey.of(Items.DIAMOND));
    }

    /** Runs {@code op} on the thread owning {@code pos} for {@code type} (null = the chunk-owning thread). */
    private static void onDomain(ServerLevel level, BlockPos pos, String type, Runnable op) {
        regionDone = false;
        try {
            BeDomainReady.enqueueTo(level, pos, type, () -> {
                op.run();
                regionDone = true;
            }, k -> failures.add("domain task at " + pos + " dropped: " + k));
        } catch (Throwable t) {
            failures.add("enqueue failed at " + pos + ": " + t);
        }
    }

    private static void remove(ServerLevel level, BlockPos pos, String type) {
        onDomain(level, pos, type, () -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
    }

    private static void forceAll(ServerLevel level, List<BlockPos> ps, boolean on) {
        for (BlockPos q : ps) level.setChunkForced(q.getX() >> 4, q.getZ() >> 4, on);
    }

    private static void command(ServerLevel level, String cmd) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }

    /** Builds a cluster of {@code ps}, seeds {@code diamonds}; returns false (and records the failure) if not formed. */
    private static boolean seed(ServerLevel level, String tag, List<BlockPos> ps, long diamonds) {
        CraftingBlockEntity first = be(level, ps.get(0));
        CraftingCPUCluster c = first == null ? null : first.getCluster();
        for (BlockPos q : ps) {
            CraftingBlockEntity b = be(level, q);
            if (c == null || b == null || b.getCluster() != c) {
                failures.add(tag + ": " + ps.size() + "-storage CPU not formed");
                return false;
            }
        }
        oldCluster = c;
        c.craftingLogic.getInventory().insert(AEItemKey.of(Items.DIAMOND), diamonds, Actionable.MODULATE);
        snapshot();
        return true;
    }

    private static void place(ServerLevel level, Block storage, List<BlockPos> ps) {
        for (BlockPos q : ps) level.setBlockAndUpdate(q, storage.defaultBlockState());
    }

    private static void clear(ServerLevel level, List<BlockPos> ps, BlockPos around) {
        for (BlockPos q : ps) level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
        diamondsAround(level, around, true);
    }

    /** Common end-of-case checks: exactly one claim of the old cluster, drops exact, no off-domain, old destroyed. */
    private static void verifyCommon(ServerLevel level, String tag, BlockPos around, long expected, List<BlockPos> survivors) {
        long world = diamondsAround(level, around, false);
        int[] v = CLAIMS.get(oldCluster);
        notes.add(tag + " " + counters() + " world=" + world + " oldClaims=" + (v == null ? "0/0" : v[0] + "/" + v[1])
                + " oldDestroyed=" + oldCluster.isDestroyed());
        if (v == null || v[1] != 1) failures.add(tag + ": old cluster claimed " + (v == null ? 0 : v[1]) + " times (expected 1)");
        if (d(2) != expected || d(3) != expected || d(4) != 0) failures.add(tag + ": captured/delivered/retained " + d(2) + "/" + d(3) + "/" + d(4));
        if (world != expected) failures.add(tag + ": " + world + " diamonds in the world (expected " + expected + ")");
        if (d(5) != 0 || d(6) != 0) failures.add(tag + ": off-domain teardown/member " + d(5) + "/" + d(6));
        if (!oldCluster.isDestroyed()) failures.add(tag + ": old cluster not destroyed");
        for (BlockPos q : survivors) {
            CraftingBlockEntity b = be(level, q);
            if (b != null && b.getCluster() == oldCluster) failures.add(tag + ": survivor " + q.toShortString() + " still in the old cluster");
        }
    }

    private static String regionOf(ServerLevel level, BlockPos pos) {
        return BeDomainReady.domainName(level, pos, null);
    }

    public static String run(ServerLevel level, BlockPos p) {
        if (started) return "running: " + status;
        Block storage = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "1k_crafting_storage"));
        BlockPos i0 = p.east(5), i1 = i0.east(), i2 = i1.east();
        List<BlockPos> f2 = List.of(p, p.east(), p.east(2));
        List<BlockPos> all = List.of(p, p.east(), p.east(2), i0, i1, i2);
        for (BlockPos q : all) if (!level.getBlockState(q).isAir()) return q + " must be air";
        if (!regionOf(level, i0).startsWith("REGION#")) return "interior " + i0 + " must be in a REGION chunk (got " + regionOf(level, i0) + ")";
        String dBand = BeDomainReady.domainName(level, p, STORAGE), dInt = BeDomainReady.domainName(level, p.east(2), STORAGE);
        boolean crossDomain = !dBand.equals(dInt);

        steps.clear(); waits.clear(); names.clear(); failures.clear(); notes.clear(); CLAIMS.clear();
        notes.add("config band=" + dBand + " interior=" + dInt + " interiorChunkOwner=" + regionOf(level, i0));
        ClusterTeardownCoordinator.claimObserver = (cluster, claimed, label) -> {
            int[] v = CLAIMS.computeIfAbsent(cluster, k -> new int[2]);
            v[0]++;
            if (claimed) v[1]++;
        };
        ClusterTeardownCoordinator.hold = (cluster, deliver) -> {
            if (cluster != holdCluster) return false;
            held = deliver;
            return true;
        };
        step("forceload", 40, () -> forceAll(level, all, true));
        step("clean-drops", 1, () -> diamondsAround(level, i0, true));

        // ---------------- F1 normal: one domain, removal in that domain (the members' own BE domain)
        List<BlockPos> f1 = List.of(i0, i1);
        step("F1-place", 60, () -> place(level, storage, f1));
        step("F1-remove", 40, () -> {
            if (!seed(level, "F1", f1, 17)) return;
            remove(level, i1, STORAGE);
        });
        step("F1-verify", 1, () -> verifyCommon(level, "F1", i0, 17, List.of(i0)));
        step("F1-clean", 20, () -> clear(level, f1, i0));

        // ---------------- F2 cross-domain: MAIN band member + REGION interior members
        if (crossDomain) {
            step("F2-place", 60, () -> place(level, storage, f2));
            step("F2-remove", 40, () -> {
                if (!seed(level, "F2", f2, 19)) return;
                remove(level, p.east(2), STORAGE);            // interior member, on its own (REGION) domain
            });
            step("F2-verify", 1, () -> {
                verifyCommon(level, "F2", p, 19, List.of(p, p.east()));
                if (d(7) < 2) failures.add("F2: member intents " + d(7) + " (expected >= 2, one per domain)");
            });
            step("F2-clean", 20, () -> clear(level, f2, p));
        } else {
            notes.add("F2 N/A in this config (band and interior storages share domain " + dBand + ")");
        }

        // ---------------- F5 split / merge while the claimed teardown is pending
        List<BlockPos> f5 = List.of(i0, i1, i2);
        for (String phase : new String[]{"split", "merge"}) {
            String tag = "F5-" + phase;
            step(tag + "-place", 60, () -> place(level, storage, f5));
            step(tag + "-hold", 20, () -> {
                if (!seed(level, tag, f5, 13)) return;
                holdCluster = oldCluster;
                held = null;
                remove(level, i2, null);                       // chunk-owning thread
            });
            step(tag + "-await-held", 1, () -> {
                if (held == null) failures.add(tag + ": teardown was not claimed / held");
                domainBefore = regionOf(level, i0);
            });
            for (int k = 1; k <= ATTEMPTS; k++) {
                final int attempt = k;
                step(tag + "-ownership-" + k, 20, () -> {
                    if (held == null || !regionOf(level, i0).equals(domainBefore)) return;
                    Integer rid = regionIdContaining(level, i0);
                    if (rid == null) {
                        failures.add(tag + ": no region contains " + i0);
                        return;
                    }
                    String id = String.valueOf(rid);
                    if (phase.equals("split")) {
                        command(level, "nestworld split " + id);
                    } else {
                        String sib = siblingId(level, Integer.parseInt(id));
                        if (sib == null) {
                            if (attempt == ATTEMPTS) failures.add(tag + ": no sibling of " + domainBefore);
                            return;
                        }
                        command(level, "nestworld merge " + id + " " + sib);
                    }
                });
            }
            step(tag + "-deliver", 40, () -> {
                String after = regionOf(level, i0);
                notes.add(tag + " owner " + domainBefore + " -> " + after);
                if (after.equals(domainBefore)) failures.add(tag + ": ownership did not change while pending (" + after + ")");
                holdCluster = null;
                Runnable r = held;
                held = null;
                if (r != null) r.run();
            });
            step(tag + "-verify", 1, () -> verifyCommon(level, tag, i0, 13, List.of(i0, i1)));
            step(tag + "-clean", 20, () -> clear(level, f5, i0));
        }

        // ---------------- F6 unload / reload while the claimed teardown is pending
        for (String variant : new String[]{"a-member", "b-core"}) {
            String tag = "F6" + variant;
            long seedN = 23;
            BlockPos[] removed = new BlockPos[1], kept = new BlockPos[1];
            step(tag + "-place", 60, () -> place(level, storage, f1));
            step(tag + "-hold", 20, () -> {
                if (!seed(level, tag, f1, seedN)) return;
                CraftingBlockEntity core = ((CraftingCPUClusterAccessor) (Object) oldCluster).nw$getCore();
                BlockPos corePos = core == null ? i0 : core.getBlockPos();
                boolean removeCore = variant.startsWith("b");
                removed[0] = removeCore ? corePos : (corePos.equals(i0) ? i1 : i0);
                kept[0] = removed[0].equals(i0) ? i1 : i0;
                ghostBe = be(level, kept[0]);
                holdCluster = oldCluster;
                held = null;
                notes.add(tag + " core=" + corePos.toShortString() + " removed=" + removed[0].toShortString());
                remove(level, removed[0], null);
            });
            step(tag + "-unforce", 1, () -> {
                if (held == null) failures.add(tag + ": teardown was not claimed / held");
                forceAll(level, all, false);
            });
            for (int k = 0; k < 30; k++) {
                step(tag + "-await-unload-" + k, 10, () -> {
                    if (level.getChunkSource().getChunkNow(i0.getX() >> 4, i0.getZ() >> 4) == null) waitOverride = 0;
                });
            }
            step(tag + "-deliver-unloaded", 40, () -> {
                boolean unloaded = level.getChunkSource().getChunkNow(i0.getX() >> 4, i0.getZ() >> 4) == null;
                notes.add(tag + " unloadedAtDelivery=" + unloaded + " keptRemovedFlag=" + (ghostBe != null && ghostBe.isRemoved()));
                if (!unloaded) failures.add(tag + ": chunk still loaded after 300 ticks");
                ghostClusterBefore = ghostBe == null ? null : ghostBe.getCluster();
                holdCluster = null;
                Runnable r = held;
                held = null;
                if (r != null) r.run();
            });
            step(tag + "-ghost-check", 1, () -> {
                // a host unloaded before delivery must not be mutated by the stale teardown
                CraftingCPUCluster afterC = ghostBe == null ? null : ghostBe.getCluster();
                if (ghostBe != null && ghostBe.isRemoved() && afterC != ghostClusterBefore) {
                    failures.add(tag + ": ghost mutation — unloaded host's cluster changed " + ghostClusterBefore + " -> " + afterC);
                }
                notes.add(tag + " ghostClusterChanged=" + (afterC != ghostClusterBefore));
            });
            step(tag + "-reload", 100, () -> forceAll(level, all, true));
            step(tag + "-verify", 1, () -> {
                long world = diamondsAround(level, i0, false);
                CraftingBlockEntity k = be(level, kept[0]);
                CraftingCPUCluster nc = k == null ? null : k.getCluster();
                long inCluster = diamondsIn(nc);
                int[] v = CLAIMS.get(oldCluster);
                notes.add(tag + " " + counters() + " world=" + world + " reloadedCluster=" + inCluster + " total=" + (world + inCluster)
                        + " oldClaims=" + (v == null ? "0/0" : v[0] + "/" + v[1]) + " keptBlock=" + (k != null));
                if (world + inCluster != seedN) failures.add(tag + ": items world+reloaded = " + world + "+" + inCluster + " (expected " + seedN + ": lost or duplicated)");
                if (v != null && v[1] > 1) failures.add(tag + ": old cluster claimed " + v[1] + " times");
                if (d(4) != 0) failures.add(tag + ": retained " + d(4));
                if (nc == oldCluster) failures.add(tag + ": reloaded host still in the old cluster");
            });
            step(tag + "-clean", 20, () -> clear(level, f1, i0));
        }

        step("cleanup", 1, () -> {
            clear(level, all, i0);
            forceAll(level, all, false);
        });
        idx = -1;
        evaluateAt = level.getServer().getTickCount();
        started = true;
        return "TEARDOWN-CLOSURE: started p=" + p + " band=" + dBand + " interior=" + dInt + " crossDomain=" + crossDomain + " steps=" + steps.size();
    }

    /** Id of the region whose bounds contain {@code pos}'s chunk (also when the chunk is in the MAIN border band). */
    private static Integer regionIdContaining(ServerLevel level, BlockPos pos) {
        try {
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            for (Object r : regions(level)) {
                int[] b = bounds(r);
                if (cx >= b[0] && cx <= b[2] && cz >= b[1] && cz <= b[3]) return (int) r.getClass().getMethod("getId").invoke(r);
            }
        } catch (Throwable t) {
            failures.add("region lookup failed: " + t);
        }
        return null;
    }

    private static java.util.Collection<?> regions(ServerLevel level) throws Exception {
        Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
        Object sys = sysC.getMethod("get").invoke(null);
        Object dr = sysC.getMethod("getDimensionRegion", net.minecraft.world.level.Level.class).invoke(sys, level);
        Object grid = dr.getClass().getMethod("getGrid").invoke(dr);
        return (java.util.Collection<?>) grid.getClass().getMethod("getAllRegions").invoke(grid);
    }

    /** The other child of region {@code id}'s last split (Core grid API, reflectively). */
    private static String siblingId(ServerLevel level, int id) {
        try {
            Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
            Object sys = sysC.getMethod("get").invoke(null);
            Object dr = sysC.getMethod("getDimensionRegion", net.minecraft.world.level.Level.class).invoke(sys, level);
            Object grid = dr.getClass().getMethod("getGrid").invoke(dr);
            java.util.Collection<?> regions = (java.util.Collection<?>) grid.getClass().getMethod("getAllRegions").invoke(grid);
            Object me = null;
            for (Object r : regions) if ((int) r.getClass().getMethod("getId").invoke(r) == id) me = r;
            if (me == null) return null;
            int[] m = bounds(me);
            for (Object r : regions) {
                if (r == me) continue;
                int[] o = bounds(r);
                boolean sameX = o[0] == m[0] && o[2] == m[2], sameZ = o[1] == m[1] && o[3] == m[3];
                boolean adjZ = o[3] + 1 == m[1] || m[3] + 1 == o[1], adjX = o[2] + 1 == m[0] || m[2] + 1 == o[0];
                if ((sameX && adjZ) || (sameZ && adjX)) return String.valueOf(r.getClass().getMethod("getId").invoke(r));
            }
        } catch (Throwable t) {
            failures.add("sibling lookup failed: " + t);
        }
        return null;
    }

    private static int[] bounds(Object r) throws Exception {
        return new int[]{(int) r.getClass().getMethod("getMinChunkX").invoke(r), (int) r.getClass().getMethod("getMinChunkZ").invoke(r),
                (int) r.getClass().getMethod("getMaxChunkX").invoke(r), (int) r.getClass().getMethod("getMaxChunkZ").invoke(r)};
    }
}
