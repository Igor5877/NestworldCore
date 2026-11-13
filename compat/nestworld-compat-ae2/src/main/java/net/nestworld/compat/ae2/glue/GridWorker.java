package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * AE2-GRID-DOMAIN-001 G1 (model B): AE2 side of the Core ExecutionDomain primitive
 * (docs/contracts/ExecutionDomainPrimitive.md). The grid's {@code onLevelEndTick / onServerEndTick} phases of eligible
 * grids are submitted, unchanged and in AE2 order, to the Core domain "ae2" with the set of levels the grid touches; Core
 * keeps MAIN out of those levels' BE phase / chunk tasks / save / stop until they finish and gives the job an
 * acting-main lease. AE2-specific duties stay here: eligibility, P(G) on NetworkStorage ingress, and ordering — MAIN
 * waits ({@link #awaitAll}) before the grid's next start phases and before any MAIN topology change.
 * OFF unless {@code -Dnestworld.compatAe2.gridWorker=true}; no compile dependency on Core or modapi (reflection).
 */
public final class GridWorker {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.gridWorker");
    private static final MethodHandle OPEN, SUBMIT, AWAIT, IS_DOMAIN, STATS, SUBMIT_OWNED, CLAIM, RELEASE, EPOCH, OWNS;
    /** Model A (TZ §3″): participating host BEs are owned by the AE2 domain while it runs the grid. */
    public static final boolean OWNERSHIP = Boolean.getBoolean("nestworld.compatAe2.gridOwnership");
    static {
        MethodHandle o = null, s = null, a = null, d = null, st = null, so = null, cl2 = null, rl = null, ep = null, ow = null;
        if (ENABLED) {
            try {
                ClassLoader cl = GridWorker.class.getClassLoader();
                Class<?> facade = Class.forName("net.nestworld.modapi.domain.NestWorldDomains", true, cl);
                Class<?> handle = Class.forName("net.nestworld.modapi.domain.DomainHandle", true, cl);
                var l = MethodHandles.publicLookup();
                o = l.findStatic(facade, "open", MethodType.methodType(handle, String.class)).asType(MethodType.methodType(Object.class, String.class));
                s = l.findVirtual(handle, "submit", MethodType.methodType(boolean.class, Set.class, Runnable.class))
                        .asType(MethodType.methodType(boolean.class, Object.class, Set.class, Runnable.class));
                a = l.findVirtual(handle, "awaitAll", MethodType.methodType(void.class)).asType(MethodType.methodType(void.class, Object.class));
                d = l.findVirtual(handle, "isDomainThread", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
                st = l.findVirtual(handle, "stats", MethodType.methodType(String.class)).asType(MethodType.methodType(String.class, Object.class));
                try {
                    so = l.findVirtual(handle, "submitOwned", MethodType.methodType(boolean.class, Set.class, Runnable.class))
                            .asType(MethodType.methodType(boolean.class, Object.class, Set.class, Runnable.class));
                    cl2 = l.findVirtual(handle, "claim", MethodType.methodType(void.class, ResourceKey.class, long[].class, String[].class))
                            .asType(MethodType.methodType(void.class, Object.class, ResourceKey.class, long[].class, String[].class));
                    rl = l.findVirtual(handle, "release", MethodType.methodType(void.class, ResourceKey.class, long[].class))
                            .asType(MethodType.methodType(void.class, Object.class, ResourceKey.class, long[].class));
                    ep = l.findVirtual(handle, "ownershipEpoch", MethodType.methodType(long.class)).asType(MethodType.methodType(long.class, Object.class));
                    ow = l.findVirtual(handle, "owns", MethodType.methodType(boolean.class, ResourceKey.class, long.class))
                            .asType(MethodType.methodType(boolean.class, Object.class, ResourceKey.class, long.class));
                } catch (Throwable noOwnership) {
                    so = cl2 = rl = ep = ow = null;
                }
            } catch (Throwable unavailable) {
                o = s = a = d = st = so = cl2 = rl = ep = ow = null;
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] grid worker requested but the Core ExecutionDomain API is absent — inline");
            }
        }
        OPEN = o; SUBMIT = s; AWAIT = a; IS_DOMAIN = d; STATS = st; SUBMIT_OWNED = so; CLAIM = cl2; RELEASE = rl; EPOCH = ep; OWNS = ow;
    }

    private static volatile Object handle;
    private static final Map<Grid, Object[]> LEVELS = new WeakHashMap<>();   // MAIN only: {nodeCount, Set<ResourceKey>}
    public static final LongAdder submitted = new LongAdder(), inline = new LongAdder(), ingressLocked = new LongAdder(),
            ingressContended = new LongAdder(), awaits = new LongAdder(), mainHops = new LongAdder(), submittedOwned = new LongAdder(),
            claimRequests = new LongAdder(), releaseRequests = new LongAdder(), promotions = new LongAdder(), demotions = new LongAdder(),
            demotionRequests = new LongAdder();

    private GridWorker() {}

    private static Object handle() {
        Object h = handle;
        if (h == null && OPEN != null) {
            try { h = (Object) OPEN.invokeExact("ae2"); } catch (Throwable t) { h = null; }
            handle = h;
            if (h != null) registerAuditedSetBlockSites();
        }
        return h;
    }

    /** Fast-Live call-site audit 2026-11-12 (TZ §3″.2, owner): setBlock(pos, state, 2), result popped, no
     *  read-after-write; only the client-visible formed/active block state lags ≤ 1 tick. */
    private static void registerAuditedSetBlockSites() {
        String[][] sites = {
                { "net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity", "updateSubType" },
                { "com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase", "updateSubType" },
                { "com.extendedae_plus.content.matrix.supermatrix.SuperAssemblerMatrixBlockEntity", "eap$updateSuperMatrixStatus" },
                { "com.extendedae_plus.content.matrix.PatternCorePlusBlockEntity", "eap$updateSuperMatrixStatus" },
                // AE2 15.4.11 (bytecode audit 2026-11-12, bereach.py): own position, result popped, no read-after-write
                { "appeng.blockentity.networking.EnergyCellBlockEntity", "updateStateForPowerLevel" },  // charge display level
                { "appeng.blockentity.networking.ControllerBlockEntity", "updateState" },             // online/conflicted look
                { "appeng.blockentity.crafting.CraftingBlockEntity", "updateSubType" },               // formed/powered look
                { "appeng.blockentity.AEBaseBlockEntity", "markForUpdate" },
                // MasterfulMachinery 0.1.35.10 (bytecode audit 2026-11-12): setBlock of the controller's own position /
                // its claimed group's port, result popped, no read-after-write (state re-derived next call, idempotent)
                { "io.ticticboom.mods.mm.controller.machine.register.MachineControllerBlockEntity", "setPortState" },
                { "io.ticticboom.mods.mm.controller.machine.register.MachineControllerBlockEntity", "updateControllerState" } };                        // BE-derived block state
        try {
            var m = Class.forName("net.nestworld.region.ExecutionDomains", true, GridWorker.class.getClassLoader())
                    .getMethod("allowDeferredSetBlock", String.class, String.class);
            for (String[] s : sites) m.invoke(null, s[0], s[1]);
        } catch (Throwable t) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] Core has no BlockMutationOp allowlist — deferred setBlock sites not registered");
        }
    }

    public static boolean isWorkerThread() {
        Object h = handle;
        if (h == null || IS_DOMAIN == null) return false;
        try { return (boolean) IS_DOMAIN.invokeExact(h); } catch (Throwable t) { return false; }
    }

    /** MAIN: hand one original grid phase to the Core domain; false ⇒ caller runs it inline (today's behaviour). */
    public static boolean submit(Grid grid, Runnable body) {
        if (!ENABLED || SUBMIT == null || !Ae2Compat.onMainThread() || !GridWorkerEligibility.eligible(grid)) return false;
        Object h = handle();
        if (h == null) return false;
        Set<ResourceKey<Level>> levels = levelsOf(grid);
        if (levels.isEmpty()) return false;
        try {
            if (OWNERSHIP && SUBMIT_OWNED != null && ownedNow(h, grid) && (boolean) SUBMIT_OWNED.invokeExact(h, levels, body)) {
                submittedOwned.increment();
                return true;
            }
            if ((boolean) SUBMIT.invokeExact(h, levels, body)) {
                submitted.increment();
                return true;
            }
        } catch (Throwable t) {
            Ae2Compat.LOG.error("[nestworld-compat-ae2] grid worker submit failed — inline", t);
        }
        inline.increment();
        return false;
    }

    /**
     * AE2-GRID-DOMAIN-MVP-001 (owner 2026-11-13): with {@code -Dnestworld.compatAe2.overlapStart=true} grid START phases
     * are also submitted to the domain (FIFO after the previous tick's END phases) and MAIN no longer waits at tick
     * start; the ownership barrier (flips, host escapes) moves to ONE await per server tick right before the first END
     * phase is submitted — the worker's window becomes MAIN's level ticking instead of MAIN's wait.
     */
    public static final boolean OVERLAP_START = Boolean.getBoolean("nestworld.compatAe2.overlapStart");
    private static int lastEndBarrierTick = Integer.MIN_VALUE;
    public static final LongAdder endBarriers = new LongAdder(), startSubmitted = new LongAdder();

    /** MAIN, before an END phase submission: the per-tick barrier (once per server tick). */
    public static void endOfTickBarrier() {
        if (!OVERLAP_START || !Ae2Compat.onMainThread()) return;
        var srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        int t = srv == null ? 0 : srv.getTickCount();
        if (t == lastEndBarrierTick) return;
        lastEndBarrierTick = t;
        endBarriers.increment();
        awaitAll();
    }

    /** MAIN: every submitted grid phase has finished (before the next start phases and before MAIN topology). */
    public static void awaitAll() {
        Object h = handle;
        if (h == null || AWAIT == null || !Ae2Compat.onMainThread()) return;
        awaits.increment();
        try { AWAIT.invokeExact(h); } catch (Throwable t) { Ae2Compat.LOG.error("[nestworld-compat-ae2] grid worker await failed", t); return; }
        HostEscape.drainOnMain();   // domain idle: one-shot MAIN escapes of claimed hosts (HOST_ESCAPE_MAIN)
        if (OWNERSHIP && EPOCH != null && !BusTransit.commitPassRunning()) {
            // ownership barrier: the domain is idle and no MAIN commit pass runs — flip commit-queue owners here only
            try {
                long epoch = (long) EPOCH.invokeExact(h);
                for (var e : OWNED.entrySet()) {
                    Owned o = e.getValue();
                    if (o.promoted && (o.demotePending || o.claimed == null)) {
                        BusTransit.setWorkerOwned(e.getKey(), false);
                        o.promoted = false;
                        o.demotePending = false;
                        demotions.increment();
                        String why = o.demoteReason != null ? o.demoteReason : o.claimed == null ? "claim released" : "requested (runtime coverage)";
                        var srvT = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        why = "tick " + (srvT == null ? -1 : srvT.getTickCount()) + " " + why;
                        DEMOTION_REASONS.computeIfAbsent(why.length() > 160 ? why.substring(0, 160) : why, k -> new LongAdder()).increment();
                        o.demoteReason = null;
                    } else if (!o.promoted && !o.demotePending && o.claimed != null && epoch > o.requestedAtEpoch) {
                        BusTransit.setWorkerOwned(e.getKey(), true);
                        o.promoted = true;
                        promotions.increment();
                    }
                }
            } catch (Throwable t) {
                Ae2Compat.LOG.error("[nestworld-compat-ae2] ownership barrier flip failed", t);
            }
        }
    }

    /** Runtime coverage violation (BusTransit): send the grid back to model B at the next barrier. */
    static void requestDemotion(Grid grid) {
        Owned o = OWNED.get(grid);
        if (o != null) {
            o.demotePending = true;
            StackWalker.StackFrame f = StackWalker.getInstance().walk(st -> st.skip(1).findFirst().orElse(null));
            o.demoteReason = "requested by " + (f == null ? "?" : f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "#" + f.getMethodName());
        }
        demotionRequests.increment();
    }

    /** Is {@code pos} owned by the AE2 domain in the published snapshot (claimed class 1/1′ participant)? */
    static boolean ownsPosition(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        Object h = handle;
        if (h == null || OWNS == null) return false;
        try { return (boolean) OWNS.invokeExact(h, level.dimension(), pos.asLong()); } catch (Throwable t) { return false; }
    }

    /**
     * NetworkStorage insert/extract from a thread that is not the grid's current owner: take P(G) so the call is
     * serialised with a phase the worker may be running. Inside the grid's own phase (P held) or with the worker off:
     * straight through. A caller already inside a kernel section must not block on P (lock order P → T0 → domains):
     * it waits with tryLock only — contention there is counted (must be 0 on the rig gate).
     */
    public static long ingress(Grid g, LongSupplier call) {
        if (!ENABLED || g == null || handle == null) return call.getAsLong();
        ReentrantLock p = ((NwGrid) g).nw$p();
        if (p.isHeldByCurrentThread()) return call.getAsLong();
        if (MainService.delegatedFor(g)) {           // MAIN acting for G's parked worker (it holds P(G)): MVP-001
            MainService.delegatedIngress.increment();
            return call.getAsLong();
        }
        if (PLocks.holdsAny()) {
            // MVP-001 cross-grid rule: already holding another grid's P ⇒ tryLock only; miss ⇒ nothing this tick (0)
            if (!p.tryLock()) {
                PLocks.miss(g, null);
                return 0L;
            }
            PLocks.crossGridTryLockHit.increment();
        } else if (Ae2Compat.KERNEL.inSection()) {
            if (!p.tryLock()) {
                ingressContended.increment();
                return call.getAsLong();          // diagnostics-only fallback; rig gate requires ingressContended == 0
            }
        } else {
            MainService.lockOnMain(g, p);           // MAIN services parked-worker calls while waiting (no deadlock)
        }
        PLocks.acquired();
        try {
            ingressLocked.increment();
            return call.getAsLong();
        } finally {
            PLocks.released();
            p.unlock();
        }
    }

    /** Per grid: the participant set claimed for it and the epoch at which the claim was requested. MAIN only. */
    private static final class Owned {
        GridOwnership.Participants claimed;
        long requestedAtEpoch = Long.MAX_VALUE;
        boolean promoted, demotePending;           // flipped only at the barrier in awaitAll()
        String demoteReason;                       // attribution (owner: every demotion explained)
    }
    private static final Map<Grid, Owned> OWNED = new java.util.HashMap<>();
    static final Map<String, LongAdder> DEMOTION_REASONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static int lastSweepTick;

    /**
     * MAIN. True when the grid's current participant set is owned by the domain in the PUBLISHED snapshot (claim requested
     * and a later barrier epoch observed). Otherwise (re)requests claims and returns false ⇒ this phase runs model B.
     */
    private static boolean ownedNow(Object h, Grid grid) throws Throwable {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        int tick = server == null ? 0 : server.getTickCount();
        sweep(h, tick);
        GridOwnership.Participants p = GridOwnership.participants(grid, tick);
        Owned o = OWNED.computeIfAbsent(grid, g -> new Owned());
        if (p.refusal() != null) {
            if (o.claimed != null) { releaseAll(h, o.claimed); o.claimed = null; o.requestedAtEpoch = Long.MAX_VALUE; }
            if (o.promoted) { o.demotePending = true; o.demoteReason = "refusal nodes=" + grid.size() + ": " + p.refusal(); }
            return false;
        }
        long epoch = (long) EPOCH.invokeExact(h);
        if (o.claimed != null && o.claimed.sameAs(p)) o.claimed = p;   // recomputed, unchanged: keep the claim
        if (o.claimed != p) {
            if (o.claimed != null) releaseAll(h, o.claimed);
            if (o.promoted) {                              // new participant set: back to B until the next barrier settles
                o.demotePending = true;
                o.demoteReason = "participant set changed nodes=" + grid.size() + " positions " + (o.claimed == null ? -1 : o.claimed.size()) + "→" + p.size();
            }
            for (var e : p.positions().entrySet()) {
                CLAIM.invokeExact(h, e.getKey(), e.getValue(), p.types().get(e.getKey()));
                claimRequests.increment();
            }
            o.claimed = p;
            o.requestedAtEpoch = epoch;
            return false;
        }
        return o.promoted && !o.demotePending && epoch > o.requestedAtEpoch;
    }

    private static void releaseAll(Object h, GridOwnership.Participants p) throws Throwable {
        for (var e : p.positions().entrySet()) {
            RELEASE.invokeExact(h, e.getKey(), e.getValue());
            releaseRequests.increment();
        }
    }

    /** Grids that lost all nodes give their participants back (no claim outlives its grid). */
    private static void sweep(Object h, int tick) throws Throwable {
        if (tick - lastSweepTick < GridOwnership.RECHECK_TICKS) return;
        lastSweepTick = tick;
        var it = OWNED.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().size() == 0 || e.getKey().getPivot() == null) {
                if (e.getValue().claimed != null) releaseAll(h, e.getValue().claimed);
                if (e.getValue().promoted) BusTransit.setWorkerOwned(e.getKey(), false);   // dead grid: nothing runs
                it.remove();
            }
        }
    }

    private static Set<ResourceKey<Level>> levelsOf(Grid grid) {
        int n = grid.size();
        Object[] c = LEVELS.get(grid);
        if (c != null && (int) c[0] == n) {
            @SuppressWarnings("unchecked") Set<ResourceKey<Level>> s = (Set<ResourceKey<Level>>) c[1];
            return s;
        }
        Set<ResourceKey<Level>> s = new HashSet<>();
        for (IGridNode node : grid.getNodes()) {
            if (node.getLevel() != null) s.add(node.getLevel().dimension());
        }
        Set<ResourceKey<Level>> frozen = Set.copyOf(s);
        LEVELS.put(grid, new Object[] { n, frozen });
        return frozen;
    }

    public static String status() {
        String core = "core domain: " + (handle == null ? "not opened" : "?");
        Object h = handle;
        if (h != null && STATS != null) {
            try { core = (String) STATS.invokeExact(h); } catch (Throwable ignored) {}
        }
        return "gridWorker enabled=" + ENABLED + " ownership=" + OWNERSHIP + " api=" + (SUBMIT != null) + "/" + (SUBMIT_OWNED != null)
                + " submitted=" + submitted.sum() + " owned=" + submittedOwned.sum() + " claimReq=" + claimRequests.sum() + " releaseReq="
                + releaseRequests.sum() + " ownedGrids=" + OWNED.size() + " promotions=" + promotions.sum() + " demotions=" + demotions.sum()
                + " demotionRequests=" + demotionRequests.sum() + " inline="
                + inline.sum() + " awaits=" + awaits.sum() + " ingressLocked=" + ingressLocked.sum() + " ingressContended="
                + ingressContended.sum() + " mainHops=" + mainHops.sum() + " overlapStart=" + OVERLAP_START + " startSubmitted=" + startSubmitted.sum() + " endBarriers=" + endBarriers.sum() + "\n" + core + "\n" + coreDomains() + "\n" + GridWorkerEligibility.status() + "\n" + GridOwnership.status() + "\n" + HostEscape.status() + "\n" + MainService.status() + "\n" + ChestGuard.status() + "\n" + PLocks.status() + "\n" + GridPhaseGate.yieldStatus()
                + "\ndemotion reasons: " + DEMOTION_REASONS.entrySet().stream().map(e -> e.getValue().sum() + "× " + e.getKey()).toList();
    }

    private static String coreDomains() {
        try {
            return String.valueOf(Class.forName("net.nestworld.region.ExecutionDomains", true, GridWorker.class.getClassLoader())
                    .getMethod("status").invoke(null));
        } catch (Throwable t) {
            return "core ExecutionDomains: unavailable";
        }
    }
}
