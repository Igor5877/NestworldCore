package net.nestworld.compat.ae2.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

/**
 * Randomized deadlock / exclusion stress: many threads, overlapping domain sets, nested ordered and
 * out-of-order acquisitions, topology entries, and (in the second test) one transition to DEGRADED
 * in the middle of the run. Every run must finish (no deadlock), each domain must never have two
 * concurrent mutators, and no NORMAL mutation may overlap a G mutation.
 */
class StressTest {
    static final int THREADS = 8, DOMAINS = 12, OPS = Integer.getInteger("nw.stressOps", 20_000);

    final DomainLock[] locks = new DomainLock[DOMAINS];
    final AtomicReferenceArray<Thread> owners = new AtomicReferenceArray<>(DOMAINS);
    final AtomicInteger exclusionViolations = new AtomicInteger();
    final AtomicInteger normalActive = new AtomicInteger(), gActive = new AtomicInteger();
    final AtomicInteger overlap = new AtomicInteger();

    StressTest() {
        for (int i = 0; i < DOMAINS; i++) locks[i] = new DomainLock();
    }

    DomainSpec spec(boolean topology, int... idx) {
        List<DomainLock> l = new ArrayList<>();
        for (int i : idx) l.add(locks[i]);
        return new DomainSpec() {
            public boolean topology() { return topology; }
            public List<DomainLock> resolve() { return l; }
            public boolean validate() { return true; }
        };
    }

    final java.util.concurrent.atomic.AtomicBoolean gStarted = new java.util.concurrent.atomic.AtomicBoolean();

    void mutate(Ae2Kernel k, int... idx) {
        boolean g = k.inDegradedSection();
        if (g) gStarted.set(true);
        else if (gStarted.get()) overlap.incrementAndGet();   // NORMAL mutation after G began
        AtomicInteger mine = g ? gActive : normalActive, other = g ? normalActive : gActive;
        mine.incrementAndGet();
        if (other.get() != 0) overlap.incrementAndGet();
        if (!g) {                      // under G the whole surface is serialized; check domains in NORMAL
            for (int i : idx) {
                Thread prev = owners.getAndSet(i, Thread.currentThread());
                if (prev != null && prev != Thread.currentThread()) exclusionViolations.incrementAndGet();
            }
            for (int s = 0; s < 10; s++) Thread.onSpinWait();
            for (int i : idx) owners.compareAndSet(i, Thread.currentThread(), null);
        }
        mine.decrementAndGet();
    }

    int[] pick(ThreadLocalRandom r) {
        int n = 1 + r.nextInt(3);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = r.nextInt(DOMAINS);
        return out;
    }

    void runStress(boolean withTransition) throws Exception {
        gStarted.set(false);
        Ae2Kernel k = new Ae2Kernel(m -> { });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger admitted = new AtomicInteger();
        Thread[] ts = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            ts[t] = new Thread(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                try {
                    for (int op = 0; op < OPS; op++) {
                        final boolean transitionHere = withTransition && tid == 0 && op == OPS / 3;
                        int[] outer = pick(r);
                        boolean topo = r.nextInt(50) == 0;
                        k.guarded(spec(topo, outer), () -> {
                            mutate(k, outer);
                            if (r.nextInt(4) == 0) {
                                int[] inner = pick(r);
                                try {
                                    k.guarded(spec(false, inner), () -> {
                                        mutate(k, inner);
                                        return null;
                                    });
                                } catch (NestedRefusal refused) {
                                    // M2: nothing applied by the nested entry
                                }
                            }
                            if (transitionHere) {
                                k.admitUnknown(() -> {
                                    mutate(k);
                                    admitted.incrementAndGet();
                                }, "stress: unknown participant");
                            }
                            if (r.nextInt(3) == 0) k.defer(() -> { });
                            return null;
                        });
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "stress-" + t);
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) {
            t.join(120_000);
            assertTrue(!t.isAlive(), "deadlock: " + t.getName() + " still running");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(0, exclusionViolations.get(), "per-domain exclusion");
        assertEquals(0, overlap.get(), "NORMAL/G overlap");
        assertEquals(0, k.inflightSum(), "in-flight leak");
        if (withTransition) {
            assertEquals(Ae2Kernel.Mode.DEGRADED, k.mode());
            assertEquals(1, k.metrics.transitions.sum());
            assertEquals(1, admitted.get());
        } else {
            assertEquals(Ae2Kernel.Mode.NORMAL, k.mode());
        }
    }

    @Test
    void noDeadlockNoExclusionViolation() throws Exception {
        runStress(false);
    }

    @Test
    void transitionUnderLoad() throws Exception {
        for (int i = 0; i < 20; i++) runStress(true);
    }
}
