package net.nestworld.compat.ae2.glue;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nestworld.compat.ae2.kernel.Ae2Kernel;
import org.slf4j.Logger;

/** Process-wide state of the addon: the kernel, glue counters, the M5c allowlist, main-thread identity. */
public final class Ae2Compat {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Ae2Kernel KERNEL = new Ae2Kernel(msg -> LOG.warn("[nestworld-compat-ae2] {}", msg));

    /**
     * M5c runtime barrier allowlist (docs/compat/AE2-COMPAT-ALLOWLIST-001.md): concrete runtime classes
     * of {@code IAEPowerStorage} participants whose power-path code is audited. A mixin's runtime class
     * is its target (SolarFlux: {@code SolarPanelTile}). Subclasses are not implied.
     */
    static final Set<String> ALLOWED_PARTICIPANTS = Set.of(
            "appeng.blockentity.grid.AENetworkPowerBlockEntity",
            "appeng.blockentity.misc.ChargerBlockEntity",
            "appeng.blockentity.misc.InscriberBlockEntity",
            "appeng.blockentity.networking.ControllerBlockEntity",
            "appeng.blockentity.networking.CreativeEnergyCellBlockEntity",
            "appeng.blockentity.networking.EnergyAcceptorBlockEntity",
            "appeng.blockentity.networking.EnergyCellBlockEntity",
            "appeng.blockentity.storage.ChestBlockEntity",
            "appeng.me.energy.GridEnergyStorage",
            "appeng.parts.networking.EnergyAcceptorPart",
            "com.glodblock.github.extendedae.common.tileentities.TileExCharger",
            "com.glodblock.github.extendedae.common.tileentities.TileExInscriber",
            "com.glodblock.github.extendedae.common.tileentities.TileCaner",
            "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter",
            "net.pedroksl.advanced_ae.common.entities.QuantumCrafterEntity",
            "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity",
            "org.zeith.solarflux.block.SolarPanelTile");

    public static boolean isAllowedParticipant(Object participant) {
        return ALLOWED_PARTICIPANTS.contains(participant.getClass().getName());
    }

    /** Glue counters (001.5 §3.4, 001.4 §4, 001.4 §3.2). */
    public static final class Counters {
        public final LongAdder t2Suspensions = new LongAdder();
        public final LongAdder t2StaleTracker = new LongAdder();
        public final LongAdder t2ForeignAlert = new LongAdder();
        public final LongAdder t2ForeignWake = new LongAdder();
        public final LongAdder t2ForeignSleep = new LongAdder();
        public final LongAdder t2SelfCall = new LongAdder();
        public final LongAdder m1Deferred = new LongAdder();
        public final LongAdder m1InTopology = new LongAdder();
        public final LongAdder m1Unaudited = new LongAdder();
        public final LongAdder mainAffinityViolation = new LongAdder();
        public final LongAdder staleServiceRetry = new LongAdder();
        public final LongAdder queueStagedServer = new LongAdder();
        public final LongAdder queueStagedLevel = new LongAdder();
        public final LongAdder queueDroppedLevelUnload = new LongAdder();
        public final LongAdder setChangedDirty = new LongAdder();
        public final LongAdder setChangedRequeue = new LongAdder();
        public final LongAdder setChangedSelfSuppressed = new LongAdder();
        public final LongAdder setChangedUnexpectedClaim = new LongAdder();
        public final LongAdder initCancelled = new LongAdder();
        public final LongAdder lifeOrderViolation = new LongAdder();
        public final LongAdder pTryFailures = new LongAdder();
        public final LongAdder pRetryCount = new LongAdder();
        public final LongAdder pWaitNs = new LongAdder();
        public final java.util.concurrent.atomic.LongAccumulator pMaxRetry = new java.util.concurrent.atomic.LongAccumulator(Math::max, 0);
        public final LongAdder pWriterStarvationWarning = new LongAdder();
        public final LongAdder pNestedUnresolved = new LongAdder();
        public final LongAdder stagedRebuild = new LongAdder();
        public final LongAdder coalescedRebuild = new LongAdder();
        public final LongAdder stagedWatcherOps = new LongAdder();
        public final LongAdder drainedRebuild = new LongAdder();
        public final LongAdder drainedWatcherOps = new LongAdder();
        public final LongAdder droppedStaged = new LongAdder();
        public final LongAdder stagingErrors = new LongAdder();
        public final LongAdder stagingRunUnderP = new LongAdder();
    }

    public static final Counters COUNTERS = new Counters();

    /** Startup compatibility manifest result: true = every provider/service is in the audited manifest. */
    public static volatile boolean manifestEligible = true;
    public static volatile java.util.List<String> manifestProblems = java.util.List.of();

    /**
     * Canonical main-server-thread identity (001.5 §4): {@code BlockableEventLoop.isSameThread()} compares
     * against the unpatched {@code serverThread}, so a NestWorld RegionThread is not main. Not
     * {@code Platform.assertServerThread()}: region threads inherit the SERVER thread group.
     */
    public static boolean onMainThread() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }

    /** Main-affinity check for AE2 sites whose correctness premise is main-only execution (001.4 §3). */
    public static void assertMain(String site) {
        if (onMainThread() || ServerLifecycleHooks.getCurrentServer() == null) return;
        COUNTERS.mainAffinityViolation.increment();
        RuntimeException e = KERNEL.unaudited("main-thread affinity violated at " + site + " on " + Thread.currentThread().getName());
        if (Boolean.getBoolean("nestworld.compatAe2.failLoud")) throw e;
    }

    private Ae2Compat() {
    }
}
