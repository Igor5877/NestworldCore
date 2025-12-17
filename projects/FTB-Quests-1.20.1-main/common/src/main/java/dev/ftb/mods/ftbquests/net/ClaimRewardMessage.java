package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * @author LatvianModder
 */
public class ClaimRewardMessage extends BaseC2SMessage {
	private final long id;
	private final boolean notify;

	ClaimRewardMessage(FriendlyByteBuf buffer) {
		id = buffer.readLong();
		notify = buffer.readBoolean();
	}

	public ClaimRewardMessage(long i, boolean n) {
		id = i;
		notify = n;
	}

	@Override
	public MessageType getType() {
		return FTBQuestsNetHandler.CLAIM_REWARD;
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(id);
		buffer.writeBoolean(notify);
	}

	@Override
	public void handle(NetworkManager.PacketContext context) {
		ServerPlayer player = (ServerPlayer) context.getPlayer();
		Reward reward = ServerQuestFile.INSTANCE.getReward(id);

		if (reward == null) {
			return;
		}

		// Use the already-modified getOrCreateIslandData to get the relevant data object (team or player)
		IslandData islandData = ServerQuestFile.INSTANCE.getOrCreateIslandData(player);

		if (islandData != null && islandData.isCompleted(reward.getQuest())) {
			// MODIFIED: Use the QuestTeamBridge for an authoritative and synchronous check
			boolean isMember = com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().isPlayerOnTeam(player.getUUID(), islandData.getTeamId());

			// ADDED: Crucial check to ensure the player is on THEIR OWN island server when claiming rewards.
			boolean isCorrectIsland = !com.skyblock.dynamic.SkyBlockMod.isIslandServer() ||
					java.util.UUID.fromString(com.skyblock.dynamic.SkyBlockMod.getOwnerUuid()).equals(islandData.getTeamId());

			if (isMember && isCorrectIsland) {
				islandData.claimReward(player, reward, notify);
			}
		}
	}
}