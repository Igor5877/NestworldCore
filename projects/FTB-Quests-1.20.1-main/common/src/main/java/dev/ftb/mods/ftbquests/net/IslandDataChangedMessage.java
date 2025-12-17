package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.client.FTBQuestsNetClient;
import net.minecraft.network.FriendlyByteBuf;

/**
 * @author LatvianModder
 */
public class IslandDataChangedMessage extends BaseS2CMessage {
	private final IslandDataUpdate oldDataUpdate;
	private final IslandDataUpdate newDataUpdate;

	IslandDataChangedMessage(FriendlyByteBuf buffer) {
		oldDataUpdate = new IslandDataUpdate(buffer);
		newDataUpdate = new IslandDataUpdate(buffer);
	}

	public IslandDataChangedMessage(IslandDataUpdate oldData, IslandDataUpdate newData) {
		oldDataUpdate = oldData;
		newDataUpdate = newData;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.ISLAND_DATA_CHANGED;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		oldDataUpdate.write(buffer);
		newDataUpdate.write(buffer);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		FTBQuestsNetClient.islandDataChanged(oldDataUpdate, newDataUpdate);
	}
}