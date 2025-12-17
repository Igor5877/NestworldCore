package dev.ftb.mods.ftbquests.quest;

import java.util.UUID;

/**
 * @deprecated For backward compatibility with addons.
 */
@Deprecated
public class TeamData {
    public final IslandData islandData;

    public TeamData(UUID islandId, BaseQuestFile file) {
        this.islandData = new IslandData(islandId, file);
    }

    public TeamData(UUID islandId, BaseQuestFile file, String name) {
        this.islandData = new IslandData(islandId, file, name);
    }
}
