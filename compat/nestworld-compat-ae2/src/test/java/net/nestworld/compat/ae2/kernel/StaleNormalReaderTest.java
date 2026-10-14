package net.nestworld.compat.ae2.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The narrow window behind the final NORMAL check (AE2-COMPAT-DESIGN-001.3 §4 step 3): C reads
 * NORMAL, then stalls before announcing itself; meanwhile B publishes QUIESCING, the transition
 * completes (sum == 0 without C) and G work runs. When C resumes it must not mutate in NORMAL: it has
 * to back out and run under G. Deterministic via the AFTER_MODE_READ hook.
 */
class StaleNormalReaderTest {
    static DomainSpec spec(DomainLock d) {
        return new DomainSpec() {
            public boolean topology() { return false; }
            public List<DomainLock> resolve() { return List.of(d); }
            public boolean validate() { return true; }
        };
    }

    @Test
    void staleNormalReaderBacksOutAndRunsUnderG() throws Exception {
        for (int it = 0; it < 200; it++) {
            Ae2Kernel k = new Ae2Kernel(m -> { });
            DomainLock dB = new DomainLock(), dC = new DomainLock();
            CountDownLatch cReadNormal = new CountDownLatch(1), resumeC = new CountDownLatch(1);
            AtomicInteger cRanUnderG = new AtomicInteger(-1);
            AtomicReference<Throwable> fail = new AtomicReference<>();
            k.setHook(p -> {
                if (p == Ae2Kernel.Point.AFTER_MODE_READ && Thread.currentThread().getName().equals("C")
                        && cReadNormal.getCount() == 1) {
                    cReadNormal.countDown();
                    try {
                        resumeC.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            Thread c = new Thread(() -> {
                try {
                    k.guarded(spec(dC), () -> {
                        cRanUnderG.set(k.inDegradedSection() ? 1 : 0);
                        return null;
                    });
                } catch (Throwable t) {
                    fail.compareAndSet(null, t);
                }
            }, "C");
            c.start();
            assertTrue(cReadNormal.await(10, TimeUnit.SECONDS));     // C has read NORMAL, not yet counted
            Thread b = new Thread(() -> {
                try {
                    k.guarded(spec(dB), () -> {
                        k.admitUnknown(() -> { }, "test");
                        return null;
                    });
                } catch (Throwable t) {
                    fail.compareAndSet(null, t);
                }
            }, "B");
            b.start();
            b.join(10_000);                                           // B returns only after admission
            assertTrue(!b.isAlive());
            assertEquals(Ae2Kernel.Mode.DEGRADED, k.mode(), "transition completed while C stalled");
            resumeC.countDown();
            c.join(10_000);
            assertTrue(!c.isAlive());
            if (fail.get() != null) throw new AssertionError(fail.get());
            assertEquals(1, cRanUnderG.get(), "iteration " + it + ": stale NORMAL reader mutated outside G");
            assertEquals(1, k.metrics.backouts.sum(), "iteration " + it + ": C must back out once");
        }
    }
}
