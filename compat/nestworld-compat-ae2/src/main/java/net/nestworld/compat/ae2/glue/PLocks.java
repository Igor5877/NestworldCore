package net.nestworld.compat.ae2.glue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * AE2-GRID-DOMAIN-MVP-001 cross-grid lock rule (owner 2026-11-12): a thread that already holds ANY P(G) never blocks for
 * another grid's P(G′) — tryLock only; a miss = "unavailable this tick" (0-result / neutral answer, no partial mutation,
 * no retry loop in the same tick; AE2 retries naturally next tick). Removes the ABBA cycle worker[P(G1)→P(G′)] ⇄
 * MAIN[P(G′)→P(G1)]. Per-thread count of held P(G) maintained at every acquisition site (phase, topology, ingress,
 * inventory guard).
 */
public final class PLocks {
    private PLocks() {}

    private static final ThreadLocal<int[]> HELD = ThreadLocal.withInitial(() -> new int[1]);
    static final LongAdder crossGridTryLockMiss = new LongAdder(), crossGridTryLockHit = new LongAdder(),
            crossGridBlockingMutation = new LongAdder();
    private static final Map<String, LongAdder> MISS_BY = new ConcurrentHashMap<>();

    public static void acquired() { HELD.get()[0]++; }
    public static void released() { HELD.get()[0]--; }
    public static boolean holdsAny() { return HELD.get()[0] > 0; }
    public static int depth() { return HELD.get()[0]; }

    static void miss(appeng.me.Grid to, Object what) {
        crossGridTryLockMiss.increment();
        appeng.me.Grid from = MainService.CURRENT.get();
        String k = (GridWorker.isWorkerThread() ? "worker" : Ae2Compat.onMainThread() ? "MAIN" : "other")
                + " from grid nodes=" + (from == null ? "?" : from.size()) + " → grid nodes=" + (to == null ? "?" : to.size())
                + " at " + (what == null ? "?" : what.getClass().getSimpleName());
        MISS_BY.computeIfAbsent(k, x -> new LongAdder()).increment();
    }

    static String status() {
        StringBuilder sb = new StringBuilder("crossGrid{tryLockMiss=" + crossGridTryLockMiss.sum() + " tryLockHit=" + crossGridTryLockHit.sum()
                + " unguardedMutation(must be 0)=" + crossGridBlockingMutation.sum() + "}");
        MISS_BY.forEach((k, v) -> sb.append("\n    miss ").append(v.sum()).append("  ").append(k));
        return sb.toString();
    }
}
