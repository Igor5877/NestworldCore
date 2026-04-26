/*
 * NestworldCore - Island lifecycle manager
 */
package net.nestworld.island;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Central state machine for island lifecycle.
 *
 * Responsibilities:
 *  - Track all islands and their current state
 *  - Drive COLD→WARM→ACTIVE→COOLING→COLD transitions
 *  - Schedule auto-cooldown when island empties
 *  - Trigger emergency shutdown on lag detection
 *
 * This class is the orchestrator-side manager. The actual Forge process
 * lifecycle (spawn/kill) is handled by ProcessManager.
 */
public class IslandLifecycleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(IslandLifecycleManager.class);

    private static final long IDLE_COOLDOWN_MS       = 5  * 60 * 1_000L;  // 5 min idle → COOLING
    private static final long QUARANTINE_DURATION_MS = 15 * 60 * 1_000L;  // 15 min quarantine after emergency
    private static final long WARMING_TIMEOUT_MS     = 3  * 60 * 1_000L;  // 3 min max to reach WARM

    private final Map<UUID, Island> islands = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> Thread.ofVirtual().name("nestworld-lifecycle").unstarted(r)
    );

    /** Callback fired when an island needs its process started (COLD → WARMING). */
    private Runnable onWarmupRequested = () -> {};
    /** Callback fired when an island should be saved and its process stopped. */
    private Runnable onCooldownRequested = () -> {};

    public void setWarmupCallback(Runnable cb)   { onWarmupRequested = cb; }
    public void setCooldownCallback(Runnable cb) { onCooldownRequested = cb; }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public Island register(UUID islandId, UUID ownerId) {
        return islands.computeIfAbsent(islandId, id -> {
            LOGGER.debug("Registered island {}", id);
            return new Island(id, ownerId);
        });
    }

    public Island get(UUID islandId) {
        return islands.get(islandId);
    }

    public Collection<Island> all() {
        return islands.values();
    }

    // -------------------------------------------------------------------------
    // Warmup: COLD → WARMING
    // -------------------------------------------------------------------------

    /**
     * Requests that an island be loaded into a Forge process.
     * Called proactively by launcher when player opens the game.
     *
     * @return false if island is quarantined or already running
     */
    public boolean requestWarmup(UUID islandId) {
        Island island = islands.get(islandId);
        if (island == null) {
            LOGGER.warn("requestWarmup: unknown island {}", islandId);
            return false;
        }
        if (island.isQuarantined()) {
            LOGGER.info("Island {} is quarantined for {}ms", islandId, island.getQuarantineRemainingMs());
            return false;
        }
        if (island.getState() != IslandState.COLD) {
            return true; // already warming or warm
        }

        island.transitionTo(IslandState.WARMING);
        LOGGER.info("Island {} → WARMING", islandId);

        // Watchdog: if warming takes too long, roll back to COLD
        scheduler.schedule(() -> {
            if (island.getState() == IslandState.WARMING) {
                LOGGER.error("Island {} WARMING timeout — rolling back to COLD", islandId);
                island.clearProcess();
                island.transitionTo(IslandState.COLD);
            }
        }, WARMING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Called by ProcessManager when the Forge process has started and reported ready.
     * WARMING → WARM
     */
    public void onProcessReady(UUID islandId, long pid, int port) {
        Island island = islands.get(islandId);
        if (island == null || island.getState() != IslandState.WARMING) return;

        island.assignProcess(pid, port);
        island.transitionTo(IslandState.WARM);
        LOGGER.info("Island {} → WARM (pid={}, port={})", islandId, pid, port);
    }

    // -------------------------------------------------------------------------
    // Player join/leave
    // -------------------------------------------------------------------------

    /**
     * Called when a player is routed to this island by Velocity.
     * WARM → ACTIVE (on first player)
     */
    public void onPlayerJoined(UUID islandId, UUID playerId) {
        Island island = islands.get(islandId);
        if (island == null) return;

        island.playerJoined(playerId);

        if (island.getState() == IslandState.WARM) {
            island.transitionTo(IslandState.ACTIVE);
            LOGGER.info("Island {} → ACTIVE (first player: {})", islandId, playerId);
        }
    }

    /**
     * Called when a player disconnects or migrates away.
     * Schedules auto-cooldown if island becomes empty.
     */
    public void onPlayerLeft(UUID islandId, UUID playerId) {
        Island island = islands.get(islandId);
        if (island == null) return;

        island.playerLeft(playerId);

        if (island.isEmpty() && island.getState() == IslandState.ACTIVE) {
            LOGGER.info("Island {} empty — scheduling auto-cooldown in {}s",
                islandId, IDLE_COOLDOWN_MS / 1_000);
            scheduleAutoCooldown(islandId);
        }
    }

    // -------------------------------------------------------------------------
    // Emergency shutdown (lag machine response)
    // -------------------------------------------------------------------------

    /**
     * Triggered by TickBudgetManager when an island consistently exceeds its tick budget.
     * Players are kicked, island is saved, process is killed, quarantine applied.
     */
    public void triggerEmergencyShutdown(UUID islandId, String reason) {
        Island island = islands.get(islandId);
        if (island == null || island.getState() == IslandState.COOLING) return;

        LOGGER.warn("EMERGENCY SHUTDOWN island {}: {}", islandId, reason);
        island.transitionTo(IslandState.COOLING);
        island.quarantine(QUARANTINE_DURATION_MS);
        // Actual process kill + player kick is done by NestworldCore on main thread
    }

    // -------------------------------------------------------------------------
    // Process terminated (clean shutdown or crash)
    // -------------------------------------------------------------------------

    /**
     * Called when a Forge process exits (clean or crash).
     * Any remaining state in COOLING → COLD. Crash → COLD without quarantine.
     */
    public void onProcessStopped(UUID islandId, boolean crashed) {
        Island island = islands.get(islandId);
        if (island == null) return;

        island.clearProcess();
        island.transitionTo(IslandState.COLD);

        if (crashed) {
            LOGGER.error("Island {} process CRASHED — returning to COLD", islandId);
        } else {
            LOGGER.info("Island {} → COLD (clean shutdown)", islandId);
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public List<Island> getByState(IslandState state) {
        return islands.values().stream()
            .filter(i -> i.getState() == state)
            .toList();
    }

    public int countByState(IslandState state) {
        return (int) islands.values().stream().filter(i -> i.getState() == state).count();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void scheduleAutoCooldown(UUID islandId) {
        scheduler.schedule(() -> {
            Island island = islands.get(islandId);
            if (island == null) return;
            if (island.isEmpty() && island.getState() == IslandState.ACTIVE) {
                island.transitionTo(IslandState.COOLING);
                LOGGER.info("Island {} → COOLING (idle timeout)", islandId);
            }
        }, IDLE_COOLDOWN_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
