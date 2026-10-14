package net.nestworld.compat.ae2.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * T2 suspension (AE2-COMPAT-DESIGN-001.4 §2). The owner rejected keeping the outer operation in
 * flight while device code runs: device code that enters AE2 during QUIESCING would wait for an
 * in-flight count that its own suspended caller keeps above zero. These tests pin the approved
 * contract: full leave, device code at depth 0, fresh re-entry under the current mode.
 */
class SuspendTest {
    static final int ITERATIONS = Integer.getInteger("nw.iterations", 10_000) / 10;

    static DomainSpec spec(DomainLock d) {
        return TransitionScenarioTest.spec(d);
    }

    /**
     * The deadlock scenario. M holds D1 and suspends; its device code enters D2 while B's detection
     * holds the mode in QUIESCING. Expected every iteration: no hang, exactly one transition and one
     * admission, the device's entry and M's resumed tail both run under G, no NORMAL/G overlap
     * (a concurrent mutator keeps entering D4 throughout), no in-flight leak.
     */
    @Test
    void deviceReentryDuringQuiescingDoesNotDeadlock() throws Exception {
        for (int it = 0; it < ITERATIONS; it++) {
            runDeadlockScenario(it);
        }
    }

    private void runDeadlockScenario(int it) throws Exception {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        TransitionScenarioTest.Tracker tr = new TransitionScenarioTest.Tracker();
        DomainLock d1 = new DomainLock(), d2 = new DomainLock(), d3 = new DomainLock(), d4 = new DomainLock();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch suspendedNow = new CountDownLatch(1), bPublished = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        AtomicInteger deviceDegraded = new AtomicInteger(-1), resumedDegraded = new AtomicInteger(-1);
        AtomicInteger admissions = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();

        Thread m = new Thread(() -> TransitionScenarioTest.run(failure, () -> k.guarded(spec(d1), () -> {
            tr.mutate(k);
            k.suspended(spec(d1), () -> {
                if (k.inSection()) throw new AssertionError("device code ran inside a section");
                suspendedNow.countDown();
                assertTrue(bPublished.await(10, TimeUnit.SECONDS));
                k.guarded(spec(d2), () -> {             // device re-enters AE2 while QUIESCING
                    deviceDegraded.set(k.inDegradedSection() ? 1 : 0);
                    tr.mutate(k);
                    return null;
                });
                return null;
            });
            resumedDegraded.set(k.inDegradedSection() ? 1 : 0);
            tr.mutate(k);                                  // the continuation after resume
            return null;
        })), "M");
        Thread b = new Thread(() -> TransitionScenarioTest.run(failure, () -> k.guarded(spec(d3), () -> {
            tr.mutate(k);
            k.admitUnknown(() -> {
                tr.mutate(k);
                admissions.incrementAndGet();
            }, "test: unknown participant");
            bPublished.countDown();
            releaseB.await();
            return null;
        })), "B");
        Thread bg = new Thread(() -> TransitionScenarioTest.run(failure, () -> {
            while (!stop.get()) {
                k.guarded(spec(d4), () -> {
                    tr.mutate(k);
                    return null;
                });
            }
            return null;
        }), "bg");

        bg.start();
        m.start();
        assertTrue(suspendedNow.await(10, TimeUnit.SECONDS));
        b.start();
        assertTrue(bPublished.await(10, TimeUnit.SECONDS));
        if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(0, 200_000);
        releaseB.countDown();
        for (Thread t : new Thread[]{m, b}) {
            t.join(10_000);
            assertFalse(t.isAlive(), "iteration " + it + ": " + t.getName() + " hung (suspend/QUIESCING deadlock)");
        }
        stop.set(true);
        bg.join(10_000);
        assertFalse(bg.isAlive(), "iteration " + it + ": background mutator hung");
        if (failure.get() != null) throw new AssertionError("iteration " + it, failure.get());
        assertEquals(Ae2Kernel.Mode.DEGRADED, k.mode());
        assertEquals(1, k.metrics.transitions.sum(), "iteration " + it + ": transitions");
        assertEquals(1, admissions.get(), "iteration " + it + ": admissions");
        assertEquals(1, deviceDegraded.get(), "iteration " + it + ": device entry after publication must run under G");
        assertEquals(1, resumedDegraded.get(), "iteration " + it + ": resumed tail must re-enter under the current mode");
        assertEquals(0, tr.overlaps.get(), "iteration " + it + ": NORMAL/G overlap");
        assertEquals(0, k.inflightSum(), "iteration " + it + ": in-flight leak");
        assertEquals(1, k.metrics.suspensions.sum());
        assertEquals(1, k.metrics.resumes.sum());
    }

    /** M1 notifications deferred before the suspension are delivered before device code runs. */
    @Test
    void deferredDeliveredBeforeDeviceAndResumeIsAFreshEntry() throws Throwable {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        DomainLock d1 = new DomainLock();
        List<String> order = new ArrayList<>();
        long entriesBefore = k.metrics.normalEntries.sum();
        k.guarded(spec(d1), () -> {
            k.defer(() -> order.add("n1"));
            k.suspended(spec(d1), () -> {
                order.add("device");
                assertFalse(k.inSection());
                assertFalse(d1.lock.isHeldByCurrentThread(), "domain held during device code");
                assertEquals(0, k.inflightSum(), "suspended caller still counted in flight");
                return null;
            });
            assertTrue(k.inSection());
            assertTrue(d1.lock.isHeldByCurrentThread());
            k.defer(() -> order.add("n2"));
            return null;
        });
        assertEquals(List.of("n1", "device", "n2"), order);
        assertEquals(entriesBefore + 2, k.metrics.normalEntries.sum(), "resume must be a fresh outer entry");
        assertEquals(0, k.inflightSum());
    }

    /** Resume resolves the spec again: a domain that moved during device code is the one re-acquired. */
    @Test
    void resumeResolvesAndValidatesAgain() throws Throwable {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        DomainLock oldD = new DomainLock(), newD = new DomainLock();
        AtomicReference<DomainLock> current = new AtomicReference<>(oldD);
        AtomicInteger validations = new AtomicInteger();
        DomainSpec moving = new DomainSpec() {
            public boolean topology() { return false; }
            public List<DomainLock> resolve() { return List.of(current.get()); }
            public boolean validate() {
                validations.incrementAndGet();
                return true;
            }
        };
        k.guarded(moving, () -> {
            k.suspended(moving, () -> {
                current.set(newD);                         // e.g. the grid merged while suspended
                return null;
            });
            assertTrue(newD.lock.isHeldByCurrentThread());
            assertFalse(oldD.lock.isHeldByCurrentThread());
            return null;
        });
        assertEquals(2, validations.get());
        assertFalse(newD.lock.isLocked());
        assertFalse(oldD.lock.isLocked());
    }

    /** A delivery exception at the leave skips device code; the section is still re-entered and exits once. */
    @Test
    void deliveryExceptionSkipsDeviceButReenters() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        DomainLock d1 = new DomainLock();
        RuntimeException boom = new RuntimeException("callback");
        AtomicBoolean deviceRan = new AtomicBoolean();
        AtomicBoolean reentered = new AtomicBoolean();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> k.guarded(spec(d1), () -> {
            k.defer(() -> { throw boom; });
            try {
                k.suspended(spec(d1), () -> {
                    deviceRan.set(true);
                    return null;
                });
            } finally {
                reentered.set(k.inSection() && d1.lock.isHeldByCurrentThread());
            }
            return null;
        }));
        assertSame(boom, thrown);
        assertFalse(deviceRan.get());
        assertTrue(reentered.get(), "unwinding code must run guarded");
        assertFalse(d1.lock.isLocked());
        assertEquals(0, k.inflightSum());
        assertEquals(Ae2Kernel.Mode.NORMAL, k.mode());
    }

    /** A device exception propagates only after the section was re-entered. */
    @Test
    void deviceExceptionReentersThenPropagates() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        DomainLock d1 = new DomainLock();
        IllegalStateException boom = new IllegalStateException("device");
        AtomicBoolean reentered = new AtomicBoolean();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> k.guarded(spec(d1), () -> {
            try {
                k.suspended(spec(d1), () -> { throw boom; });
            } finally {
                reentered.set(k.inSection() && d1.lock.isHeldByCurrentThread());
            }
            return null;
        }));
        assertSame(boom, thrown);
        assertTrue(reentered.get());
        assertFalse(d1.lock.isLocked());
        assertEquals(0, k.inflightSum());
    }

    /** Suspension from a nested frame is not an audited shape: fail loud and serialize. */
    @Test
    void nestedSuspensionIsUnaudited() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        DomainLock d1 = new DomainLock(), d2 = new DomainLock();
        assertThrows(UnauditedPathException.class, () -> k.guarded(spec(d1), () -> k.guarded(spec(d2), () ->
                k.suspended(spec(d2), () -> null))));
        assertEquals(Ae2Kernel.Mode.DEGRADED, k.mode(), "unaudited path must switch to serialization");
        assertEquals(0, k.inflightSum());
        assertFalse(d1.lock.isLocked());
        assertFalse(d2.lock.isLocked());
    }

    /** Outside any section there is nothing to suspend. */
    @Test
    void suspensionOutsideSectionIsUnaudited() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        assertThrows(UnauditedPathException.class, () -> k.suspended(spec(new DomainLock()), () -> null));
    }
}
