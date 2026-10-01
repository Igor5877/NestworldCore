package nwprobe;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nestworld.modapi.inventory.InventorySlotVersionTracker;
import net.nestworld.modapi.inventory.InventoryTargetKey;
import net.nestworld.modapi.inventory.NestWorldInventory;
import net.nestworld.modapi.inventory.PlayerIncarnationRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mod API compatibility consumer (tests/modapi-compat). Compiles against the published API with
 * {@code compileOnly}; the runtime API comes from the nestworld_modapi provider mod (external,
 * jar-in-jar, or NestWorld's built-in copy). {@code smoke} needs no player and is the matrix
 * runner's pass criterion; the other scenarios are the bot-driven API-1B.7 stock-Forge fixtures
 * (they report whether NestWorldCore is present, so on NestWorld they label it "UNEXPECTED").
 *
 * <p>No Core barriers exist here. A request is held "in flight" by parking the main thread's task
 * queue behind a latch: while parked, packets (respawn, disconnect) still arrive on Netty threads
 * and queue up behind it, so after release the server executes queued tasks in FIFO order --
 * exactly the ordering a busy server produces naturally. The Forge peer emits no telemetry, so
 * outcomes are proven from end state, the version tracker's read-only diagnostics, and a FIFO
 * sentinel task that guarantees every earlier-queued apply has run.
 */
@Mod("nwinvprobe")
public class NwInvProbe {
    static final Logger LOG = LogManager.getLogger("NWPROBE");
    static final Capability<Counter> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    static final AtomicBoolean CAP_ARMED = new AtomicBoolean(false);
    static final AtomicReference<Consumer<ServerPlayer>> RESPAWN_HOOK = new AtomicReference<>();
    static final AtomicReference<Consumer<ServerPlayer>> LOGOUT_HOOK = new AtomicReference<>();

    static final class Counter { int value; }

    public NwInvProbe() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    // ------------------------------------------------------------------ wiring

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("nwprobe").requires(s -> s.hasPermission(2))
                .then(Commands.argument("scenario", StringArgumentType.word())
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> start(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "scenario"),
                                        StringArgumentType.getString(ctx, "player"))))));
    }

    @SubscribeEvent
    public void attachCaps(AttachCapabilitiesEvent<ItemStack> e) {
        if (!CAP_ARMED.get() || !e.getObject().is(Items.KNOWLEDGE_BOOK)) return;
        Counter c = new Counter();
        LazyOptional<Counter> lo = LazyOptional.of(() -> c);
        e.addCapability(new ResourceLocation("nwinvprobe", "counter"), new ICapabilitySerializable<CompoundTag>() {
            @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                return cap == CAP ? lo.cast() : LazyOptional.empty();
            }
            @Override public CompoundTag serializeNBT() { CompoundTag t = new CompoundTag(); t.putInt("v", c.value); return t; }
            @Override public void deserializeNBT(CompoundTag t) { c.value = t.getInt("v"); }
        });
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        LOG.info("[NWPROBE] event RESPAWN newObj={} thread={}", obj(e.getEntity()), Thread.currentThread().getName());
        Consumer<ServerPlayer> h = RESPAWN_HOOK.getAndSet(null);
        if (h != null) h.accept((ServerPlayer) e.getEntity());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LOG.info("[NWPROBE] event LOGOUT obj={} thread={}", obj(e.getEntity()), Thread.currentThread().getName());
        Consumer<ServerPlayer> h = LOGOUT_HOOK.getAndSet(null);
        if (h != null) h.accept((ServerPlayer) e.getEntity());
    }

    private static int start(CommandSourceStack src, String scenario, String name) {
        MinecraftServer s = src.getServer();
        Thread t = new Thread(() -> {
            LOG.info("[NWPROBE] scenario={} STARTED", scenario);
            try {
                switch (scenario) {
                    case "snapshot" -> snapshot(s, name);
                    case "respawn" -> inFlightRespawn(s, name, false);
                    case "crossboundary" -> inFlightRespawn(s, name, true);
                    case "stale" -> staleReference(s, name);
                    case "logout" -> logoutBeforeApply(s, name);
                    case "smoke" -> smoke(s);
                    default -> LOG.info("[NWPROBE] unknown scenario {}", scenario);
                }
            } catch (Throwable e) {
                LOG.error("[NWPROBE] EXCEPTION", e);
            } finally {
                CAP_ARMED.set(false);
                RESPAWN_HOOK.set(null);
                LOG.info("[NWPROBE] scenario={} FINISHED", scenario);
            }
        }, "nwprobe-" + scenario);
        t.setDaemon(true);
        t.start();
        src.sendSuccess(() -> Component.literal("nwprobe " + scenario + " started"), false);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    static void out(String line) { LOG.info("[NWPROBE] {}", line); }

    static String obj(Object o) { return o == null ? "null" : Integer.toHexString(System.identityHashCode(o)); }

    static String desc(ItemStack s) { return s.isEmpty() ? "EMPTY" : s.getItem() + "x" + s.getCount(); }

    static <T> T onMain(MinecraftServer s, Supplier<T> f) throws Exception {
        return s.submit(f::get).get(10, TimeUnit.SECONDS);
    }

    /** FIFO sentinel: returns once every task queued before it has executed. */
    static void drain(MinecraftServer s) throws Exception {
        s.submit(() -> {}).get(15, TimeUnit.SECONDS);
    }

    /** Parks the main thread's task queue until the returned latch is released. */
    static CountDownLatch gate(MinecraftServer s) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        s.execute(() -> {
            entered.countDown();
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });
        entered.await(10, TimeUnit.SECONDS);
        return release;
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(25); }
        return c.getAsBoolean();
    }

    static void proveNoCore() {
        boolean core;
        try { Class.forName("net.nestworld.region.NestworldRegionSystem"); core = true; }
        catch (ClassNotFoundException e) { core = false; }
        out("runtime: NestWorldCore present=" + core + " -> only possible peer: " + (core ? "UNEXPECTED" : "ForgeInventorySetExecution"));
    }

    static ServerPlayer player(MinecraftServer s, String name) throws Exception {
        return onMain(s, () -> s.getPlayerList().getPlayerByName(name));
    }

    static ServerPlayer awaitNewObject(MinecraftServer s, UUID id, ServerPlayer old) throws Exception {
        AtomicReference<ServerPlayer> seen = new AtomicReference<>();
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            ServerPlayer cur = onMain(s, () -> s.getPlayerList().getPlayer(id));
            if (cur != null && cur != old) { seen.set(cur); break; }
            Thread.sleep(50);
        }
        return seen.get();
    }

    static void awaitMarker(String file) throws InterruptedException {
        Path p = Path.of(file);
        waitFor(() -> Files.exists(p), 60_000);
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ smoke (no player needed)

    /** Provider resolution + one real call per primitive, no player required:
     *  setVelocity on a fresh armor stand at spawn (verified by observed motion), and the setSlot
     *  path entered far enough to link NestWorldInventory (invalid slot -> facade rejects early). */
    static void smoke(MinecraftServer s) throws Exception {
        out("=== SMOKE: provider resolution + real API calls ===");
        proveNoCore();
        var prov = net.minecraftforge.fml.ModList.get().getModContainerById("nestworld_modapi");
        out("provider: " + prov.map(c -> c.getModInfo().getVersion() + " from " + c.getModInfo().getOwningFile().getFile().getFileName()).orElse("NONE"));
        AtomicReference<net.minecraft.world.entity.decoration.ArmorStand> ref = new AtomicReference<>();
        onMain(s, () -> {
            net.minecraft.server.level.ServerLevel lvl = s.overworld();
            net.minecraft.core.BlockPos sp = lvl.getSharedSpawnPos();
            var a = new net.minecraft.world.entity.decoration.ArmorStand(lvl, sp.getX() + 0.5, sp.getY() + 3, sp.getZ() + 0.5);
            a.setNoGravity(true);
            lvl.addFreshEntity(a);
            ref.set(a);
            return null;
        });
        net.nestworld.modapi.entity.NestWorldEntities.setVelocity(ref.get(), new net.minecraft.world.phys.Vec3(1.5, 0, 0));
        double max = 0;
        for (int i = 0; i < 40; i++) {
            max = Math.max(max, onMain(s, () -> ref.get().getDeltaMovement().x));
            Thread.sleep(50);
        }
        out("setVelocity: max observed delta x over 40 samples = " + String.format("%.3f", max) + " (requested 1.5)");
        String slotPath;
        try {
            NestWorldInventory.setSlot(null, -1, ItemStack.EMPTY); // facade rejects the slot before touching the player
            slotPath = "linked (invalid slot rejected by the facade)";
        } catch (Throwable t) {
            slotPath = "FAILED: " + t;
        }
        out("setSlot path: " + slotPath);
        onMain(s, () -> { ref.get().discard(); return null; });
        boolean pass = prov.isPresent() && max >= 1.0 && slotPath.startsWith("linked");
        out("RESULT SMOKE: " + (pass ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------ A (+ worker-thread submission)

    static void snapshot(MinecraftServer s, String name) throws Exception {
        out("=== STOCK-A: snapshot independence + worker-thread submission (count, nested NBT, capability) ===");
        proveNoCore();
        CAP_ARMED.set(true);
        ServerPlayer p = player(s, name);
        int slot = 0;
        ItemStack orig = new ItemStack(Items.KNOWLEDGE_BOOK, 5);
        CompoundTag inner = new CompoundTag(); inner.putInt("v", 1);
        CompoundTag nw = new CompoundTag(); nw.put("inner", inner);
        orig.getOrCreateTag().put("nw", nw);
        Counter oc = orig.getCapability(CAP).resolve().orElse(null);
        if (oc == null) { out("SETUP FAILURE: capability not attached"); return; }
        oc.value = 111;
        long tok = PlayerIncarnationRegistry.incarnationOf(p);
        InventoryTargetKey key = new InventoryTargetKey(p.getUUID(), slot);
        CountDownLatch g = gate(s);
        out("main task queue gated; submitting from thread=" + Thread.currentThread().getName());
        NestWorldInventory.setSlot(p, slot, orig);
        orig.setCount(64);
        orig.getTag().getCompound("nw").getCompound("inner").putInt("v", 999);
        oc.value = 999;
        Long atMutation = InventorySlotVersionTracker.peekLastApplied(key, tok);
        out("setSlot returned on the worker thread; caller mutations done; lastApplied at that moment=" + atMutation
                + " (null = not applied on the caller thread -> marshaled)");
        g.countDown();
        drain(s);
        ItemStack applied = onMain(s, () -> p.getInventory().getItem(slot));
        Counter ac = applied.getCapability(CAP).resolve().orElse(null);
        int v = applied.hasTag() ? applied.getTag().getCompound("nw").getCompound("inner").getInt("v") : -1;
        out("applied: " + desc(applied) + " nw.inner.v=" + v + " cap=" + (ac == null ? "NO-CAP" : ac.value)
                + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tok)
                + " shared: stack=" + (applied == orig) + " tag=" + (applied.getTag() == orig.getTag()) + " cap=" + (ac == oc));
        boolean pass = atMutation == null && applied.is(Items.KNOWLEDGE_BOOK) && applied.getCount() == 5 && v == 1
                && ac != null && ac.value == 111 && applied != orig && applied.getTag() != orig.getTag() && ac != oc
                && Long.valueOf(1).equals(InventorySlotVersionTracker.peekLastApplied(key, tok));
        out("RESULT STOCK-A: " + (pass ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------ C / D (in-flight across a real respawn)

    /**
     * Sequence (task-queue order after the gate opens): [respawn packet task] -> [A's request apply]
     * (-> [B's V1 apply], D only). A's request is SUBMITTED while object A is still the published
     * player (the respawn packet is queued but not executed), and APPLIED after B is published.
     */
    static void inFlightRespawn(MinecraftServer s, String name, boolean withV1) throws Exception {
        String tag = withV1 ? "STOCK-D" : "STOCK-C";
        out("=== " + tag + ": request in flight across a REAL respawn" + (withV1 ? " + new-incarnation V1" : "") + " ===");
        proveNoCore();
        ServerPlayer a = player(s, name);
        UUID id = a.getUUID();
        int slot = withV1 ? 3 : 2;
        InventoryTargetKey key = new InventoryTargetKey(id, slot);
        long tokA = PlayerIncarnationRegistry.incarnationOf(a);
        out("object A=" + obj(a) + " token=" + tokA);
        out("ACTION_REQUIRED kill player=" + name);
        // The CLIENT must have seen its own death before the queue is gated, otherwise the bot never
        // sends a respawn packet (first run: death packet unflushed while gated -> no respawn).
        awaitMarker("killed.seen");
        out("A is dead and still the published object: " + (player(s, name) == a));
        CountDownLatch g = gate(s);
        out("main task queue gated");
        out("ACTION_REQUIRED respawn player=" + name);
        awaitMarker("respawn.sent");
        Thread.sleep(500); // margin for the packet to cross Netty into the queue
        out("respawn packet sent by the bot (queued behind the gate)");

        AtomicReference<Long> tokBRef = new AtomicReference<>();
        AtomicReference<Long> v1Ref = new AtomicReference<>();
        AtomicReference<ServerPlayer> bRef = new AtomicReference<>();
        if (withV1) {
            RESPAWN_HOOK.set(b -> {
                // Inside the respawn task (B just published): submit V1 for B from a WORKER thread,
                // so its apply is queued behind A's already-queued request.
                Thread w = new Thread(() -> {
                    long tokB = PlayerIncarnationRegistry.incarnationOf(b);
                    NestWorldInventory.setSlot(b, slot, new ItemStack(Items.APPLE));
                    tokBRef.set(tokB);
                    v1Ref.set(InventorySlotVersionTracker.peekAssigned(key, tokB));
                }, "nwprobe-V1-submitter");
                w.start();
                try { w.join(5000); } catch (InterruptedException ignored) {}
                bRef.set(b);
                out("in respawn task: B=" + obj(b) + " token=" + tokBRef.get() + " -> V" + v1Ref.get() + " (apple) submitted in domain(B) from a worker");
            });
        }

        long vOld;
        if (withV1) {
            while (InventorySlotVersionTracker.peekAssigned(key, tokA) < 999) InventorySlotVersionTracker.assignVersion(key, tokA);
        }
        NestWorldInventory.setSlot(a, slot, new ItemStack(withV1 ? Items.NETHERITE_INGOT : Items.GOLD_INGOT, 3));
        vOld = InventorySlotVersionTracker.peekAssigned(key, tokA);
        out("A's request submitted while A is still published: token=" + tokA + " V" + vOld + " (queued behind the respawn task)");

        g.countDown();
        ServerPlayer b = awaitNewObject(s, id, a);
        drain(s);
        if (b == null) { out("TIMEOUT waiting for the respawn"); return; }
        long tokBnow = PlayerIncarnationRegistry.incarnationOf(b);
        ItemStack bs = onMain(s, () -> b.getInventory().getItem(slot));
        out("after FIFO drain: B=" + obj(b) + " token=" + tokBnow + " B.slot=" + desc(bs)
                + " | domain(A): assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA)
                + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokA)
                + " | domain(B): assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokBnow)
                + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokBnow));
        boolean oldRejected = InventorySlotVersionTracker.peekLastApplied(key, tokA) == null
                && !bs.is(Items.NETHERITE_INGOT) && !bs.is(Items.GOLD_INGOT);
        boolean pass = tokA != tokBnow && oldRejected && (!withV1
                || (bs.is(Items.APPLE) && Long.valueOf(1).equals(InventorySlotVersionTracker.peekLastApplied(key, tokBnow))
                    && tokBRef.get() != null && tokBRef.get() == tokBnow && vOld == 1000));
        out("RESULT " + tag + ": " + (pass ? "PASS" : "FAIL") + " (A's V" + vOld + " applied after B was published -> rejected, domain(A) untouched"
                + (withV1 ? "; B's V1 applied, domain(B).lastApplied=1" : "; B.slot untouched") + ")");
    }

    // ------------------------------------------------------------------ stale caller reference

    static void staleReference(MinecraftServer s, String name) throws Exception {
        out("=== STOCK-STALE: caller keeps a pre-respawn ServerPlayer reference and calls setSlot after respawn ===");
        proveNoCore();
        ServerPlayer a = player(s, name);
        UUID id = a.getUUID();
        int slot = 5;
        InventoryTargetKey key = new InventoryTargetKey(id, slot);
        long tokA = PlayerIncarnationRegistry.incarnationOf(a);
        out("ACTION_REQUIRED kill player=" + name);
        // The CLIENT must have seen its own death before the queue is gated, otherwise the bot never
        // sends a respawn packet (first run: death packet unflushed while gated -> no respawn).
        awaitMarker("killed.seen");
        out("ACTION_REQUIRED respawn player=" + name);
        ServerPlayer b = awaitNewObject(s, id, a);
        if (b == null) { out("TIMEOUT"); return; }
        NestWorldInventory.setSlot(a, slot, new ItemStack(Items.TNT, 13));
        drain(s);
        long tokB = PlayerIncarnationRegistry.incarnationOf(b);
        ItemStack bs = onMain(s, () -> b.getInventory().getItem(slot));
        out("setSlot(A-ref) after respawn: tokenA=" + tokA + " tokenB=" + tokB + " B.slot=" + desc(bs)
                + " domain(A): assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA)
                + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokA));
        boolean pass = tokA != tokB && !bs.is(Items.TNT) && InventorySlotVersionTracker.peekLastApplied(key, tokA) == null;
        out("RESULT STOCK-STALE: " + (pass ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------ logout before apply, then relogin

    static void logoutBeforeApply(MinecraftServer s, String name) throws Exception {
        out("=== STOCK-LOGOUT: request submitted during logout, applied after removal; then relogin ===");
        proveNoCore();
        ServerPlayer a = player(s, name);
        UUID id = a.getUUID();
        int slot = 4;
        InventoryTargetKey key = new InventoryTargetKey(id, slot);
        long tokA = PlayerIncarnationRegistry.incarnationOf(a);
        CountDownLatch submitted = new CountDownLatch(1);
        LOGOUT_HOOK.set(p -> {
            Thread w = new Thread(() -> { NestWorldInventory.setSlot(p, slot, new ItemStack(Items.EMERALD, 7)); submitted.countDown(); });
            w.start();
            try { w.join(5000); } catch (InterruptedException ignored) {}
            out("in logout task: request for A=" + obj(p) + " submitted from a worker (apply queued after the logout task)");
        });
        out("ACTION_REQUIRED relogin player=" + name);
        submitted.await(60, TimeUnit.SECONDS);
        drain(s);
        out("after logout drain: online=" + (onMain(s, () -> s.getPlayerList().getPlayer(id)) != null)
                + " domain(A): assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA)
                + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokA));
        ServerPlayer b = awaitNewObject(s, id, a);
        if (b == null) { out("TIMEOUT waiting for relogin"); return; }
        drain(s);
        long tokB = PlayerIncarnationRegistry.incarnationOf(b);
        ItemStack bs = onMain(s, () -> b.getInventory().getItem(slot));
        out("relogged in: B=" + obj(b) + " tokenA=" + tokA + " tokenB=" + tokB + " B.slot=" + desc(bs)
                + " domain(A).lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tokA));
        boolean pass = tokA != tokB && InventorySlotVersionTracker.peekLastApplied(key, tokA) == null && !bs.is(Items.EMERALD);
        out("RESULT STOCK-LOGOUT: " + (pass ? "PASS" : "FAIL") + " (offline at apply -> no-op; relogged-in inventory untouched)");
    }
}
