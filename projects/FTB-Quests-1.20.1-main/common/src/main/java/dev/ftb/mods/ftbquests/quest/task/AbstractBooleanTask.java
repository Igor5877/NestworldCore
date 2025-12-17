package dev.ftb.mods.ftbquests.quest.task;

import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.Quest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public abstract class AbstractBooleanTask extends Task {
	public AbstractBooleanTask(long id, Quest quest) {
		super(id, quest);
	}

	@Override
	public String formatMaxProgress() {
		return "1";
	}

	@Override
	public String formatProgress(IslandData islandData, long progress) {
		return progress >= 1L ? "1" : "0";
	}

	public abstract boolean canSubmit(IslandData islandData, ServerPlayer player);

	@Override
	public void submitTask(IslandData islandData, ServerPlayer player, ItemStack craftedItem) {
		if (!islandData.isCompleted(this) && checkTaskSequence(islandData) && canSubmit(islandData, player)) {
			islandData.setProgress(this, 1L);
		}
	}
}
