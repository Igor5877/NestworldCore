package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.me.GridNode;

/**
 * AE2-CROSS-REGION-TOPOLOGY-001 / R1-a scope 3 (owner decisions 2026-10-28 (3), (5), (8)): node-listener callbacks
 * fired inside a guarded section are recorded as intents (kernel M1 deferral: FIFO, delivered at the outermost exit
 * after the locks are released) and delivered through {@link HostDomainCallbackDelivery} (channel TOPOLOGY) in the
 * host's CURRENT block-entity execution domain — never under T0.
 */
public final class TopologyCallbacks {
    /** Kept for the race detector (looked up by name): same thread-local as the generic transport's. */
    public static final ThreadLocal<boolean[]> DELIVERING = HostDomainCallbackDelivery.DELIVERING;

    private TopologyCallbacks() {
    }

    /** Intent recording point: inside a section → deferred; outside → unchanged (inline). */
    public static boolean record(GridNode node, Runnable call) {
        if (!Ae2Compat.KERNEL.defer(() -> HostDomainCallbackDelivery.deliver(HostDomainCallbackDelivery.Channel.TOPOLOGY, node, "topology", call))) {
            return false;
        }
        COUNTERS.cbRecorded.increment();
        return true;
    }
}
