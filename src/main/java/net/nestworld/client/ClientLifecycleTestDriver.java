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
    /** MODULE-LIFECYCLE-001: how many times to open "Create New World" and Cancel before world A. */
    private static final int CREATE_SCREEN_CANCELS = Integer.getInteger("nestworld.clientLifecycleTest.createScreenCancels", 0);
    /** Number of sequential worlds (server lifetimes) in this JVM: 2 = A,B; 3 = A,B,C. */
    private static final int WORLDS = Integer.getInteger("nestworld.clientLifecycleTest.worlds", 2);
    private static int cancelsDone = 0;
    private static int world = 0;
    private static final long WORLD_LOAD_TIMEOUT_MS = 180_000L;
    private static long waitWorldSince;

    private enum State { WAIT_TITLE, WAIT_WORLD, SETTLE, RUNNING, QUIT, HOLD, DONE }

    private static State state = State.WAIT_TITLE;
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
                    if (world == 0 && cancelsDone < CREATE_SCREEN_CANCELS) {
                        cancelsDone++;
                        long createdBefore = IntegratedLifecycleTests.registriesCreated();
                        LOG.info("[API1B-CLOSURE] CLIENT Create New World screen #{} (loads data packs), then Cancel", cancelsDone);
                        net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc, mc.screen);
                        LOG.info("[API1B-CLOSURE] CLIENT screen after openFresh = {}",
                                mc.screen == null ? null : mc.screen.getClass().getSimpleName());
                        mc.setScreen(new TitleScreen());
                        IntegratedLifecycleTests.checkNoModuleLifecycleWithoutServer("createScreenCancel" + cancelsDone, createdBefore);
                        return;
                    }
                    createWorld(mc, "nwlc" + name());
                    waitWorldSince = System.currentTimeMillis();
                    state = State.WAIT_WORLD;
                }
            }
            case WAIT_WORLD -> {
                IntegratedServer s = mc.getSingleplayerServer();
                if (mc.player != null && mc.screen == null && s != null && !s.getPlayerList().getPlayers().isEmpty()) {
                    settleTicks = 0;
                    state = State.SETTLE;
                } else if (System.currentTimeMillis() - waitWorldSince > WORLD_LOAD_TIMEOUT_MS) {
                    LOG.error("[API1B-CLOSURE] CLIENT world {} did NOT load within {} ms (screen={}) -- RESULT {} -- FAILURES PRESENT",
                            name(), WORLD_LOAD_TIMEOUT_MS,
                            mc.screen == null ? null : mc.screen.getClass().getSimpleName(), IntegratedLifecycleTests.totals());
                    state = State.DONE;
                    mc.stop();
                }
            }
            case SETTLE -> {
                if (++settleTicks >= 60) {
                    IntegratedServer s = mc.getSingleplayerServer();
                    LOG.info("[API1B-CLOSURE] CLIENT world {} ready (server {}), starting phase {}",
                            name(), Integer.toHexString(System.identityHashCode(s)), name());
                    phaseDone = false;
                    int w = world;
                    Thread t = new Thread(() -> {
                        try {
                            if (w == 0) IntegratedLifecycleTests.runPhaseA(s);
                            else if (w == 1) IntegratedLifecycleTests.runPhaseB(s);
                            else IntegratedLifecycleTests.runPhaseC(s);
                        } finally {
                            phaseDone = true;
                        }
                    }, "API1B-CLOSURE-phase-" + name());
                    t.setDaemon(true);
                    t.start();
                    state = State.RUNNING;
                }
            }
            case RUNNING -> {
                if (phaseDone) state = State.QUIT;
            }
            case QUIT -> {
                LOG.info("[API1B-CLOSURE] CLIENT quitting world {} to title", name());
                if (mc.level != null) mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.literal("NestWorld lifecycle test: saving")));
                mc.setScreen(new TitleScreen());
                LOG.info("[API1B-CLOSURE] CLIENT back at title; integrated server stopped (singleplayerServer={})",
                        mc.getSingleplayerServer());
                if (world + 1 < WORLDS) {
                    if (world == 0) serverAStoppedAt = System.currentTimeMillis();
                    IntegratedLifecycleTests.boundarySnapshot();
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
                if ((world > 0 || now - holdStart >= HOLD_MS) && atMenu(mc)) {
                    world++;
                    state = State.WAIT_TITLE;
                }
            }
            case DONE -> { }
        }
    }

    private static String name() {
        return String.valueOf((char) ('A' + world));
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
