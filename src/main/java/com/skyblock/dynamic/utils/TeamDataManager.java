package com.skyblock.dynamic.utils;

import com.google.gson.Gson;
import com.skyblock.dynamic.Config;
import com.skyblock.dynamic.SkyBlockMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

// Цей клас буде централізовано зберігати дані про команду
public class TeamDataManager {
    private static TeamData cachedTeamData = null;
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newHttpClient();

    // Головний метод, який виконує блокуючий запит
    public static void loadTeamData() {
        SkyBlockMod.LOGGER.info("Attempting to synchronously fetch team data from API...");
        try {
            // Спочатку завантажуємо конфіг, щоб отримати URL та UUID
            String ownerUuid = Config.getOwnerUuid();
            String apiBaseUrl = Config.getApiBaseUrl();

            if (ownerUuid == null || ownerUuid.isEmpty() || apiBaseUrl == null || apiBaseUrl.isEmpty()) {
                throw new IllegalStateException("owner_uuid or api_base_url is not set in skyblock_island_data.toml");
            }

            String apiUrl = String.format("%s/teams/my_team/%s", apiBaseUrl, ownerUuid);
            SkyBlockMod.LOGGER.info("Fetching from URL: {}", apiUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .build();

            // Виконуємо СИНХРОННИЙ запит
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch team data. API returned status code: " + response.statusCode() + " | Body: " + response.body());
            }

            cachedTeamData = gson.fromJson(response.body(), TeamData.class);

            if (cachedTeamData == null || cachedTeamData.team_id == null) {
                 throw new RuntimeException("Failed to parse team data from API response. Body: " + response.body());
            }

            SkyBlockMod.LOGGER.info("Successfully fetched and cached team data for team ID: {}", cachedTeamData.team_id);

        } catch (Exception e) {
            SkyBlockMod.LOGGER.error("Could not fetch team data during server startup. Quests may not function correctly.", e);
        }
    }

    public static TeamData getCachedTeamData() {
        if (cachedTeamData == null) {
            // Ця ситуація не повинна виникати, якщо логіка запуску правильна
            SkyBlockMod.LOGGER.error("Attempted to access team data before it was loaded!");
            return null;
        }
        return cachedTeamData;
    }

    public static String getCachedTeamId() {
        TeamData data = getCachedTeamData();
        return (data != null) ? data.team_id : null;
    }
}