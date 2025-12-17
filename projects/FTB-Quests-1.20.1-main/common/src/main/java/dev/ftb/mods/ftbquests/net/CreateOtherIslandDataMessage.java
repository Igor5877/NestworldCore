package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.client.FTBQuestsNetClient;
import net.minecraft.network.FriendlyByteBuf;

/**
 * @author LatvianModder
 */
public class CreateOtherIslandDataMessage extends BaseS2CMessage {
	private final IslandDataUpdate dataUpdate;

	CreateOtherIslandDataMessage(FriendlyByteBuf buffer) {
		dataUpdate = new IslandDataUpdate(buffer);
	}

	public CreateOtherIslandDataMessage(IslandDataUpdate update) {
		dataUpdate = update;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.CREATE_OTHER_ISLAND_DATA;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		dataUpdate.write(buffer);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		FTBQuestsNetClient.createOtherIslandData(dataUpdate);
	}
}