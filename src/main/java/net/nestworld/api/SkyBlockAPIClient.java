/*
 * NestworldCore - HTTP client for the orchestrator API
 */
package net.nestworld.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP client for communicating with the FastAPI orchestrator.
 *
 * All calls are fire-and-forget (CompletableFuture<Void>).
 * API unavailability is logged at WARN/DEBUG but never throws — the island
 * server must keep running even if the orchestrator is temporarily unreachable.
 *
 * Auth: Bearer token passed in every request header.
 */
public class SkyBlockAPIClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyBlockAPIClient.class);
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final String baseUrl;
    private final String authToken;

    public SkyBlockAPIClient(String baseUrl, String authToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authToken = authToken;
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            // Virtual threads for each async send (Java 21)
            .executor(command -> Thread.ofVirtual().start(command))
            .build();
    }

    // -------------------------------------------------------------------------
    // Island lifecycle reporting
    // -------------------------------------------------------------------------

    /**
     * Reports island state change to the orchestrator.
     * Called after every WARM/ACTIVE/COOLING/COLD transition.
     */
    public CompletableFuture<Void> reportIslandState(UUID islandId, String state) {
        JsonObject body = new JsonObject();
        body.addProperty("island_id", islandId.toString());
        body.addProperty("state", state);
        body.addProperty("timestamp", System.currentTimeMillis());

        return post("/internal/island/state", body)
            .thenAccept(resp -> {
                if (resp.statusCode() != 200)
                    LOGGER.warn("State report rejected for island {}: HTTP {}", islandId, resp.statusCode());
            })
            .exceptionally(ex -> {
                LOGGER.warn("Orchestrator unreachable (state report island {}): {}", islandId, ex.getMessage());
                return null;
            });
    }

    /**
     * Reports that this island process is ready to accept players.
     * Orchestrator will update Velocity routing tables after receiving this.
     */
    public CompletableFuture<Void> reportProcessReady(UUID islandId, int port) {
        JsonObject body = new JsonObject();
        body.addProperty("island_id", islandId.toString());
        body.addProperty("port", port);
        body.addProperty("timestamp", System.currentTimeMillis());

        return post("/internal/island/ready", body)
            .thenAccept(resp -> {
                if (resp.statusCode() == 200)
                    LOGGER.info("Island {} registered with orchestrator on port {}", islandId, port);
                else
                    LOGGER.error("Orchestrator rejected ready signal for island {}: HTTP {}", islandId, resp.statusCode());
            })
            .exceptionally(ex -> {
                LOGGER.error("Could not reach orchestrator on startup (island {}): {}", islandId, ex.getMessage());
                return null;
            });
    }

    /**
     * Reports an emergency shutdown event.
     * Orchestrator will quarantine the island and remove it from Velocity routing.
     */
    public CompletableFuture<Void> reportEmergency(UUID islandId, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("island_id", islandId.toString());
        body.addProperty("reason", reason);
        body.addProperty("timestamp", System.currentTimeMillis());

        return post("/internal/island/emergency", body)
            .thenAccept(resp -> {
                if (resp.statusCode() != 200)
                    LOGGER.error("Emergency report failed for island {}: HTTP {}", islandId, resp.statusCode());
            })
            .exceptionally(ex -> {
                LOGGER.error("Could not report emergency for island {}: {}", islandId, ex.getMessage());
                return null;
            });
    }

    // -------------------------------------------------------------------------
    // Inventory / player data (write-ahead before confirming action to player)
    // -------------------------------------------------------------------------

    /**
     * Persists player inventory to Redis via orchestrator.
     * Must complete successfully before the game confirms item changes to the player.
     *
     * Returns the HTTP status code so the caller can decide to block or rollback.
     */
    public CompletableFuture<Integer> persistPlayerInventory(UUID playerId, String inventoryJson) {
        JsonObject body = new JsonObject();
        body.addProperty("player_id", playerId.toString());
        body.addProperty("inventory", inventoryJson);
        body.addProperty("timestamp", System.currentTimeMillis());

        return post("/internal/player/inventory", body)
            .thenApply(HttpResponse::statusCode)
            .exceptionally(ex -> {
                LOGGER.error("Inventory persist failed for player {}: {}", playerId, ex.getMessage());
                return 503;
            });
    }

    // -------------------------------------------------------------------------
    // Metrics push (fire-and-forget, failures are silent)
    // -------------------------------------------------------------------------

    public CompletableFuture<Void> pushMetrics(JsonObject metrics) {
        return post("/internal/metrics", metrics)
            .thenAccept(resp -> {
                if (resp.statusCode() != 200)
                    LOGGER.debug("Metrics push rejected: HTTP {}", resp.statusCode());
            })
            .exceptionally(ex -> {
                LOGGER.debug("Metrics push failed: {}", ex.getMessage());
                return null;
            });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<HttpResponse<String>> post(String path, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + authToken)
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
