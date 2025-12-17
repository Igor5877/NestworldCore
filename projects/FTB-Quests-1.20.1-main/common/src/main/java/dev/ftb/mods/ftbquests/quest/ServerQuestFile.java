package dev.ftb.mods.ftbquests.quest;

import com.mojang.util.UUIDTypeAdapter;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.FTBQuests;
import dev.ftb.mods.ftbquests.events.QuestProgressEventData;
import dev.ftb.mods.ftbquests.integration.PermissionsHelper;
import dev.ftb.mods.ftbquests.net.*;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import dev.ftb.mods.ftbquests.util.FileUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

public class ServerQuestFile extends BaseQuestFile {
	public static final LevelResource FTBQUESTS_DATA = new LevelResource("ftbquests");

	public static ServerQuestFile INSTANCE;

	public final MinecraftServer server;
	private boolean shouldSave;
	private boolean isLoading;
	private Path folder;
	private ServerPlayer currentPlayer = null;

	public ServerQuestFile(MinecraftServer s) {
		server = s;
		shouldSave = false;
		isLoading = false;

		int taskTypeId = 0;

		for (TaskType type : TaskTypes.TYPES.values()) {
			type.internalId = ++taskTypeId;
			taskTypeIds.put(type.internalId, type);
		}

		int rewardTypeId = 0;

		for (RewardType type : RewardTypes.TYPES.values()) {
			type.intId = ++rewardTypeId;
			rewardTypeIds.put(type.intId, type);
		}
	}

	public void load() {
		folder = Platform.getConfigFolder().resolve("ftbquests/quests");

		if (Files.exists(folder)) {
			FTBQuests.LOGGER.info("Loading quests from " + folder);
			isLoading = true;
			readDataFull(folder);
			isLoading = false;
		}

		Path path = server.getWorldPath(FTBQUESTS_DATA);

		if (Files.exists(path)) {
			try (Stream<Path> s = Files.list(path)) {
				s.filter(p -> p.getFileName().toString().contains("-") && p.getFileName().toString().endsWith(".snbt")).forEach(path1 -> {
					SNBTCompoundTag nbt = SNBT.read(path1);

					if (nbt != null) {
						try {
							UUID uuid = UUIDTypeAdapter.fromString(nbt.getString("uuid"));
							IslandData data = new IslandData(uuid, this);
							addData(data, true);
							data.deserializeNBT(nbt);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				});
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	@Override
	public Env getSide() {
		return Env.SERVER;
	}

	@Override
	public boolean isLoading() {
		return isLoading;
	}

	@Override
	public Path getFolder() {
		return folder;
	}

	@Override
	public void deleteObject(long id) {
		QuestObjectBase object = getBase(id);

		if (object != null) {
			object.deleteChildren();
			object.deleteSelf();
			refreshIDMap();
			markDirty();
			object.getPath().ifPresent(path -> FileUtils.delete(getFolder().resolve(path).toFile()));
		}

		new DeleteObjectResponseMessage(id).sendToAll(server);
	}

	@Override
	public void markDirty() {
		shouldSave = true;
	}

	public void saveNow() {
		if (shouldSave) {
			writeDataFull(getFolder());
			shouldSave = false;
		}

		getAllIslandData().forEach(IslandData::saveIfChanged);
	}

	public void unload() {
		saveNow();
		deleteChildren();
		deleteSelf();
	}

	public ServerPlayer getCurrentPlayer() {
		return currentPlayer;
	}

	public void withPlayerContext(ServerPlayer player, Runnable toDo) {
		currentPlayer = player;
		try {
			toDo.run();
		} finally {
			currentPlayer = null;
		}
	}

	@Override
	public boolean isPlayerOnTeam(Player player, IslandData islandData) {
		// Replaced with call to our cached provider
		UUID playerTeamId = NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(player.getUUID());
		return playerTeamId != null && playerTeamId.equals(islandData.getTeamId());
	}

	@Override
	public boolean moveChapterGroup(long id, boolean movingUp) {
		if (super.moveChapterGroup(id, movingUp)) {
			markDirty();
			clearCachedData();
			new MoveChapterGroupResponseMessage(id, movingUp).sendToAll(server);
			return true;
		}
		return false;
	}
}
