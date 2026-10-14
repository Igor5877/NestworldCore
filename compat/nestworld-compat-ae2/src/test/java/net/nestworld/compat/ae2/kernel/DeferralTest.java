package net.nestworld.compat.ae2.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M1 contract (AE2-COMPAT-DESIGN-001.2 §2, 001.3 §6). */
class DeferralTest {
    final DomainLock d1 = new DomainLock(), d2 = new DomainLock();

    static DomainSpec spec(DomainLock... ds) {
        return new DomainSpec() {
            public boolean topology() { return false; }
            public List<DomainLock> resolve() { return List.of(ds); }
            public boolean validate() { return true; }
        };
    }

    @Test
    void outsideSectionRunsInline() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        assertFalse(k.defer(() -> { }));
    }

    @Test
    void fifoAcrossNestingDeliveredAfterReleaseBeforeReturn() throws Throwable {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        List<String> log = new ArrayList<>();
        k.guarded(spec(d1), () -> {
            k.defer(() -> {
                log.add("n1 lockFree=" + !d1.isHeldByCurrentThread());
            });
            k.guarded(spec(d1, d2), () -> {                // reentrant + ordered nested
                k.defer(() -> log.add("n2"));
                return null;
            });
            k.defer(() -> log.add("n3"));
            log.add("body-end");
            return null;
        });
        log.add("returned");
        assertEquals(List.of("body-end", "n1 lockFree=true", "n2", "n3", "returned"), log);
    }

    @Test
    void noCoalescingOfRepeatedNotifications() throws Throwable {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        List<Integer> log = new ArrayList<>();
        k.guarded(spec(d1), () -> {
            for (int i = 0; i < 5; i++) k.defer(() -> log.add(7));
            return null;
        });
        assertEquals(List.of(7, 7, 7, 7, 7), log);
    }

    @Test
    void stopsAtFirstCallbackExceptionAndDiscardsRest() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        List<String> log = new ArrayList<>();
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException second = new RuntimeException("never");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> k.guarded(spec(d1), () -> {
            k.defer(() -> log.add("a"));
            k.defer(() -> { throw boom; });
            k.defer(() -> log.add("must-not-run"));
            k.defer(() -> { throw second; });
            return null;
        }));
        assertSame(boom, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(List.of("a"), log);
        assertEquals(1, k.metrics.callbackExceptions.sum());
    }

    @Test
    void operationExceptionPropagatesAfterDelivery() {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        List<String> log = new ArrayList<>();
        IllegalStateException op = new IllegalStateException("E_op");
        Throwable t = assertThrows(IllegalStateException.class, () -> k.guarded(spec(d1), () -> {
            k.defer(() -> log.add("before-op-throw"));
            throw op;
        }));
        assertSame(op, t);
        assertEquals(List.of("before-op-throw"), log);
    }

    @Test
    void callbackExceptionSupersedesOperationExceptionWhichIsReportedSeparately() {
        List<String> diag = new ArrayList<>();
        Ae2Kernel k = new Ae2Kernel(diag::add);
        RuntimeException cb = new RuntimeException("E_k");
        Throwable t = assertThrows(RuntimeException.class, () -> k.guarded(spec(d1), () -> {
            k.defer(() -> { throw cb; });
            throw new IllegalStateException("E_op");
        }));
        assertSame(cb, t);
        assertEquals(0, t.getSuppressed().length);
        assertTrue(diag.stream().anyMatch(s -> s.contains("E_op")));
    }

    @Test
    void callbackEnteringAnotherSectionIsDeliveredDepthFirst() throws Throwable {
        Ae2Kernel k = new Ae2Kernel(m -> { });
        List<String> log = new ArrayList<>();
        k.guarded(spec(d1), () -> {
            k.defer(() -> {
                log.add("k1");
                try {
                    k.guarded(spec(d2), () -> {                // outermost again during delivery
                        k.defer(() -> log.add("k1.inner"));
                        return null;
                    });
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            k.defer(() -> log.add("k2"));
            return null;
        });
        assertEquals(List.of("k1", "k1.inner", "k2"), log);
    }
}
