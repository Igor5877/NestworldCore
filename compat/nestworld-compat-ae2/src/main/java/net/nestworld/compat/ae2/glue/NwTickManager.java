package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;
import appeng.me.service.helpers.TickTracker;

/** Duck interface added to {@code appeng.me.service.TickManagerService} (T1/T2, 001.5 §3.1). */
public interface NwTickManager {
    Grid nw$grid();

    void nw$setGrid(Grid grid);

    /** The identity token of the tick in progress during a T2 suspension, else {@code null}. */
    TickTracker nw$suspendedTt();
}
