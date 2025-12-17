package dev.ftb.mods.ftbquests.quest.task;

import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.theme.property.ThemeProperties;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.level.ServerPlayer;

public class CheckmarkTask extends AbstractBooleanTask {
	public CheckmarkTask(long id, Quest quest) {
		super(id, quest);
	}

	@Override
	public TaskType getType() {
		return TaskTypes.CHECKMARK;
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void drawGUI(IslandData islandData, GuiGraphics graphics, int x, int y, int w, int h) {
		(islandData.isCompleted(this) ? ThemeProperties.CHECKMARK_TASK_ACTIVE.get(this) : ThemeProperties.CHECKMARK_TASK_INACTIVE.get(this))
				.draw(graphics, x, y, w, h);
	}

	@Override
	public boolean canSubmit(IslandData islandData, ServerPlayer player) {
		return true;
	}

	@Override
	public boolean checkOnLogin() {
		return false;
	}

	@Override
	protected boolean hasIconConfig() {
		return false;
	}
}