package net.nestworld.compat.ae2.glue;

import appeng.me.cluster.IAECluster;
import appeng.me.cluster.IAEMultiBlock;
import appeng.me.cluster.MBCalculator;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import java.util.IdentityHashMap;
import java.util.Map;
import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

/**
 * AE2-DEMAIN-001 B1 deterministic lane fixture (fixtures flag; TEST-ONLY narrow unpin: the crafting types must resolve to
 * two different REGION domains). Two 2-storage crafting CPUs, A at {@code a} and B at {@code b}, each recalculated in its
 * own region thread at the same time, choreographed with bounded holds at AE2's probe points:
 * <ul>
 * <li><b>skip</b>: A held inside its flag window (MBCalculator:143, flag = A's cluster); B recalculates →
 * lane OFF: B's recalc is silently skipped at MBCalculator:85 (detector silentSkipForeign); lane ON: B queued, runs
 * after A in B's domain;</li>
 * <li><b>ise</b>: A held after its bounds scan (flag still null), B recalculates up to its own flag window and is held
 * there, A released → lane OFF: A's setModificationInProgress throws "already in-progress" and A's finally clears B's
 * flag (detector ise + foreignFinallyReset); lane ON: B never enters while A owns the token.</li>
 * </ul>
 * <li><b>drops</b> (drop accounting under the lane): both CPUs seeded with diamonds in their own domain; A held inside its
 * flag window (token owned by A's region thread); B's member removed on B's region thread → B's D4a cluster teardown
 * (inventory drop) must queue behind A (lane tdQueued / tdRan), then run in B's anchor domain: old B claimed exactly once,
 * items captured = delivered = world = seeded, retained 0, off-domain 0; A keeps its inventory. Lane OFF: the teardown
 * runs inside A's foreign flag window (negative control).</li>
 * </ul>
 * Holds are bounded (1.5 s / 3 s, test-only). Verdict: lane OFF = negative control (race must be observed); lane ON = 0.
 * <p>Soak helpers (fixtures flag): {@code seed <pos> <n>} inserts n diamonds into the crafting cluster at pos in its BE
 * domain; {@code collect <pos> <r>} counts + removes diamond item entities in the column box around pos (chunk-owning
 * domain) and samples the diamonds still held by crafting clusters there; {@code items} prints the cumulative ledger
 * ({@code seeded = collected + inCluster} at quiescence = conservation).
 */
public final class MbLaneFixtures {
    private static final String STORAGE = "ae2:crafting_storage";
    private static volatile String status = "idle";
    private static volatile boolean started;
    private static volatile int phase, waitUntil;
    private static volatile ServerLevel lv;
    private static volatile BlockPos pa, pb;
    private static volatile String mode;
    private static volatile boolean aMark, bMark, aDone, bDone;
    private static volatile BlockEntity hostA, hostB;
    private static volatile Throwable aErr, bErr;
    private static final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private static long[] base;

    private MbLaneFixtures() {
    }

    public static String status() {
        return status;
    }

    private static final String[] DET = {"silentSkipForeign", "ise", "iseTotal", "foreignFinallyReset", "concurrentEntry", "requested", "completed", "failed"};

    /** Detector counters (separate test-only mod), reflectively; -1 when absent. */
    static long det(String name) {
        try {
            Field f = Class.forName("net.nestworld.ae2detect.MbDetect").getField(name);
            return ((LongAdder) f.get(null)).sum();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long[] snap() {
        return new long[]{det("silentSkipForeign"), det("iseTotal"), det("foreignFinallyReset"), det("concurrentEntry"),
                MbLane.queued.sum(), MbLane.ticketRan.sum(), MbLane.completedInline.sum(), MbLane.coalesced.sum(),
                MbLane.stale.sum(), MbLane.failed.sum(), MbLane.bodyConcurrent.sum(), MbLane.flagWithoutToken.sum()};
    }

    private static final String[] SNAP = {"det.silentSkipForeign", "det.ise", "det.foreignFinallyReset", "det.concurrentEntry",
            "lane.queued", "lane.ticketRan", "lane.inline", "lane.coalesced", "lane.stale", "lane.failed", "lane.bodyConcurrent",
            "lane.flagWithoutToken"};

    static MBCalculator<?, ?> calculatorOf(BlockEntity be) {
        for (Class<?> c = be.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (MBCalculator.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (MBCalculator<?, ?>) f.get(be);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static void spin(java.util.function.BooleanSupplier until, long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (!until.getAsBoolean() && System.nanoTime() < end) Thread.onSpinWait();
    }

    public static String run(ServerLevel level, BlockPos a, BlockPos b, String m) {
        if (started) return "running: " + status;
        if (!m.equals("skip") && !m.equals("ise") && !m.equals("drops")) return "mode must be skip|ise|drops";
        String da = BeDomainReady.domainName(level, a, STORAGE), db = BeDomainReady.domainName(level, b, STORAGE);
        if (!da.startsWith("REGION#") || !db.startsWith("REGION#") || da.equals(db)) {
            return "need ae2:crafting_storage at a and b in two different REGION domains (TEST-ONLY narrow unpin); got a=" + da + " b=" + db;
        }
        for (BlockPos q : new BlockPos[]{a, a.east(), b, b.east()}) if (!level.getBlockState(q).isAir()) return q + " must be air";
        dropsOldB = null;
        dbase = null;
        DCLAIMS.clear();
        lv = level;
        pa = a;
        pb = b;
        mode = m;
        notes.clear();
        aMark = bMark = aDone = bDone = false;
        aErr = bErr = null;
        phase = 0;
        waitUntil = level.getServer().getTickCount();
        started = true;
        status = "MBRACE " + m + ": started a=" + a.toShortString() + " (" + da + ") b=" + b.toShortString() + " (" + db + ") lane=" + MbLane.ENABLED;
        return status;
    }

    public static void onServerTickEnd(int tick) {
        if (!started || tick < waitUntil) return;
        ServerLevel level = lv;
        try {
            switch (phase++) {
                case 0 -> {
                    level.setChunkForced(pa.getX() >> 4, pa.getZ() >> 4, true);
                    level.setChunkForced(pb.getX() >> 4, pb.getZ() >> 4, true);
                    waitUntil = tick + 40;
                }
                case 1 -> {
                    Block storage = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "1k_crafting_storage"));
                    if ("drops".equals(mode)) floor(level, true);   // drops land on a floor (void below the rig positions)
                    for (BlockPos q : new BlockPos[]{pa, pa.east(), pb, pb.east()}) level.setBlockAndUpdate(q, storage.defaultBlockState());
                    waitUntil = tick + 60;
                }
                case 2 -> {
                    hostA = level.getBlockEntity(pa);
                    hostB = level.getBlockEntity(pb);
                    IAECluster ca = clusterOf(hostA), cb = clusterOf(hostB);
                    notes.add("before: A.formed=" + (ca != null) + " B.formed=" + (cb != null));
                    if (ca == null || cb == null) {
                        finish("FAIL precondition: CPUs not formed");
                        return;
                    }
                    MBCalculator<?, ?> calcA = calculatorOf(hostA), calcB = calculatorOf(hostB);
                    if (calcA == null || calcB == null) {
                        finish("FAIL precondition: no calculator");
                        return;
                    }
                    base = snap();
                    if ("drops".equals(mode)) {
                        startDrops(level, calcA, (CraftingCPUCluster) cb);
                        waitUntil = tick + 100;
                        return;
                    }
                    installHook();
                    BeDomainReady.enqueueTo(level, pa, STORAGE, () -> {
                        try {
                            calcA.calculateMultiblock(level, pa);
                        } catch (Throwable t) {
                            aErr = t;
                        } finally {
                            aDone = true;
                        }
                    }, k -> notes.add("A dropped " + k));
                    BeDomainReady.enqueueTo(level, pb, STORAGE, () -> {
                        spin(() -> aMark, 3000);
                        try {
                            calcB.calculateMultiblock(level, pb);
                        } catch (Throwable t) {
                            bErr = t;
                        } finally {
                            bDone = true;
                        }
                    }, k -> notes.add("B dropped " + k));
                    waitUntil = tick + 100;
                }
                case 3 -> {
                    MbLane.hook = null;
                    if (!aDone || !bDone || MbLane.outstanding() != 0) {
                        if (tick < waitUntil + 200) {
                            phase = 3;
                            waitUntil = tick + 10;
                            return;
                        }
                    }
                    long[] now = snap();
                    StringBuilder d = new StringBuilder();
                    for (int i = 0; i < SNAP.length; i++) d.append(' ').append(SNAP[i]).append("+=").append(now[i] - base[i]);
                    IAECluster ca = clusterOf(level.getBlockEntity(pa)), cb = clusterOf(level.getBlockEntity(pb));
                    boolean formed = formedOver(ca, pa) && formedOver(cb, pb);
                    notes.add("delta" + d + " aErr=" + aErr + " bErr=" + bErr + " aMark=" + aMark + " bMark=" + bMark
                            + " afterFormed=" + formed + " outstanding=" + MbLane.outstanding());
                    long skip = now[0] - base[0], ise = now[1] - base[1], reset = now[2] - base[2], conc = now[3] - base[3];
                    String verdict;
                    if ("drops".equals(mode)) {
                        ClusterTeardownCoordinator.claimObserver = null;
                        verdict = dropsVerdict(level, skip, ise, reset, conc);
                    } else if (!MbLane.ENABLED) {
                        boolean reproduced = "skip".equals(mode) ? skip > 0 : (ise > 0 || aErr instanceof IllegalStateException);
                        verdict = reproduced ? "NEGATIVE-CONTROL REPRODUCED" : "NEGATIVE-CONTROL NOT REPRODUCED (invalid)";
                    } else {
                        boolean clean = skip == 0 && ise == 0 && reset == 0 && conc == 0 && aErr == null && bErr == null
                                && now[4] - base[4] >= 1 && now[5] - base[5] >= 1 && now[10] - base[10] == 0 && formed
                                && MbLane.outstanding() == 0;
                        verdict = clean ? "PASS" : "FAIL";
                    }
                    finish(verdict);
                    phase = 4;
                    waitUntil = tick + 1;
                    started = true;
                }
                case 4 -> {
                    for (BlockPos q : new BlockPos[]{pa, pa.east(), pb, pb.east()}) level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
                    waitUntil = tick + 20;
                }
                default -> {
                    if ("drops".equals(mode)) {        // cleanup: the fixture's own diamonds and floor
                        diamondsAround(level, pa, true);
                        diamondsAround(level, pb, true);
                        floor(level, false);
                    }
                    level.setChunkForced(pa.getX() >> 4, pa.getZ() >> 4, false);
                    level.setChunkForced(pb.getX() >> 4, pb.getZ() >> 4, false);
                    started = false;
                }
            }
        } catch (Throwable t) {
            MbLane.hook = null;
            finish("FAIL threw " + t);
            started = false;
        }
    }

    // ------------------------------------------------------------------ drops mode

    static final long SEED_A = 23, SEED_B = 19;
    private static volatile CraftingCPUCluster dropsOldB;
    private static long[] dbase;
    private static final Map<Object, int[]> DCLAIMS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static volatile long seededA, seededB;

    private static long[] dsnap() {
        var k = COUNTERS;
        return new long[]{k.drItemsCaptured.sum(), k.drItemsDelivered.sum(), k.drItemsRetained.sum(), k.tdOffDomain.sum(),
                k.tmOffDomain.sum(), k.tdFallback.sum(), k.drFallback.sum(), MbLane.tdQueued.sum(), MbLane.tdRan.sum(),
                MbLane.tdInline.sum(), MbLane.tdReentrant.sum(), k.tdClaimed.sum(), k.drIntents.sum()};
    }

    private static final String[] DSNAP = {"captured", "delivered", "retained", "tdOffDomain", "tmOffDomain", "tdFallback",
            "drFallback", "lane.tdQueued", "lane.tdRan", "lane.tdInline", "lane.tdReentrant", "claimed", "dropIntents"};

    private static void floor(ServerLevel level, boolean place) {
        Block f = place ? Blocks.GLASS : Blocks.AIR;
        for (BlockPos c : new BlockPos[]{pa, pb}) {
            for (int dx = -3; dx <= 4; dx++) for (int dz = -3; dz <= 3; dz++) level.setBlockAndUpdate(c.offset(dx, -1, dz), f.defaultBlockState());
        }
    }

    private static long diamondsAround(ServerLevel level, BlockPos p, boolean discard) {
        long n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(6), e -> e.getItem().is(Items.DIAMOND))) {
            n += e.getItem().getCount();
            if (discard) e.discard();
        }
        return n;
    }

    private static long diamondsIn(IAECluster c) {
        return c instanceof CraftingCPUCluster cc ? cc.craftingLogic.getInventory().list.get(AEItemKey.of(Items.DIAMOND)) : 0;
    }

    private static void startDrops(ServerLevel level, MBCalculator<?, ?> calcA, CraftingCPUCluster oldB) throws Throwable {
        dropsOldB = oldB;
        seededA = seededB = 0;
        diamondsAround(level, pa, true);
        diamondsAround(level, pb, true);
        ClusterTeardownCoordinator.claimObserver = (cluster, claimed, label) -> {
            int[] v = DCLAIMS.computeIfAbsent(cluster, k -> new int[2]);
            v[0]++;
            if (claimed) v[1]++;
        };
        // A: held inside its flag window (token owner) until B's removal is done
        MbLane.hook = (point, target) -> {
            if ("status".equals(point) && target == hostA && !aMark) {
                aMark = true;
                spin(() -> bDone, 3000);
            }
        };
        dbase = dsnap();
        BeDomainReady.enqueueTo(level, pa, STORAGE, () -> {
            try {
                IAECluster c = clusterOf(level.getBlockEntity(pa));
                if (c instanceof CraftingCPUCluster cc) seededA = insertDiamonds(cc, SEED_A);
                calcA.calculateMultiblock(level, pa);
            } catch (Throwable t) {
                aErr = t;
            } finally {
                aDone = true;
            }
        }, k -> notes.add("A dropped " + k));
        // B: in its own region thread, seeded, then one member removed while A owns the token → B's teardown must queue
        BeDomainReady.enqueueTo(level, pb, STORAGE, () -> {
            spin(() -> aMark, 3000);
            try {
                IAECluster c = clusterOf(level.getBlockEntity(pb));
                if (c == oldB) seededB = insertDiamonds(oldB, SEED_B);
                bMark = aMark && !aDone;                // removal happened while A was inside its window
                level.setBlockAndUpdate(pb.east(), Blocks.AIR.defaultBlockState());
            } catch (Throwable t) {
                bErr = t;
            } finally {
                bDone = true;
            }
        }, k -> notes.add("B dropped " + k));
    }

    /** Inserts and returns the amount actually added (the inventory's diamond count before / after). */
    private static long insertDiamonds(CraftingCPUCluster c, long n) {
        var inv = c.craftingLogic.getInventory();
        long before = inv.list.get(AEItemKey.of(Items.DIAMOND));
        inv.insert(AEItemKey.of(Items.DIAMOND), n, Actionable.MODULATE);
        return inv.list.get(AEItemKey.of(Items.DIAMOND)) - before;
    }

    private static String dropsVerdict(ServerLevel level, long skip, long ise, long reset, long conc) {
        long[] d = dsnap();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < DSNAP.length; i++) b.append(' ').append(DSNAP[i]).append("+=").append(d[i] - dbase[i]);
        long worldB = diamondsAround(level, pb, false), worldA = diamondsAround(level, pa, false);
        IAECluster ca = clusterOf(level.getBlockEntity(pa)), cbNew = clusterOf(level.getBlockEntity(pb));
        long inA = diamondsIn(ca), inBnew = cbNew == dropsOldB ? -1 : diamondsIn(cbNew);
        int[] cl = DCLAIMS.get(dropsOldB);
        String claims = cl == null ? "0/0" : cl[0] + "/" + cl[1];
        boolean aIntact = formedOver(ca, pa) && inA == seededA && worldA == 0;
        notes.add("drops" + b + " | seededA=" + seededA + " seededB=" + seededB + " worldA=" + worldA + " inA=" + inA
                + " worldB=" + worldB + " inBnew=" + inBnew + " oldB requests/claims=" + claims + " oldBdestroyed="
                + (dropsOldB != null && dropsOldB.isDestroyed()) + " removedWhileAHeld=" + bMark + " aIntact=" + aIntact);
        long cap = d[0] - dbase[0], del = d[1] - dbase[1], ret = d[2] - dbase[2];
        boolean conserved = seededB == SEED_B && cap == SEED_B && del == SEED_B && ret == 0 && worldB + Math.max(inBnew, 0) == SEED_B
                && aIntact;
        if (!MbLane.ENABLED) {
            boolean reproduced = skip > 0 || ise > 0 || reset > 0 || conc > 0 || aErr != null || bErr != null;
            return (reproduced ? "NEGATIVE-CONTROL REPRODUCED" : "NEGATIVE-CONTROL NOT REPRODUCED (invalid)")
                    + " conserved=" + conserved;
        }
        boolean clean = skip == 0 && ise == 0 && reset == 0 && conc == 0 && aErr == null && bErr == null && bMark
                && d[7] - dbase[7] >= 1 && d[8] - dbase[8] >= 1 && cl != null && cl[1] == 1
                && d[3] == dbase[3] && d[4] == dbase[4] && d[5] == dbase[5] && d[6] == dbase[6]
                && conserved && MbLane.outstanding() == 0;
        return clean ? "PASS" : "FAIL";
    }

    // ------------------------------------------------------------------ soak helpers (seed / collect / items)

    public static final LongAdder seeded = new LongAdder(), seedOps = new LongAdder(), seedMiss = new LongAdder(),
            collected = new LongAdder(), collectOps = new LongAdder();
    /** Last in-cluster diamond sample per collect position (the ledger sums them). */
    private static final Map<Long, Long> IN_CLUSTER = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastCollect = "-";

    /** Inserts {@code n} diamonds into the crafting cluster at {@code pos}, in that block entity's current domain. */
    public static String seed(ServerLevel level, BlockPos pos, int n) {
        if (!(level.getBlockEntity(pos) instanceof CraftingBlockEntity be)) {
            seedMiss.increment();
            return "seed: no crafting BE at " + pos.toShortString();
        }
        try {
            BeDomainReady.enqueueTo(level, pos, BeDomainReady.typeOf(be), () -> {
                CraftingCPUCluster cl = level.getBlockEntity(pos) instanceof CraftingBlockEntity c ? c.getCluster() : null;
                if (cl != null && !cl.isDestroyed()) {
                    long in = insertDiamonds(cl, n);
                    seeded.add(in);
                    seedOps.increment();
                } else {
                    seedMiss.increment();
                }
            }, k -> seedMiss.increment());
        } catch (Throwable t) {
            seedMiss.increment();
            return "seed: enqueue failed " + t;
        }
        return "seed queued " + n + " at " + pos.toShortString();
    }

    /**
     * In the chunk-owning domain of {@code pos}: counts and removes diamond item entities in the column box
     * (x/z ± r, full build height) and samples diamonds held by distinct crafting clusters in x/z ± r, y ± 1.
     */
    public static String collect(ServerLevel level, BlockPos pos, int r) {
        try {
            BeDomainReady.enqueueTo(level, pos, null, () -> {
                AABB box = new AABB(pos.getX() - r, level.getMinBuildHeight() - 64, pos.getZ() - r,
                        pos.getX() + r + 1, level.getMaxBuildHeight() + 64, pos.getZ() + r + 1);
                long n = 0;
                for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.getItem().is(Items.DIAMOND))) {
                    n += e.getItem().getCount();
                    e.discard();
                }
                Map<Object, Boolean> seen = new IdentityHashMap<>();
                long in = 0;
                for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
                    CraftingCPUCluster cl = level.getBlockEntity(q) instanceof CraftingBlockEntity c ? c.getCluster() : null;
                    if (cl != null && seen.put(cl, Boolean.TRUE) == null) {
                        in += cl.craftingLogic.getInventory().list.get(AEItemKey.of(Items.DIAMOND));
                    }
                }
                collected.add(n);
                collectOps.increment();
                IN_CLUSTER.put(pos.asLong(), in);
                lastCollect = "collect@" + level.getServer().getTickCount() + " world=" + n + " inCluster=" + in + " thread=" + Thread.currentThread().getName();
            }, k -> lastCollect = "collect dropped " + k);
        } catch (Throwable t) {
            return "collect: enqueue failed " + t;
        }
        return "collect queued at " + pos.toShortString() + " r=" + r;
    }

    public static String items() {
        var k = COUNTERS;
        long in = 0;
        for (long v : IN_CLUSTER.values()) in += v;
        return "MBITEMS seeded=" + seeded.sum() + " seedOps=" + seedOps.sum() + " seedMiss=" + seedMiss.sum() + " collected="
                + collected.sum() + " collectOps=" + collectOps.sum() + " inCluster=" + in + " (positions " + IN_CLUSTER.size()
                + ") balance(seeded-collected-inCluster)=" + (seeded.sum() - collected.sum() - in) + " | last " + lastCollect
                + " | drops captured=" + k.drItemsCaptured.sum() + " delivered=" + k.drItemsDelivered.sum() + " retained="
                + k.drItemsRetained.sum() + " fallback=" + k.drFallback.sum() + " intents=" + k.drIntents.sum();
    }

    private static void installHook() {
        if ("skip".equals(mode)) {
            MbLane.hook = (point, target) -> {
                if ("status".equals(point) && target == hostA && !aMark) {
                    aMark = true;
                    spin(() -> bDone, 3000);
                }
            };
        } else {
            MbLane.hook = (point, target) -> {
                if ("scale".equals(point) && target == hostA && !aMark) {
                    aMark = true;
                    spin(() -> bMark, 1500);
                } else if ("status".equals(point) && target == hostB && !bMark) {
                    bMark = true;
                    spin(() -> aDone, 1500);
                }
            };
        }
    }

    private static IAECluster clusterOf(BlockEntity be) {
        return be instanceof IAEMultiBlock<?> mb ? mb.getCluster() : null;
    }

    private static boolean formedOver(IAECluster c, BlockPos p) {
        return c != null && !c.isDestroyed() && c.getBoundsMin().equals(p) && c.getBoundsMax().equals(p.east());
    }

    private static void finish(String verdict) {
        status = "MBRACE " + mode + " lane=" + MbLane.ENABLED + ": " + verdict + " | " + String.join(" | ", notes);
        Ae2Compat.LOG.info("[nestworld-compat-ae2] B1 lane fixture {}", status);
    }
}
