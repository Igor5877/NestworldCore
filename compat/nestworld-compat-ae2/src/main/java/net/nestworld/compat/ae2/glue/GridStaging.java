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
    /** key = provider IDENTITY + kind (review 1.2: never the provider's equals/hashCode). */
    final Map<Key, Item> rebuilds = new ConcurrentHashMap<>();
    final Queue<Item> watcherOps = new ConcurrentLinkedQueue<>();

    /** A staged operation and the object it belongs to (the replay owner, review 1.3). */
    record Item(Object owner, Runnable replay) {
    }

    static final class Key {
        final Object provider;
        final String kind;

        Key(Object provider, String kind) {
            this.provider = provider;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.provider == provider && k.kind.equals(kind);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(provider) + kind.hashCode();
        }
    }

    boolean isEmpty() {
        return rebuilds.isEmpty() && watcherOps.isEmpty();
    }
}
