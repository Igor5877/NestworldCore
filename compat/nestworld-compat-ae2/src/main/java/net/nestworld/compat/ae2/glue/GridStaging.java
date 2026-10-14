package net.nestworld.compat.ae2.glue;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-grid staging of phase-deferred service mutations (GRID-PHASE, owner-approved):
 * provider rebuilds coalesced per (provider, kind); StackWatcher operations in FIFO order, no coalescing.
 * Drained by main under P(G) at the head of the grid's server-END phase (the drain cut).
 */
public final class GridStaging {
    /** key = provider identity + kind; value = the replay (re-invokes the wrapped AE2 method). */
    final Map<Object, Runnable> rebuilds = new ConcurrentHashMap<>();
    final Queue<Runnable> watcherOps = new ConcurrentLinkedQueue<>();

    record Key(Object provider, String kind) {
    }

    boolean isEmpty() {
        return rebuilds.isEmpty() && watcherOps.isEmpty();
    }
}
