package dev.ftb.mods.ftbquests.quest.team;

import dev.ftb.mods.ftbquests.util.NBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TeamData {
    private final UUID id;
    private String name;
    private UUID owner;
    private final Map<UUID, TeamRole> members;

    public TeamData(UUID id, String name, UUID owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.members = new LinkedHashMap<>();
        addMember(owner, TeamRole.LEADER);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        // Demote old owner if they exist and are not the new owner
        this.members.computeIfPresent(this.owner, (uuid, role) -> TeamRole.MEMBER);
        this.owner = owner;
        // Promote new owner
        addMember(owner, TeamRole.LEADER);
    }

    public Map<UUID, TeamRole> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    public boolean isMember(UUID playerId) {
        return members.containsKey(playerId);
    }

    public void addMember(UUID playerId, TeamRole role) {
        members.put(playerId, role);
    }

    public TeamRole removeMember(UUID playerId) {
        if (owner.equals(playerId)) {
            throw new IllegalArgumentException("Cannot remove the team owner");
        }
        return members.remove(playerId);
    }

    public TeamRole getRole(UUID playerId) {
        return members.getOrDefault(playerId, null);
    }

    public void write(CompoundTag nbt) {
        nbt.putString("id", id.toString());
        nbt.putString("name", name);
        nbt.putString("owner", owner.toString());

        ListTag membersTag = new ListTag();
        for (Map.Entry<UUID, TeamRole> entry : members.entrySet()) {
            CompoundTag memberTag = new CompoundTag();
            memberTag.putString("uuid", entry.getKey().toString());
            memberTag.putString("role", entry.getValue().name());
            membersTag.add(memberTag);
        }
        nbt.put("members", membersTag);
    }

    public static TeamData read(CompoundTag nbt) {
        UUID id = UUID.fromString(nbt.getString("id"));
        String name = nbt.getString("name");
        UUID owner = UUID.fromString(nbt.getString("owner"));

        TeamData teamData = new TeamData(id, name, owner);
        teamData.members.clear(); // Clear default member added by constructor

        ListTag membersTag = nbt.getList("members", Tag.TAG_COMPOUND);
        for (Tag tag : membersTag) {
            CompoundTag memberTag = (CompoundTag) tag;
            UUID memberUuid = UUID.fromString(memberTag.getString("uuid"));
            TeamRole role = TeamRole.valueOf(memberTag.getString("role"));
            teamData.addMember(memberUuid, role);
        }

        return teamData;
    }
}
