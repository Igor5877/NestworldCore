package dev.ftb.mods.ftbquests.quest.team;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeamManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEAMS_FILE = "ftbquests_teams.snbt";

    private static TeamManager instance;

    private final MinecraftServer server;
    private final Map<UUID, TeamData> teams;
    private final Map<UUID, TeamData> playerTeamMap;
    private final Map<UUID, UUID> invitations; // Invited Player UUID -> Team ID

    private TeamManager(MinecraftServer server) {
        this.server = server;
        this.teams = new HashMap<>();
        this.playerTeamMap = new HashMap<>();
        this.invitations = new HashMap<>();
    }

    public static TeamManager getInstance(MinecraftServer server) {
        if (instance == null) {
            instance = new TeamManager(server);
        }
        return instance;
    }

    public static void clearInstance() {
        instance = null;
    }

    private Path getTeamsFilePath() {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(TEAMS_FILE);
    }

    public void load() {
        teams.clear();
        playerTeamMap.clear();
        invitations.clear();

        Path path = getTeamsFilePath();
        if (!Files.exists(path)) {
            LOGGER.info("No FTB Quests teams file found, starting fresh.");
            return;
        }

        try {
            SNBTCompoundTag nbt = SNBT.read(path);
            if (nbt != null && nbt.contains("teams", Tag.TAG_LIST)) {
                ListTag teamsTag = nbt.getList("teams", Tag.TAG_COMPOUND);
                for (Tag tag : teamsTag) {
                    TeamData teamData = TeamData.read((CompoundTag) tag);
                    teams.put(teamData.getId(), teamData);
                    for (UUID memberId : teamData.getMembers().keySet()) {
                        playerTeamMap.put(memberId, teamData);
                    }
                }
                LOGGER.info("Loaded {} FTB Quests teams.", teams.size());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load FTB Quests teams file", e);
        }
    }

    public void save() {
        SNBTCompoundTag nbt = new SNBTCompoundTag();
        ListTag teamsTag = new ListTag();

        for (TeamData teamData : teams.values()) {
            CompoundTag teamNbt = new CompoundTag();
            teamData.write(teamNbt);
            teamsTag.add(teamNbt);
        }
        nbt.put("teams", teamsTag);

        try {
            SNBT.write(getTeamsFilePath(), nbt);
            LOGGER.info("Saved {} FTB Quests teams.", teams.size());
        } catch (Exception e) {
            LOGGER.error("Failed to save FTB Quests teams file", e);
        }
    }

    public TeamData createTeam(String name, UUID ownerId) {
        if (playerTeamMap.containsKey(ownerId)) {
            // Player is already in a team
            return null;
        }
        // Team ID is the owner's UUID
        UUID teamId = ownerId;
        TeamData newTeam = new TeamData(teamId, name, ownerId);
        teams.put(teamId, newTeam);
        playerTeamMap.put(ownerId, newTeam);
        save();
        return newTeam;
    }

    public void deleteTeam(UUID teamId) {
        TeamData team = teams.remove(teamId);
        if (team != null) {
            for (UUID memberId : team.getMembers().keySet()) {
                playerTeamMap.remove(memberId);
            }
            save();
        }
    }

    public TeamData getTeam(UUID teamId) {
        return teams.get(teamId);
    }
    
    public Map<UUID, TeamData> getTeams() {
        return Map.copyOf(teams);
    }

    public TeamData getPlayerTeam(UUID playerId) {
        return playerTeamMap.get(playerId);
    }

    public void addInvitation(UUID invitedPlayer, UUID teamId) {
        invitations.put(invitedPlayer, teamId);
    }

    public UUID getInvitation(UUID invitedPlayer) {
        return invitations.get(invitedPlayer);
    }

    public boolean addPlayerToTeam(UUID playerId, TeamData team) {
        if (playerTeamMap.containsKey(playerId) || !teams.containsKey(team.getId())) {
            return false; // Player already in a team or team doesn't exist
        }
        team.addMember(playerId, TeamRole.MEMBER);
        playerTeamMap.put(playerId, team);
        invitations.remove(playerId); // Clear invitation on join
        save();
        return true;
    }

    public TeamData removePlayerFromTeam(UUID playerId) {
        TeamData team = getPlayerTeam(playerId);
        if (team != null) {
            if (team.getOwner().equals(playerId)) {
                // To remove an owner, the team must be disbanded
                deleteTeam(team.getId());
                return null;
            } else {
                team.removeMember(playerId);
                playerTeamMap.remove(playerId);
                save();
            }
        }
        return team;
    }
}
