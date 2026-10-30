package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * AE2-PIN-LEAK-001 D1 deterministic S1 fixture (fixtures flag; production pins): crafting storage X at P (pinned → MAIN)
 * in a FREE-RUNNING region's interior chunk. Main readiness of X pauses at "init-locked" (revalidated, nw$life held)
 * waiting up to {@link #MAIN_WAIT_MS} for the section write; the chunk-owning region thread waits for that point, then
 * removes X. D1 OFF (negative control): the section write lands → onReady reads AIR → S1, window probe +1. D1 ON: the
 * region blocks at admission before the write → main's wait times out → readiness completes, window 0, one contended
 * admission (wait imposed by the fixture), removal completes afterwards.
 */
public final class LifeAdmissionFixtures {
    private static final String STORAGE = "ae2:crafting_storage";
    static final long MAIN_WAIT_MS = 2000, REGION_WAIT_MS = 4000;
    static final int FREE_RUNNING_POLLS = 90;                      // ≤ 90 × 20 ticks
    private static volatile boolean awaited;
    private static volatile int waitOverride = -1;
    private static volatile String status = "idle";
    private static final List<Runnable> steps = new ArrayList<>();
    private static final List<Integer> waits = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static volatile int idx = -1;
    private static volatile boolean started;
    private static volatile int evaluateAt = Integer.MAX_VALUE;
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    private static volatile BlockPos armed;
    private static volatile boolean mainAtInitLocked, sectionWritten, writtenSeenByMain, initDone, regionTimedOut;
    private static volatile String cancelled;

    private LifeAdmissionFixtures() {
    }

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        if (!started || tick < evaluateAt) return;
        idx++;
        if (idx >= steps.size()) {
            started = false;
            LifeHooks.hook = null;
            LifeAdmission.sectionHook = null;
            LifeAdmission.ENABLED = true;
            status = "S1" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " " + String.join(" | ", notes);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] D1 S1 fixture {}", status);
            return;
        }
        status = "S1: running " + names.get(idx) + " (" + (idx + 1) + "/" + steps.size() + ")";
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

    private static boolean freeRunning(ServerLevel level, BlockPos p) {
        try {
            Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
            Object sys = sysC.getMethod("get").invoke(null);
            Object dr = sysC.getMethod("getDimensionRegion", net.minecraft.world.level.Level.class).invoke(sys, level);
            Object grid = dr.getClass().getMethod("getGrid").invoke(dr);
            Object r = grid.getClass().getMethod("getRegionForChunk", int.class, int.class).invoke(grid, p.getX() >> 4, p.getZ() >> 4);
            return r != null && (boolean) r.getClass().getMethod("isFreeRunning").invoke(r);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String run(ServerLevel level, BlockPos p) {
        if (started) return "running: " + status;
        BlockPos n = p.east();
        String dp = BeDomainReady.domainName(level, p, STORAGE), dn = BeDomainReady.domainName(level, n, null);
        if (!"MAIN".equals(dp) || !BeDomainReady.pinnedByPolicy(STORAGE) || !dn.startsWith("REGION#")) {
            return "need X pinned with domain MAIN and its east neighbour in a REGION (got X=" + dp + " pinned="
                    + BeDomainReady.pinnedByPolicy(STORAGE) + " N=" + dn + ")";
        }
        if (!level.getBlockState(p).isAir() || !level.getBlockState(n).isAir()) return "P and its east neighbour must be air";
        Block storage = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "1k_crafting_storage"));
        steps.clear(); waits.clear(); names.clear(); failures.clear(); notes.clear();
        long[] base = new long[4];

        step("forceload", 40, () -> level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true));
        for (boolean d1 : new boolean[]{false, true}) {
            String phase = d1 ? "POS(D1 on)" : "NEG(D1 off)";
            // the interleaving needs P's region to be free-running (auto-promotion happens some time after start)
            for (int k = 0; k < FREE_RUNNING_POLLS; k++) {
                final int poll = k;
                step(phase + "-await-free-running-" + k, 20, () -> {
                    if (awaited || freeRunning(level, p)) {
                        if (!awaited) notes.add(phase + " free-running after " + poll + " polls");
                        awaited = true;
                        waitOverride = 0;                // remaining polls cost one tick each
                    }
                });
            }
            step(phase + "-arm-place-remove", 140, () -> {
                awaited = false;
                if (!freeRunning(level, p)) {
                    failures.add(phase + ": INCONCLUSIVE — P's region is not free-running (interleaving impossible)");
                    return;
                }
                LifeAdmission.ENABLED = d1;
                armed = p;
                mainAtInitLocked = false; sectionWritten = false; writtenSeenByMain = false; initDone = false;
                regionTimedOut = false; cancelled = null;
                base[0] = COUNTERS.lifeWindowObserved.sum();
                base[1] = COUNTERS.lifeAdmissionContended.sum();
                base[2] = COUNTERS.lifeAdmissionWaitNanos.sum();
                base[3] = COUNTERS.lifeAdmissionCompleted.sum();
                LifeAdmission.sectionHook = pos -> {
                    // only the orchestrated removal (after main reached init-locked) — not X's own placement
                    if (pos.equals(armed) && mainAtInitLocked) sectionWritten = true;
                };
                LifeHooks.hook = (point, be) -> {
                    if (!be.getBlockPos().equals(armed)) return;
                    if (point.equals("init-locked")) {
                        mainAtInitLocked = true;
                        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAIN_WAIT_MS);
                        while (!sectionWritten && System.nanoTime() < end) Thread.onSpinWait();
                        writtenSeenByMain = sectionWritten;
                    } else if (point.equals("init-done")) {
                        initDone = true;
                    } else if (point.startsWith("init-cancelled")) {
                        cancelled = point;
                    }
                };
                level.setBlockAndUpdate(p, storage.defaultBlockState());          // readiness queued (MAIN, end of tick)
                try {
                    BeDomainReady.enqueueTo(level, n, null, () -> {              // P's chunk-owning region thread
                        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGION_WAIT_MS);
                        while (!mainAtInitLocked && System.nanoTime() < end) Thread.onSpinWait();
                        regionTimedOut = !mainAtInitLocked;
                        level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    }, k -> failures.add(phase + ": region task dropped " + k));
                } catch (Throwable t) {
                    failures.add(phase + ": enqueue failed " + t);
                }
            });
            step(phase + "-check", 1, () -> {
                long window = COUNTERS.lifeWindowObserved.sum() - base[0];
                long contended = COUNTERS.lifeAdmissionContended.sum() - base[1];
                long waitMs = (COUNTERS.lifeAdmissionWaitNanos.sum() - base[2]) / 1_000_000;
                long completed = COUNTERS.lifeAdmissionCompleted.sum() - base[3];
                notes.add(phase + " mainAtInitLocked=" + mainAtInitLocked + " writtenSeenByMain=" + writtenSeenByMain
                        + " initDone=" + initDone + " cancelled=" + cancelled + " regionTimedOut=" + regionTimedOut
                        + " window+" + window + " admission{contended+" + contended + " waitMs+" + waitMs + " completed+" + completed + "}");
                if (!mainAtInitLocked || regionTimedOut) {
                    failures.add(phase + ": INCONCLUSIVE — the interleaving did not occur");
                } else if (!d1) {
                    if (!writtenSeenByMain || window < 1) failures.add("NEG: the S1 window was not reproduced (negative control failed)");
                } else {
                    if (writtenSeenByMain) failures.add("POS: main observed the section write during readiness");
                    if (window != 0) failures.add("POS: window observed " + window);
                    if (!initDone) failures.add("POS: readiness did not complete");
                    if (contended < 1 || completed < 1) failures.add("POS: no contended D1 admission");
                }
                if (!level.getBlockState(p).isAir()) failures.add(phase + ": X not removed afterwards");
                if (level.getBlockEntity(p) != null) failures.add(phase + ": block entity still present afterwards");
                LifeHooks.hook = null;
                LifeAdmission.sectionHook = null;
                LifeAdmission.ENABLED = true;
            });
        }
        step("cleanup", 1, () -> level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false));
        idx = -1;
        evaluateAt = level.getServer().getTickCount();
        started = true;
        return "S1: started X=" + p + " (" + dp + ") N=" + n + " (" + dn + ") steps=" + steps.size();
    }
}
