package dev.nestworld.fasttransition;

import com.google.gson.Gson;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.nestworld.module.ChunkPreparation;
import net.nestworld.module.ChunkPreparationRequest;
import net.nestworld.module.LoginBudget;
import net.nestworld.module.ModuleContext;
import net.nestworld.module.ModuleDescriptor;
import net.nestworld.module.ModuleId;
import net.nestworld.module.ModuleVersion;
import net.nestworld.module.NestWorldModule;
import net.nestworld.module.SyncDataSnapshot;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** External Fast Transition module loaded only from nestworld-modules/fast-transition.jar. */
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
        context.registerLoginBudget(new LoginBudget(32, 1024 * 1024));
        refreshManifest();
        context.registerListener(OnDatapackSyncEvent.class, EventPriority.LOWEST, false, this::onDatapackSync);
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
        LOGGER.info("Fast Transition external module enabled: bounded FML batching; full login retained; run={}", runId);
    }

    public ChunkPreparation prepare(ChunkPreparationRequest request) {
        return context == null ? null : context.prepareChunks(request);
    }

    public Manifest manifest() { return manifest; }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (context != null && context.ownsServer(event.getPlayerList().getServer()) && event.getPlayer() == null)
            refreshManifest();
    }

    private void refreshManifest() {
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
