package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.client.FTBQuestsClient;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.util.NetUtils;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * @author LatvianModder
 */
public class ChangeProgressMessage extends BaseC2SMessage {
	private final UUID islandId;
	private final ProgressChange progressChange;

	ChangeProgressMessage(FriendlyByteBuf buffer) {
		islandId = buffer.readUUID();
		progressChange = new ProgressChange(ServerQuestFile.INSTANCE, buffer);
	}

	public ChangeProgressMessage(UUID islandId, ProgressChange progressChange) {
		this.islandId = islandId;
		this.progressChange = progressChange;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.CHANGE_PROGRESS;
	}

	public static void sendToServer(IslandData island, QuestObjectBase object, Consumer<ProgressChange> progressChange) {
		if (island.isLocked()) {
			return;
		}

		ProgressChange change = new ProgressChange(island.getFile(), object, FTBQuestsClient.getClientPlayer().getUUID());
		progressChange.accept(change);
		new ChangeProgressMessage(island.getTeamId(), change).sendToServer();
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeUUID(islandId);
		progressChange.write(buffer);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		if (NetUtils.canEdit(context)) {
			progressChange.maybeForceProgress(islandId);
		}
	}
}