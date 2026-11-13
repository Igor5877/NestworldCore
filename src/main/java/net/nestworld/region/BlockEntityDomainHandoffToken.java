package net.nestworld.region;

import java.util.concurrent.atomic.AtomicInteger;

/** One invocation identity, retained while a native callback is rerouted between owners. */
final class BlockEntityDomainHandoffToken {
    private final AtomicInteger state = new AtomicInteger(); // pending, running, executed, cancelled
    boolean pending() { return state.get() == 0; }
    boolean start() { return state.compareAndSet(0, 1); }
    void finish() {
        if (!state.compareAndSet(1, 2)) throw new IllegalStateException("handoff invocation completed outside execution");
    }
    boolean cancel() { return state.compareAndSet(0, 3); }
}
