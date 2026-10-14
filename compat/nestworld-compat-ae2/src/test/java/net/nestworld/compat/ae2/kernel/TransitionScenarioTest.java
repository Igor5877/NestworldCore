package net.nestworld.compat.ae2.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The owner's mandatory deterministic scenario (AE2-COMPAT-DESIGN-001.3 §8):
 * A holds D1; B detects an unknown participant under NORMAL; C attempts a new NORMAL entry; A exits;
 * B/C race to complete. Expected every iteration: exactly one DEGRADED transition, exactly one
 * admission, zero NORMAL/G mutation overlap, and B's original call returns only after admission.
 */
class TransitionScenarioTest {
    static final int ITERATIONS = Integer.getInteger("nw.iterations", 10_000);

    static DomainSpec spec(DomainLock d) {
        return new DomainSpec() {
            public boolean topology() { return false; }
            public List<DomainLock> resolve() { return List.of(d); }
            public boolean validate() { return true; }
        };
    }

    /** Overlap tracker: NORMAL and G mutators must never be active at the same time. */
    static final class Tracker {
        final AtomicInteger normalActive = new AtomicInteger();
        final AtomicInteger gActive = new AtomicInteger();
        final AtomicInteger overlaps = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean gStarted = new java.util.concurrent.atomic.AtomicBoolean();

        void mutate(Ae2Kernel k) {
            boolean g = k.inDegradedSection();
            if (g) gStarted.set(true);
            else if (gStarted.get()) overlaps.incrementAndGet();   // NORMAL mutation after G began
            AtomicInteger mine = g ? gActive : normalActive;
            AtomicInteger other = g ? normalActive : gActive;
            mine.incrementAndGet();
            if (other.get() != 0) overlaps.incrementAndGet();
            for (int i = 0; i < 20; i++) Thread.onSpinWait();
            if (other.get() != 0) overlaps.incrementAndGet();
            mine.decrementAndGet();
        }
    }

    @Test
    void mandatoryScenario() throws Exception {
        for (int it = 0; it < ITERATIONS; it++) {
            runOnce(it);
        }
    }

    private void runOnce(int it) throws Exception {
        Ae2Kernel k = new Ae2Kernel(msg -> { });
        Tracker tr = new Tracker();
        DomainLock d1 = new DomainLock(), d2 = new DomainLock(), d3 = new DomainLock();
        AtomicLong seq = new AtomicLong();
        AtomicLong admittedAt = new AtomicLong(-1), bReturnedAt = new AtomicLong(-1);
        AtomicInteger admissionRuns = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger cDegraded = new AtomicInteger(-1);
        CountDownLatch aInside = new CountDownLatch(1), releaseA = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1), startC = new CountDownLatch(1);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean cBeforePublish = rnd.nextBoolean();

        Thread a = new Thread(() -> run(failure, () -> k.guarded(spec(d1), () -> {
            tr.mutate(k);
            aInside.countDown();
            releaseA.await();
            tr.mutate(k);
            return null;
        })), "A");
        Thread b = new Thread(() -> run(failure, () -> {
            k.guarded(spec(d2), () -> {
                tr.mutate(k);
                k.admitUnknown(() -> {
                    // must run under G with no NORMAL mutator active
                    if (!k.inDegradedSection()) failure.compareAndSet(null, new AssertionError("admission not under G"));
                    tr.mutate(k);
                    admissionRuns.incrementAndGet();
                    admittedAt.set(seq.incrementAndGet());
                }, "test: unknown participant");
                published.countDown();
                return null;
            });
            bReturnedAt.set(seq.incrementAndGet());
            return null;
        }), "B");
        Thread c = new Thread(() -> run(failure, () -> {
            startC.await();
            k.guarded(spec(d3), () -> {
                cDegraded.set(k.inDegradedSection() ? 1 : 0);
                tr.mutate(k);
                return null;
            });
            return null;
        }), "C");

        a.start();
        assertTrue(aInside.await(10, TimeUnit.SECONDS));
        c.start();
        if (cBeforePublish) startC.countDown();          // C may race the publication itself
        b.start();
        assertTrue(published.await(10, TimeUnit.SECONDS));
        if (!cBeforePublish) startC.countDown();
        if (rnd.nextBoolean()) Thread.yield();
        releaseA.countDown();                            // A exits; B/C (and A) race to complete
        for (Thread t : new Thread[]{a, b, c}) {
            t.join(30_000);
            assertTrue(!t.isAlive(), "iteration " + it + ": thread " + t.getName() + " hung");
        }
        if (failure.get() != null) throw new AssertionError("iteration " + it, failure.get());
        assertEquals(Ae2Kernel.Mode.DEGRADED, k.mode(), "iteration " + it);
        assertEquals(1, k.metrics.transitions.sum(), "iteration " + it + ": transitions");
        assertEquals(1, admissionRuns.get(), "iteration " + it + ": admissions");
        assertEquals(1, k.metrics.admissions.sum(), "iteration " + it + ": admission metric");
        assertEquals(0, tr.overlaps.get(), "iteration " + it + ": NORMAL/G overlap");
        assertTrue(bReturnedAt.get() > admittedAt.get() && admittedAt.get() > 0,
                "iteration " + it + ": B returned before its admission");
        if (!cBeforePublish) assertEquals(1, cDegraded.get(), "iteration " + it + ": C entered after publication must run under G");
        assertEquals(0, k.inflightSum(), "iteration " + it + ": in-flight leak");
    }

    interface Action {
        Object run() throws Throwable;
    }

    static void run(AtomicReference<Throwable> failure, Action a) {
        try {
            a.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
