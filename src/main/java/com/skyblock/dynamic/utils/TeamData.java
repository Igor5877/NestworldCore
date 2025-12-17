package com.skyblock.dynamic.utils;

import java.util.List;

// Цей клас представляє структуру JSON-відповіді від API
public class TeamData {
    public String team_id;
    public String owner_uuid;
    public List<TeamMember> members;

    public static class TeamMember {
        public String player_uuid;
        public String player_name;
    }
}