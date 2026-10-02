package net.nestworld.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.nestworld.region.IntegratedLifecycleTests;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * API-1B-CLOSURE TEST-ONLY client driver. Registered only on the client dist and only with
 * {@code -Dnestworld.clientLifecycleTest=true}. Drives a REAL client JVM through two complete
 * integrated-server lifetimes with no human input:
 * <pre>
 *   title -> create world A -> phase A (server-side fixtures) -> quit to title (server A stops)
 *         -> boundary snapshot -> hold at title (nestworld.clientLifecycleTest.titleHoldMs)
 *         -> create world B -> phase B (isolation + fixtures) -> quit to title -> stop client
 * </pre>
 * The hold at the title screen exists to observe the client JVM surviving well past any
 * shutdown watchdog delay after server A stopped.
 */
public final class ClientLifecycleTestDriver {
    private static final Logger LOG = LogManager.getLogger("NestWorld/API1B-CLOSURE");
    private static final long HOLD_MS = Long.getLong("nestworld.clientLifecycleTest.titleHoldMs", 5_000L);

    private enum State { WAIT_TITLE, WAIT_WORLD, SETTLE, RUNNING, QUIT, HOLD, DONE }

    private static State state = State.WAIT_TITLE;
    private static boolean second = false;
    private static boolean busy = false;
    private static int settleTicks = 0;
    private static volatile boolean phaseDone = false;
    private static long holdStart, lastAliveLog, serverAStoppedAt;

    private ClientLifecycleTestDriver() {}

    public static void register() {
        LOG.info("[API1B-CLOSURE] CLIENT driver armed (titleHoldMs={})", HOLD_MS);
        MinecraftForge.EVENT_BUS.addListener(ClientLifecycleTestDriver::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || busy) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DeathScreen && mc.player != null) {
            mc.player.respawn();
            mc.setScreen(null);
        }
        try {
            busy = true;
            step(mc);
        } catch (Throwable t) {
            LOG.error("[API1B-CLOSURE] CLIENT driver failure in state {}", state, t);
            state = State.DONE;
            mc.stop();
        } finally {
            busy = false;
        }
    }

    private static int diagTicks = 0;

    private static void step(Minecraft mc) {
        if (++diagTicks % 200 == 0 && state != State.RUNNING) {
            LOG.info("[API1B-CLOSURE] CLIENT state={} screen={} overlay={} level={}", state,
                    mc.screen == null ? null : mc.screen.getClass().getSimpleName(),
                    mc.getOverlay() == null ? null : mc.getOverlay().getClass().getSimpleName(), mc.level != null);
        }
        switch (state) {
            case WAIT_TITLE -> {
                // Any menu once resource loading is done (a first launch shows the accessibility
                // onboarding screen instead of the title screen).
                if (atMenu(mc)) {
                    createWorld(mc, second ? "nwlcB" : "nwlcA");
                    state = State.WAIT_WORLD;
                }
            }
            case WAIT_WORLD -> {
                IntegratedServer s = mc.getSingleplayerServer();
                if (mc.player != null && mc.screen == null && s != null && !s.getPlayerList().getPlayers().isEmpty()) {
                    settleTicks = 0;
                    state = State.SETTLE;
                }
            }
            case SETTLE -> {
                if (++settleTicks >= 60) {
                    IntegratedServer s = mc.getSingleplayerServer();
                    LOG.info("[API1B-CLOSURE] CLIENT world {} ready (server {}), starting phase {}",
                            second ? "B" : "A", Integer.toHexString(System.identityHashCode(s)), second ? "B" : "A");
                    phaseDone = false;
                    boolean b = second;
                    Thread t = new Thread(() -> {
                        try {
                            if (b) IntegratedLifecycleTests.runPhaseB(s); else IntegratedLifecycleTests.runPhaseA(s);
                        } finally {
                            phaseDone = true;
                        }
                    }, "API1B-CLOSURE-phase-" + (b ? "B" : "A"));
                    t.setDaemon(true);
                    t.start();
                    state = State.RUNNING;
                }
            }
            case RUNNING -> {
                if (phaseDone) state = State.QUIT;
            }
            case QUIT -> {
                LOG.info("[API1B-CLOSURE] CLIENT quitting world {} to title", second ? "B" : "A");
                if (mc.level != null) mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.literal("NestWorld lifecycle test: saving")));
                mc.setScreen(new TitleScreen());
                LOG.info("[API1B-CLOSURE] CLIENT back at title; integrated server stopped (singleplayerServer={})",
                        mc.getSingleplayerServer());
                if (!second) {
                    serverAStoppedAt = System.currentTimeMillis();
                    IntegratedLifecycleTests.boundarySnapshot();
                    IntegratedLifecycleTests.harnessResetModuleRegistry();
                    holdStart = lastAliveLog = System.currentTimeMillis();
                    state = State.HOLD;
                } else {
                    LOG.info("[API1B-CLOSURE] CLIENT client JVM alive {} ms after server A stopped; RESULT {}{}",
                            System.currentTimeMillis() - serverAStoppedAt, IntegratedLifecycleTests.totals(),
                            IntegratedLifecycleTests.anyFailure() ? " -- FAILURES PRESENT" : " -- ALL CHECKS PASS");
                    state = State.DONE;
                    mc.stop();
                }
            }
            case HOLD -> {
                long now = System.currentTimeMillis();
                if (now - lastAliveLog >= 10_000) {
                    lastAliveLog = now;
                    LOG.info("[API1B-CLOSURE] CLIENT alive at title, {} ms after server A stopped", now - serverAStoppedAt);
                }
                if (now - holdStart >= HOLD_MS && atMenu(mc)) {
                    second = true;
                    state = State.WAIT_TITLE;
                }
            }
            case DONE -> { }
        }
    }

    private static boolean atMenu(Minecraft mc) {
        return mc.level == null && mc.getOverlay() == null && mc.screen != null
                && !(mc.screen instanceof GenericDirtMessageScreen);
    }

    private static void createWorld(Minecraft mc, String base) {
        String name = base + "-" + System.currentTimeMillis();
        LOG.info("[API1B-CLOSURE] CLIENT creating world {}", name);
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261002L, false, false),
                ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }
}
