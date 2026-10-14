package net.nestworld.compat.ae2.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Concurrency kernel of nestworld-compat-ae2 (docs/compat/AE2-COMPAT-DESIGN-001.3.md, approved).
 * Plain Java, no Minecraft or AE2 types: the Mixin glue maps AE2 entries onto {@link #guarded}.
 *
 * <p>Guarantees, each tied to a design section:
 * <ul>
 *   <li>One AE2 concurrency domain = the member grids' {@link DomainLock}s, held together; outermost
 *       entries take T0 (topology) first, then domains in ascending serial order, then validate
 *       (001 §3, 001.1 §4). Nested entries never block on a lower-order lock: they try once and
 *       otherwise refuse with no side effects (M2).</li>
 *   <li>NORMAL → QUIESCING → DEGRADED (001.3 §2–§4): in-flight NORMAL operations are counted in
 *       correctness-grade atomic stripes; the mode is validated after acquisition and before the
 *       first mutation; one CAS owner completes the transition and runs pending admissions under the
 *       global lock G; a detector's original call returns only after its admission ran.</li>
 *   <li>M1 (001.2 §2, 001.3 §6): notifications deferred inside a section are delivered in exact FIFO
 *       order on the same thread at the outermost exit, after the locks are released; delivery stops
 *       at the first exception, which propagates; the rest are discarded.</li>
 * </ul>
 */
public final class Ae2Kernel {
    public enum Mode { NORMAL, QUIESCING, DEGRADED }

    /** Test hook points (deterministic interleaving tests only; {@code null} in production). */
    public enum Point { AFTER_MODE_READ, AFTER_INCREMENT, BEFORE_MODE_CHECK, BEFORE_BODY, AFTER_DECREMENT, AFTER_PUBLISH, BEFORE_CAS, AFTER_CAS }

    @FunctionalInterface
    public interface Body<T> {
        T run() throws Throwable;
    }

    @FunctionalInterface
    public interface Hook {
        void at(Point point);
    }

    private static final int NORMAL = 0, QUIESCING = 1, DEGRADED = 2;
    private static final int STRIPES = 64;              // power of two
    private static final int PAD = 16;                  // ints per stripe: one 64-byte line each
    private static final int MAX_VALIDATE_RETRIES = 1000;

    private final AtomicIntegerArray inflight = new AtomicIntegerArray(STRIPES * PAD);
    private final AtomicInteger mode = new AtomicInteger(NORMAL);
    private final ReentrantLock t0 = new ReentrantLock();
    private final ReentrantLock g = new ReentrantLock();
    private final ConcurrentLinkedQueue<Admission> pending = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Section> sections = ThreadLocal.withInitial(Section::new);
    private final Consumer<String> diagnostics;
    public final Metrics metrics = new Metrics();
    private volatile Hook hook;

    public Ae2Kernel(Consumer<String> diagnostics) {
        this.diagnostics = diagnostics;
    }

    public Mode mode() {
        return Mode.values()[mode.get()];
    }

    /** Tests only. */
    public void setHook(Hook hook) {
        this.hook = hook;
    }

    /** True while the current thread is inside a guarded section (any depth). */
    public boolean inSection() {
        return sections.get().depth > 0;
    }

    /** True while the current thread runs under the global lock G (DEGRADED entry or admission). */
    public boolean inDegradedSection() {
        Section s = sections.get();
        return s.depth > 0 && s.degraded;
    }

    // ------------------------------------------------------------------ guarded execution

    /** Runs {@code body} as a guarded AE2 entry described by {@code spec}. */
    public <T> T guarded(DomainSpec spec, Body<T> body) throws Throwable {
        Section s = sections.get();
        if (s.depth > 0) {
            return nested(s, spec, body);
        }
        enterOuter(s, spec);
        s.depth = 1;
        T result = null;
        Throwable opEx = null;
        try {
            point(Point.BEFORE_BODY);
            result = body.run();
        } catch (Throwable t) {
            opEx = t;
        }
        s.depth = 0;
        exitOuter(s);
        Throwable admissionEx = null;
        for (Admission a : s.own) {
            if (a.failure != null && admissionEx == null) admissionEx = a.failure;
        }
        s.own.clear();
        ArrayList<Object> queue = s.deferred;
        s.deferred = new ArrayList<>();
        Throwable callbackEx = deliver(queue);
        Throwable opFirst = opEx != null ? opEx : admissionEx;
        if (callbackEx != null) {
            if (opFirst != null) {
                // 001.3 §6: the callback would have unwound first in vanilla; E_op is reported, not attached
                diagnostics.accept("AE2 guarded operation exception superseded by a deferred callback exception: " + opFirst);
            }
            throw callbackEx;
        }
        if (opFirst != null) throw opFirst;
        return result;
    }

    /** {@link #guarded} without checked exceptions (for Mixin wrappers). */
    public <T> T guardedUnchecked(DomainSpec spec, Body<T> body) {
        try {
            return guarded(spec, body);
        } catch (Throwable t) {
            throw Ae2Kernel.<RuntimeException>sneaky(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable t) throws E {
        throw (E) t;
    }

    // ------------------------------------------------------------------ M1 deferral

    /**
     * Defers a notification if the current thread is inside a guarded section (M1); returns
     * {@code false} when it is not, in which case the caller runs the notification inline.
     */
    public boolean defer(Runnable notification) {
        Section s = sections.get();
        if (s.depth == 0) return false;
        (s.redirect != null ? s.redirect : s.deferred).add(notification);
        metrics.deferred.increment();
        return true;
    }

    private Throwable deliver(List<Object> queue) {
        for (Object o : queue) {
            if (o instanceof Admission a) {
                Throwable t = deliver(a.notifications);
                if (t != null) return t;
            } else {
                try {
                    ((Runnable) o).run();
                } catch (Throwable t) {
                    metrics.callbackExceptions.increment();
                    return t;                     // stop at the first exception; the rest is discarded
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ M5c detection

    /**
     * Called from inside a guarded section when an unaudited participant must be admitted. In
     * DEGRADED (holding G) the continuation runs now; otherwise it is queued as the exact continuation,
     * the mode moves to QUIESCING, and the current outermost call returns only after it ran under G.
     */
    public void admitUnknown(Runnable continuation, String diagnostic) {
        Section s = sections.get();
        if (s.depth == 0) throw new IllegalStateException("admitUnknown outside a guarded section");
        diagnostics.accept(diagnostic);
        metrics.unknownDetected.increment();
        if (s.degraded) {
            continuation.run();
            metrics.admissions.increment();
            return;
        }
        Admission a = new Admission(continuation);
        (s.redirect != null ? s.redirect : s.deferred).add(a);   // FIFO position of its notifications
        s.own.add(a);
        pending.add(a);                                          // before publication (happens-before)
        mode.compareAndSet(NORMAL, QUIESCING);
        point(Point.AFTER_PUBLISH);
    }

    /** An unaudited path was reached: serialize from now on, fail this operation loud. */
    public UnauditedPathException unaudited(String what) {
        diagnostics.accept("AE2 unaudited path: " + what);
        metrics.unauditedPaths.increment();
        mode.compareAndSet(NORMAL, QUIESCING);
        return new UnauditedPathException(what);
    }

    // ------------------------------------------------------------------ outer entry / exit

    private void enterOuter(Section s, DomainSpec spec) {
        for (int round = 0; ; round++) {
            int m = mode.get();
            if (m == DEGRADED) {
                g.lock();
                s.degraded = true;
                metrics.degradedEntries.increment();
                return;
            }
            if (m == QUIESCING) {
                tryCompleteTransition(s);
                if (mode.get() == QUIESCING) park(round);
                continue;
            }
            point(Point.AFTER_MODE_READ);
            int stripe = stripeIndex();
            inflight.incrementAndGet(stripe * PAD);       // (1) announce in-flight
            s.stripe = stripe;
            point(Point.AFTER_INCREMENT);
            acquireOuter(s, spec);                         // (2) T0, ascending domains, validate
            point(Point.BEFORE_MODE_CHECK);
            if (mode.get() != NORMAL) {                    // (3) final NORMAL check before the first mutation
                releaseAll(s);
                inflight.decrementAndGet(stripe * PAD);
                s.stripe = -1;
                metrics.backouts.increment();
                tryCompleteTransition(s);
                continue;
            }
            s.degraded = false;
            metrics.normalEntries.increment();
            return;
        }
    }

    private void exitOuter(Section s) {
        if (s.degraded) {
            s.degraded = false;
            g.unlock();
            return;
        }
        releaseAll(s);
        inflight.decrementAndGet(s.stripe * PAD);          // (5)
        s.stripe = -1;
        point(Point.AFTER_DECREMENT);
        if (mode.get() != NORMAL) tryCompleteTransition(s);
        for (int round = 0; hasUndone(s.own); round++) {   // the original call waits for its admission
            tryCompleteTransition(s);
            if (hasUndone(s.own)) park(round);
        }
    }

    private static boolean hasUndone(List<Admission> own) {
        for (Admission a : own) if (!a.done) return true;
        return false;
    }

    private void acquireOuter(Section s, DomainSpec spec) {
        if (spec.topology()) {
            t0.lock();
            s.holdsT0 = true;
        }
        for (int attempt = 0; ; attempt++) {
            for (DomainLock d : sortedDistinct(spec.resolve())) {
                if (d.lock.isHeldByCurrentThread()) continue;
                if (!d.lock.tryLock()) {
                    metrics.contended.increment();
                    d.lock.lock();
                }
                s.held.add(d);
                metrics.acquisitions.increment();
            }
            if (spec.validate()) return;
            releaseDomainsDownTo(s, 0);
            metrics.retries.increment();
            if (attempt > MAX_VALIDATE_RETRIES) {
                releaseAll(s);
                throw new IllegalStateException("AE2 domain validation did not converge after " + attempt + " retries");
            }
            park(attempt);
        }
    }

    // ------------------------------------------------------------------ nested entry

    private <T> T nested(Section s, DomainSpec spec, Body<T> body) throws Throwable {
        int before = s.held.size();
        boolean tookT0 = false;
        if (!s.degraded) {
            if (spec.topology() && !s.holdsT0) {
                if (!s.held.isEmpty()) throw unaudited("T0 requested while AE2 domain locks are held");
                t0.lock();
                s.holdsT0 = true;
                tookT0 = true;
            }
            acquireNested(s, spec, before);
        }
        s.depth++;
        try {
            return body.run();
        } finally {
            s.depth--;
            releaseDomainsDownTo(s, before);
            if (tookT0) {
                s.holdsT0 = false;
                t0.unlock();
            }
        }
    }

    private void acquireNested(Section s, DomainSpec spec, int base) {
        for (int attempt = 0; ; attempt++) {
            boolean ok = true;
            for (DomainLock d : sortedDistinct(spec.resolve())) {
                if (d.lock.isHeldByCurrentThread()) continue;
                if (d.serial() > maxHeldSerial(s)) {
                    d.lock.lock();                          // ordered: safe to block
                    s.held.add(d);
                    metrics.acquisitions.increment();
                } else if (d.lock.tryLock()) {              // lower order: never block
                    s.held.add(d);
                    metrics.outOfOrderTries.increment();
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                releaseDomainsDownTo(s, base);
                metrics.refusals.increment();
                throw NestedRefusal.INSTANCE;
            }
            if (spec.validate()) return;
            releaseDomainsDownTo(s, base);
            metrics.retries.increment();
            if (attempt > MAX_VALIDATE_RETRIES) {
                metrics.refusals.increment();
                throw NestedRefusal.INSTANCE;
            }
        }
    }

    // ------------------------------------------------------------------ transition

    /** Completes QUIESCING → DEGRADED if no NORMAL operation is in flight; exactly one CAS owner. */
    void tryCompleteTransition(Section s) {
        if (mode.get() != QUIESCING) return;
        if (inflightSum() != 0) return;
        point(Point.BEFORE_CAS);
        if (!mode.compareAndSet(QUIESCING, DEGRADED)) return;
        metrics.transitions.increment();
        point(Point.AFTER_CAS);
        g.lock();
        boolean savedDegraded = s.degraded;
        int savedDepth = s.depth;
        ArrayList<Object> savedRedirect = s.redirect;
        s.degraded = true;
        s.depth = savedDepth + 1;                          // continuations run as nested DEGRADED code
        try {
            Admission a;
            while ((a = pending.poll()) != null) {
                s.redirect = a.notifications;
                try {
                    a.continuation.run();
                } catch (Throwable t) {
                    a.failure = t;
                } finally {
                    s.redirect = savedRedirect;
                    metrics.admissions.increment();
                    a.done = true;
                }
            }
        } finally {
            s.degraded = savedDegraded;
            s.depth = savedDepth;
            g.unlock();
        }
        diagnostics.accept("AE2 compat: switched to DEGRADED (global serialization) until restart");
    }

    int inflightSum() {
        int sum = 0;
        for (int i = 0; i < STRIPES; i++) sum += inflight.get(i * PAD);
        return sum;
    }

    // ------------------------------------------------------------------ helpers

    private void releaseAll(Section s) {
        releaseDomainsDownTo(s, 0);
        if (s.holdsT0) {
            s.holdsT0 = false;
            t0.unlock();
        }
    }

    private static void releaseDomainsDownTo(Section s, int size) {
        for (int i = s.held.size() - 1; i >= size; i--) {
            s.held.remove(i).lock.unlock();
        }
    }

    private static long maxHeldSerial(Section s) {
        long max = Long.MIN_VALUE;
        for (DomainLock d : s.held) if (d.serial() > max) max = d.serial();
        return max;
    }

    private static List<DomainLock> sortedDistinct(List<DomainLock> in) {
        if (in.size() <= 1) return in;
        ArrayList<DomainLock> out = new ArrayList<>(in);
        out.sort(null);
        for (int i = out.size() - 1; i > 0; i--) if (out.get(i) == out.get(i - 1)) out.remove(i);
        return out;
    }

    private static int stripeIndex() {
        return (int) (Thread.currentThread().getId() & (STRIPES - 1));
    }

    private static void park(int round) {
        LockSupport.parkNanos(Math.min(1_000_000L, 20_000L << Math.min(round, 6)));
    }

    private void point(Point p) {
        Hook h = hook;
        if (h != null) h.at(p);
    }

    // ------------------------------------------------------------------ state

    static final class Section {
        int depth;
        boolean degraded;
        int stripe = -1;
        boolean holdsT0;
        final ArrayList<DomainLock> held = new ArrayList<>();
        ArrayList<Object> deferred = new ArrayList<>();     // Runnable or Admission (FIFO marker)
        ArrayList<Object> redirect;                         // set while a continuation runs
        final ArrayList<Admission> own = new ArrayList<>();
    }

    static final class Admission {
        final Runnable continuation;
        final ArrayList<Object> notifications = new ArrayList<>();
        volatile boolean done;
        volatile Throwable failure;

        Admission(Runnable continuation) {
            this.continuation = continuation;
        }
    }

    public static final class Metrics {
        public final LongAdder normalEntries = new LongAdder();
        public final LongAdder degradedEntries = new LongAdder();
        public final LongAdder acquisitions = new LongAdder();
        public final LongAdder contended = new LongAdder();
        public final LongAdder retries = new LongAdder();
        public final LongAdder backouts = new LongAdder();
        public final LongAdder outOfOrderTries = new LongAdder();
        public final LongAdder refusals = new LongAdder();
        public final LongAdder deferred = new LongAdder();
        public final LongAdder callbackExceptions = new LongAdder();
        public final LongAdder unknownDetected = new LongAdder();
        public final LongAdder admissions = new LongAdder();
        public final LongAdder transitions = new LongAdder();
        public final LongAdder unauditedPaths = new LongAdder();
    }
}
