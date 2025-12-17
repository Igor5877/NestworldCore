package dev.ftb.mods.ftbquests.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.config.FTBQuestsTeamConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TeamHttpClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static CompletableFuture<Boolean> notifyTeamCreated(UUID ownerUuid, String teamName) {
        // This is a hypothetical endpoint. The user needs to confirm the actual API endpoints.
        String apiUrl = FTBQuestsTeamConfig.API_BASE_URL + "teams/";
        
        JsonObject payload = new JsonObject();
        payload.addProperty("name", teamName);
        payload.addProperty("owner_uuid", ownerUuid.toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        LOGGER.info("Successfully notified backend of team creation for owner {}", ownerUuid);
                        return true;
                    } else {
                        LOGGER.error("Failed to notify backend of team creation for owner {}. Status: {}, Body: {}",
                                ownerUuid, response.statusCode(), response.body());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Exception while notifying backend of team creation for owner {}", ownerUuid, ex);
                    return false;
                });
    }

    public static CompletableFuture<Boolean> notifyIslandAction(UUID playerUuid, String action) {
        String apiUrl = FTBQuestsTeamConfig.API_BASE_URL + "islands/" + playerUuid.toString() + "/" + action; // e.g. /api/v1/islands/uuid/archive

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()) // Assuming these are trigger-like endpoints with no body
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        LOGGER.info("Successfully notified backend of island action '{}' for player {}", action, playerUuid);
                        return true;
                    } else {
                        LOGGER.error("Failed to notify backend of island action '{}' for player {}. Status: {}, Body: {}",
                                action, playerUuid, response.statusCode(), response.body());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Exception during island action '{}' for player {}", action, playerUuid, ex);
                    return false;
                });
    }
}
