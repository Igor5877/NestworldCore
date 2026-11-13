package net.nestworld.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * AE2-GRID-DOMAIN-001 A-PERF diagnostics (read-only): per SERVER TICK sums of MAIN tick time, domain-worker busy time,
 * MAIN waits on domain jobs and MAIN world-op drains, so the gate can show MAIN || worker overlap instead of
 * MAIN + waits + worker. Ticks without activity count as 0. Nothing here changes behaviour.
 */
public final class DomainTickMetrics {
    private DomainTickMetrics() {}

    /** One value per server tick (sum of everything recorded during that tick), last {@link #CAP} ticks. */
    static final class TickHist {
        private static final int CAP = 24_000;
        private final long[] ring = new long[CAP];
        private long count, curTick = Long.MIN_VALUE, cur;

        synchronized void add(long tick, long nanos) {
            roll(tick);
            cur += nanos;
        }

        /** Close every tick before {@code tick} (gaps = 0). */
        synchronized void roll(long tick) {
            if (tick == curTick) return;
            if (curTick != Long.MIN_VALUE && tick > curTick) {
                push(cur);
                for (long t = curTick + 1, gap = 0; t < tick && gap < 200; t++, gap++) push(0);
            }
            curTick = tick;
            cur = 0;
        }

        private void push(long v) { ring[(int) (count++ % CAP)] = v; }

        synchronized long[] snapshot() {
            int n = (int) Math.min(count, CAP);
            long[] a = Arrays.copyOf(ring, n);
            return a;
        }

        synchronized void reset() { count = 0; curTick = Long.MIN_VALUE; cur = 0; }
    }

    /** Bounded sample of durations (last 20 000) with exact count/total: p50/p95/p99/max for event timings. */
    static final class Reservoir {
        private final long[] ring = new long[20_000];
        private long count, total, max;

        synchronized void add(long nanos) {
            ring[(int) (count++ % ring.length)] = nanos;
            total += nanos;
            if (nanos > max) max = nanos;
        }

        synchronized long total() { return total; }

        synchronized String describe() {
            int n = (int) Math.min(count, ring.length);
            if (n == 0) return "n=0";
            long[] s = Arrays.copyOf(ring, n);
            Arrays.sort(s);
            return String.format("n=%d totalWaitMs=%.1f p50=%.3f p95=%.3f p99=%.3f max=%.3f ms", count, total / 1e6,
                    s[n / 2] / 1e6, s[Math.min(n - 1, (int) (n * 0.95))] / 1e6, s[Math.min(n - 1, (int) (n * 0.99))] / 1e6, max / 1e6);
        }
    }

    static final TickHist MAIN_TICK = new TickHist(), WORKER = new TickHist(), WORKER_OWNED = new TickHist(), MAIN_AWAIT = new TickHist(), WORLD_OPS = new TickHist();
    /** per world-op kind + block: {count, nanos} */
    static final Map<String, LongAdder[]> OP_COST = new ConcurrentHashMap<>();
    private static volatile MinecraftServer server;
    private static long mainTickStart;
    private static volatile boolean registered;

    static void install(MinecraftServer s) {
        server = s;
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.addListener(DomainTickMetrics::onServerTick);
        }
    }

    static long tick() {
        MinecraftServer s = server;
        return s == null ? 0 : s.getTickCount();
    }

    private static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            mainTickStart = System.nanoTime();
            long t = tick();
            MAIN_TICK.roll(t);
            WORKER.roll(t);
            WORKER_OWNED.roll(t);
            MAIN_AWAIT.roll(t);
            WORLD_OPS.roll(t);
        } else if (mainTickStart != 0) {
            MAIN_TICK.add(tick(), System.nanoTime() - mainTickStart);
        }
    }

    static void worker(long nanos, boolean owned) {
        long t = tick();
        WORKER.add(t, nanos);
        if (owned) WORKER_OWNED.add(t, nanos);
    }
    static void mainAwait(long nanos) { MAIN_AWAIT.add(tick(), nanos); }
    static void worldOps(long nanos) { WORLD_OPS.add(tick(), nanos); }

    static void opCost(String key, long nanos) {
        LongAdder[] c = OP_COST.computeIfAbsent(key, k -> new LongAdder[] { new LongAdder(), new LongAdder() });
        c[0].increment();
        c[1].add(nanos);
    }

    public static void reset() {
        MAIN_TICK.reset(); WORKER.reset(); WORKER_OWNED.reset(); MAIN_AWAIT.reset(); WORLD_OPS.reset();
        OP_COST.clear();
    }

    private static String pct(long[] a) {
        if (a.length == 0) return "n=0";
        long[] s = a.clone();
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) sum += v;
        return String.format("n=%d mean=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f ms", s.length, sum / s.length / 1e6,
                s[(int) (s.length * 0.50)] / 1e6, s[Math.min(s.length - 1, (int) (s.length * 0.95))] / 1e6,
                s[Math.min(s.length - 1, (int) (s.length * 0.99))] / 1e6, s[s.length - 1] / 1e6);
    }

    public static String status() {
        long[] main = MAIN_TICK.snapshot(), worker = WORKER.snapshot(), await = MAIN_AWAIT.snapshot(), ops = WORLD_OPS.snapshot();
        // per-tick alignment: all four rings are rolled together at tick START, so equal indices are the same tick
        int n = Math.min(Math.min(main.length, worker.length), Math.min(await.length, ops.length));
        double w = 0, a = 0, m = 0, hidden = 0;
        long[] mainOwn = new long[n], serialEq = new long[n];
        for (int i = 0; i < n; i++) {
            long mi = main[main.length - n + i], wi = worker[worker.length - n + i], ai = await[await.length - n + i];
            w += wi; a += ai; m += mi;
            hidden += Math.max(0, wi - ai);
            mainOwn[i] = Math.max(0, mi - ai);
            serialEq[i] = mainOwn[i] + wi;
        }
        StringBuilder sb = new StringBuilder("domainTick{");
        sb.append("\n  MAIN mspt     ").append(pct(main));
        sb.append("\n  MAIN own(-await) ").append(pct(mainOwn));
        sb.append("\n  worker/tick   ").append(pct(worker));
        sb.append("\n    of which owned (A) ").append(pct(WORKER_OWNED.snapshot()));
        sb.append("\n  MAIN await/tick ").append(pct(await));
        sb.append("\n  worldOps/tick ").append(pct(ops));
        sb.append("\n  serial-equivalent (own+worker) ").append(pct(serialEq));
        sb.append(String.format("%n  overlap: worker hidden from MAIN = %.1f%% of worker time (worker %.0f ms, MAIN await %.0f ms, MAIN %.0f ms over %d ticks)",
                w == 0 ? 0 : 100 * hidden / w, w / 1e6, a / 1e6, m / 1e6, n));
        var l = new ArrayList<>(OP_COST.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue()[1].sum(), x.getValue()[1].sum()));
        sb.append("\n  worldOp cost top (count, ms):");
        for (int i = 0; i < Math.min(10, l.size()); i++) {
            sb.append("\n    ").append(l.get(i).getValue()[0].sum()).append("  ")
                    .append(String.format("%.1f", l.get(i).getValue()[1].sum() / 1e6)).append("  ").append(l.get(i).getKey());
        }
        return sb.append("\n}").toString();
    }
}
