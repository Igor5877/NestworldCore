package dev.ftb.mods.ftbquests.api;

import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestLink;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

public interface QuestFile {
    boolean isServerSide();

    boolean canEdit();

    @Nullable IslandData getNullableIslandData(UUID id);

    IslandData getOrCreateIslandData(UUID islandId);

    IslandData getOrCreateIslandData(Entity player);

    Collection<IslandData> getAllIslandData();

    void forAllChapters(Consumer<Chapter> consumer);

    void forAllQuests(Consumer<Quest> consumer);

    void forAllQuestLinks(Consumer<QuestLink> consumer);
}
