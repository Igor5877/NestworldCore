package net.nestworld.region;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU Capacity Planner -- bounded startup benchmark, Phase 1 (ТЗ, 2026-08-30 §5). Measures
 * single-thread vs multi-thread compute throughput on a tight, allocation-free integer mixing
 * loop (xorshift-style -- deliberately not floating point or memory-bound, so it stresses raw
 * ALU/scheduling throughput rather than something one CPU might be atypically good/bad at) to
 * produce a RELATIVE {@code singleCoreScore}/{@code parallelScore}/{@code parallelEfficiency} --
 * not an absolute score, only meaningful for gauging how well THIS machine's cores scale (per
 * §5's "20 слабких ядер" vs "8 дуже швидких ядер" distinction).
 *
 * <p>Bounded (~300ms total by default: {@code nestworld.cpuBenchmarkDurationMs}, default 150,
 * run once single-threaded then once at {@code physicalCores} threads), disableable via {@code
 * -Dnestworld.cpuBenchmark=false} (falls back to a neutral estimate, no measurement taken), runs
 * exactly once per JVM session (result cached) -- per §5's "коротким; bounded; вимикаємим; не
 * запускатися автоматично повторно" requirements.
 */
public final class CpuBenchmark {
    public final long singleCoreScore;
    public final long parallelScore;
    public final double parallelEfficiency;
    public final boolean ran;

    private CpuBenchmark(long single, long parallel, boolean ran) {
        this.singleCoreScore = single;
        this.parallelScore = parallel;
        this.ran = ran;
        int physicalCores = Math.max(1, CpuHardwareInfo.detect().physicalCores);
        this.parallelEfficiency = single > 0
                ? (double) parallel / ((double) single * physicalCores) : 0.0;
    }

    private static volatile CpuBenchmark instance;

    public static CpuBenchmark run() {
        CpuBenchmark local = instance;
        if (local != null) return local;
        synchronized (CpuBenchmark.class) {
            if (instance != null) return instance;
            instance = doRun();
            return instance;
        }
    }

    private static CpuBenchmark doRun() {
        if (!Boolean.parseBoolean(System.getProperty("nestworld.cpuBenchmark", "true"))) {
            return new CpuBenchmark(0, 0, false);
        }
        long durationMs = Long.getLong("nestworld.cpuBenchmarkDurationMs", 150L);
        int physical = Math.max(1, CpuHardwareInfo.detect().physicalCores);
        long single = runWorkload(1, durationMs);
        long parallel = runWorkload(physical, durationMs);
        return new CpuBenchmark(single, parallel, true);
    }

    /** Runs {@code threads} concurrent workers for {@code durationMs}, each counting mixing-hash
     *  iterations; returns TOTAL iterations across all workers, so a single-thread run and a
     *  {@code threads}-way run are directly comparable (perfect scaling -> parallelScore ==
     *  threads x singleCoreScore). */
    private static long runWorkload(int threads, long durationMs) {
        AtomicLong total = new AtomicLong();
        CountDownLatch startGate = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                long deadline = System.nanoTime() + durationMs * 1_000_000L;
                long x = System.nanoTime() | 1L;
                long count = 0;
                while (System.nanoTime() < deadline) {
                    for (int u = 0; u < 16; u++) {
                        x ^= x << 13;
                        x ^= x >>> 7;
                        x ^= x << 17;
                    }
                    count += 16;
                }
                if (x == 0) count--; // touch x so the JIT can't dead-code-eliminate the loop
                total.addAndGet(count);
            }, "NestWorld-CpuBenchmark-" + i);
            workers[i].setDaemon(true);
        }
        for (Thread t : workers) t.start();
        startGate.countDown();
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        return total.get();
    }
}
