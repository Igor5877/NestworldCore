package net.nestworld.module.fasttransition;

import com.google.gson.Gson;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.nestworld.module.*;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** Startup-loaded server component. Initial migration deliberately preserves the complete
 * Forge login protocol. The manifest is advisory until all attach contracts are implemented. */
public final class FastTransitionModule implements NestWorldModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(FastTransitionModule.class);
    private static final Gson JSON = new Gson();
    private final UUID runId = UUID.randomUUID();
    private ModuleContext context;
    private volatile Manifest manifest;
    private ChunkPreparation commandPreparation;

    public record Manifest(int version, UUID runId, String moduleVersion,
                           SyncDataSnapshot data, boolean fastAttachSupported, String status) {}

    @Override public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(new ModuleId("fast-transition"), new ModuleVersion("1.0.0"), "Fast Transition");
    }

    @Override public void onEnable(ModuleContext context) {
        this.context = context;
        // Bounded batching replaces the external server-mod's unbounded drain.
        context.registerLoginBudget(new LoginBudget(32, 1024 * 1024));
        refreshManifest();
        context.registerListener(this);
        context.registerCommand("nwtransition", command -> {
            if (!command.getSource().hasPermission(2)) return 0;
            command.getSource().sendSuccess(() -> Component.literal(JSON.toJson(manifest)
                    + " preparation=" + (commandPreparation == null ? "none" : commandPreparation.state())), false);
            return 1;
        });
        context.registerCommand("nwprepare", command -> {
            var source = command.getSource();
            if (!source.hasPermission(2)) return 0;
            if (commandPreparation != null) commandPreparation.revoke();
            var center = new ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
            ChunkPreparation preparation = prepare(new ChunkPreparationRequest(UUID.randomUUID(),
                    source.getLevel().dimension().location(), center.x, center.z, 2, 600));
            if (preparation == null) return 0;
            commandPreparation = preparation;
            source.sendSuccess(() -> Component.literal("Preparing 5x5 chunks; lease=" + preparation.request().id()
                    + " (600 ticks, including READY hold). Use /nwtransition for status."), false);
            return 1;
        });
        context.registerCommand("nwpreparecancel", command -> {
            if (!command.getSource().hasPermission(2)) return 0;
            if (commandPreparation != null) commandPreparation.revoke();
            command.getSource().sendSuccess(() -> Component.literal("Preparation cancelled; tickets released."), false);
            return 1;
        });
        LOGGER.info("Fast Transition module enabled: bounded FML batching; full login retained; run={}", runId);
    }

    /** Server-thread integration point for a future authenticated coordinator adapter.
     * The supplied position is a hint; final player placement remains the backend's responsibility. */
    public ChunkPreparation prepare(ChunkPreparationRequest request) {
        if (context == null) return null;
        return context.prepareChunks(request);
    }

    public Manifest manifest() { return manifest; }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDatapackSync(OnDatapackSyncEvent event) {
        if (context != null && context.ownsServer(event.getPlayerList().getServer()) && event.getPlayer() == null)
            refreshManifest();
    }

    private void refreshManifest() {
        // Invalidate before capture. A failing serializer must never leave old generations advertised.
        manifest = new Manifest(1, runId, "1.0.0", null, false, "UNAVAILABLE");
        try {
            manifest = new Manifest(1, runId, "1.0.0", context.snapshotSyncData(), false, "FULL_LOGIN_ONLY");
        } catch (RuntimeException e) {
            LOGGER.warn("Fast Transition data generations unavailable; full login remains active", e);
        }
    }

    @Override public void onDisable() {
        manifest = new Manifest(1, runId, "1.0.0", null, false, "DISABLED");
        commandPreparation = null;
        context = null;
    }
}
