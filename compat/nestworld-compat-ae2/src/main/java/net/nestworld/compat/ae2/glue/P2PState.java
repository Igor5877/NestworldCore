package net.nestworld.compat.ae2.glue;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * P2P-MAP-STATE-001 (AE2-COMPAT-P2P-001 H1, owner-approved 2026-10-15): compatibility-side
 * synchronization metadata of ONE P2PService (one grid). AE2's maps stay where they are; this guard
 * defines the linearization point of every reader and writer of {@code inputs} / {@code outputs} /
 * a tunnel's {@code freq}.
 *
 * <ul>
 *   <li>Readers take the read lock, copy a stable snapshot, release, then work on the snapshot.</li>
 *   <li>Writers (addNode, removeNode, updateFreq, allocateAndBind) mutate under the write lock and
 *       bump {@link #generation}. AE2's tunnel callbacks ({@code updateTunnel}) and the host's
 *       markForSave/markForUpdate are captured under the lock and run AFTER the outermost writer
 *       released it, in AE2's order.</li>
 *   <li>Leaf lock: nothing is acquired while it is held (no capability, no energy domain, no T2, no
 *       world call, no part callback).</li>
 * </ul>
 * One guard per grid: independent grids never serialize on each other.
 */
public final class P2PState {
    /** Negative-control switch: rig/negp2p_noguard.py flips it to false (= vanilla AE2 behaviour). */
    public static final boolean PROTECT = true;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public volatile long generation;
    private static final ThreadLocal<ArrayList<Runnable>> DEFERRED = new ThreadLocal<>();

    // ---------------------------------------------------------------- fixture hooks (null unless a fixture runs)
    /** Inside updateFreq, after the tunnel left the maps and before it is re-inserted. */
    public static volatile Runnable hookAtSetFrequency;
    /** At the shift-activate call site, after newFrequency() returned the candidate, before the bind. */
    public static volatile Runnable hookAfterCandidate;
    /** Per-thread forced newFrequency() result, consumed by the first call on that thread. */
    public static final ThreadLocal<Short> FORCED_CANDIDATE = new ThreadLocal<>();

    public <T> T read(Supplier<T> body) {
        if (!PROTECT) return body.get();
        lock.readLock().lock();
        try {
            return body.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean writeHeldByCurrentThread() {
        return lock.isWriteLockedByCurrentThread();
    }

    public <T> T write(Supplier<T> body) {
        if (!PROTECT) return body.get();
        boolean outermost = !lock.isWriteLockedByCurrentThread();
        ArrayList<Runnable> prev = null, mine = null;
        if (outermost) {
            prev = DEFERRED.get();
            mine = new ArrayList<>();
            DEFERRED.set(mine);
        }
        T result;
        Throwable failure = null;
        lock.writeLock().lock();
        try {
            result = body.get();
            generation++;
        } catch (Throwable t) {
            failure = t;
            result = null;
        } finally {
            lock.writeLock().unlock();
            if (outermost) DEFERRED.set(prev);
        }
        Ae2Compat.COUNTERS.p2pWrites.increment();
        if (outermost) {
            // AE2 would already have run the callbacks queued before a failure; run them, then rethrow.
            for (Runnable r : mine) {
                Ae2Compat.COUNTERS.p2pDeferredCallbacks.increment();
                r.run();
            }
        }
        if (failure instanceof RuntimeException re) throw re;
        if (failure instanceof Error e) throw e;
        if (failure != null) throw new IllegalStateException(failure);
        return result;
    }

    /** Queue {@code r} for after the outermost writer released the lock; false = not inside a writer. */
    public static boolean defer(Runnable r) {
        ArrayList<Runnable> list = DEFERRED.get();
        if (list == null) return false;
        list.add(r);
        return true;
    }
}
