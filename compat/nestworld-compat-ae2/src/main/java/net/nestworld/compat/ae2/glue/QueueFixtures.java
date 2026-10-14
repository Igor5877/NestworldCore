package net.nestworld.compat.ae2.glue;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.hooks.ticking.TickHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * QUEUE-001 acceptance fixtures (QUEUE-001.1 §1.5 + §4.2) on a real AE2 block entity and the real
 * TickHandler. Producers that must be "foreign" run on a plain thread (not main), so they take the
 * staged path exactly as a region thread would. Enabled with -Dnestworld.compatAe2.fixtures=true.
 */
public final class QueueFixtures {
    public enum Kind { IDLE, BEFORE_DEQUEUE, DURING, MANY_DURING, SELF, AT_FINISH, AFTER_FINISH, STAGING }

    private static volatile Run current;
    private static volatile String status = "idle";

    public static String status() {
        return status;
    }

    static final class Run {
        Kind kind;
        AEBaseBlockEntity be;
        int startTick, settleUntil = -1;
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger stagedRan = new AtomicInteger();
        int stagedExpected;
        long requeueBefore;
        volatile boolean actionDone;
        final List<String> failures = new ArrayList<>();
    }

    public static String start(Kind kind, ServerLevel level, BlockPos pos) {
        if (current != null) return "a queue fixture is running: " + status;
        Run r = new Run();
        r.kind = kind;
        r.startTick = level.getServer().getTickCount();
        r.requeueBefore = Ae2Compat.COUNTERS.setChangedRequeue.sum();
        if (kind == Kind.STAGING) {
            stagingStress(r, level);
        } else {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof AEBaseBlockEntity ae)) return "no AE2 block entity at " + pos;
            r.be = ae;
            installHook(r);
            current = r;
            trigger(r);
        }
        current = r;
        status = kind + ": running";
        return status;
    }

    /**
     * The protocol part of {@code saveChanges}' server branch as a foreign producer runs it: P-LP, then
     * publish when uncovered (staged, because the caller is off main). The preceding vanilla
     * {@code level.blockEntityChanged(pos)} is left out: from a plain test thread (neither main nor a
     * region thread) its getChunkAt waits for main, which the fixture holds, so it would only measure
     * a join timeout. Region-thread producers reach the chunk through Core's fast path.
     */
    static void foreignSave(AEBaseBlockEntity be) {
        if (!SetChangedProtocol.produce((NwAeBlockEntity) be)) {
            net.nestworld.compat.ae2.mixin.AEBaseBlockEntityInvoker inv = (net.nestworld.compat.ae2.mixin.AEBaseBlockEntityInvoker) be;
            TickHandler.instance().addCallable(null, (net.minecraft.world.level.Level l) -> inv.nw$setChangedAtEndOfTick(l));
        }
    }

    private static void foreign(Runnable action) {
        Thread t = new Thread(action, "nw-queue-fixture-foreign");
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void installHook(Run r) {
        SetChangedProtocol.hook = (point, be) -> {
            if (be != r.be) return;
            if (point.equals("C-claim")) r.claims.incrementAndGet();
            if (r.actionDone) return;
            switch (r.kind) {
                case DURING -> {
                    if (point.equals("C-body")) {
                        r.actionDone = true;
                        foreign(() -> foreignSave(r.be));
                    }
                }
                case MANY_DURING -> {
                    if (point.equals("C-body")) {
                        r.actionDone = true;
                        foreign(() -> {
                            for (int i = 0; i < 10; i++) foreignSave(r.be);
                        });
                    }
                }
                case SELF -> {
                    if (point.equals("C-body")) {
                        r.actionDone = true;
                        r.be.saveChanges();               // this block entity's own running callback
                    }
                }
                case AT_FINISH -> {
                    if (point.equals("C-finish")) {     // producer races the finish CAS: still RUNNING
                        r.actionDone = true;
                        foreign(() -> foreignSave(r.be));
                    }
                }
                default -> {
                }
            }
        };
    }

    private static void trigger(Run r) {
        switch (r.kind) {
            case IDLE, DURING, MANY_DURING, SELF, AT_FINISH, AFTER_FINISH -> foreign(() -> foreignSave(r.be));
            case BEFORE_DEQUEUE -> foreign(() -> {
                foreignSave(r.be);
                foreignSave(r.be);
                foreignSave(r.be);
            });
            default -> {
            }
        }
    }

    private static void stagingStress(Run r, ServerLevel level) {
        int threads = 8, per = 2_000;
        r.stagedExpected = threads * per * 2;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < per; i++) {
                    TickHandler.instance().addCallable(null, r.stagedRan::incrementAndGet);
                    TickHandler.instance().addCallable(level, r.stagedRan::incrementAndGet);
                }
            }, "nw-queue-fixture-producer-" + t);
            ts[t].start();
        }
        r.settleUntil = level.getServer().getTickCount() + 200;  // producers race several drains
        new Thread(() -> {
            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    /** Main thread, end of every server tick. */
    public static void onServerTickEnd(int tick) {
        Run r = current;
        if (r == null) return;
        if (r.kind == Kind.AFTER_FINISH && !r.actionDone && r.claims.get() >= 1) {
            r.actionDone = true;                          // first callback finished (IDLE): produce again
            foreign(() -> foreignSave(r.be));
        }
        int wait = r.kind == Kind.STAGING ? r.settleUntil : r.startTick + 40;
        if (tick < wait) return;
        current = null;
        SetChangedProtocol.hook = null;
        long requeues = Ae2Compat.COUNTERS.setChangedRequeue.sum() - r.requeueBefore;
        int claims = r.claims.get();
        switch (r.kind) {
            case IDLE, BEFORE_DEQUEUE, SELF -> expect(r, claims == 1, "callbacks=" + claims + " (expected 1)");
            case DURING, MANY_DURING, AT_FINISH -> {
                expect(r, claims == 2, "callbacks=" + claims + " (expected 2: one follow-up)");
                expect(r, requeues == 1, "requeues=" + requeues + " (expected exactly 1)");
            }
            case AFTER_FINISH -> {
                expect(r, claims == 2, "callbacks=" + claims + " (expected 2)");
                expect(r, requeues == 0, "requeues=" + requeues + " (expected 0: producer saw IDLE)");
            }
            case STAGING -> expect(r, r.stagedRan.get() == r.stagedExpected,
                    "staged ran " + r.stagedRan.get() + " of " + r.stagedExpected);
        }
        if (r.be != null) {
            long ph = r.be.getClass() != null ? ((NwAeBlockEntity) r.be).nw$sc().get() & SetChangedProtocol.PHASE : -1;
            expect(r, ph == SetChangedProtocol.IDLE, "final phase " + ph + " (expected IDLE)");
        }
        status = r.kind + (r.failures.isEmpty() ? ": PASS" : ": FAIL " + r.failures) + " claims=" + claims
                + " requeues=" + requeues + (r.kind == Kind.STAGING ? " staged=" + r.stagedRan.get() : "");
        Ae2Compat.LOG.info("[nestworld-compat-ae2] queue fixture {}", status);
    }

    private static void expect(Run r, boolean ok, String msg) {
        if (!ok) r.failures.add(msg);
    }

    private QueueFixtures() {
    }
}
