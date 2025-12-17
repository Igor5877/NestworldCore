package com.skyblock.dynamic.nestworld.mods;

import com.skyblock.dynamic.utils.TeamDataManager;

public class NestworldModsServer {
    // Цей клас є точкою входу для FTB Quests
    public static final IslandProvider ISLAND_PROVIDER = new IslandProvider();

    public static class IslandProvider {
        public String getCachedTeamId() {
            // Тепер цей метод надійно повертає вже завантажені дані
            return TeamDataManager.getCachedTeamId();
        }
    }
}