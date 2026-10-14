package net.nestworld.compat.ae2.glue;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Four-state {@code setChangedQueued} protocol (AE2-COMPAT-QUEUE-001.1 §1.3 + §4, owner-approved).
 * IDLE → QUEUED (producer, then publish) → RUNNING (consumer claim before setChanged reads state) →
 * IDLE, or RUNNING_DIRTY (foreign producer during the callback) → QUEUED + exactly one follow-up.
 * Invariant: every foreign saveChanges is covered by an already-pending callback or causes a future
 * callback to become pending before it returns. Self re-entry (this block entity's own running
 * callback, same generation) keeps vanilla suppression.
 */
public final class SetChangedProtocol {
    static final long IDLE = 0, QUEUED = 1, RUNNING = 2, DIRTY = 3, PHASE = 3, SEQ = 4;

    /** The callback currently running on this thread: block entity + its generation. */
    private record Ctx(Object be, int gen) {
    }

    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

    /** Test hooks (fixtures only; null in production). */
    public interface Hook {
        void at(String point, Object be);
    }

    public static volatile Hook hook;

    private static void point(String p, Object be) {
        Hook h = hook;
        if (h != null) h.at(p, be);
    }

    /**
     * Producer, replacing the read of {@code setChangedQueued}. Returns {@code true} when this change
     * is already covered (vanilla skips the enqueue), {@code false} when the caller must publish now.
     * Every outcome writes the word (seq++), so each producer happens-before the next claim.
     */
    public static boolean produce(NwAeBlockEntity be) {
        AtomicLong sc = be.nw$sc();
        Ctx ctx = CTX.get();
        boolean self = ctx != null && ctx.be() == be && ctx.gen() == be.nw$cbGen();
        for (;;) {
            long v = sc.get();
            long ph = v & PHASE, seq = (v & ~PHASE) + SEQ;
            long next;
            boolean covered;
            if (ph == IDLE) {
                next = seq | QUEUED;
                covered = false;
            } else if (ph == RUNNING && !self) {
                next = seq | DIRTY;
                covered = true;
            } else {                                      // QUEUED, DIRTY, or self during RUNNING
                next = seq | ph;
                covered = true;
            }
            if (sc.compareAndSet(v, next)) {
                if (ph == RUNNING && self) Ae2Compat.COUNTERS.setChangedSelfSuppressed.increment();
                if (ph == RUNNING && !self) Ae2Compat.COUNTERS.setChangedDirty.increment();
                point("P-LP", be);
                return covered;
            }
        }
    }

    /** Consumer: claim, run the callback body under this block entity's callback context, finish. */
    public static Object run(NwAeBlockEntity be, java.util.function.Supplier<Object> body) {
        AtomicLong sc = be.nw$sc();
        for (;;) {                                        // C-claim LP: before setChanged reads any state
            long v = sc.get();
            long ph = v & PHASE;
            if (ph != QUEUED) Ae2Compat.COUNTERS.setChangedUnexpectedClaim.increment();
            if (sc.compareAndSet(v, (v & ~PHASE) | RUNNING)) break;
        }
        int gen = be.nw$nextCbGen();
        point("C-claim", be);
        Ctx saved = CTX.get();
        CTX.set(new Ctx(be, gen));
        try {
            point("C-body", be);
            return body.get();
        } finally {
            CTX.set(saved);
            point("C-finish", be);
            for (;;) {                                    // C-finish LP
                long v = sc.get();
                long ph = v & PHASE;
                if (ph == DIRTY) {
                    if (sc.compareAndSet(v, (v & ~PHASE) | QUEUED)) {
                        Ae2Compat.COUNTERS.setChangedRequeue.increment();
                        be.nw$requeueSetChanged();        // exactly one follow-up
                        break;
                    }
                } else if (sc.compareAndSet(v, (v & ~PHASE) | IDLE)) {
                    break;
                }
            }
        }
    }

    private SetChangedProtocol() {
    }
}
