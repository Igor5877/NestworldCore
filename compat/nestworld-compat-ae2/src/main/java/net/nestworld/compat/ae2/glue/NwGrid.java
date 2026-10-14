package net.nestworld.compat.ae2.glue;

import java.util.concurrent.locks.ReentrantLock;

/** Duck interface added to {@code appeng.me.Grid}: the phase/topology gate P(G) and its staging. */
public interface NwGrid {
    ReentrantLock nw$p();

    /** > 0 while main runs a Grid.on*Tick phase of this grid (main writes only). */
    int nw$phaseDepth();

    void nw$phaseDepth(int depth);

    GridStaging nw$staging();
}
