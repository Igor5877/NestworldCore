/*
 * NestworldCore - Tick budget enforcement
 */
package net.nestworld.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.LogicalSide;
import net.nestworld.island.Island;
import net.nestworld.island.IslandLifecycleManager;
import net.nestworld.island.IslandState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Monitors tick performance for this island-process and triggers protective responses.
 *
 * Since each Forge process hosts exactly ONE island, this monitors the whole server.
 * Budget is the full 50ms tick — we don't need to split it between multiple islands.
 *
 * Protection levels:
 *   NORMAL     → full ticks, no interference
 *   SKIP_HALF  → suppress entity AI / block ticks on alternate ticks (warning logged)
 *   SKIP_THREE → suppress 3 of 4 ticks (critical warning + player notification)
 *   EMERGENCY  → trigger island shutdown via IslandLifecycleManager
 *
 * Note: actual tick cancellation is not possible via standard Forge events.
 * "Suppression" means NestworldCore uses ForgeHooks to skip expensive per-entity
 * work during the tick (requires patches — marked TODO for Phase 2).
 */
public class TickBudgetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickBudgetManager.class);

    /** Full tick budget: 50ms = 20 TPS target. */
    private static final long DEFAULT_BUDGET_NANOS = 50_000_000L;

    private final IslandLifecycleManager lifecycleManager;
    private final UUID islandId;

    private IslandTickProfile profile;
    private boolean emergencyTriggered = false;

    public TickBudgetManager(IslandLifecycleManager lifecycleManager, UUID islandId) {
        this.lifecycleManager = lifecycleManager;
        this.islandId = islandId;
        this.profile = new IslandTickProfile(islandId, DEFAULT_BUDGET_NANOS);
    }

    // -------------------------------------------------------------------------
    // Forge event hooks (wired in NestworldCore)
    // -------------------------------------------------------------------------

    public void onServerTickPre(TickEvent.ServerTickEvent.Pre event) {
        profile.recordTickStart();
    }

    public void onServerTickPost(TickEvent.ServerTickEvent.Post event) {
        profile.recordTickEnd();
        evaluateAdaptiveState(event.server());
    }

    // -------------------------------------------------------------------------
    // Adaptive response
    // -------------------------------------------------------------------------

    private void evaluateAdaptiveState(net.minecraft.server.MinecraftServer server) {
        IslandTickProfile.AdaptiveState state = profile.getAdaptiveState();

        switch (state) {
            case SKIP_HALF -> {
                if (profile.getViolationCount() == 5) {
                    LOGGER.warn("Island {} TPS degraded ({:.1f} TPS) — entering SKIP_HALF mode",
                        islandId, profile.getCurrentTps());
                    notifyPlayers(server,
                        "§e[NestWorld] Your island is lagging. Investigating...");
                }
            }
            case SKIP_THREE -> {
                if (profile.getViolationCount() == 10) {
                    LOGGER.error("Island {} critical lag ({:.1f} TPS) — entering SKIP_THREE mode",
                        islandId, profile.getCurrentTps());
                    notifyPlayers(server,
                        "§c[NestWorld] Severe lag detected. If it continues, island will restart.");
                }
            }
            case EMERGENCY -> {
                if (!emergencyTriggered) {
                    emergencyTriggered = true;
                    LOGGER.error("Island {} exceeded tick budget {} times — triggering emergency shutdown",
                        islandId, profile.getViolationCount());
                    lifecycleManager.triggerEmergencyShutdown(
                        islandId,
                        String.format("Tick budget exceeded %d times (TPS %.1f)",
                            profile.getViolationCount(), profile.getCurrentTps())
                    );
                }
            }
            default -> {}
        }
    }

    private void notifyPlayers(net.minecraft.server.MinecraftServer server, String message) {
        var component = net.minecraft.network.chat.Component.literal(message);
        server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(component));
    }

    // -------------------------------------------------------------------------
    // Query (used by MetricsCollector and entity limiter)
    // -------------------------------------------------------------------------

    /** Returns true when the current tick's heavy work should be deferred. */
    public boolean shouldSuppressHeavyWork() {
        return profile.shouldSuppressHeavyWork();
    }

    public IslandTickProfile getProfile() {
        return profile;
    }

    public void resetForTesting() {
        this.profile = new IslandTickProfile(islandId, DEFAULT_BUDGET_NANOS);
        this.emergencyTriggered = false;
    }
}
