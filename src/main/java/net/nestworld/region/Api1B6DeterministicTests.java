package net.nestworld.region;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.nestworld.modapi.inventory.InventorySlotVersionTracker;
import net.nestworld.modapi.inventory.InventoryTargetKey;
import net.nestworld.modapi.inventory.NestWorldInventory;
import net.nestworld.modapi.inventory.PlayerIncarnationRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * API-1B.7 TEST-ONLY. Deterministic fixtures for setSlot. Same posture as
 * {@link Api1A7DeterministicTests}: observes and reports, never applies a production fix.
 *
 * <p>Incarnation = one live ServerPlayer object ({@link PlayerIncarnationRegistry}, reference
 * identity). Lifecycle fixtures use REAL transitions of a connected bot, driven externally (RCON
 * {@code /kill}, bot reconnect); the fixture holds its intent at barrier A (its own thread,
 * pre-enqueue -- B/C would park a region thread and, in non-free-running mode, the main thread
 * that must process the transition).
 *
 * <p>Each lifecycle fixture has two routing variants:
 * <ul>
 *   <li><b>direct</b> -- the player as the world leaves it (in this config not region-owned, so
 *       {@code dispatch()} applies directly, against the object reference it was given);</li>
 *   <li><b>routed</b> -- the player assigned to a pinned region, so the request is enqueued and a
 *       region thread re-acquires the target BY UUID -- the NestWorld execution path that matters
 *       for cross-incarnation hazards. Regions are pinned for the fixture's duration so a merge
 *       cannot silently drop the message (Core finding CORE-016, out of API-1B's scope).</li>
 * </ul>
 * All barriers are filtered to SetInventorySlot ops ({@link EntityMutationTestHooks#ONLY_OP}) so
 * no unrelated dispatch() can be parked by a fixture.
 */
final class Api1B6DeterministicTests {
    private Api1B6DeterministicTests() {}

    private static final AtomicLong OPIDS = new AtomicLong(2_000_000_000L);

    /** Streams every line to the log the moment it is added. A fixture that waits for an
     *  external lifecycle action must have its ACTION_REQUIRED line visible while it waits --
     *  the first live D run buffered it until the end and timed out without a respawn. */
    static final class Out extends ArrayList<String> {
        private static final org.apache.logging.log4j.Logger DIAG = org.apache.logging.log4j.LogManager.getLogger("NestWorld/API1B7");
        @Override public boolean add(String line) { DIAG.info("[API1B6-DET] {}", line); return super.add(line); }
        @Override public boolean addAll(java.util.Collection<? extends String> c) { c.forEach(this::add); return !c.isEmpty(); }
    }

    // ------------------------------------------------------------------ shared helpers

    private static void begin(List<String> out, String title) {
        InventorySetTelemetry.reset();
        InventorySetTelemetry.PER_INTENT = true;
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ONLY_OP = EntityMutationOp.SetInventorySlot.class;
        EntityMutationTestHooks.ENABLED = false;
        out.add("=== " + title + " ===");
    }

    /** Routed variant setup: ≥2 regions, all pinned (no merge can retire the target's mailbox),
     *  player assigned to region A. Returns the previous pin flags for {@link #unroute}. */
    private static java.util.Map<WorldRegion, Boolean> route(NestworldRegionSystem sys, ServerPlayer p, List<String> out) {
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = Api1A7DeterministicTests.ensureTwoRegions(sys);
        Api1A7DeterministicTests.ensureAnchors(regions, level);
        java.util.Map<WorldRegion, Boolean> prev = new java.util.HashMap<>();
        for (WorldRegion r : sys.getDimensionRegion(level).getGrid().getAllRegions()) { prev.put(r, r.pinned); r.pinned = true; }
        Api1A7DeterministicTests.assignSole(regions, regions[0], p.getUUID());
        out.add("ROUTED: player assigned to region " + regions[0].getId() + ", " + prev.size() + " regions pinned");
        return prev;
    }

    /** Direct-variant precondition: the player owned by NO region (the state a fresh login is in),
     *  so dispatch() really takes its direct branch. Earlier routed fixtures leave the player
     *  assigned (found live: OFFLINE-DIRECT ran routed, owner=1). */
    private static void unownForDirect(NestworldRegionSystem sys, ServerPlayer p, List<String> out) {
        WorldRegion was = sys.getDimensionRegion(sys.getOverworld()).getGrid().findOwningRegion(p.getUUID());
        for (WorldRegion r : sys.getDimensionRegion(sys.getOverworld()).getGrid().getAllRegions()) r.removeEntity(p.getUUID());
        out.add("DIRECT: player made unowned (was owner=" + (was == null ? "null" : was.getId()) + ", now="
                + sys.getDimensionRegion(sys.getOverworld()).getGrid().findOwningRegion(p.getUUID()) + ")");
    }

    private static void unroute(java.util.Map<WorldRegion, Boolean> prev) {
        if (prev != null) prev.forEach((r, was) -> r.pinned = was);
    }

    private static void end(List<String> out) {
        EntityMutationTestHooks.ENABLED = false;
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ONLY_OP = null;
        out.add("INVARIANTS " + InventorySetTelemetry.invariantLine());
        InventorySetTelemetry.PER_INTENT = false;
    }

    private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
    }

    private static String obj(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static String desc(ItemStack s) {
        return s.isEmpty() ? "EMPTY" : s.getItem() + "x" + s.getCount();
    }

    /** Waits until the player list holds a DIFFERENT ServerPlayer object for {@code id} -- a real
     *  lifecycle transition finished. Deliberately consults no incarnation counter or event. */
    private static ServerPlayer awaitNewObject(MinecraftServer server, UUID id, ServerPlayer oldObj,
                                               long timeoutMs) throws InterruptedException {
        AtomicReference<ServerPlayer> seen = new AtomicReference<>();
        waitFor(() -> {
            ServerPlayer cur = server.getPlayerList().getPlayer(id);
            if (cur != null && cur != oldObj) {
                seen.set(cur);
                return true;
            }
            return false;
        }, timeoutMs);
        return seen.get();
    }

    // ------------------------------------------------------------------ test capability (fixture A)

    /** Mutable state reachable only through a Forge capability, serializable so it travels via
     *  ItemStack.serializeCaps()/deserializeCaps() -- the exact path copy() uses. */
    static final class TestCounter { int value; }

    static final Capability<TestCounter> TEST_CAP = CapabilityManager.get(new CapabilityToken<>() {});
    static final ResourceLocation TEST_CAP_KEY = new ResourceLocation("nestworld", "api1b7_test_counter");
    private static final AtomicBoolean CAP_ARMED = new AtomicBoolean(false);
    private static final AtomicBoolean CAP_LISTENER_REGISTERED = new AtomicBoolean(false);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void ensureCapListener() {
        if (!CAP_LISTENER_REGISTERED.compareAndSet(false, true)) return;
        MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, EventPriority.NORMAL, false,
                (Class) AttachCapabilitiesEvent.class, (java.util.function.Consumer) (Object raw) -> {
                    AttachCapabilitiesEvent<ItemStack> e = (AttachCapabilitiesEvent<ItemStack>) raw;
                    if (!CAP_ARMED.get() || !e.getObject().is(Items.KNOWLEDGE_BOOK)) return;
                    TestCounter c = new TestCounter();
                    LazyOptional<TestCounter> lo = LazyOptional.of(() -> c);
                    e.addCapability(TEST_CAP_KEY, new ICapabilitySerializable<CompoundTag>() {
                        @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                            return cap == TEST_CAP ? lo.cast() : LazyOptional.empty();
                        }
                        @Override public CompoundTag serializeNBT() { CompoundTag t = new CompoundTag(); t.putInt("v", c.value); return t; }
                        @Override public void deserializeNBT(CompoundTag t) { c.value = t.getInt("v"); }
                    });
                });
    }

    private static TestCounter cap(ItemStack s) {
        return s.getCapability(TEST_CAP).resolve().orElse(null);
    }

    // ------------------------------------------------------------------ Fixture A

    /** Aggressive snapshot independence: after setSlot() returns, mutate the caller's stack's
     *  count, a NESTED NBT value, and Forge capability state. The applied slot must carry the
     *  submission-time values of all three, and share no object with the original. */
    static List<String> runSnapshotIndependenceTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-A: snapshot independence (count + nested NBT + capability state)");
        ensureCapListener();
        CAP_ARMED.set(true);
        try {
            int slot = 0;
            ItemStack original = new ItemStack(Items.KNOWLEDGE_BOOK, 5);
            CompoundTag inner = new CompoundTag();
            inner.putInt("v", 1);
            CompoundTag nw = new CompoundTag();
            nw.put("inner", inner);
            original.getOrCreateTag().put("nw", nw);
            TestCounter origCap = cap(original);
            if (origCap == null) {
                out.add("SETUP FAILURE: test capability not attached to original -- fixture would be vacuous, aborting");
                return out;
            }
            origCap.value = 111;
            out.add("submitted: count=5 nw.inner.v=1 cap.value=111 originalObj=" + obj(original));

            // Ordering made deterministic: park the main thread's task queue behind a gate so
            // the apply (marshaled via server.execute by either peer) cannot run until the
            // caller's mutations below are complete.
            MinecraftServer server = sys.getOverworld().getServer();
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch gateEntered = new java.util.concurrent.CountDownLatch(1);
            server.execute(() -> { gateEntered.countDown(); try { gate.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {} });
            out.add("main-thread task queue gated: " + gateEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            NestWorldInventory.setSlot(p, slot, original);

            original.setCount(64);
            original.getTag().getCompound("nw").getCompound("inner").putInt("v", 999);
            origCap.value = 999;
            long appliedAtMutation = InventorySetTelemetry.applied.get();
            out.add("applyCount at the moment all caller mutations completed=" + appliedAtMutation
                    + " (must be 0: mutation strictly precedes apply)");
            gate.countDown();
            out.add("caller mutated original after return: count=" + original.getCount()
                    + " nw.inner.v=" + original.getTag().getCompound("nw").getCompound("inner").getInt("v")
                    + " cap.value=" + origCap.value);

            UUID id = p.getUUID();
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long inc = PlayerIncarnationRegistry.incarnationOf(p);
            boolean landed = waitFor(() -> InventorySetTelemetry.applied.get() >= 1, 5000);
            ServerPlayer cur = sys.getOverworld().getServer().getPlayerList().getPlayer(id);
            ItemStack applied = cur.getInventory().getItem(slot);
            TestCounter appliedCap = cap(applied);
            int appliedV = applied.hasTag() ? applied.getTag().getCompound("nw").getCompound("inner").getInt("v") : -1;
            out.add("applied (landed=" + landed + "): " + desc(applied) + " nw.inner.v=" + appliedV
                    + " cap.value=" + (appliedCap == null ? "NO-CAP" : appliedCap.value)
                    + " appliedObj=" + obj(applied) + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, inc));
            boolean noShared = applied != original && applied.getTag() != original.getTag()
                    && applied.getTag().getCompound("nw") != original.getTag().getCompound("nw")
                    && appliedCap != origCap;
            out.add("identity: stackShared=" + (applied == original) + " tagShared=" + (applied.getTag() == original.getTag())
                    + " capShared=" + (appliedCap == origCap));
            boolean pass = appliedAtMutation == 0 && landed && applied.is(Items.KNOWLEDGE_BOOK) && applied.getCount() == 5 && appliedV == 1
                    && appliedCap != null && appliedCap.value == 111 && noShared;
            out.add("RESULT A: " + (pass ? "PASS" : "FAIL") + " (expected mutation-before-apply, count=5, nw.inner.v=1, cap.value=111, no shared objects)");
        } finally {
            CAP_ARMED.set(false);
            end(out);
        }
        return out;
    }

    // ------------------------------------------------------------------ Fixture B

    /** Same-incarnation LATEST_INTENT_WINS under a forced region migration (SEC11 shape). */
    static List<String> runSameIncarnationOrderingTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-B: same-incarnation temporal arbitration across a migration");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            pins = route(sys, p, out);
            ServerLevel level = sys.getOverworld();
            WorldRegion[] regions = Api1A7DeterministicTests.ensureTwoRegions(sys);
            Api1A7DeterministicTests.ensureAnchors(regions, level);
            WorldRegion a = regions[0], b = regions[1];
            UUID id = p.getUUID();
            Api1A7DeterministicTests.assignSole(regions, a, id);
            int slot = 1;
            long inc = PlayerIncarnationRegistry.incarnationOf(p);
            InventoryTargetKey key = new InventoryTargetKey(id, slot);

            long v1 = InventorySlotVersionTracker.assignVersion(key, inc);
            out.add("player=" + id + " slot=" + slot + " owner=" + a.getId() + " incarnation=" + inc + " V1=cobblestone v" + v1);
            EntityMutationTestHooks.armA();
            EntityMutationTestHooks.ENABLED = true;
            Thread t1 = new Thread(() -> EntityMutationDispatcher.dispatch(p,
                    new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.COBBLESTONE), v1, inc), OPIDS.incrementAndGet()),
                    "API1B6-B-V1");
            t1.start();
            out.add("V1 held at barrier A: " + EntityMutationTestHooks.awaitHitA(10));
            Api1A7DeterministicTests.assignSole(regions, b, id);
            out.add("migrated owner " + a.getId() + " -> " + b.getId() + " while V1 held");

            EntityMutationTestHooks.ENABLED = false;
            long v2 = InventorySlotVersionTracker.assignVersion(key, inc);
            out.add("V2=diamond v" + v2 + " submitted and dispatched");
            EntityMutationDispatcher.dispatch(p, new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.DIAMOND), v2, inc),
                    OPIDS.incrementAndGet());
            boolean v2Landed = waitFor(() -> InventorySetTelemetry.applyCount(id, slot, inc, v2) == 1, 5000);
            out.add("V2 applied=" + v2Landed + " slot=" + desc(p.getInventory().getItem(slot)));

            EntityMutationTestHooks.releaseA();
            t1.join(5000);
            boolean v1Arrived = waitFor(() -> InventorySetTelemetry.staleDropped.get() >= 1
                    || InventorySetTelemetry.applyCount(id, slot, inc, v1) > 0, 5000);
            ItemStack fin = p.getInventory().getItem(slot);
            out.add("V1 released: arrived=" + v1Arrived + " V1.tryAccept=" + InventorySetTelemetry.tryAcceptCount(id, slot, inc, v1)
                    + " V1.applyCount=" + InventorySetTelemetry.applyCount(id, slot, inc, v1)
                    + " V2.applyCount=" + InventorySetTelemetry.applyCount(id, slot, inc, v2)
                    + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, inc) + " final=" + desc(fin));
            boolean pass = v1Arrived && fin.is(Items.DIAMOND)
                    && InventorySetTelemetry.applyCount(id, slot, inc, v1) == 0
                    && InventorySetTelemetry.applyCount(id, slot, inc, v2) == 1;
            out.add("RESULT B: " + (pass ? "PASS" : "FAIL") + " (expected V1 STALE_INTENT_DROPPED after reaching the tracker, V2 applied once, slot=diamond)");
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    // ------------------------------------------------------------------ Fixture C-SIM: equals trap

    /**
     * Unit-level proof of the identity rule on real objects, no lifecycle transition needed. Builds
     * an UNPUBLISHED second ServerPlayer X for the same profile and copies A's entity id onto it
     * -- exactly what PlayerList.respawn does -- so {@code X.equals(A)} is true. Then:
     * tokens must differ; a request stamped for A must be rejected on X without touching the
     * version tracker or X's inventory; a request stamped for X must apply (positive control).
     * Runs on the main thread (vanilla constructs ServerPlayer there too).
     */
    static List<String> runEqualsTrapTest(NestworldRegionSystem sys, ServerPlayer a) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-C-SIM: equals-trap (same entity id, different object) -- no transition, no event");
        try {
            MinecraftServer server = sys.getOverworld().getServer();
            server.submit(() -> {
                ServerPlayer x = new ServerPlayer(server, a.serverLevel(), a.getGameProfile());
                x.setId(a.getId());
                UUID id = a.getUUID();
                int slot = 6;
                InventoryTargetKey key = new InventoryTargetKey(id, slot);
                long tokA = PlayerIncarnationRegistry.incarnationOf(a);
                long tokX = PlayerIncarnationRegistry.incarnationOf(x);
                out.add("A=" + obj(a) + " X=" + obj(x) + " X.equals(A)=" + x.equals(a) + " sameId=" + (x.getId() == a.getId())
                        + " tokenA=" + tokA + " tokenX=" + tokX);
                long vA = InventorySlotVersionTracker.assignVersion(key, tokA);
                EntityMutationDispatcher.ApplyOutcome o1 = EntityMutationDispatcher.applyDirect(x,
                        new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.GOLD_INGOT, 3), vA, tokA));
                out.add("request stamped for A applied to X -> " + o1 + " tryAccept(A-domain)=" + InventorySetTelemetry.tryAcceptCount(id, slot, tokA, vA)
                        + " lastApplied(A-domain)=" + InventorySlotVersionTracker.peekLastApplied(key, tokA)
                        + " lastApplied(X-domain)=" + InventorySlotVersionTracker.peekLastApplied(key, tokX)
                        + " X.slot=" + desc(x.getInventory().getItem(slot)));
                long vX = InventorySlotVersionTracker.assignVersion(key, tokX);
                EntityMutationDispatcher.ApplyOutcome o2 = EntityMutationDispatcher.applyDirect(x,
                        new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.EMERALD, 2), vX, tokX));
                out.add("positive control: request stamped for X applied to X -> " + o2 + " vX=" + vX
                        + " X.slot=" + desc(x.getInventory().getItem(slot)) + " A.slot=" + desc(a.getInventory().getItem(slot)));
                boolean pass = x.equals(a) && tokA != tokX
                        && o1 == EntityMutationDispatcher.ApplyOutcome.INCARNATION_MISMATCH
                        && InventorySetTelemetry.tryAcceptCount(id, slot, tokA, vA) == 0
                        && o2 == EntityMutationDispatcher.ApplyOutcome.APPLIED && vX == 1
                        && x.getInventory().getItem(slot).is(Items.EMERALD)
                        && !a.getInventory().getItem(slot).is(Items.GOLD_INGOT);
                out.add("RESULT C-SIM: " + (pass ? "PASS" : "FAIL")
                        + " (equal-by-id objects get distinct tokens; cross-object request rejected untouched; own request applies; fresh domain starts at v1)");
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            end(out);
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle fixtures

    private static boolean rejectedUntouched(UUID id, int slot, long tok, long v) {
        return InventorySetTelemetry.tryAcceptCount(id, slot, tok, v) == 0 && InventorySetTelemetry.applyCount(id, slot, tok, v) == 0;
    }

    /** C / R: one intent stamped against the old object, held across a REAL transition. */
    static List<String> runTransitionRejectTest(NestworldRegionSystem sys, ServerPlayer p, String kind, boolean routed) throws Exception {
        List<String> out = new Out();
        String tag = ("respawn".equals(kind) ? "C" : "R") + (routed ? "-ROUTED" : "-DIRECT");
        begin(out, "API1B6-" + tag + ": REAL " + kind + " rejects a pre-transition intent");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            if (routed) pins = route(sys, p, out); else unownForDirect(sys, p, out);
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            int slot = "respawn".equals(kind) ? 2 : 4;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tokOld = PlayerIncarnationRegistry.incarnationOf(p);
            long v = InventorySlotVersionTracker.assignVersion(key, tokOld);
            EntityMutationTestHooks.armA();
            EntityMutationTestHooks.ENABLED = true;
            long opId = OPIDS.incrementAndGet();
            Thread t = new Thread(() -> EntityMutationDispatcher.dispatch(p,
                    new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.GOLD_INGOT, 3), v, tokOld), opId), "API1B6-" + tag);
            t.start();
            out.add("intent opId=" + opId + " token=" + tokOld + " v" + v + " oldObj=" + obj(p) + " held at barrier A: " + EntityMutationTestHooks.awaitHitA(10));
            out.add("ACTION_REQUIRED " + kind + " player=" + p.getGameProfile().getName());
            if ("relogin".equals(kind)) {
                out.add("observed offline: " + waitFor(() -> server.getPlayerList().getPlayer(id) == null, 60_000)
                        + " domainsForPlayer=" + InventorySlotVersionTracker.domainCount(id));
            }
            ServerPlayer np = awaitNewObject(server, id, p, 60_000);
            if (np == null) { out.add("TIMEOUT waiting for a real " + kind); EntityMutationTestHooks.releaseA(); return out; }
            out.add(kind + " observed: newObj=" + obj(np) + " (no token minted yet for it: knownObjects=" + PlayerIncarnationRegistry.knownObjects(id) + ")");
            EntityMutationTestHooks.releaseA();
            t.join(5000);
            boolean arrived = waitFor(() -> InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.targetRemoved.get()
                    + InventorySetTelemetry.applied.get() + InventorySetTelemetry.staleDropped.get() >= 1, 10_000);
            ItemStack after = np.getInventory().getItem(slot);
            out.add("arrived=" + arrived + " incMismatch=" + InventorySetTelemetry.incarnationMismatch.get()
                    + " targetRemoved=" + InventorySetTelemetry.targetRemoved.get()
                    + " tryAccept=" + InventorySetTelemetry.tryAcceptCount(id, slot, tokOld, v)
                    + " applyCount=" + InventorySetTelemetry.applyCount(id, slot, tokOld, v)
                    + " lastApplied(old domain)=" + InventorySlotVersionTracker.peekLastApplied(key, tokOld) + " newObjSlot=" + desc(after));
            boolean rejected = routed ? InventorySetTelemetry.incarnationMismatch.get() == 1
                    : InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.targetRemoved.get() == 1;
            boolean pass = arrived && rejected && rejectedUntouched(id, slot, tokOld, v) && !after.is(Items.GOLD_INGOT);
            out.add("RESULT " + tag + ": " + (pass ? "PASS" : "FAIL") + (routed
                    ? " (expected INCARNATION_MISMATCH after UUID re-acquisition found the NEW object; tracker and new inventory untouched)"
                    : " (direct path re-checks the OLD reference it was given: expected TARGET_REMOVED or INCARNATION_MISMATCH; tracker and new inventory untouched)"));
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    /**
     * D -- cross-boundary, the architecture owner's sequence:
     * <pre>
     * object A (token a):  V1000 submitted (held)
     * REAL respawn -> object B becomes visible (no Forge event consulted anywhere)
     * object B (token b):  V1 submitted        -- its own (player, slot, b) domain, starts at 1
     * force V1000 to arrive -> rejected, version tracker untouched, inventory untouched
     * force V1    to arrive -> ACCEPTED, applied exactly once
     * </pre>
     */
    static List<String> runCrossBoundaryOrderingTest(NestworldRegionSystem sys, ServerPlayer p, boolean routed) throws Exception {
        List<String> out = new Out();
        String tag = "D" + (routed ? "-ROUTED" : "-DIRECT");
        begin(out, "API1B6-" + tag + ": cross-boundary (A/V1000 rejected, B/V1 accepted) -- REAL respawn");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            if (routed) pins = route(sys, p, out); else unownForDirect(sys, p, out);
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            int slot = 3;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tokA = PlayerIncarnationRegistry.incarnationOf(p);
            long vv = 0;
            while (vv < 1000) vv = InventorySlotVersionTracker.assignVersion(key, tokA);
            final long v1000 = vv;
            out.add("object A=" + obj(p) + " token=" + tokA + ": V" + v1000 + " (netherite) submitted, domain(A).assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA));
            EntityMutationTestHooks.armA();
            EntityMutationTestHooks.ENABLED = true;
            long opOld = OPIDS.incrementAndGet();
            Thread tOld = new Thread(() -> EntityMutationDispatcher.dispatch(p,
                    new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.NETHERITE_INGOT), v1000, tokA), opOld), "API1B6-" + tag + "-V1000");
            tOld.start();
            out.add("V" + v1000 + " opId=" + opOld + " held at barrier A: " + EntityMutationTestHooks.awaitHitA(10));
            out.add("ACTION_REQUIRED respawn player=" + p.getGameProfile().getName());
            ServerPlayer np = awaitNewObject(server, id, p, 60_000);
            if (np == null) { out.add("TIMEOUT waiting for a real respawn"); EntityMutationTestHooks.releaseA(); return out; }
            long tokB = PlayerIncarnationRegistry.incarnationOf(np);
            long v1 = InventorySlotVersionTracker.assignVersion(key, tokB);
            out.add("object B=" + obj(np) + " visible; token=" + tokB + "; V" + v1 + " (apple) submitted in domain(B)"
                    + " | domain(A).assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokA) + " domain(B).assigned=" + InventorySlotVersionTracker.peekAssigned(key, tokB));

            long tryBefore = InventorySetTelemetry.tryAcceptCalls.get();
            EntityMutationTestHooks.releaseA();
            tOld.join(5000);
            boolean oldArrived = waitFor(() -> InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.targetRemoved.get() >= 1
                    || InventorySetTelemetry.applyCount(id, slot, tokA, v1000) > 0, 10_000);
            long tryDelta = InventorySetTelemetry.tryAcceptCalls.get() - tryBefore;
            out.add("force V" + v1000 + " to arrive: arrived=" + oldArrived
                    + " incMismatch=" + InventorySetTelemetry.incarnationMismatch.get() + " targetRemoved=" + InventorySetTelemetry.targetRemoved.get()
                    + " versionTrackerTouched=" + (InventorySetTelemetry.tryAcceptCount(id, slot, tokA, v1000) > 0 ? "YES" : "NO")
                    + " tryAcceptCallsDelta=" + tryDelta + " mutationCount=" + InventorySetTelemetry.applyCount(id, slot, tokA, v1000)
                    + " lastApplied(A)=" + InventorySlotVersionTracker.peekLastApplied(key, tokA)
                    + " lastApplied(B)=" + InventorySlotVersionTracker.peekLastApplied(key, tokB) + " B.slot=" + desc(np.getInventory().getItem(slot)));

            EntityMutationTestHooks.ENABLED = false;
            long opNew = OPIDS.incrementAndGet();
            EntityMutationOp.SetInventorySlot opV1 = new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.APPLE), v1, tokB);
            if (routed) {
                EntityMutationDispatcher.dispatch(np, opV1, opNew);
            } else {
                server.submit(() -> EntityMutationDispatcher.dispatch(np, opV1, opNew)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            boolean newArrived = waitFor(() -> InventorySetTelemetry.applyCount(id, slot, tokB, v1) >= 1 || InventorySetTelemetry.staleDropped.get() >= 1, 10_000);
            ItemStack fin = np.getInventory().getItem(slot);
            out.add("force V" + v1 + " (opId=" + opNew + ") to arrive: arrived=" + newArrived
                    + " applyCount=" + InventorySetTelemetry.applyCount(id, slot, tokB, v1)
                    + " lastApplied(B)=" + InventorySlotVersionTracker.peekLastApplied(key, tokB) + " B.slot=" + desc(fin));
            boolean oldRejected = routed ? InventorySetTelemetry.incarnationMismatch.get() == 1
                    : InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.targetRemoved.get() == 1;
            boolean pass = oldArrived && newArrived && oldRejected
                    && rejectedUntouched(id, slot, tokA, v1000) && tryDelta == 0
                    && InventorySlotVersionTracker.peekLastApplied(key, tokA) == null
                    && InventorySetTelemetry.applyCount(id, slot, tokB, v1) == 1
                    && Long.valueOf(v1).equals(InventorySlotVersionTracker.peekLastApplied(key, tokB))
                    && fin.is(Items.APPLE) && v1 == 1 && tokA != tokB;
            out.add("RESULT " + tag + ": " + (pass ? "PASS" : "FAIL") + " (V" + v1000 + " rejected" + (routed ? " as INCARNATION_MISMATCH" : "")
                    + ", tracker untouched, 0 mutations; V1 applied once in its own domain; B.slot=apple)");
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    // ------------------------------------------------------------------ Fixture E

    private static final AtomicReference<Runnable> WINDOW_PROBE = new AtomicReference<>();
    private static final AtomicBoolean WINDOW_LISTENERS_REGISTERED = new AtomicBoolean(false);

    private static void ensureWindowListeners() {
        if (!WINDOW_LISTENERS_REGISTERED.compareAndSet(false, true)) return;
        // HIGHEST priority: runs before every other listener of the same event -- i.e. at the
        // earliest point Forge offers, AFTER vanilla has already published the new ServerPlayer.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, PlayerEvent.PlayerRespawnEvent.class,
                e -> { Runnable r = WINDOW_PROBE.getAndSet(null); if (r != null) r.run(); });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, PlayerEvent.PlayerLoggedInEvent.class,
                e -> { Runnable r = WINDOW_PROBE.getAndSet(null); if (r != null) r.run(); });
    }

    /**
     * E -- the publication window, re-run against the identity model. Inside the window (new
     * object B published, Forge listeners not yet run), the unmodified production apply path runs
     * for an intent stamped against object A. Expected: B is re-acquired by UUID, gets its own
     * token on first observation, INCARNATION_MISMATCH, tracker and B's inventory untouched --
     * i.e. the outcome no longer depends on when (or whether) any Forge event fires. Fidelity
     * caveat: the interleaving is forced (production code run synchronously on the main thread at
     * the instant a concurrently draining region thread could run it).
     */
    static List<String> runPublicationWindowTest(NestworldRegionSystem sys, ServerPlayer p, String kind, boolean routed) throws Exception {
        List<String> out = new Out();
        String tag = "E-" + kind.toUpperCase() + (routed ? "-ROUTED" : "-DIRECT");
        begin(out, "API1B6-" + tag + ": publication window (REAL " + kind + ", forced interleaving)");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            if (routed) pins = route(sys, p, out); else unownForDirect(sys, p, out);
            ensureWindowListeners();
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            int slot = 5;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tokA = PlayerIncarnationRegistry.incarnationOf(p);
            long v = InventorySlotVersionTracker.assignVersion(key, tokA);
            EntityMutationOp.SetInventorySlot op = new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.TNT, 13), v, tokA);
            out.add("intent stamped against object A=" + obj(p) + " token=" + tokA + " v" + v + " (TNTx13 -> slot " + slot + ")");
            List<String> probe = java.util.Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Object> mutatedObj = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>("none");
            WINDOW_PROBE.set(() -> {
                ServerPlayer published = server.getPlayerList().getPlayer(id);
                ServerLevel lvl = published == null ? null : published.serverLevel();
                net.minecraft.world.entity.Entity byLevel = lvl == null ? null : lvl.getEntity(id);
                probe.add("IN WINDOW (thread=" + Thread.currentThread().getName() + ", before any other listener of this event): playerList->"
                        + obj(published) + " level.getEntity->" + obj(byLevel) + " isOldObj=" + (published == p));
                if (lvl == null || byLevel == null) { probe.add("new object not reachable via level yet -- no window on this path"); return; }
                WorldRegion owner = NestworldRegionSystem.isInitialised() && NestworldRegionSystem.get().isManagedLevel(lvl)
                        ? NestworldRegionSystem.get().getDimensionRegion(lvl).getGrid().findOwningRegion(id) : null;
                long applyBefore = InventorySetTelemetry.applied.get();
                long mmBefore = InventorySetTelemetry.incarnationMismatch.get();
                if (owner != null) {
                    EntityMutationDispatcher.applyQueued(id, op, 0, OPIDS.incrementAndGet(), owner, lvl);
                    probe.add("ran production applyQueued(applyingRegion=owner=" + owner.getId() + ") -- UUID re-acquisition path");
                } else {
                    probe.add("owner=null -> ran production applyDirect on level.getEntity(uuid): " + EntityMutationDispatcher.applyDirect(byLevel, op));
                }
                if (InventorySetTelemetry.applied.get() > applyBefore) { mutatedObj.set(byLevel); outcome.set("APPLIED"); }
                else if (InventorySetTelemetry.incarnationMismatch.get() > mmBefore) outcome.set("INCARNATION_MISMATCH");
                probe.add("published object's token now=" + PlayerIncarnationRegistry.incarnationOf((ServerPlayer) byLevel)
                        + " slot right after=" + desc(((ServerPlayer) byLevel).getInventory().getItem(slot)));
            });
            out.add("ACTION_REQUIRED " + kind + " player=" + p.getGameProfile().getName());
            ServerPlayer np = awaitNewObject(server, id, p, 60_000);
            Thread.sleep(200);
            WINDOW_PROBE.set(null);
            out.addAll(probe);
            if (np == null) { out.add("TIMEOUT waiting for real " + kind); return out; }
            ItemStack after = np.getInventory().getItem(slot);
            boolean wrongObj = mutatedObj.get() == np;
            out.add("after transition: B=" + obj(np) + " B.slot=" + desc(after) + " outcomeInWindow=" + outcome.get()
                    + " tryAccept(A-domain)=" + InventorySetTelemetry.tryAcceptCount(id, slot, tokA, v)
                    + " lastApplied(A)=" + InventorySlotVersionTracker.peekLastApplied(key, tokA));
            out.add("wrongIncarnationMutation(by raw object reference)=" + (wrongObj ? 1 : 0));
            boolean pass = !probe.isEmpty() && "INCARNATION_MISMATCH".equals(outcome.get()) && !wrongObj
                    && rejectedUntouched(id, slot, tokA, v) && !after.is(Items.TNT);
            out.add("RESULT " + tag + ": " + (pass ? "PASS -- event timing is not part of the correctness model"
                    : (probe.isEmpty() ? "INCONCLUSIVE (probe never ran)" : "FAIL")));
        } finally {
            WINDOW_PROBE.set(null);
            unroute(pins);
            end(out);
        }
        return out;
    }

    // ================================================================== DoD deterministic cases

    /** Worker-thread submission through the PUBLIC facade: the caller's thread must never be the
     *  thread that mutates the inventory. */
    static List<String> runWorkerThreadTest(NestworldRegionSystem sys, ServerPlayer p, boolean routed) throws Exception {
        List<String> out = new Out();
        String tag = "W" + (routed ? "-ROUTED" : "-DIRECT");
        begin(out, "API1B6-" + tag + ": worker-thread submission via NestWorldInventory.setSlot");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            if (routed) pins = route(sys, p, out); else unownForDirect(sys, p, out);
            int slot = 7;
            AtomicReference<String> caller = new AtomicReference<>();
            Thread w = new Thread(() -> { caller.set(Thread.currentThread().getName()); NestWorldInventory.setSlot(p, slot, new ItemStack(Items.IRON_INGOT, 4)); },
                    "API1B6-worker-submitter");
            w.start(); w.join(5000);
            boolean landed = waitFor(() -> InventorySetTelemetry.applied.get() >= 1, 10_000);
            ItemStack got = p.getInventory().getItem(slot);
            String applyThread = InventorySetTelemetry.lastApplyThread;
            out.add("callerThread=" + caller.get() + " applyThread=" + applyThread + " landed=" + landed + " slot=" + desc(got));
            boolean ownerThread = routed ? applyThread.startsWith("NestWorld-Region-") : applyThread.equals("Server thread");
            boolean pass = landed && got.is(Items.IRON_INGOT) && !applyThread.equals(caller.get()) && ownerThread;
            out.add("RESULT " + tag + ": " + (pass ? "PASS" : "FAIL") + " (applied on " + (routed ? "the owning region thread" : "the server thread")
                    + ", never on the caller's worker thread)");
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    /** Offline-before-apply: request held across a REAL disconnect, released while the player is
     *  offline, then the player reconnects. Expected terminal: routed -> TARGET_NOT_FOUND (UUID no
     *  longer resolves); direct -> TARGET_REMOVED (the old reference it holds is discarded). */
    static List<String> runOfflineBeforeApplyTest(NestworldRegionSystem sys, ServerPlayer p, boolean routed) throws Exception {
        List<String> out = new Out();
        String tag = "OFFLINE" + (routed ? "-ROUTED" : "-DIRECT");
        begin(out, "API1B6-" + tag + ": request released while the player is offline");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            if (routed) pins = route(sys, p, out); else unownForDirect(sys, p, out);
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            int slot = 8;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tok = PlayerIncarnationRegistry.incarnationOf(p);
            long v = InventorySlotVersionTracker.assignVersion(key, tok);
            EntityMutationTestHooks.armA();
            EntityMutationTestHooks.ENABLED = true;
            Thread t = new Thread(() -> EntityMutationDispatcher.dispatch(p,
                    new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.LAPIS_LAZULI, 9), v, tok), OPIDS.incrementAndGet()), "API1B6-" + tag);
            t.start();
            out.add("intent token=" + tok + " v" + v + " held at barrier A: " + EntityMutationTestHooks.awaitHitA(10));
            out.add("ACTION_REQUIRED relogin player=" + p.getGameProfile().getName());
            boolean offline = waitFor(() -> server.getPlayerList().getPlayer(id) == null, 60_000);
            EntityMutationTestHooks.releaseA();
            t.join(5000);
            boolean terminal = waitFor(() -> InventorySetTelemetry.targetNotFound.get() + InventorySetTelemetry.targetRemoved.get()
                    + InventorySetTelemetry.applied.get() + InventorySetTelemetry.incarnationMismatch.get() >= 1, 10_000);
            out.add("released while offline=" + offline + " (online now=" + (server.getPlayerList().getPlayer(id) != null) + "): terminal=" + terminal
                    + " targetNotFound=" + InventorySetTelemetry.targetNotFound.get() + " targetRemoved=" + InventorySetTelemetry.targetRemoved.get()
                    + " applied=" + InventorySetTelemetry.applied.get() + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tok));
            ServerPlayer np = awaitNewObject(server, id, p, 60_000);
            ItemStack after = np == null ? ItemStack.EMPTY : np.getInventory().getItem(slot);
            out.add("reconnected: newObj=" + obj(np) + " newSlot=" + desc(after));
            boolean expected = routed ? InventorySetTelemetry.targetNotFound.get() == 1 : InventorySetTelemetry.targetRemoved.get() == 1;
            boolean pass = offline && terminal && expected && InventorySetTelemetry.applied.get() == 0
                    && InventorySlotVersionTracker.peekLastApplied(key, tok) == null && np != null && !after.is(Items.LAPIS_LAZULI);
            out.add("RESULT " + tag + ": " + (pass ? "PASS" : "FAIL") + " (expected " + (routed ? "TARGET_NOT_FOUND" : "TARGET_REMOVED")
                    + ", tracker untouched, reconnected inventory untouched)");
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    /** Different slots are independent temporal domains: a stale V1 on slot 9 is dropped while a
     *  numerically lower V1 on slot 10 (never superseded in ITS domain) applies. Deterministic
     *  arrival order: everything dispatched on the main thread, direct path. */
    static List<String> runSlotIndependenceTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-SLOTS: per-slot temporal domains are independent");
        try {
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            long tok = PlayerIncarnationRegistry.incarnationOf(p);
            InventoryTargetKey k9 = new InventoryTargetKey(id, 9), k10 = new InventoryTargetKey(id, 10);
            long s9v1 = InventorySlotVersionTracker.assignVersion(k9, tok);
            long s10v1 = InventorySlotVersionTracker.assignVersion(k10, tok);
            long s9v2 = InventorySlotVersionTracker.assignVersion(k9, tok);
            out.add("submitted: slot9 v" + s9v1 + ", slot10 v" + s10v1 + ", slot9 v" + s9v2 + " (token=" + tok + ")");
            server.submit(() -> {
                EntityMutationDispatcher.dispatch(p, new EntityMutationOp.SetInventorySlot(9, new ItemStack(Items.DIAMOND), s9v2, tok), OPIDS.incrementAndGet());
                EntityMutationDispatcher.dispatch(p, new EntityMutationOp.SetInventorySlot(9, new ItemStack(Items.DIRT), s9v1, tok), OPIDS.incrementAndGet());
                EntityMutationDispatcher.dispatch(p, new EntityMutationOp.SetInventorySlot(10, new ItemStack(Items.EMERALD), s10v1, tok), OPIDS.incrementAndGet());
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            waitFor(() -> InventorySetTelemetry.applied.get() + InventorySetTelemetry.staleDropped.get() >= 3, 10_000);
            ItemStack a9 = p.getInventory().getItem(9), a10 = p.getInventory().getItem(10);
            out.add("arrival order: slot9 v2, slot9 v1, slot10 v1 -> slot9=" + desc(a9) + " slot10=" + desc(a10)
                    + " lastApplied: slot9=" + InventorySlotVersionTracker.peekLastApplied(k9, tok) + " slot10=" + InventorySlotVersionTracker.peekLastApplied(k10, tok)
                    + " applyCount: s9v2=" + InventorySetTelemetry.applyCount(id, 9, tok, s9v2) + " s9v1=" + InventorySetTelemetry.applyCount(id, 9, tok, s9v1)
                    + " s10v1=" + InventorySetTelemetry.applyCount(id, 10, tok, s10v1) + " staleDropped=" + InventorySetTelemetry.staleDropped.get());
            boolean pass = a9.is(Items.DIAMOND) && a10.is(Items.EMERALD) && InventorySetTelemetry.staleDropped.get() == 1
                    && InventorySetTelemetry.applyCount(id, 9, tok, s9v1) == 0 && InventorySetTelemetry.applyCount(id, 10, tok, s10v1) == 1
                    && Long.valueOf(2).equals(InventorySlotVersionTracker.peekLastApplied(k9, tok))
                    && Long.valueOf(1).equals(InventorySlotVersionTracker.peekLastApplied(k10, tok));
            out.add("RESULT SLOTS: " + (pass ? "PASS" : "FAIL") + " (slot9 v1 STALE in its own domain; slot10 v1 applies although slot9 already reached v2)");
        } finally {
            end(out);
        }
        return out;
    }

    /** Invalid slots are rejected at the public facade: no exception, no version assigned, no
     *  dispatch; boundary slots 0 and 40 are accepted. */
    static List<String> runInvalidSlotTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-INVALID: slot validation at the public boundary");
        try {
            UUID id = p.getUUID();
            long tok = PlayerIncarnationRegistry.incarnationOf(p);
            boolean ok = true;
            for (int bad : new int[]{-1, 41, 45, 1000, Integer.MIN_VALUE}) {
                String ex = "none";
                try { NestWorldInventory.setSlot(p, bad, new ItemStack(Items.BEDROCK)); } catch (Throwable e) { ex = e.toString(); }
                long assigned = InventorySlotVersionTracker.peekAssigned(new InventoryTargetKey(id, bad), tok);
                out.add("slot " + bad + ": exception=" + ex + " versionAssigned=" + assigned);
                ok &= ex.equals("none") && assigned == 0;
            }
            Thread.sleep(500);
            long dispatchedForInvalid = InventorySetTelemetry.applied.get() + InventorySetTelemetry.staleDropped.get()
                    + InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.targetRemoved.get();
            out.add("apply-side outcomes for invalid slots=" + dispatchedForInvalid + " (must be 0: nothing was dispatched)");
            ok &= dispatchedForInvalid == 0;
            NestWorldInventory.setSlot(p, 0, new ItemStack(Items.STICK));
            NestWorldInventory.setSlot(p, 40, new ItemStack(Items.SHIELD));
            waitFor(() -> InventorySetTelemetry.applied.get() >= 2, 10_000);
            out.add("boundary: slot0=" + desc(p.getInventory().getItem(0)) + " slot40(offhand)=" + desc(p.getInventory().getItem(40)));
            ok &= p.getInventory().getItem(0).is(Items.STICK) && p.getInventory().getItem(40).is(Items.SHIELD);
            out.add("RESULT INVALID: " + (ok ? "PASS" : "FAIL"));
        } finally {
            end(out);
        }
        return out;
    }

    /** Reroute limit (SEC10 shape): ownership forced to ping-pong at every drain until the hop
     *  limit -> REROUTE_LIMIT_DROPPED; the op never reaches applyDirect, so tracker untouched and
     *  zero mutations. Barrier B parks the region thread; nothing here needs the main thread. */
    static List<String> runRerouteLimitTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-REROUTE: bounded reroute for setSlot");
        java.util.Map<WorldRegion, Boolean> pins = null;
        try {
            pins = route(sys, p, out);
            ServerLevel level = sys.getOverworld();
            WorldRegion[] regions = Api1A7DeterministicTests.ensureTwoRegions(sys);
            WorldRegion a = regions[0], b = regions[1];
            UUID id = p.getUUID();
            int slot = 11;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tok = PlayerIncarnationRegistry.incarnationOf(p);
            long v = InventorySlotVersionTracker.assignVersion(key, tok);
            int limit = NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS;
            EntityMutationTestHooks.armB();
            EntityMutationTestHooks.ENABLED = true;
            EntityMutationDispatcher.dispatch(p, new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.REDSTONE, 5), v, tok), OPIDS.incrementAndGet());
            boolean toB = true;
            int hits = 0;
            for (int hop = 0; hop <= limit; hop++) {
                if (!EntityMutationTestHooks.awaitHitB(10)) { out.add("barrier B not hit at hop " + hop); break; }
                hits++;
                Api1A7DeterministicTests.assignSole(regions, toB ? b : a, id);
                toB = !toB;
                EntityMutationTestHooks.releaseB();
                if (hop < limit) EntityMutationTestHooks.armB();
            }
            EntityMutationTestHooks.ENABLED = false;
            boolean terminal = waitFor(() -> InventorySetTelemetry.rerouteLimitDropped.get() + InventorySetTelemetry.applied.get() >= 1, 10_000);
            out.add("hopLimit=" + limit + " barrierBHits=" + hits + " terminal=" + terminal + " rerouteLimitDropped=" + InventorySetTelemetry.rerouteLimitDropped.get()
                    + " maxRerouteHop=" + InventorySetTelemetry.maxRerouteHop.get() + " tryAccept=" + InventorySetTelemetry.tryAcceptCount(id, slot, tok, v)
                    + " applied=" + InventorySetTelemetry.applied.get() + " lastApplied=" + InventorySlotVersionTracker.peekLastApplied(key, tok)
                    + " slot=" + desc(p.getInventory().getItem(slot)));
            boolean pass = terminal && InventorySetTelemetry.rerouteLimitDropped.get() == 1 && InventorySetTelemetry.maxRerouteHop.get() <= limit
                    && InventorySetTelemetry.applied.get() == 0 && InventorySlotVersionTracker.peekLastApplied(key, tok) == null
                    && !p.getInventory().getItem(slot).is(Items.REDSTONE);
            out.add("RESULT REROUTE: " + (pass ? "PASS" : "FAIL") + " (REROUTE_LIMIT_DROPPED at hop " + limit + ", hops never exceeded the limit, nothing applied)");
        } finally {
            unroute(pins);
            end(out);
        }
        return out;
    }

    /** Server-generation boundary: runs exactly the resets NestworldRegionSystem.onServerStopping
     *  performs, then applies a request stamped BEFORE them. It must not validate: tokens are never
     *  reused, so the cleared registry mints a NEW token for the same live object. Honest scope:
     *  this is the in-JVM state boundary; a second MinecraftServer in the same JVM (integrated
     *  world restart) is not exercised by a dedicated server. */
    static List<String> runGenerationResetTest(NestworldRegionSystem sys, ServerPlayer p) throws Exception {
        List<String> out = new Out();
        begin(out, "API1B6-GEN: request from a previous server generation cannot validate");
        try {
            MinecraftServer server = sys.getOverworld().getServer();
            UUID id = p.getUUID();
            int slot = 12;
            InventoryTargetKey key = new InventoryTargetKey(id, slot);
            long tokOld = PlayerIncarnationRegistry.incarnationOf(p);
            long v = InventorySlotVersionTracker.assignVersion(key, tokOld);
            out.add("before reset: token=" + tokOld + " v" + v + " knownObjects=" + PlayerIncarnationRegistry.knownObjects(id)
                    + " domains=" + InventorySlotVersionTracker.domainCount(id));
            PlayerIncarnationRegistry.resetAll();
            InventorySlotVersionTracker.resetAll();
            out.add("after onServerStopping resets: knownObjects=" + PlayerIncarnationRegistry.knownObjects(id) + " domains=" + InventorySlotVersionTracker.domainCount(id));
            server.submit(() -> EntityMutationDispatcher.dispatch(p,
                    new EntityMutationOp.SetInventorySlot(slot, new ItemStack(Items.SLIME_BALL, 2), v, tokOld), OPIDS.incrementAndGet()))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            waitFor(() -> InventorySetTelemetry.incarnationMismatch.get() + InventorySetTelemetry.applied.get() >= 1, 10_000);
            long tokNew = PlayerIncarnationRegistry.incarnationOf(p);
            out.add("same live object after reset: token " + tokOld + " -> " + tokNew + " | old request: incMismatch=" + InventorySetTelemetry.incarnationMismatch.get()
                    + " applied=" + InventorySetTelemetry.applied.get() + " lastApplied(old)=" + InventorySlotVersionTracker.peekLastApplied(key, tokOld)
                    + " slot=" + desc(p.getInventory().getItem(slot)));
            boolean pass = tokNew > tokOld && InventorySetTelemetry.incarnationMismatch.get() == 1 && InventorySetTelemetry.applied.get() == 0
                    && InventorySlotVersionTracker.peekLastApplied(key, tokOld) == null;
            out.add("RESULT GEN: " + (pass ? "PASS" : "FAIL") + " (tokens never reused across a reset; pre-reset request rejected, tracker untouched)");
        } finally {
            end(out);
        }
        return out;
    }

    // ================================================================== stress

    /**
     * Mixed concurrent load: 4 worker threads submit through the PUBLIC facade against randomly
     * chosen players (from a periodically refreshed, possibly stale, snapshot of player objects --
     * stale references are part of the load) and slots 13..20, mutating each caller-owned stack right
     * after the call; a migrator reassigns player ownership between live regions (as main-thread
     * tasks, where real ownership changes happen); the external driver kills (auto-respawn) and
     * relogs bots concurrently ("chaos"). Invariants are computed independently of the trackers they
     * audit; delivery is accounted (submitted vs. terminal outcomes) so silent loss is visible.
     * {@code pinned}=false leaves regions free to merge, to see whether this load can reach the
     * known Core defect CORE-016 (mailbox loss on merge).
     */
    static List<String> runStressTest(NestworldRegionSystem sys, int seconds, boolean pinned, boolean churn) throws Exception {
        List<String> out = new Out();
        String tag = "STRESS-" + (churn ? "CHURN" : pinned ? "PINNED" : "UNPINNED");
        begin(out, "API1B6-" + tag + ": " + seconds + "s concurrent setSlot + migrations + respawn/relogin chaos");
        java.util.Map<WorldRegion, Boolean> prevPins = new java.util.HashMap<>();
        // First attempt crashed the server via the watchdog: uncapped submitters x several
        // synchronous per-op INFO diagnostic lines (~25k lines/s) parked the main thread inside
        // log4j's file write. Per-op diagnostics are muted for the run (WARN still logs, e.g.
        // REROUTE_LIMIT_DROPPED); fixture output uses its own logger; submission rate is bounded.
        org.apache.logging.log4j.core.config.Configurator.setLevel("NestWorld/DIAG", org.apache.logging.log4j.Level.WARN);
        out.add("per-op DIAG logging muted to WARN for the run; submitters capped at ~200/s each");
        try {
            MinecraftServer server = sys.getOverworld().getServer();
            ServerLevel level = sys.getOverworld();
            WorldRegion[] seed = Api1A7DeterministicTests.ensureTwoRegions(sys);
            Api1A7DeterministicTests.ensureAnchors(seed, level);
            if (pinned) for (WorldRegion r : sys.getDimensionRegion(level).getGrid().getAllRegions()) { prevPins.put(r, r.pinned); r.pinned = true; }
            java.util.Set<Integer> regionIdsAtStart = new java.util.HashSet<>();
            for (WorldRegion r : sys.getDimensionRegion(level).getGrid().getAllRegions()) regionIdsAtStart.add(r.getId());
            java.util.Set<Object> originals = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
            InventorySetTelemetry.CALLER_ORIGINALS = originals;
            AtomicReference<List<ServerPlayer>> players = new AtomicReference<>(List.of());
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong submitted = new AtomicLong(), seq = new AtomicLong(), migrations = new AtomicLong(), tokenCollision = new AtomicLong();
            List<Thread> threads = new ArrayList<>();
            threads.add(new Thread(() -> {
                while (running.get()) {
                    try {
                        players.set(server.submit(() -> {
                            List<ServerPlayer> l = new ArrayList<>();
                            for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (sp.getGameProfile().getName().startsWith("stress")) l.add(sp);
                            return l;
                        }).get(5, java.util.concurrent.TimeUnit.SECONDS));
                        Thread.sleep(100);
                    } catch (Exception ignored) {}
                }
            }, "API1B6-stress-refresher"));
            threads.add(new Thread(() -> {
                java.util.Random rnd = new java.util.Random(7);
                while (running.get()) {
                    List<ServerPlayer> l = players.get();
                    if (!l.isEmpty()) {
                        ServerPlayer target = l.get(rnd.nextInt(l.size()));
                        int pick = rnd.nextInt(1000);
                        server.execute(() -> {
                            List<WorldRegion> all = new ArrayList<>(sys.getDimensionRegion(level).getGrid().getAllRegions());
                            if (all.size() >= 2) {
                                Api1A7DeterministicTests.assignSole(all.toArray(new WorldRegion[0]), all.get(pick % all.size()), target.getUUID());
                                migrations.incrementAndGet();
                            }
                        });
                    }
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                }
            }, "API1B6-stress-migrator"));
            for (int w = 0; w < 4; w++) {
                final int seed2 = w;
                threads.add(new Thread(() -> {
                    java.util.Random rnd = new java.util.Random(100 + seed2);
                    while (running.get()) {
                        List<ServerPlayer> l = players.get();
                        if (l.isEmpty()) { try { Thread.sleep(20); } catch (InterruptedException ignored) {} continue; }
                        ServerPlayer target = l.get(rnd.nextInt(l.size()));
                        int slot = 13 + rnd.nextInt(8);
                        ItemStack st = new ItemStack(Items.PAPER, 1 + rnd.nextInt(63));
                        long mySeq = seq.incrementAndGet();
                        st.getOrCreateTag().putLong("nwseq", mySeq);
                        long tok = PlayerIncarnationRegistry.incarnationOf(target);
                        java.lang.ref.WeakReference<Object> prev = InventorySetTelemetry.TOKEN_OBJECT.putIfAbsent(tok, new java.lang.ref.WeakReference<>(target));
                        if (prev != null && prev.get() != null && prev.get() != target) tokenCollision.incrementAndGet();
                        originals.add(st);
                        NestWorldInventory.setSlot(target, slot, st);
                        submitted.incrementAndGet();
                        st.setCount(1);                         // caller mutates its own stack after the call
                        st.getOrCreateTag().putLong("nwseq", -mySeq);
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    }
                }, "API1B6-stress-submitter-" + w));
            }
            // Every region object ever seen during the run (identity), so retired regions' mailboxes
            // can be inspected afterwards: messages stranded there are exactly CORE-016's loss.
            java.util.Set<WorldRegion> seenRegions = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
            seenRegions.addAll(sys.getDimensionRegion(level).getGrid().getAllRegions());
            AtomicLong splits = new AtomicLong(), merges = new AtomicLong();
            if (churn) {
                threads.add(new Thread(() -> {
                    java.util.Random rnd = new java.util.Random(11);
                    while (running.get()) {
                        try {
                            server.submit(() -> {
                                NestworldDimensionRegion dr = sys.getDimensionRegion(level);
                                List<WorldRegion> all = new ArrayList<>(dr.getGrid().getAllRegions());
                                seenRegions.addAll(all);
                                if (all.size() < 4 || rnd.nextBoolean()) {
                                    WorldRegion[] kids = dr.getSplitManager().doSplit(all.get(rnd.nextInt(all.size())), "api1b7-churn");
                                    if (kids != null) { splits.incrementAndGet(); seenRegions.addAll(java.util.List.of(kids)); }
                                } else {
                                    outer:
                                    for (WorldRegion x : all) for (WorldRegion y : all) {
                                        if (x == y) continue;
                                        WorldRegion m = dr.getSplitManager().doMerge(x, y, "api1b7-churn");
                                        if (m != null) { merges.incrementAndGet(); seenRegions.add(m); break outer; }
                                    }
                                }
                                return null;
                            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
                            Thread.sleep(2000);
                        } catch (Exception ignored) {}
                    }
                }, "API1B6-stress-churn"));
            }
            threads.forEach(Thread::start);
            out.add("ACTION_REQUIRED chaos " + seconds);
            Thread.sleep(seconds * 1000L);
            running.set(false);
            for (Thread t : threads) t.join(10_000);
            // Drain: wait until terminal outcomes stop changing for 2s.
            long last = -1, stableSince = System.currentTimeMillis();
            while (System.currentTimeMillis() - stableSince < 2000) {
                long now = terminals();
                if (now != last) { last = now; stableSince = System.currentTimeMillis(); }
                Thread.sleep(100);
            }
            long term = terminals();
            long missing = submitted.get() - term;
            int[] finalMismatch = {0}, finalChecked = {0};
            String finalDetail = server.submit(() -> {
                StringBuilder sb = new StringBuilder();
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    if (!sp.getGameProfile().getName().startsWith("stress")) continue;
                    long tok = PlayerIncarnationRegistry.incarnationOf(sp);
                    for (int slot = 13; slot <= 20; slot++) {
                        Long expect = InventorySetTelemetry.maxAppliedSeq(sp.getUUID(), slot, tok);
                        if (expect == null) continue;
                        finalChecked[0]++;
                        ItemStack it = sp.getInventory().getItem(slot);
                        long actual = it.hasTag() ? it.getTag().getLong("nwseq") : Long.MIN_VALUE;
                        if (actual != expect) { finalMismatch[0]++; sb.append(" ").append(sp.getGameProfile().getName()).append("#").append(slot)
                                .append(" expect=").append(expect).append(" actual=").append(actual); }
                    }
                }
                return sb.toString();
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            java.util.Set<WorldRegion> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            live.addAll(sys.getDimensionRegion(level).getGrid().getAllRegions());
            int retiredCount = 0; long stranded = 0;
            synchronized (seenRegions) {
                for (WorldRegion r : seenRegions) if (!live.contains(r)) { retiredCount++; stranded += r.nestworldMailboxDepthFast(); }
            }
            out.add("churn: splits=" + splits.get() + " merges=" + merges.get() + " retiredRegions=" + retiredCount
                    + " messagesStrandedInRetiredMailboxes=" + stranded + " (all message types; CORE-016's silent loss, measured)");
            // CORE-MAILBOX-MERGE-001: retirement handoff accounting (C10 emptiness sweep over every
            // recently retired region; C11 accepted = applied + explicitTerminal + stillInFlight with
            // unaccounted == 0 when -Dnestworld.mailboxAudit=true).
            waitFor(() -> MailboxLifecycle.orphanCount() == 0, 5_000);
            out.add("lifecycle: " + MailboxLifecycle.summary());
            out.add(server.submit(MailboxLifecycle::c10Sweep).get(10, java.util.concurrent.TimeUnit.SECONDS));
            out.add(server.submit(() -> MailboxAudit.accountingReport(MailboxLifecycle.reachableAuditIds())).get(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.Set<Integer> regionIdsAtEnd = new java.util.HashSet<>();
            for (WorldRegion r : sys.getDimensionRegion(level).getGrid().getAllRegions()) regionIdsAtEnd.add(r.getId());
            java.util.Set<Integer> retired = new java.util.HashSet<>(regionIdsAtStart); retired.removeAll(regionIdsAtEnd);
            int limit = NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS;
            out.add("load: submitted=" + submitted.get() + " migrations=" + migrations.get() + " regionsAtStart=" + regionIdsAtStart + " atEnd=" + regionIdsAtEnd
                    + " retiredDuringRun=" + retired);
            out.add("outcomes: applied=" + InventorySetTelemetry.applied.get() + " staleDropped=" + InventorySetTelemetry.staleDropped.get()
                    + " incarnationMismatch=" + InventorySetTelemetry.incarnationMismatch.get() + " targetRemoved=" + InventorySetTelemetry.targetRemoved.get()
                    + " targetNotFound=" + InventorySetTelemetry.targetNotFound.get() + " backendTargetNotFound=" + InventorySetTelemetry.backendTargetNotFound.get()
                    + " rerouteLimitDropped=" + InventorySetTelemetry.rerouteLimitDropped.get() + " notPlayer=" + InventorySetTelemetry.notPlayer.get()
                    + " | terminal=" + term + " MISSING_TERMINAL=" + missing);
            out.add("final state: checked=" + finalChecked[0] + " slots, mismatches=" + finalMismatch[0] + finalDetail);
            long unboundedReroute = InventorySetTelemetry.maxRerouteHop.get() > limit ? 1 : 0;
            out.add("INVARIANTS(stress) duplicateApply=" + InventorySetTelemetry.duplicateApply.get()
                    + " wrongOwnerMutation=" + InventorySetTelemetry.wrongOwnerMutation.get()
                    + " wrongIncarnationMutation=" + InventorySetTelemetry.wrongIncarnationMutation.get()
                    + " staleIntentApply=" + InventorySetTelemetry.staleIntentApply.get()
                    + " callerObjectLeak=" + InventorySetTelemetry.callerObjectLeak.get()
                    + " unboundedReroute=" + unboundedReroute + " (maxRerouteHop=" + InventorySetTelemetry.maxRerouteHop.get() + ", limit=" + limit + ")"
                    + " tokenCollision=" + tokenCollision.get() + " finalStateMismatch=" + finalMismatch[0]);
            boolean primitivePass = InventorySetTelemetry.duplicateApply.get() == 0 && InventorySetTelemetry.wrongOwnerMutation.get() == 0
                    && InventorySetTelemetry.wrongIncarnationMutation.get() == 0 && InventorySetTelemetry.staleIntentApply.get() == 0
                    && InventorySetTelemetry.callerObjectLeak.get() == 0 && unboundedReroute == 0 && tokenCollision.get() == 0
                    && finalMismatch[0] == 0 && InventorySetTelemetry.applied.get() > 0 && InventorySetTelemetry.incarnationMismatch.get() >= 0;
            out.add("RESULT " + tag + ": primitive correctness " + (primitivePass ? "PASS" : "FAIL")
                    + " | delivery: " + (missing == 0 ? "every submission reached a terminal outcome" : missing + " submission(s) with NO terminal outcome -- see CORE-016 attribution"));
        } finally {
            org.apache.logging.log4j.core.config.Configurator.setLevel("NestWorld/DIAG", org.apache.logging.log4j.Level.INFO);
            InventorySetTelemetry.CALLER_ORIGINALS = null;
            prevPins.forEach((r, was) -> r.pinned = was);
            end(out);
        }
        return out;
    }

    private static long terminals() {
        return InventorySetTelemetry.applied.get() + InventorySetTelemetry.staleDropped.get() + InventorySetTelemetry.incarnationMismatch.get()
                + InventorySetTelemetry.targetRemoved.get() + InventorySetTelemetry.targetNotFound.get() + InventorySetTelemetry.backendTargetNotFound.get()
                + InventorySetTelemetry.rerouteLimitDropped.get() + InventorySetTelemetry.notPlayer.get()
                + InventorySetTelemetry.redeliveryLimitDropped.get();
    }
}
