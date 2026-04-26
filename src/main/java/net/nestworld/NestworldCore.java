/*
 * NestworldCore - Main Forge mod entry point
 */
package net.nestworld;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.nestworld.api.SkyBlockAPIClient;
import net.nestworld.island.Island;
import net.nestworld.island.IslandLifecycleManager;
import net.nestworld.island.IslandState;
import net.nestworld.metrics.MetricsCollector;
import net.nestworld.tick.TickBudgetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NestworldCore Forge mod — runs inside each island's dedicated Forge process.
 *
 * One island = one Forge process = one instance of this mod.
 * The island UUID and orchestrator URL are passed via JVM system properties:
 *
 *   -Dnestworld.island.id=<uuid>
 *   -Dnestworld.island.owner=<uuid>
 *   -Dnestworld.api.url=http://orchestrator:8000
 *   -Dnestworld.api.token=<secret>
 *   -Dnestworld.server.port=25565
 *
 * On startup this mod:
 *   1. Registers the island with the lifecycle manager
 *   2. Reports WARM state to the orchestrator (server is ready)
 *   3. Subscribes to tick events for budget monitoring
 *   4. Schedules metrics push every 30s
 *
 * On emergency shutdown:
 *   1. Kicks all players with a message
 *   2. Forces world save
 *   3. Notifies orchestrator
 *   4. Calls System.exit(0) — orchestrator restarts if needed
 */
@Mod(NestworldCore.MOD_ID)
public final class NestworldCore {

    public static final String MOD_ID = "nestworldcore";
    private static final Logger LOGGER = LoggerFactory.getLogger(NestworldCore.class);

    // Singleton — one instance per JVM (= one island)
    private static NestworldCore instance;

    private final UUID islandId;
    private final UUID ownerId;
    private final int serverPort;

    private final IslandLifecycleManager lifecycleManager;
    private final TickBudgetManager tickBudgetManager;
    private final SkyBlockAPIClient apiClient;
    private final MetricsCollector metricsCollector;
    private final ScheduledExecutorService metricsScheduler;

    private volatile MinecraftServer server;
    private volatile boolean emergencyInProgress = false;

    public NestworldCore(FMLJavaModLoadingContext context) {
        instance = this;

        this.islandId   = UUID.fromString(requireProp("nestworld.island.id"));
        this.ownerId    = UUID.fromString(requireProp("nestworld.island.owner"));
        this.serverPort = Integer.parseInt(System.getProperty("nestworld.server.port", "25565"));

        String apiUrl   = requireProp("nestworld.api.url");
        String apiToken = requireProp("nestworld.api.token");

        this.lifecycleManager  = new IslandLifecycleManager();
        this.tickBudgetManager = new TickBudgetManager(lifecycleManager, islandId);
        this.apiClient         = new SkyBlockAPIClient(apiUrl, apiToken);
        this.metricsScheduler  = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("nestworld-metrics").unstarted(r)
        );

        Island island = lifecycleManager.register(islandId, ownerId);

        // Wire emergency shutdown: lifecycle → this mod's handler
        lifecycleManager.setWarmupCallback(() -> {}); // warmup is initiated externally
        lifecycleManager.setCooldownCallback(this::initiateEmergencyShutdown);

        this.metricsCollector = new MetricsCollector(lifecycleManager, tickBudgetManager);

        // Register Forge events using modern 1.21.x BUS API
        ServerAboutToStartEvent.BUS.addListener(this::onServerAboutToStart);
        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
        TickEvent.ServerTickEvent.Pre.BUS.addListener(this::onServerTickPre);
        TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTickPost);

        LOGGER.info("NestworldCore initialised for island {} (owner {})", islandId, ownerId);
    }

    // -------------------------------------------------------------------------
    // Server lifecycle events
    // -------------------------------------------------------------------------

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        this.server = event.getServer();
        LOGGER.info("Server starting on port {}", serverPort);
    }

    private void onServerStarted(ServerStartedEvent event) {
        Island island = lifecycleManager.get(islandId);
        if (island == null) return;

        // Transition COLD → WARMING → WARM (process started = warming done)
        lifecycleManager.requestWarmup(islandId);
        lifecycleManager.onProcessReady(islandId, ProcessHandle.current().pid(), serverPort);

        // Tell the orchestrator this process is ready; Velocity will start routing here
        apiClient.reportProcessReady(islandId, serverPort)
            .thenRun(() -> LOGGER.info("Island {} announced as WARM to orchestrator", islandId));

        // Start periodic metrics push
        metricsScheduler.scheduleAtFixedRate(this::pushMetrics, 30, 30, TimeUnit.SECONDS);

        LOGGER.info("Island {} is WARM and accepting players", islandId);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Island {} server stopping", islandId);
        metricsScheduler.shutdownNow();
        lifecycleManager.shutdown();
        apiClient.reportIslandState(islandId, IslandState.COLD.name()).join();
    }

    // -------------------------------------------------------------------------
    // Tick events → budget manager
    // -------------------------------------------------------------------------

    private void onServerTickPre(TickEvent.ServerTickEvent.Pre event) {
        tickBudgetManager.onServerTickPre(event);
    }

    private void onServerTickPost(TickEvent.ServerTickEvent.Post event) {
        tickBudgetManager.onServerTickPost(event);

        // If lifecycle manager flagged emergency, execute it on main thread
        Island island = lifecycleManager.get(islandId);
        if (island != null && island.getState() == IslandState.COOLING && !emergencyInProgress) {
            initiateEmergencyShutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Emergency shutdown (runs on main thread via tick post)
    // -------------------------------------------------------------------------

    private void initiateEmergencyShutdown() {
        if (emergencyInProgress || server == null) return;
        emergencyInProgress = true;

        LOGGER.error("Island {} emergency shutdown initiated", islandId);

        // Kick all players with explanation
        var message = net.minecraft.network.chat.Component.literal(
            "§c[NestWorld] Your island has been shut down due to excessive lag. " +
            "It will be back online in 15 minutes."
        );
        server.getPlayerList().getPlayers().forEach(p -> p.connection.disconnect(message));

        // Force save
        server.getAllLevels().forEach(level -> level.save(null, true, false));

        // Report to orchestrator, then stop the JVM
        String reason = String.format("Tick budget exceeded %d times (TPS %.1f)",
            tickBudgetManager.getProfile().getViolationCount(),
            tickBudgetManager.getProfile().getCurrentTps());

        apiClient.reportEmergency(islandId, reason)
            .whenComplete((v, ex) -> {
                LOGGER.info("Island {} shutting down JVM after emergency", islandId);
                server.halt(false);
            });
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    private void pushMetrics() {
        try {
            apiClient.pushMetrics(metricsCollector.collect());
        } catch (Exception e) {
            LOGGER.debug("Metrics push error: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public static NestworldCore get() {
        if (instance == null) throw new IllegalStateException("NestworldCore not initialised");
        return instance;
    }

    public IslandLifecycleManager getLifecycleManager() { return lifecycleManager; }
    public TickBudgetManager getTickBudgetManager()     { return tickBudgetManager; }
    public SkyBlockAPIClient getApiClient()             { return apiClient; }
    public UUID getIslandId()                           { return islandId; }
    public MinecraftServer getServer()                  { return server; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String requireProp(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required JVM system property missing: -D" + key + "=<value>"
            );
        }
        return value;
    }
}
