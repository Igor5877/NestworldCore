package mekanism.common.lib.transmitter;

import java.util.concurrent.atomic.LongAdder;

/** Stand-in: counts changed-network registrations made by NetworkAcceptorCache.acceptorChanged. */
public final class TransmitterNetworkRegistry {
    public static final LongAdder CHANGED = new LongAdder();
    public static void registerChangedNetwork(DynamicNetwork network) { CHANGED.increment(); }
}
