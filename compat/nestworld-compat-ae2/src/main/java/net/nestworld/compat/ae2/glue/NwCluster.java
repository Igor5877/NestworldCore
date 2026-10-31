package net.nestworld.compat.ae2.glue;

/** Duck interface on AE2 multiblock clusters (crafting CPU, QNB, spatial pylon): AE2-PIN-LEAK-001 D4a teardown claim. */
public interface NwCluster {
    /** CAS: true exactly once per cluster instance — the caller owns the teardown. */
    boolean nw$claimTeardown();

    boolean nw$teardownClaimed();
}
