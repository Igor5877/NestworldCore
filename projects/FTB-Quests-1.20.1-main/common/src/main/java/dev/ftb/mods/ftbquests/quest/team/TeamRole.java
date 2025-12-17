package dev.ftb.mods.ftbquests.quest.team;

public enum TeamRole {
    LEADER,
    MEMBER;

    public boolean isLeader() {
        return this == LEADER;
    }
}
