package com.skyblock.dynamic.utils;

import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.team.TeamData;
import dev.ftb.mods.ftbquests.quest.team.TeamManager;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A bridge between the SkyBlock mod and the FTB Quests mod.
 */
public class QuestTeamBridge {

    private static final QuestTeamBridge INSTANCE = new QuestTeamBridge();

    private QuestTeamBridge() {
    }

    /**
     * Gets the singleton instance of the QuestTeamBridge.
     *
     * @return The singleton instance of the QuestTeamBridge.
     */
    public static QuestTeamBridge getInstance() {
        return INSTANCE;
    }

    /**
     * Synchronizes the team data from an external source (like an API) with both the native FTB Quests TeamData
     * and the custom IslandData. This ensures both systems are aware of the team structure.
     *
     * @param ownerUuid The UUID of the team's owner.
     * @param memberUuids A collection of UUIDs for all members of the team (including the owner).
     */
    public void syncTeamData(UUID ownerUuid, Collection<UUID> memberUuids) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.server == null) {
            // Can't do anything without the server instance
            return;
        }
        MinecraftServer server = file.server;
        TeamManager teamManager = TeamManager.getInstance(server);

        // --- Step 1: Synchronize with native FTB Quests TeamData ---
        TeamData teamData = teamManager.getTeam(ownerUuid);
        if (teamData == null) {
            // Team doesn't exist, so create it. The owner is automatically added as a leader.
            teamData = teamManager.createTeam("Island of " + ownerUuid.toString().substring(0, 8), ownerUuid);
        }

        if (teamData != null) {
            Set<UUID> apiMembers = new HashSet<>(memberUuids);
            Set<UUID> currentMembers = new HashSet<>(teamData.getMembers().keySet());

            // Add new members
            for (UUID apiMember : apiMembers) {
                if (!currentMembers.contains(apiMember)) {
                    teamManager.addPlayerToTeam(apiMember, teamData);
                }
            }

            // Remove old members
            for (UUID currentMember : currentMembers) {
                if (!apiMembers.contains(currentMember) && !currentMember.equals(ownerUuid)) {
                    teamManager.removePlayerFromTeam(currentMember);
                }
            }
        }

        // --- Step 2: Synchronize with custom IslandData ---
        // This maintains compatibility with any existing code that directly checks IslandData.
        IslandData islandData = file.getOrCreateIslandData(ownerUuid);
        if (islandData != null) {
            islandData.setMembers(memberUuids);
            islandData.markDirty(); // Ensure it gets saved
        }
    }

    /**
     * Checks if a player is a member of a given team using the authoritative FTB Quests team data.
     *
     * @param playerUuid The UUID of the player to check.
     * @param islandOwnerUuid The UUID of the island's owner, which is also the team ID.
     * @return true if the player is on the team, false otherwise.
     */
    public boolean isPlayerOnTeam(UUID playerUuid, UUID islandOwnerUuid) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.server == null) {
            return false;
        }
        TeamManager teamManager = TeamManager.getInstance(file.server);
        TeamData teamData = teamManager.getTeam(islandOwnerUuid);
        return teamData != null && teamData.isMember(playerUuid);
    }
}
