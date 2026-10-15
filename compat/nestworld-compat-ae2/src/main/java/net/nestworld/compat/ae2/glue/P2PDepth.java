package net.nestworld.compat.ae2.glue;

import java.util.Set;
import java.util.function.BiConsumer;

/**
 * P2P-ACCESS-DEPTH-001 (AE2-COMPAT-P2P-001 H2, owner-approved 2026-10-15). AE2's
 * {@code CapabilityP2PTunnelPart.accessDepth} is the recursion guard of the synchronous capability
 * call stack, but it lives on the tunnel object, so concurrent transfers on one tunnel corrupt it.
 * The depth is moved to (thread × tunnel instance): a per-instance ThreadLocal; the entry is removed
 * at depth 0. AE2's semantics are unchanged: depth 1 → real handler, depth > 1 → empty handler
 * (recursion), depth 0 at get → ISE, depth < 0 at close → ISE.
 */
public final class P2PDepth {
    /** Negative-control switch: rig/negp2p_shareddepth.py flips it to false (= AE2's shared int). */
    public static final boolean PROTECT = true;

    // fixture hooks (null unless a fixture runs)
    /** Called from CapabilityGuard.get with (tunnel, depth as seen) before AE2 decides. */
    public static volatile BiConsumer<Object, Integer> hookInGet;
    /** Tunnels on which every rejection is a false (cross-thread) one: no fixture recursion goes through them. */
    public static volatile Set<Object> falseRejectWatch;

    public static void rejected(Object tunnel) {
        Ae2Compat.COUNTERS.p2pDepthRejected.increment();
        Set<Object> w = falseRejectWatch;
        if (w != null && w.contains(tunnel)) Ae2Compat.COUNTERS.p2pDepthFalseReject.increment();
    }

    public static void stored(int value) {
        if (value < 0) Ae2Compat.COUNTERS.p2pDepthUnderflow.increment();
    }

    private P2PDepth() {
    }
}
