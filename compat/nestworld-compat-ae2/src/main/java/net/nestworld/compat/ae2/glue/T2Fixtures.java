package net.nestworld.compat.ae2.glue;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.mixin.TickManagerServiceAccessor;

/**
 * T2 acceptance fixtures (AE2-COMPAT-DESIGN-001.5 §3.4) on real AE2 classes: a controllable grid
 * tickable whose device callback runs on main inside a real T2 suspension, while a foreign thread acts
 * on the same node. Enabled only with {@code -Dnestworld.compatAe2.fixtures=true}.
 */
public final class T2Fixtures {
    public enum Kind { STALE, WAKE, ALERT, SELF }

    public static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.fixtures");
    private static final int SETTLE_CALLBACKS = 2, OBSERVE_TICKS = 60;

    private static volatile Run current;
    private static volatile String status = "idle";

    public static String status() {
        return status;
    }

    static final class Run implements IGridTickable {
        final Kind kind;
        final MinecraftServer server;
        IManagedGridNode managed;
        IGridNode node;
        ServerLevel level;
        final List<Integer> ticks = Collections.synchronizedList(new ArrayList<>());
        volatile int callbacks;
        volatile int triggerTick = -1;
        volatile String foreignResult = "-";
        volatile Object selfResult = "-";
        volatile TickTracker oldTt, newTt;
        long staleBefore;
        final List<String> failures = new ArrayList<>();

        Run(Kind kind, MinecraftServer server) {
            this.kind = kind;
            this.server = server;
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(1, 20, false, true);
        }

        TickManagerService tm() {
            return (TickManagerService) node.getGrid().getTickManager();
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode n, int ticksSinceLastCall) {
            int now = server.getTickCount();
            ticks.add(now);
            if (++callbacks != SETTLE_CALLBACKS + 1 || triggerTick >= 0) return TickRateModulation.SAME;
            triggerTick = now;
            if (Ae2Compat.KERNEL.inSection()) failures.add("device callback ran inside a guarded section");
            if (kind == Kind.SELF) {
                selfResult = tm().sleepDevice(n);         // self-call from the callback's own extent
                return TickRateModulation.IDLE;
            }
            Thread foreign = new Thread(this::foreign, "nw-t2-fixture-foreign");
            foreign.start();
            try {
                foreign.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (foreign.isAlive()) failures.add("foreign thread did not finish in 10 s (suspension not released?)");
            return switch (kind) {
                case WAKE -> TickRateModulation.SLEEP;    // the device decided to sleep before the wake
                case ALERT -> TickRateModulation.IDLE;
                default -> TickRateModulation.SAME;
            };
        }

        private void foreign() {
            TickManagerService tm = tm();
            TickManagerServiceAccessor acc = (TickManagerServiceAccessor) tm;
            switch (kind) {
                case STALE -> {
                    oldTt = acc.nw$awake().get(node);
                    tm.removeNode(node);
                    tm.addNode(node, null);
                    newTt = acc.nw$awake().get(node);
                    foreignResult = "removed+re-added";
                }
                case WAKE -> foreignResult = "wake=" + tm.wakeDevice(node);
                case ALERT -> foreignResult = "alert=" + tm.alertDevice(node);
                default -> {
                }
            }
        }
    }

    public static String start(Kind kind, ServerLevel level) {
        if (current != null) return "a fixture is running: " + status;
        Run r = new Run(kind, level.getServer());
        r.level = level;
        r.staleBefore = Ae2Compat.COUNTERS.t2StaleTracker.sum();
        r.managed = GridHelper.createManagedNode(r, (IGridNodeListener<Run>) (owner, n) -> { })
                .setInWorldNode(false)
                .addService(IGridTickable.class, r);
        r.managed.create(level, null);
        r.node = r.managed.getNode();
        current = r;
        status = kind + ": running";
        return status;
    }

    /** Main thread, end of every server tick. */
    public static void onServerTickEnd() {
        Run r = current;
        if (r == null || r.triggerTick < 0) return;
        if (r.server.getTickCount() < r.triggerTick + OBSERVE_TICKS) return;
        current = null;
        evaluate(r);
        r.managed.destroy();
        status = r.kind + (r.failures.isEmpty() ? ": PASS" : ": FAIL " + r.failures) + " foreign=" + r.foreignResult
                + " self=" + r.selfResult + " ticks=" + r.ticks;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] fixture {}", status);
    }

    private static void evaluate(Run r) {
        List<Integer> ticks;
        synchronized (r.ticks) {
            ticks = new ArrayList<>(r.ticks);
        }
        for (int i = 1; i < ticks.size(); i++) {
            if (ticks.get(i).equals(ticks.get(i - 1))) r.failures.add("node ticked twice in server tick " + ticks.get(i));
        }
        List<Integer> after = ticks.stream().filter(t -> t > r.triggerTick).toList();
        TickManagerServiceAccessor acc = (TickManagerServiceAccessor) r.tm();
        TickTracker awake = acc.nw$awake().get(r.node);
        PriorityQueue<TickTracker> q = acc.nw$upcomingTicks().get(r.node.getLevel());
        long inQueue = q == null ? 0 : q.stream().filter(t -> t.getNode() == r.node).count();
        switch (r.kind) {
            case STALE -> {
                if (r.newTt == null || r.newTt == r.oldTt) r.failures.add("re-add did not install a new tracker");
                if (awake != r.newTt) r.failures.add("awake tracker is not the new tracker");
                if (inQueue != 1) r.failures.add("queue holds " + inQueue + " trackers for the node (expected exactly 1)");
                if (q != null && q.contains(r.oldTt)) r.failures.add("stale tracker re-queued (zombie)");
                if (Ae2Compat.COUNTERS.t2StaleTracker.sum() - r.staleBefore < 1) r.failures.add("t2StaleTracker not counted");
                if (after.isEmpty()) r.failures.add("node never ticked again");
            }
            case WAKE -> {
                if (!r.foreignResult.equals("wake=true")) r.failures.add("foreign wake returned " + r.foreignResult);
                if (awake == null) r.failures.add("node asleep after a foreign wake (lost wake)");
                if (after.isEmpty()) r.failures.add("node not ticked after the wake (lost wake)");
                if (!after.isEmpty() && after.get(0) > r.triggerTick + 21) r.failures.add("first tick after wake only at " + after.get(0) + " (beyond max tick rate)");
            }
            case ALERT -> {
                if (!r.foreignResult.equals("alert=true")) r.failures.add("foreign alert returned " + r.foreignResult);
                if (after.isEmpty() || after.get(0) != r.triggerTick + 1) r.failures.add("not eligible exactly from the next tick: " + after);
            }
            case SELF -> {
                if (!Boolean.FALSE.equals(r.selfResult)) r.failures.add("self sleepDevice returned " + r.selfResult + " (vanilla: false)");
                if (awake == null) r.failures.add("self-call put the node to sleep");
                if (inQueue != 1) r.failures.add("queue holds " + inQueue + " trackers for the node");
                if (after.isEmpty()) r.failures.add("node not ticked after IDLE");
            }
        }
    }

    private T2Fixtures() {
    }
}
