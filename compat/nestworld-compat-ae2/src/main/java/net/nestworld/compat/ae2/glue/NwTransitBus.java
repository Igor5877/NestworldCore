package net.nestworld.compat.ae2.glue;

import appeng.api.networking.security.IActionSource;
import appeng.util.prioritylist.IPartitionList;

/** Duck interface added to {@code appeng.parts.automation.IOBusPart} (WP3-B bus transit). */
public interface NwTransitBus {
    BusTransit.BusState nw$transit();

    int nw$opsPerTick();

    int nw$availableSlots();

    IPartitionList nw$filter();

    IActionSource nw$source();
}
