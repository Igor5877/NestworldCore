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
 * Holds are bounded (1.5 s / 3 s, test-only). Verdict: lane OFF = negative control (race must be observed); lane ON = 0.
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
        if (!m.equals("skip") && !m.equals("ise")) return "mode must be skip|ise";
        String da = BeDomainReady.domainName(level, a, STORAGE), db = BeDomainReady.domainName(level, b, STORAGE);
        if (!da.startsWith("REGION#") || !db.startsWith("REGION#") || da.equals(db)) {
            return "need ae2:crafting_storage at a and b in two different REGION domains (TEST-ONLY narrow unpin); got a=" + da + " b=" + db;
        }
        for (BlockPos q : new BlockPos[]{a, a.east(), b, b.east()}) if (!level.getBlockState(q).isAir()) return q + " must be air";
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
                    if (!MbLane.ENABLED) {
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
