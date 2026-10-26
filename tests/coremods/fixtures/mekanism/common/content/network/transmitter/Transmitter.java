package mekanism.common.content.network.transmitter;

import mekanism.common.lib.transmitter.DynamicNetwork;

/** Minimal stand-in for the two Transmitter calls made by NetworkAcceptorCache.acceptorChanged/commit. */
public class Transmitter {
    public final int id;
    private final DynamicNetwork network;
    public Transmitter(int id, DynamicNetwork network) { this.id = id; this.network = network; }
    public boolean isValid() { return true; }
    public DynamicNetwork getTransmitterNetwork() { return network; }
}
