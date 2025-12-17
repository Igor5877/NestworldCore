package dev.ftb.mods.ftbquests;

import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.*;
import dev.architectury.hooks.level.entity.PlayerHooks;
import dev.ftb.mods.ftbquests.block.FTBQuestsBlocks;
import dev.ftb.mods.ftbquests.block.entity.FTBQuestsBlockEntities;
import dev.ftb.mods.ftbquests.command.FTBQuestsCommands;
import dev.ftb.mods.ftbquests.config.FTBQuestsTeamConfig;
import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import dev.ftb.mods.ftbquests.item.FTBQuestsItems;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.team.TeamManager;
import dev.ftb.mods.ftbquests.quest.task.DimensionTask;
import dev.ftb.mods.ftbquests.quest.task.KillTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.integration.PermissionsHelper;
import dev.ftb.mods.ftbquests.net.SyncEditorPermissionMessage;
import dev.ftb.mods.ftbquests.net.SyncIslandDataMessage;
import dev.ftb.mods.ftbquests.net.SyncQuestsMessage;
import dev.ftb.mods.ftbquests.util.DeferredInventoryDetection;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

import java.util.List;

public enum FTBQuestsEventHandler {
	INSTANCE;

	private List<KillTask> killTasks = null;
	private List<Task> autoSubmitTasks = null;

	void init() {
		LifecycleEvent.SERVER_BEFORE_START.register(this::serverAboutToStart);
		CommandRegistrationEvent.EVENT.register(this::registerCommands);
		LifecycleEvent.SERVER_STARTED.register(this::serverStarted);
		LifecycleEvent.SERVER_STOPPING.register(this::serverStopped);
		LifecycleEvent.SERVER_LEVEL_SAVE.register(this::worldSaved);
		FTBQuestsBlocks.register();
		FTBQuestsItems.register();
		FTBQuestsBlockEntities.register();
		ClearFileCacheEvent.EVENT.register(this::fileCacheClear);
		EntityEvent.LIVING_DEATH.register(this::playerKill);
		TickEvent.PLAYER_POST.register(this::playerTick);
		PlayerEvent.CRAFT_ITEM.register(this::itemCrafted);
		PlayerEvent.SMELT_ITEM.register(this::itemSmelted);
		PlayerEvent.PLAYER_CLONE.register(this::cloned);
		PlayerEvent.CHANGE_DIMENSION.register(this::changedDimension);
		PlayerEvent.OPEN_MENU.register(this::containerOpened);
		PlayerEvent.PLAYER_JOIN.register(this::playerLoggedIn);
		TickEvent.SERVER_POST.register(DeferredInventoryDetection::tick);
	}

	private void playerLoggedIn(ServerPlayer player) {
		ServerQuestFile file = ServerQuestFile.INSTANCE;
		if (file != null) {
			// Sync the entire quest file structure
			new SyncQuestsMessage(file).sendTo(player);

			// Sync the player's permission level
			new SyncEditorPermissionMessage(PermissionsHelper.hasEditorPermission(player, false)).sendTo(player);

			// Sync ONLY the player's own team/personal data initially.
			// This is critical for preventing the "no data" error on login.
			IslandData selfData = file.getOrCreateIslandData(player);
			if (selfData != null) {
				new SyncIslandDataMessage(selfData, true).sendTo(player);
			}
		}
		
		// Attach inventory listener so item detection tasks work immediately
		player.inventoryMenu.addSlotListener(new FTBQuestsInventoryListener(player));
	}

	private void serverAboutToStart(MinecraftServer server) {
		ServerQuestFile.INSTANCE = new ServerQuestFile(server);
		FTBQuestsTeamConfig.register();
	}

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx, Commands.CommandSelection selection) {
		FTBQuestsCommands.register(dispatcher);
	}

	private void serverStarted(MinecraftServer server) {
		FTBQuestsTeamConfig.load(server);
		ServerQuestFile.INSTANCE.load();
		TeamManager.getInstance(server).load();
	}

	private void serverStopped(MinecraftServer server) {
		if (ServerQuestFile.INSTANCE != null) {
			ServerQuestFile.INSTANCE.saveNow();
			ServerQuestFile.INSTANCE.unload();
			ServerQuestFile.INSTANCE = null;
		}
		TeamManager.getInstance(server).save();
		TeamManager.clearInstance();
	}

	private void worldSaved(ServerLevel level) {
		if (ServerQuestFile.INSTANCE != null) {
			ServerQuestFile.INSTANCE.saveNow();
			TeamManager.getInstance(level.getServer()).save();
		}
	}

	private void fileCacheClear(BaseQuestFile file) {
		if (file.isServerSide()) {
			killTasks = null;
			autoSubmitTasks = null;
		}
	}

	private EventResult playerKill(LivingEntity entity, DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer player && !PlayerHooks.isFake(player)) {
			if (killTasks == null) {
				killTasks = ServerQuestFile.INSTANCE.collect(KillTask.class);
			}

			if (killTasks.isEmpty()) {
				return EventResult.pass();
			}

			IslandData data = ServerQuestFile.INSTANCE.getOrCreateIslandData(player);

			if (data != null && data.isMember(player.getUUID())) {
				for (KillTask task : killTasks) {
					if (data.getProgress(task) < task.getMaxProgress() && data.canStartTasks(task.getQuest())) {
						task.kill(data, entity);
					}
				}
			}
		}

		return EventResult.pass();
	}

	private void playerTick(Player player) {
		ServerQuestFile file = ServerQuestFile.INSTANCE;

		if (player instanceof ServerPlayer && file != null && !PlayerHooks.isFake(player)) {
			if (autoSubmitTasks == null) {
				autoSubmitTasks = file.collect(o -> o instanceof Task && ((Task) o).autoSubmitOnPlayerTick() > 0);
			}

			// Don't be deceived, its somehow possible to be null here
			if (autoSubmitTasks == null || autoSubmitTasks.isEmpty()) {
				return;
			}

			IslandData data = file.getOrCreateIslandData(player);

			if (data == null || data.isLocked() || !data.isMember(player.getUUID())) {
				return;
			}

			long t = player.level().getGameTime();

			file.withPlayerContext((ServerPlayer) player, () -> {
				for (Task task : autoSubmitTasks) {
					long d = task.autoSubmitOnPlayerTick();

					if (d > 0L && t % d == 0L) {
						if (!data.isCompleted(task) && data.canStartTasks(task.getQuest())) {
							task.submitTask(data, (ServerPlayer) player);
						}
					}
				}
			});
		}
	}

	private void itemCrafted(Player player, ItemStack crafted, Container inventory) {
		if (player instanceof ServerPlayer && !crafted.isEmpty()) {
			FTBQuestsInventoryListener.detect((ServerPlayer) player, crafted, 0);
		}
	}

	private void itemSmelted(Player player, ItemStack smelted) {
		if (player instanceof ServerPlayer && !smelted.isEmpty()) {
			FTBQuestsInventoryListener.detect((ServerPlayer) player, smelted, 0);
		}
	}

	private void cloned(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean wonGame) {
		newPlayer.inventoryMenu.addSlotListener(new FTBQuestsInventoryListener(newPlayer));

		if (wonGame) {
			return;
		}

		if (PlayerHooks.isFake(newPlayer) || newPlayer.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
			return;
		}

		if (!ServerQuestFile.INSTANCE.dropBookOnDeath()) {
			for (int i = 0; i < oldPlayer.getInventory().items.size(); i++) {
				ItemStack stack = oldPlayer.getInventory().items.get(i);

				if (stack.getItem() == FTBQuestsItems.BOOK.get() && newPlayer.addItem(stack)) {
					oldPlayer.getInventory().items.set(i, ItemStack.EMPTY);

				}
			}
		}
	}

	private void changedDimension(ServerPlayer player, ResourceKey<Level> oldLevel, ResourceKey<Level> newLevel) {
		if (!PlayerHooks.isFake(player)) {
			ServerQuestFile file = ServerQuestFile.INSTANCE;
			IslandData data = file.getOrCreateIslandData(player);

			if (data == null || data.isLocked() || !data.isMember(player.getUUID())) {
				return;
			}

			file.withPlayerContext(player, () -> {
				for (DimensionTask task : file.collect(DimensionTask.class)) {
					if (data.canStartTasks(task.getQuest())) {
						task.submitTask(data, player);
					}
				}
			});
		}
	}

	private void containerOpened(Player player, AbstractContainerMenu menu) {
		if (player instanceof ServerPlayer && !PlayerHooks.isFake(player) && !(menu instanceof InventoryMenu)) {
			menu.addSlotListener(new FTBQuestsInventoryListener((ServerPlayer) player));
		}
	}
}