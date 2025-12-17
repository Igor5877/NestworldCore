package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.client.FTBQuestsNetClient;
import dev.ftb.mods.ftbquests.quest.IslandData;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * @author LatvianModder
 */
public class UpdateIslandDataMessage extends BaseS2CMessage {
	private final UUID islandId;
	private final String name;

	UpdateIslandDataMessage(FriendlyByteBuf buffer) {
		islandId = buffer.readUUID();
		name = buffer.readUtf(Short.MAX_VALUE);
	}

	public UpdateIslandDataMessage(IslandData data) {
		islandId = data.getTeamId();
		name = data.getName();
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.UPDATE_ISLAND_DATA;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeUUID(islandId);
		buffer.writeUtf(name, Short.MAX_VALUE);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		FTBQuestsNetClient.updateIslandData(islandId, name);
	}
}