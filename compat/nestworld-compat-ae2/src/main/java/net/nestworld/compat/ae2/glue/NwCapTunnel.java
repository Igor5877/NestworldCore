package net.nestworld.compat.ae2.glue;

/** Implemented by CapabilityP2PTunnelPartMixin (P2P-ACCESS-DEPTH-001). */
public interface NwCapTunnel {
    /** The recursion depth of the CURRENT thread on this tunnel (AE2's field when P2PDepth.PROTECT is off). */
    int nw$depth();

    void nw$depth(int value);

    /** Fixture: whether the current thread still holds a ThreadLocal entry for this tunnel (leak check). */
    boolean nw$hasEntry();
}
