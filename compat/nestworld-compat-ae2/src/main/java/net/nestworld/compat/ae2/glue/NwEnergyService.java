package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;
import net.nestworld.compat.ae2.kernel.DomainLock;

/** Duck interface added to {@code appeng.me.service.EnergyService} (one domain lock per grid, 001 §3). */
public interface NwEnergyService {
    DomainLock nw$lock();

    Grid nw$grid();

    /** M2 refusal value for E4 reads: the value published at the last completed read (001.1 §5). */
    double nw$publishedStored();

    double nw$publishedMaxStored();
}
