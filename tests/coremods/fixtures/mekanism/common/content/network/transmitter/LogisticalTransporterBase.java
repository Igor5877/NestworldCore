package mekanism.common.content.network.transmitter;

import mekanism.common.content.network.InventoryNetwork;
import net.minecraft.core.BlockPos;

public final class LogisticalTransporterBase {
    private final InventoryNetwork network;
    private final BlockPos position;

    public LogisticalTransporterBase(InventoryNetwork network, BlockPos position) {
        this.network = network;
        this.position = position;
    }

    public mekanism.common.lib.transmitter.DynamicNetwork getTransmitterNetwork() {
        return network;
    }

    public BlockPos getTilePos() {
        return position;
    }
}
