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
public class UpdateTaskProgressMessage extends BaseS2CMessage {
	private final UUID islandId;
	private final long task;
	private final long progress;

	public UpdateTaskProgressMessage(FriendlyByteBuf buffer) {
		islandId = buffer.readUUID();
		task = buffer.readLong();
		progress = buffer.readVarLong();
	}

	public UpdateTaskProgressMessage(IslandData islandData, long task, long progress) {
		islandId = islandData.getTeamId();
		this.task = task;
		this.progress = progress;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.UPDATE_TASK_PROGRESS;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeUUID(islandId);
		buffer.writeLong(task);
		buffer.writeVarLong(progress);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		FTBQuestsNetClient.updateTaskProgress(islandId, task, progress);
	}
}