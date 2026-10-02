package net.nestworld.region;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nestworld.modapi.entity.NestWorldEntities;
import net.nestworld.modapi.entity.SetVelocityIntentTracker;
import net.nestworld.modapi.inventory.InventorySlotVersionTracker;
import net.nestworld.modapi.inventory.InventoryTargetKey;
import net.nestworld.modapi.inventory.NestWorldInventory;
import net.nestworld.modapi.inventory.PlayerIncarnationRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * API-1B-CLOSURE TEST-ONLY: server-side half of the integrated-server / multi-server-in-one-JVM
 * lifecycle fixture. Driven by the client-side {@code net.nestworld.client.ClientLifecycleTestDriver}
 * (enabled only with {@code -Dnestworld.clientLifecycleTest=true}) inside a real client JVM:
 * <pre>
 *   world A (IntegratedServer A): setSlot fixtures + "version 1000" setup + A identity capture
 *   quit to title  -> boundary snapshot of every JVM-global API-1A/1B/region-runtime state
 *   world B (IntegratedServer B): isolation checks + fixtures again
 * </pre>
 * Invariant under test: ServerLifetime(A) ∩ MutableRuntimeState(ServerLifetime(B)) = ∅ — statics
 * need not be physically null, but no mutable state of A may influence the semantics of B.
 * Every result line is logged as {@code [API1B-CLOSURE] PASS|FAIL|NOTE ...}.
 */
public final class IntegratedLifecycleTests {
    private static final Logger LOG = LogManager.getLogger("NestWorld/API1B-CLOSURE");

    private IntegratedLifecycleTests() {}

    private static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        LOG.info("[API1B-CLOSURE] {} {} -- {}", ok ? "PASS" : "FAIL", name, detail);
    }

    static void note(String s) {
        LOG.info("[API1B-CLOSURE] NOTE {}", s);
    }

    public static String totals() {
        return "pass=" + pass + " fail=" + fail;
    }

    public static boolean anyFailure() {
        return fail > 0;
    }

    // ------------------------------------------------------------------ server A identity (weak)

    private static WeakReference<MinecraftServer> aServer;
    private static WeakReference<NestworldRegionSystem> aSystem;
    private static WeakReference<ServerPlayer> aPlayer;
    private static WeakReference<Object> aInventoryBackend;
    private static UUID playerId;
    private static long tokA = -1;
    private static final int ISO_SLOT = 7;

    // ------------------------------------------------------------------ helpers

    private static <T> T onMain(MinecraftServer server, Callable<T> c) throws Exception {
        return server.submit(() -> {
            try {
                return c.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(30, TimeUnit.SECONDS);
    }

    private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
    }

    private static Object staticField(String cls, String name) throws Exception {
        Field f = Class.forName(cls).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static long sizeOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Map<?, ?> m) return m.size();
        if (o instanceof Collection<?> c) return c.size();
        return -1;
    }

    private static ServerPlayer player(MinecraftServer server) throws Exception {
        return onMain(server, () -> server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0));
    }

    /** Runs one Api1B6 fixture against the CURRENT player object and returns PASS/FAIL. */
    private static void fixture(MinecraftServer server, String phase, String name,
                                java.util.function.BiFunction<NestworldRegionSystem, ServerPlayer, List<String>> f) throws Exception {
        ServerPlayer p = player(server);
        List<String> out = f.apply(NestworldRegionSystem.get(), p);
        String result = out.stream().filter(l -> l.startsWith("RESULT ")).reduce((a, b) -> b).orElse("RESULT <none>");
        check(phase + ".fixture." + name, result.contains(": PASS"), result.length() > 200 ? result.substring(0, 200) : result);
    }

    private static List<String> safe(Callable<List<String>> c) {
        try {
            return c.call();
        } catch (Exception e) {
            return List.of("RESULT EXCEPTION: FAIL " + e);
        }
    }

    /** Respawn driver for the fixtures' ACTION_REQUIRED lines: kill the player server-side; with
     *  doImmediateRespawn the client respawns at once (a real ServerPlayer removal + new object). */
    private static void installActionHook(MinecraftServer server) {
        Api1B6DeterministicTests.ACTION_HOOK = line -> {
            if (line.startsWith("ACTION_REQUIRED respawn")) {
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
                    if (p != null) p.kill();
                });
            } else {
                note("unhandled fixture action in single-player: " + line);
            }
        };
    }

    private static void prepareWorld(MinecraftServer server) throws Exception {
        onMain(server, () -> {
            GameRules rules = server.getGameRules();
            rules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
            return null;
        });
    }

    // ------------------------------------------------------------------ phase A

    /** World A: fixtures on a real IntegratedServer, then the V1000 setup, then capture A identity. */
    public static void runPhaseA(MinecraftServer server) {
        try {
            note("phase A on " + server.getClass().getSimpleName() + " dedicated=" + server.isDedicatedServer()
                    + " serverObj=" + Integer.toHexString(System.identityHashCode(server)));
            check("A.integratedServer", !server.isDedicatedServer(), "server class " + server.getClass().getName());
            check("A.regionSystemActive", NestworldRegionSystem.isInitialised(), "NestworldRegionSystem initialised on the integrated server");
            prepareWorld(server);
            installActionHook(server);
            // setSlot on a real integrated server: direct owner, routed / foreign thread,
            // snapshot isolation, LATEST_INTENT_WINS ordering, respawn removal transitions.
            fixture(server, "A", "snapshot", (s, p) -> safe(() -> Api1B6DeterministicTests.runSnapshotIndependenceTest(s, p)));
            fixture(server, "A", "ordering", (s, p) -> safe(() -> Api1B6DeterministicTests.runSameIncarnationOrderingTest(s, p)));
            fixture(server, "A", "equalstrap", (s, p) -> safe(() -> Api1B6DeterministicTests.runEqualsTrapTest(s, p)));
            fixture(server, "A", "worker-direct", (s, p) -> safe(() -> Api1B6DeterministicTests.runWorkerThreadTest(s, p, false)));
            fixture(server, "A", "worker-routed", (s, p) -> safe(() -> Api1B6DeterministicTests.runWorkerThreadTest(s, p, true)));
            fixture(server, "A", "respawn-direct", (s, p) -> safe(() -> Api1B6DeterministicTests.runTransitionRejectTest(s, p, "respawn", false)));
            fixture(server, "A", "respawn-routed", (s, p) -> safe(() -> Api1B6DeterministicTests.runTransitionRejectTest(s, p, "respawn", true)));
            fixture(server, "A", "crossboundary-direct", (s, p) -> safe(() -> Api1B6DeterministicTests.runCrossBoundaryOrderingTest(s, p, false)));
            fixture(server, "A", "crossboundary-routed", (s, p) -> safe(() -> Api1B6DeterministicTests.runCrossBoundaryOrderingTest(s, p, true)));
            fixture(server, "A", "window-respawn-routed", (s, p) -> safe(() -> Api1B6DeterministicTests.runPublicationWindowTest(s, p, "respawn", true)));
            fixture(server, "A", "slots", (s, p) -> safe(() -> Api1B6DeterministicTests.runSlotIndependenceTest(s, p)));
            fixture(server, "A", "invalid", (s, p) -> safe(() -> Api1B6DeterministicTests.runInvalidSlotTest(s, p)));
            fixture(server, "A", "reroutelimit", (s, p) -> safe(() -> Api1B6DeterministicTests.runRerouteLimitTest(s, p)));
            fixture(server, "A", "generation", (s, p) -> safe(() -> Api1B6DeterministicTests.runGenerationResetTest(s, p)));
            note("relogin/offline fixtures not run in single-player: logging out ends the integrated server; "
                    + "the respawn transition is the in-world ServerPlayer removal, the world stop is the logout");

            // Isolation setup: same UUID + slot reaches version 1000 in A's (UUID, slot, incarnation) domain.
            ServerPlayer p = player(server);
            playerId = p.getUUID();
            tokA = PlayerIncarnationRegistry.incarnationOf(p);
            InventoryTargetKey key = new InventoryTargetKey(playerId, ISO_SLOT);
            long v = 0;
            while (v < 999) v = InventorySlotVersionTracker.assignVersion(key, tokA);
            NestWorldInventory.setSlot(p, ISO_SLOT, new ItemStack(Items.NETHERITE_INGOT));
            boolean applied = waitFor(() -> Long.valueOf(1000).equals(InventorySlotVersionTracker.peekLastApplied(key, tokA)), 10_000);
            check("A.version1000Applied", applied && InventorySlotVersionTracker.peekAssigned(key, tokA) == 1000,
                    "domain(A) assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA) + " lastApplied="
                            + InventorySlotVersionTracker.peekLastApplied(key, tokA) + " tokA=" + tokA);
            // setVelocity state in A (same UUID key in B): version counter for (overworld, player UUID).
            for (int i = 0; i < 5; i++) NestWorldEntities.setVelocity(p, new Vec3(0, 0.01, 0));
            note("A setVelocity counter for player = " + velocityAssigned(Level.OVERWORLD, playerId));

            aServer = new WeakReference<>(server);
            aSystem = new WeakReference<>(NestworldRegionSystem.get());
            aPlayer = new WeakReference<>(p);
            aInventoryBackend = new WeakReference<>(staticField("net.nestworld.modapi.inventory.InventorySetBackendRegistry", "backend"));
            note("A identity: server=" + hex(server) + " regionSystem=" + hex(aSystem.get()) + " player=" + hex(p)
                    + " invBackend=" + hex(aInventoryBackend.get()) + " tokA=" + tokA);
        } catch (Throwable t) {
            check("A.exception", false, t.toString());
            LOG.error("[API1B-CLOSURE] phase A threw", t);
        } finally {
            Api1B6DeterministicTests.ACTION_HOOK = null;
        }
    }

    private static String hex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    @SuppressWarnings("unchecked")
    private static long velocityAssigned(net.minecraft.resources.ResourceKey<Level> dim, UUID id) throws Exception {
        Map<Object, java.util.concurrent.atomic.AtomicLong> m =
                (Map<Object, java.util.concurrent.atomic.AtomicLong>) staticField("net.nestworld.modapi.entity.SetVelocityIntentTracker", "COUNTERS");
        var a = m.get(new SetVelocityIntentTracker.TargetKey(dim, id));
        return a == null ? 0 : a.get();
    }

    // ------------------------------------------------------------------ boundary

    private enum Kind { SEMANTIC, CONFIG, METRIC }

    private record Item(Kind kind, String cls, String field) {}

    private static final String R = "net.nestworld.region.";
    private static final List<Item> STATICS = List.of(
            new Item(Kind.SEMANTIC, R + "NestworldRegionSystem", "INSTANCE"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.entity.VelocityBackendRegistry", "backend"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.entity.VelocityBackendRegistry", "installedFor"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.inventory.InventorySetBackendRegistry", "backend"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.inventory.InventorySetBackendRegistry", "installedFor"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.entity.SetVelocityIntentTracker", "COUNTERS"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.entity.SetVelocityIntentTracker", "LAST_APPLIED"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.inventory.InventorySlotVersionTracker", "COUNTERS"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.inventory.InventorySlotVersionTracker", "LAST_APPLIED"),
            new Item(Kind.SEMANTIC, "net.nestworld.modapi.inventory.PlayerIncarnationRegistry", "BY_UUID"),
            new Item(Kind.SEMANTIC, R + "MailboxLifecycle", "ORPHANS"),
            new Item(Kind.SEMANTIC, R + "MailboxLifecycle", "RECENTLY_RETIRED"),
            new Item(Kind.SEMANTIC, R + "MailboxLifecycle", "EXIT_WATCH"),
            new Item(Kind.SEMANTIC, R + "MailboxAudit", "inFlight"),
            new Item(Kind.SEMANTIC, R + "ModCallbackRouter", "PENDING"),
            new Item(Kind.SEMANTIC, R + "ChunkOutboundQueue", "byKey"),
            new Item(Kind.SEMANTIC, R + "PairingOutboundQueue", "byPlayerEntityKey"),
            new Item(Kind.SEMANTIC, R + "PairingOutboundQueue", "byPlayerId"),
            new Item(Kind.SEMANTIC, R + "ConnectionAdmissionGate", "waiting"),
            new Item(Kind.SEMANTIC, R + "PairingAdmissionGate", "perPlayerAdmittedThisTick"),
            new Item(Kind.SEMANTIC, R + "PlayerInputDiagnostics", "pendingCounts"),
            new Item(Kind.SEMANTIC, "net.nestworld.module.ModuleRegistry", "MODULES"),
            new Item(Kind.SEMANTIC, "net.nestworld.module.ModuleRegistry", "FAILURES"),
            new Item(Kind.METRIC, "net.nestworld.module.ModuleRegistry", "GENERATION_COUNTERS"),
            new Item(Kind.METRIC, R + "RegionTimeCompatibility", "emaBatchSize"),
            new Item(Kind.METRIC, R + "SplitMergeEventLog", "events"),
            new Item(Kind.METRIC, R + "InventorySetTelemetry", "applied"));

    /** Client thread, after world A's server is fully shut down and before world B exists. */
    public static void boundarySnapshot() {
        note("=== boundary: A stopped, B not started ===");
        for (Item it : STATICS) {
            String what = it.cls.substring(it.cls.lastIndexOf('.') + 1) + "." + it.field;
            try {
                Object v = staticField(it.cls, it.field);
                long size = sizeOf(v);
                String shown = v == null ? "null" : size >= 0 ? "size=" + size : (v.getClass().getSimpleName() + "@" + hex(v)
                        + (v instanceof java.util.concurrent.atomic.AtomicLong a ? "=" + a.get() : ""));
                boolean empty = size >= 0 ? size == 0 : v == null;
                if (it.kind == Kind.SEMANTIC) check("boundary." + what, empty, shown);
                else note("boundary." + what + " (" + it.kind + ") " + shown);
            } catch (Throwable t) {
                check("boundary." + what, false, "unreadable: " + t);
            }
        }
        // Operator opt-ins (JVM-scoped configuration, keyed by UUID -- would carry into B for the same UUID).
        note("boundary.experimentOptIns (CONFIG) playerTick=" + PlayerTickExperiment.snapshot().size()
                + " attack=" + PlayerAttackExperiment.snapshot().size() + " interaction=" + PlayerInteractionExperiment.snapshot().size()
                + " useItem=" + PlayerUseItemExperiment.snapshot().size() + " useItemOnBlock=" + PlayerUseItemOnBlockExperiment.snapshot().size()
                + " entityInteract=" + PlayerEntityInteractExperiment.snapshot().size());
        check("boundary.testHooksDisarmed", !EntityMutationTestHooks.ENABLED && !MailboxLifecycleTestHooks.ENABLED,
                "EntityMutationTestHooks=" + EntityMutationTestHooks.ENABLED + " MailboxLifecycleTestHooks=" + MailboxLifecycleTestHooks.ENABLED);
        // Threads that belong to server A's lifetime.
        List<String> regionThreads = new ArrayList<>(), watchdogs = new ArrayList<>(), otherNw = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isAlive()) continue;
            String n = t.getName();
            if (n.startsWith("NestWorld-Region-")) regionThreads.add(n);
            else if (n.startsWith("NestWorld-Shutdown-Forced-Exit-Watchdog")) watchdogs.add(n);
            else if (n.toLowerCase().contains("nestworld")) otherNw.add(n);
        }
        check("boundary.regionThreadsGone", regionThreads.isEmpty(), "alive=" + regionThreads);
        check("boundary.noForcedExitWatchdog", watchdogs.isEmpty(),
                "alive=" + watchdogs + " (a forced-exit watchdog armed by an integrated-server stop would halt the CLIENT JVM)");
        note("boundary.otherNestWorldThreads " + otherNw);
    }

    /**
     * TEST-HARNESS WORKAROUND, NOT A PRODUCT FIX: MODULE-1's ModuleRegistry keeps server A's
     * discovered modules in JVM-global state, so server B's module discovery throws
     * "ModuleId already discovered" and world B fails to load (finding MODULE-LIFECYCLE, same root
     * as MODULE-RELOAD-001). To still measure API-1B isolation in B, the harness clears the
     * discovered-module map and failure list here (generation counters are left untouched, so no
     * generation number can be reused). The product bug stays open.
     */
    public static void harnessResetModuleRegistry() {
        try {
            synchronized (Class.forName("net.nestworld.module.ModuleRegistry")) {
                long modules = sizeOf(staticField("net.nestworld.module.ModuleRegistry", "MODULES"));
                ((Map<?, ?>) staticField("net.nestworld.module.ModuleRegistry", "MODULES")).clear();
                ((Collection<?>) staticField("net.nestworld.module.ModuleRegistry", "FAILURES")).clear();
                note("HARNESS WORKAROUND: cleared ModuleRegistry.MODULES (" + modules + " entries from server A) and FAILURES "
                        + "so world B can load -- product bug MODULE-LIFECYCLE remains open");
            }
        } catch (Throwable t) {
            note("HARNESS WORKAROUND failed: " + t);
        }
    }

    // ------------------------------------------------------------------ phase B

    /** World B: isolation from A, then fixtures again on the new server. */
    public static void runPhaseB(MinecraftServer server) {
        try {
            prepareWorld(server);
            installActionHook(server);
            ServerPlayer p = player(server);
            check("B.newServerObject", aServer == null || aServer.get() != server, "A=" + hex(aServer == null ? null : aServer.get()) + " B=" + hex(server));
            check("B.newRegionSystem", NestworldRegionSystem.isInitialised() && (aSystem == null || aSystem.get() != NestworldRegionSystem.get()),
                    "A=" + hex(aSystem == null ? null : aSystem.get()) + " B=" + hex(NestworldRegionSystem.get()));
            Object invBackend = staticField("net.nestworld.modapi.inventory.InventorySetBackendRegistry", "backend");
            Object invFor = staticField("net.nestworld.modapi.inventory.InventorySetBackendRegistry", "installedFor");
            Object velFor = staticField("net.nestworld.modapi.entity.VelocityBackendRegistry", "installedFor");
            check("B.backendsBoundToB", invFor == server && velFor == server && (aInventoryBackend == null || aInventoryBackend.get() != invBackend),
                    "inventory installedFor=" + hex(invFor) + " velocity installedFor=" + hex(velFor) + " B=" + hex(server)
                            + " invBackend A=" + hex(aInventoryBackend == null ? null : aInventoryBackend.get()) + " B=" + hex(invBackend));
            check("B.sameUuidNewPlayerObject", p.getUUID().equals(playerId) && (aPlayer == null || aPlayer.get() != p),
                    "uuid same=" + p.getUUID().equals(playerId) + " A=" + hex(aPlayer == null ? null : aPlayer.get()) + " B=" + hex(p));

            // The negative test: A reached version 1000 for (UUID, slot); B's first intent must be version 1 and apply.
            long tokB = PlayerIncarnationRegistry.incarnationOf(p);
            InventoryTargetKey key = new InventoryTargetKey(playerId, ISO_SLOT);
            check("B.freshIncarnation", tokB != tokA && tokB > tokA, "tokA=" + tokA + " tokB=" + tokB + " (tokens never reused)");
            check("B.noDomainFromA", InventorySlotVersionTracker.peekLastApplied(key, tokA) == null
                            && InventorySlotVersionTracker.domainCount(playerId) <= 1,
                    "domain(A).lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokA)
                            + " domainCount(uuid)=" + InventorySlotVersionTracker.domainCount(playerId));
            NestWorldInventory.setSlot(p, ISO_SLOT, new ItemStack(Items.APPLE));
            boolean applied = waitFor(() -> Long.valueOf(1).equals(InventorySlotVersionTracker.peekLastApplied(key, tokB)), 10_000);
            ItemStack now = onMain(server, () -> p.getInventory().getItem(ISO_SLOT).copy());
            check("B.firstIntentIsVersion1AndApplies", applied && InventorySlotVersionTracker.peekAssigned(key, tokB) == 1 && now.is(Items.APPLE),
                    "domain(B) assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokB) + " lastApplied="
                            + InventorySlotVersionTracker.peekLastApplied(key, tokB) + " slot=" + now);
            // setVelocity: same (dimension, UUID) key as in A must start a fresh counter.
            long before = velocityAssigned(Level.OVERWORLD, playerId);
            NestWorldEntities.setVelocity(p, new Vec3(0, 0.01, 0));
            long after = velocityAssigned(Level.OVERWORLD, playerId);
            check("B.velocityCounterFresh", before == 0 && after == 1, "player setVelocity counter before=" + before + " after first B call=" + after);
            // Velocity on a fresh entity routes and applies on B.
            ArmorStand stand = onMain(server, () -> {
                ServerLevel lvl = server.overworld();
                ArmorStand a = new ArmorStand(lvl, p.getX() + 1, p.getY(), p.getZ());
                a.setNoGravity(true);
                lvl.addFreshEntity(a);
                return a;
            });
            NestWorldEntities.setVelocity(stand, new Vec3(0.5, 0, 0));
            boolean moved = waitFor(() -> {
                try {
                    return onMain(server, () -> stand.getDeltaMovement().x != 0 || stand.getX() > p.getX() + 1.01);
                } catch (Exception e) {
                    return false;
                }
            }, 5_000);
            check("B.setVelocityApplies", moved, "fresh armor stand moved after setVelocity on server B");

            fixture(server, "B", "snapshot", (s, q) -> safe(() -> Api1B6DeterministicTests.runSnapshotIndependenceTest(s, q)));
            fixture(server, "B", "ordering", (s, q) -> safe(() -> Api1B6DeterministicTests.runSameIncarnationOrderingTest(s, q)));
            fixture(server, "B", "respawn-routed", (s, q) -> safe(() -> Api1B6DeterministicTests.runTransitionRejectTest(s, q, "respawn", true)));
            fixture(server, "B", "crossboundary-routed", (s, q) -> safe(() -> Api1B6DeterministicTests.runCrossBoundaryOrderingTest(s, q, true)));
            fixture(server, "B", "worker-routed", (s, q) -> safe(() -> Api1B6DeterministicTests.runWorkerThreadTest(s, q, true)));

            // No strong reference from JVM-global state keeps A's server, region system or player alive.
            if (aServer == null) throw new IllegalStateException("phase A captured no identity");
            for (int i = 0; i < 6 && (aServer.get() != null || aSystem.get() != null || aPlayer.get() != null); i++) {
                System.gc();
                Thread.sleep(300);
            }
            check("B.serverAObjectsCollectable", aServer.get() == null && aSystem.get() == null && aPlayer.get() == null,
                    "after GC: serverA=" + hex(aServer.get()) + " regionSystemA=" + hex(aSystem.get()) + " playerA=" + hex(aPlayer.get()));
            if (aServer.get() != null) {
                // Evidence for the leak path: a live-object heap dump to inspect for the GC root
                // still holding server A (test-only; path from -Dnestworld.clientLifecycleTest.heapDump).
                String dump = System.getProperty("nestworld.clientLifecycleTest.heapDump");
                if (dump != null) {
                    note("server A identity for the dump: " + aServer.get().getClass().getName() + "@" + hex(aServer.get())
                            + " worldName=" + aServer.get().getWorldData().getLevelName());
                    java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(dump, true);
                    note("heap dump written: " + dump);
                }
            }
        } catch (Throwable t) {
            check("B.exception", false, t.toString());
            LOG.error("[API1B-CLOSURE] phase B threw", t);
        } finally {
            Api1B6DeterministicTests.ACTION_HOOK = null;
        }
    }
}
