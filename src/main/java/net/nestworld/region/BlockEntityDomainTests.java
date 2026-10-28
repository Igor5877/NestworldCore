package net.nestworld.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AE2-BE-READY-001 R1-a scope 1: deterministic fixtures for {@link BlockEntityExecutionDomain}
 * ({@code /nestworld testmutate bedomain <scenario>}). Core-only (no mod involved): every op just records the thread
 * it ran on, so no chunk needs to be loaded. Each scenario asserts that the op ran exactly once and on the thread that
 * IS the block-entity execution domain of its position at run time (the same resolver the BE phase buckets with),
 * plus BE-domain and mailbox accounting.
 */
final class BlockEntityDomainTests {
    private static final Logger LOG = LogManager.getLogger("NestWorld/BE-DOMAIN");
    /** A vanilla ticking BE type the fixtures temporarily pin / mark cascade-safe (restored afterwards). */
    private static final String TEST_TYPE = "minecraft:beacon";
    private static final int BAND = NestworldTuning.BORDER_BAND_CHUNKS;

    static final List<String> SCENARIOS = List.of("interior", "band", "pinned", "cascade", "outside", "unmanaged", "never-inline",
            "pin-change", "split", "merge", "limit", "failure", "all");

    private BlockEntityDomainTests() {
    }

    static final class Out extends ArrayList<String> {
        @Override
        public boolean add(String s) {
            LOG.info("[BE-DOMAIN-DET] {}", s);
            return super.add(s);
        }
    }

    private static final class Ctx {
        final ServerLevel level;
        final MinecraftServer server;
        final NestworldDimensionRegion dr;
        final List<String> out;
        int pass, fail, skip;

        Ctx(NestworldRegionSystem sys, List<String> out) {
            this.level = sys.getOverworld();
            this.server = level.getServer();
            this.dr = sys.getDimensionRegion(level);
            this.out = out;
        }

        <T> T onMain(Callable<T> c) throws Exception {
            return server.submit(() -> {
                try {
                    return c.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(30, TimeUnit.SECONDS);
        }

        void check(String name, boolean ok, String detail) {
            if (ok) pass++; else fail++;
            out.add((ok ? "PASS " : "FAIL ") + name + " -- " + detail);
        }

        void skipped(String name, String why) {
            skip++;
            out.add("SKIP " + name + " -- " + why);
        }
    }

    /** Records where (and how often) a task's op ran, and its terminal outcome. */
    private static final class Probe {
        final ServerLevel level;
        final BlockPos pos;
        final String type;
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger terminals = new AtomicInteger();
        final AtomicReference<String> thread = new AtomicReference<>("-");
        final AtomicReference<String> domainAtRun = new AtomicReference<>("-");
        final AtomicReference<BlockEntityExecutionDomain.Terminal> terminal = new AtomicReference<>();
        volatile boolean inDomainAtRun;
        volatile boolean terminalOnMain;

        Probe(ServerLevel level, BlockPos pos, String type) {
            this.level = level;
            this.pos = pos;
            this.type = type;
        }

        Runnable op() {
            return () -> {
                runs.incrementAndGet();
                thread.set(Thread.currentThread().getName());
                domainAtRun.set(BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(level, pos, type).toString());
                inDomainAtRun = BlockEntityExecutionDomain.isCurrentThreadIn(level, pos, type);
            };
        }

        void onTerminal(BlockEntityExecutionDomain.Terminal t) {
            terminals.incrementAndGet();
            terminal.set(t);
            terminalOnMain = level.getServer().isSameThread();
        }

        void enqueue() {
            BlockEntityExecutionDomain.enqueueForBlockEntityDomain(level, pos, type, op(), this::onTerminal);
        }

        @Override
        public String toString() {
            return "runs=" + runs + " terminals=" + terminals + " thread=" + thread + " domainAtRun=" + domainAtRun
                    + " inDomainAtRun=" + inDomainAtRun + (terminal.get() == null ? "" : " terminal=" + terminal.get());
        }
    }

    private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
    }

    /** Waits for the first run, then a settle window in which no second run may appear. */
    private static Probe settle(Probe p) throws InterruptedException {
        waitFor(() -> p.runs.get() + p.terminals.get() >= 1, 10_000);
        Thread.sleep(500);
        return p;
    }

    private static boolean exactlyOnceInDomain(Probe p) {
        return p.runs.get() == 1 && p.terminals.get() == 0 && p.inDomainAtRun;
    }

    private static BlockPos posIn(int cx, int cz) {
        return new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8);
    }

    /** Deterministic split: midpoint of the long axis (independent of where entities are). */
    private static WorldRegion[] midpointSplit(Ctx c, WorldRegion r, String reason) {
        return c.dr.getSplitManager().doSplitAtForTest(r, r.preferredSplitAxis(), Integer.MIN_VALUE, reason);
    }

    private static String bounds(WorldRegion r) {
        return "r" + r.getId() + "[" + r.getMinChunkX() + ".." + r.getMaxChunkX() + "," + r.getMinChunkZ() + ".."
                + r.getMaxChunkZ() + "]";
    }

    private static int centerX(WorldRegion r) {
        return (r.getMinChunkX() + r.getMaxChunkX()) >> 1;
    }

    private static int centerZ(WorldRegion r) {
        return (r.getMinChunkZ() + r.getMaxChunkZ()) >> 1;
    }

    /** True if the region has interior chunks on both axes (span > 2 border bands). */
    private static boolean hasInterior(WorldRegion r) {
        return r.getChunkSpanX() > 2 * BAND && r.getMaxChunkZ() - r.getMinChunkZ() + 1 > 2 * BAND;
    }

    /** The region at chunk (0,0), merged with neighbours until it has an interior. Main thread. */
    private static WorldRegion roomyRegion(Ctx c) {
        WorldRegion r = c.dr.getGrid().getRegionForChunk(0, 0);
        for (int guard = 0; r != null && guard < 16 && !(hasInterior(r) && r.canSplit()); guard++) {
            WorldRegion merged = null;
            for (WorldRegion x : List.copyOf(c.dr.getGrid().getAllRegions())) {
                if (x == r) continue;
                merged = c.dr.getSplitManager().doMerge(r, x, "be-domain-setup");
                if (merged != null) break;
            }
            if (merged == null) break;
            r = merged;
        }
        return r != null && hasInterior(r) ? r : null;
    }

    /** BE-domain + mailbox accounting after a scenario. */
    private static void invariants(Ctx c, String name, long pendingBefore) throws Exception {
        waitFor(() -> MailboxLifecycle.orphanCount() == 0 && BlockEntityExecutionDomain.pending() == pendingBefore, 5_000);
        c.check(name + ".beDomainAccounting", BlockEntityExecutionDomain.pending() == pendingBefore,
                BlockEntityExecutionDomain.stats() + " (pending before " + pendingBefore + ")");
        if (MailboxAudit.ENABLED) {
            String acc = c.onMain(() -> MailboxAudit.accountingReport(MailboxLifecycle.reachableAuditIds()));
            long unacc = c.onMain(() -> MailboxAudit.unaccounted(MailboxLifecycle.reachableAuditIds()));
            c.check(name + ".C11", unacc == 0, acc);
        } else {
            c.out.add("NOTE " + name + ".C11 not evaluated: run with -Dnestworld.mailboxAudit=true");
        }
    }

    static List<String> run(NestworldRegionSystem sys, String scenario) {
        Out out = new Out();
        Ctx c = new Ctx(sys, out);
        out.add("=== BE-DOMAIN fixtures: " + scenario + " (audit=" + MailboxAudit.ENABLED + ", freeRunning="
                + NestworldTuning.FREE_RUNNING_REGIONS_ENABLED + ", band=" + BAND + ") ===");
        List<String> todo = scenario.equals("all")
                ? List.of("interior", "band", "pinned", "cascade", "outside", "unmanaged", "never-inline", "pin-change", "split", "merge", "limit", "failure")
                : List.of(scenario);
        for (String s : todo) {
            out.add("--- " + s + " ---");
            long pendingBefore = BlockEntityExecutionDomain.pending();
            try {
                switch (s) {
                    case "interior" -> interior(c);
                    case "band" -> band(c);
                    case "pinned" -> pinned(c);
                    case "cascade" -> cascade(c);
                    case "outside" -> outside(c);
                    case "unmanaged" -> unmanaged(c);
                    case "never-inline" -> neverInline(c);
                    case "pin-change" -> pinChange(c);
                    case "split" -> split(c);
                    case "merge" -> merge(c);
                    case "limit" -> limit(c);
                    case "failure" -> failure(c);
                    default -> c.skipped(s, "unknown scenario");
                }
                invariants(c, s, pendingBefore);
            } catch (Throwable t) {
                c.fail++;
                out.add("FAIL " + s + " -- exception " + t);
                LOG.warn("[BE-DOMAIN-DET] {} threw", s, t);
            }
        }
        out.add("beDomain: " + BlockEntityExecutionDomain.stats());
        out.add("RESULT pass=" + c.pass + " fail=" + c.fail + " skip=" + c.skip
                + (c.fail == 0 ? " -- ALL CHECKS PASS" : " -- FAILURES PRESENT"));
        return out;
    }

    // ------------------------------------------------------------------
    // Resolution policy: the op runs on the domain the BE phase would tick the position on
    // ------------------------------------------------------------------

    /** Interior of R → R's region thread; enqueued from a non-main, non-region thread. */
    private static void interior(Ctx c) throws Exception {
        WorldRegion r = c.onMain(() -> roomyRegion(c));
        if (r == null) { c.skipped("interior", "no region with an interior around chunk (0,0)"); return; }
        Probe p = new Probe(c.level, posIn(centerX(r), centerZ(r)), null);
        String before = BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, p.pos, null).toString();
        p.enqueue();
        settle(p);
        c.check("interior.regionThread", exactlyOnceInDomain(p) && p.domainAtRun.get().equals("REGION#" + r.getId())
                        && p.thread.get().equals(r.owningThread == null ? "?" : r.owningThread.getName()),
                "r" + r.getId() + " resolvedAtEnqueue=" + before + " " + p);
    }

    /** Border band of R (grid-owned by R) → main. */
    private static void band(Ctx c) throws Exception {
        WorldRegion r = c.onMain(() -> roomyRegion(c));
        if (r == null) { c.skipped("band", "no region with an interior around chunk (0,0)"); return; }
        Probe p = new Probe(c.level, posIn(r.getMinChunkX(), centerZ(r)), null);
        boolean gridOwnedByR = c.dr.getGrid().getRegionForChunk(r.getMinChunkX(), centerZ(r)) == r;
        p.enqueue();
        settle(p);
        c.check("band.main", gridOwnedByR && exactlyOnceInDomain(p) && p.domainAtRun.get().equals("MAIN")
                && p.thread.get().equals(c.server.getRunningThread().getName()), "gridOwnedByR=" + gridOwnedByR + " " + p);
    }

    /** Pinned type in R's interior → main. */
    private static void pinned(Ctx c) throws Exception {
        NestworldPins pins = NestworldRegionSystem.get().getPins();
        if (pins.isBePinned(TEST_TYPE)) { c.skipped("pinned", TEST_TYPE + " already pinned on this server"); return; }
        WorldRegion r = c.onMain(() -> roomyRegion(c));
        if (r == null) { c.skipped("pinned", "no region with an interior around chunk (0,0)"); return; }
        String err = c.onMain(() -> pins.pin(TEST_TYPE));
        if (err != null) { c.skipped("pinned", err); return; }
        try {
            Probe typed = new Probe(c.level, posIn(centerX(r), centerZ(r)), TEST_TYPE);
            Probe untyped = new Probe(c.level, typed.pos, null);
            typed.enqueue();
            untyped.enqueue();
            settle(typed);
            settle(untyped);
            c.check("pinned.main", exactlyOnceInDomain(typed) && typed.domainAtRun.get().equals("MAIN"), typed.toString());
            c.check("pinned.sameChunkUntypedStaysRegion", exactlyOnceInDomain(untyped)
                    && untyped.domainAtRun.get().equals("REGION#" + r.getId()), untyped.toString());
        } finally {
            c.onMain(() -> pins.unpin(TEST_TYPE));
        }
    }

    /** Cascade-safe type in R's border band → R's region thread (band included). */
    private static void cascade(Ctx c) throws Exception {
        NestworldPins pins = NestworldRegionSystem.get().getPins();
        if (pins.isCascadeSafeBe(TEST_TYPE) || pins.isBePinned(TEST_TYPE)) {
            c.skipped("cascade", TEST_TYPE + " already pinned/cascade-safe on this server");
            return;
        }
        WorldRegion r = c.onMain(() -> roomyRegion(c));
        if (r == null) { c.skipped("cascade", "no region with an interior around chunk (0,0)"); return; }
        String err = c.onMain(() -> pins.markCascadeSafe(TEST_TYPE));
        if (err != null) { c.skipped("cascade", err); return; }
        try {
            Probe p = new Probe(c.level, posIn(r.getMinChunkX(), centerZ(r)), TEST_TYPE);
            p.enqueue();
            settle(p);
            c.check("cascade.bandOnRegion", exactlyOnceInDomain(p) && p.domainAtRun.get().equals("REGION#" + r.getId()),
                    p.toString());
        } finally {
            c.onMain(() -> pins.unmarkCascadeSafe(TEST_TYPE));
        }
    }

    /** A chunk no region covers → main. */
    private static void outside(Ctx c) throws Exception {
        int cx = 1_800_000;
        if (c.dr.getGrid().getRegionForChunk(cx, 0) != null) { c.skipped("outside", "grid covers chunk " + cx); return; }
        Probe p = new Probe(c.level, posIn(cx, 0), null);
        p.enqueue();
        settle(p);
        c.check("outside.main", exactlyOnceInDomain(p) && p.domainAtRun.get().equals("MAIN"), p.toString());
    }

    /** A level the region system does not manage (the Nether here) → main, via a server task, never inline. */
    private static void unmanaged(Ctx c) throws Exception {
        ServerLevel nether = c.server.getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether == null || NestworldRegionSystem.get().isManagedLevel(nether)) {
            c.skipped("unmanaged", "the Nether is absent or managed on this server");
            return;
        }
        Probe p = new Probe(nether, BlockPos.ZERO, null);
        int inlineRuns = c.onMain(() -> {
            p.enqueue();
            return p.runs.get();
        });
        settle(p);
        c.check("unmanaged.main", inlineRuns == 0 && exactlyOnceInDomain(p) && p.domainAtRun.get().equals("MAIN")
                && p.thread.get().equals(c.server.getRunningThread().getName()), "runsInsideEnqueue=" + inlineRuns + " " + p);
    }

    /** Enqueue on main for a MAIN-domain position never runs the op inline. */
    private static void neverInline(Ctx c) throws Exception {
        WorldRegion r = c.onMain(() -> roomyRegion(c));
        if (r == null) { c.skipped("never-inline", "no region with an interior around chunk (0,0)"); return; }
        Probe p = new Probe(c.level, posIn(r.getMinChunkX(), centerZ(r)), null);
        int inlineRuns = c.onMain(() -> {
            p.enqueue();
            return p.runs.get();
        });
        settle(p);
        c.check("never-inline", inlineRuns == 0 && exactlyOnceInDomain(p), "runsInsideEnqueue=" + inlineRuns + " " + p);
    }

    // ------------------------------------------------------------------
    // Re-resolution: the domain changes between enqueue and execution
    // ------------------------------------------------------------------

    /** Enqueued to R's mailbox (interior), then the type is pinned in the same main task: R's consumer re-resolves
     *  to MAIN and re-routes instead of running it (domain change without any retirement). */
    private static void pinChange(Ctx c) throws Exception {
        NestworldPins pins = NestworldRegionSystem.get().getPins();
        if (pins.isBePinned(TEST_TYPE) || pins.isCascadeSafeBe(TEST_TYPE)) {
            c.skipped("pin-change", TEST_TYPE + " already pinned/cascade-safe on this server");
            return;
        }
        long reroutedBefore = BlockEntityExecutionDomain.rerouted.get();
        Probe[] holder = new Probe[1];
        String setup;
        try {
            setup = c.onMain(() -> {
                WorldRegion r = roomyRegion(c);
                if (r == null) return null;
                Probe p = new Probe(c.level, posIn(centerX(r), centerZ(r)), TEST_TYPE);
                holder[0] = p;
                String at = BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, p.pos, TEST_TYPE).toString();
                String err = pins.pin(TEST_TYPE);
                if (err != null) return "pinfail " + err;
                // The leg exactly as route() addressed it while r was still the domain (r is free-running, so
                // enqueue-then-pin could let r drain in between; posting after the pin makes the order fixed).
                BlockEntityExecutionDomain.accepted.incrementAndGet();
                r.nestworldPostMessage(new RegionMessage<>(null, r, c.server.getTickCount(), 0,
                        RegionMessage.Type.BE_DOMAIN_TASK, new BlockEntityExecutionDomain.Task(c.level, p.pos, TEST_TYPE,
                        p.op(), p::onTerminal, -2, 0), System.nanoTime(), MailboxAudit.nextId()));
                return at + " -> pinned, now " + BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, p.pos, TEST_TYPE);
            });
            if (setup == null) { c.skipped("pin-change", "no region with an interior around chunk (0,0)"); return; }
            settle(holder[0]);
        } finally {
            c.onMain(() -> pins.unpin(TEST_TYPE));
        }
        Probe p = holder[0];
        long rerouted = BlockEntityExecutionDomain.rerouted.get() - reroutedBefore;
        c.check("pin-change.regionToMain", setup.startsWith("REGION#") && setup.endsWith("now MAIN")
                && exactlyOnceInDomain(p) && p.domainAtRun.get().equals("MAIN") && rerouted >= 1,
                setup + "; rerouted+" + rerouted + " " + p);
    }

    /** Split/merge take the affected regions' write locks with a timeout and skip the pass when a region is busy
     *  (documented "will be retried" path). Fixtures retry the whole attempt; every abandoned attempt's probe must
     *  still have run exactly once in its (unchanged) domain. */
    private static final int TOPOLOGY_ATTEMPTS = 10;

    private static boolean abandonedAttemptOk(Ctx c, String name, Probe p) throws Exception {
        settle(p);
        boolean ok = exactlyOnceInDomain(p);
        if (!ok) c.check(name + ".abandonedAttemptRanOnce", false, p.toString());
        Thread.sleep(500);
        return ok;
    }

    /** Enqueued to R's mailbox, R split in the same main task (R never drains): the leg is orphaned, handed back,
     *  re-resolved and runs once in the position's NEW domain. */
    private static void split(Ctx c) throws Exception {
        for (int attempt = 1; attempt <= TOPOLOGY_ATTEMPTS; attempt++) {
            long reroutedBefore = BlockEntityExecutionDomain.rerouted.get();
            Probe[] holder = new Probe[1];
            String setup = c.onMain(() -> {
                WorldRegion r = roomyRegion(c);
                if (r == null) return null;
                // Quarter point on the long axis: interior of r now, interior of the low child after a midpoint cut.
                SplitAxis axis = r.preferredSplitAxis();
                Probe p = new Probe(c.level, axis == SplitAxis.X
                        ? posIn((r.getMinChunkX() + centerX(r)) >> 1, centerZ(r))
                        : posIn(centerX(r), (r.getMinChunkZ() + centerZ(r)) >> 1), null);
                holder[0] = p;
                String at = BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, p.pos, null).toString();
                p.enqueue();                                                       // leg ACCEPTED by r
                WorldRegion[] kids = midpointSplit(c, r, "be-domain-split");
                if (kids == null) return "nosplit";
                return "r" + r.getId() + "(" + at + ") -> [r" + kids[0].getId() + ", r" + kids[1].getId() + "], now "
                        + BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, p.pos, null);
            });
            if (setup == null) { c.skipped("split", "no region with an interior around chunk (0,0)"); return; }
            if (setup.equals("nosplit")) {
                if (!abandonedAttemptOk(c, "split", holder[0])) return;
                continue;
            }
            Probe p = settle(holder[0]);
            long rerouted = BlockEntityExecutionDomain.rerouted.get() - reroutedBefore;
            c.check("split.exactlyOnceInNewDomain", exactlyOnceInDomain(p) && rerouted >= 1,
                    setup + "; attempt " + attempt + "; rerouted+" + rerouted + " " + p);
            return;
        }
        c.check("split.split", false, "doSplit skipped (region lock busy) in all " + TOPOLOGY_ATTEMPTS + " attempts");
    }

    /** Border band of A next to the A|B cut (MAIN domain) enqueued, then A+B merged in the same main task: the
     *  position becomes interior of the merged region; the main pass re-resolves and re-routes it there. */
    private static void merge(Ctx c) throws Exception {
        WorldRegion[] pair = null;
        for (int attempt = 1; attempt <= TOPOLOGY_ATTEMPTS && pair == null; attempt++) {
            pair = c.onMain(() -> {
                WorldRegion r = roomyRegion(c);
                return r == null ? null : midpointSplit(c, r, "be-domain-merge-setup");
            });
            if (pair == null) Thread.sleep(500);
        }
        if (pair == null) { c.check("merge.setupSplit", false, "could not create a sibling pair in " + TOPOLOGY_ATTEMPTS + " attempts"); return; }
        final WorldRegion a = pair[0], b = pair[1];
        int cx, cz;
        if (a.getMaxChunkX() + 1 == b.getMinChunkX()) { cx = a.getMaxChunkX(); cz = centerZ(a); }
        else if (b.getMaxChunkX() + 1 == a.getMinChunkX()) { cx = a.getMinChunkX(); cz = centerZ(a); }
        else if (a.getMaxChunkZ() + 1 == b.getMinChunkZ()) { cx = centerX(a); cz = a.getMaxChunkZ(); }
        else { cx = centerX(a); cz = a.getMinChunkZ(); }
        final BlockPos pos = posIn(cx, cz);
        final String where = " [chunk " + cx + "," + cz + " a=" + bounds(a) + " b=" + bounds(b);
        for (int attempt = 1; attempt <= TOPOLOGY_ATTEMPTS; attempt++) {
            long reroutedBefore = BlockEntityExecutionDomain.rerouted.get();
            Probe p = new Probe(c.level, pos, null);
            String setup = c.onMain(() -> {
                String at = BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, pos, null).toString();
                p.enqueue();                                                   // MAIN queue (A's band)
                WorldRegion m = c.dr.getSplitManager().doMerge(a, b, "be-domain-merge");
                if (m == null) return "nomerge";
                return at + " -> merged, now " + BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(c.level, pos, null)
                        + where + " m=" + bounds(m) + "]";
            });
            if (setup.equals("nomerge")) {
                if (!abandonedAttemptOk(c, "merge", p)) return;
                continue;
            }
            settle(p);
            long rerouted = BlockEntityExecutionDomain.rerouted.get() - reroutedBefore;
            c.check("merge.domainChanged", setup.startsWith("MAIN") && setup.contains(", now REGION#"), setup);
            c.check("merge.mainToRegion", exactlyOnceInDomain(p) && p.domainAtRun.get().startsWith("REGION#") && rerouted >= 1,
                    setup + "; attempt " + attempt + "; rerouted+" + rerouted + " " + p);
            return;
        }
        c.check("merge.merge", false, "doMerge skipped (region lock busy) in all " + TOPOLOGY_ATTEMPTS + " attempts" + where + "]");
    }

    // ------------------------------------------------------------------
    // Bounds and isolation
    // ------------------------------------------------------------------

    /** A task at the redelivery bound is terminated explicitly (on main), and its op never runs. */
    private static void limit(Ctx c) throws Exception {
        Probe p = new Probe(c.level, posIn(0, 0), null);
        BlockEntityExecutionDomain.accepted.incrementAndGet(); // the task is built directly instead of via enqueue()
        BlockEntityExecutionDomain.reroute(new BlockEntityExecutionDomain.Task(c.level, p.pos, null, p.op(), p::onTerminal,
                -1, NestworldTuning.MAILBOX_MAX_REDELIVERIES - 1));
        settle(p);
        c.check("limit.terminalNotRun", p.runs.get() == 0 && p.terminals.get() == 1 && p.terminalOnMain
                && p.terminal.get() == BlockEntityExecutionDomain.Terminal.REDELIVERY_LIMIT_DROPPED, p.toString());
    }

    /** A throwing op is contained and counted; the next task in the same domain still runs. */
    private static void failure(Ctx c) throws Exception {
        long failuresBefore = BlockEntityExecutionDomain.opFailures.get();
        BlockPos pos = posIn(0, 0);
        AtomicInteger ran = new AtomicInteger();
        BlockEntityExecutionDomain.enqueueForBlockEntityDomain(c.level, pos, null, () -> {
            ran.incrementAndGet();
            throw new IllegalStateException("be-domain fixture: deliberate op failure");
        }, null);
        Probe after = new Probe(c.level, pos, null);
        after.enqueue();
        settle(after);
        c.check("failure.contained", ran.get() == 1 && BlockEntityExecutionDomain.opFailures.get() - failuresBefore == 1
                && exactlyOnceInDomain(after), "failingRuns=" + ran + " next: " + after);
    }
}
