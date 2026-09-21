package mekanism.common.content.network;

import java.util.UUID;

public final class InventoryNetwork extends mekanism.common.lib.transmitter.DynamicNetwork {
    private final UUID uuid;

    public InventoryNetwork(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUUID() {
        return uuid;
    }
}
