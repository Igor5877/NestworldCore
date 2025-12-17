package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseS2CMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.FTBQuestsNetClient;
import dev.ftb.mods.ftbquests.quest.IslandData;
import net.minecraft.network.FriendlyByteBuf;

public class SyncIslandDataMessage extends BaseS2CMessage {
	private final boolean self;
	private final IslandData islandData;

	SyncIslandDataMessage(FriendlyByteBuf buffer) {
		self = buffer.readBoolean();
		islandData = new IslandData(buffer.readUUID(), ClientQuestFile.INSTANCE);
		islandData.read(buffer, self);
	}

	public SyncIslandDataMessage(IslandData islandData, boolean self) {
		this.self = self;
		this.islandData = islandData;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.SYNC_ISLAND_DATA;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeBoolean(self);
		buffer.writeUUID(islandData.getTeamId()); // Keep getTeamId for now to avoid cascading changes
		islandData.write(buffer, self);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		FTBQuestsNetClient.syncIslandData(self, islandData);
	}
}