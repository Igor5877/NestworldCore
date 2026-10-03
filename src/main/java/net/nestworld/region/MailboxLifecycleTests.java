package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * CORE-MAILBOX-MERGE-001 TEST-ONLY deterministic fixtures (docs/contracts/
 * CORE-MAILBOX-MERGE-001-fix-spec.md, "Verification"). Run from a background controller thread
 * ({@code /nestworld testmutate mailbox <scenario>}); every world/topology step runs as ONE
 * main-thread task via {@code server.submit(...).get()}, so "enqueue then retire" happens with no
 * region tick in between — the exact L1 shape that lost messages before the fix.
 *
 * <p>Every scenario asserts the closure property for its tracked message(s): exactly ONE
 * observable outcome (apply or explicit terminal), never applied on a RETIRED region, plus C10
 * (every retired region drained and empty) and, with {@code -Dnestworld.mailboxAudit=true}, C11
 * {@code unaccounted == 0}.
 */
final class MailboxLifecycleTests {
    private static final Logger LOG = LogManager.getLogger("NestWorld/CORE-MAILBOX");
    private static final AtomicLong OPIDS = new AtomicLong(3_000_000_000L); // disjoint from other fixtures

    private MailboxLifecycleTests() {}

    /** Streams every line to the log as it is produced (a hung scenario still shows progress). */
    static final class Out extends ArrayList<String> {
        @Override public boolean add(String s) {
            LOG.info("[CORE-MAILBOX-DET] {}", s);
            return super.add(s);
        }
    }

    static final List<String> SCENARIOS = List.of("l1-split", "l1-merge", "l2a", "l2b", "l2b-consumed", "l2c",
            "l3", "l4", "l5", "transfer", "transfer-owned", "notfound", "redeliverylimit", "parked-inbound", "parked-pending", "parked-idle", "parked-demotion", "all");

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final class Ctx {
        final NestworldRegionSystem sys;
        final ServerLevel level;
        final MinecraftServer server;
        final NestworldDimensionRegion dr;
        final List<String> out;
        int pass, fail, skip;

        Ctx(NestworldRegionSystem sys, List<String> out) {
            this.sys = sys;
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

        WorldRegion regionAtChunk(int cx, int cz) {
            return dr.getGrid().getRegionForChunk(cx, cz);
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

    private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return cond.getAsBoolean();
    }

    /** Test entity in chunk (0,0), owned solely by the region covering it. Main thread. */
    private static UUID spawnTarget(Ctx c) throws Exception {
        return c.onMain(() -> {
            loadFixtureChunks(c);
            ArmorStand stand = new ArmorStand(c.level, 4.5, 210.0, 4.5);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            c.level.addFreshEntity(stand);
            WorldRegion owner = ensureSplittable(c, c.regionAtChunk(0, 0));
            Api1A7DeterministicTests.assignSole(c.dr.getGrid().getAllRegions().toArray(new WorldRegion[0]), owner, stand.getUUID());
            return stand.getUUID();
        });
    }

    /** Forces AND synchronously loads chunks (-1..1, 0) to FULL (main thread). setChunkForced alone
     *  is asynchronous: on a world whose spawn is away from the origin the chunk is not loaded yet,
     *  an entity added there is not in the world, and block writes do not stick. */
    private static void loadFixtureChunks(Ctx c) {
        for (int cx = -1; cx <= 1; cx++) {
            c.level.setChunkForced(cx, 0, true);
            c.level.getChunk(cx, 0);
        }
    }

    /** Earlier scenarios keep splitting the region around chunk (0,0); merge it back with its tree
     *  sibling (no message posted yet) until it spans >= 2 chunks on both axes, so every scenario's
     *  own split is possible whatever ran before it. Main thread. */
    private static WorldRegion ensureSplittable(Ctx c, WorldRegion r) {
        for (int guard = 0; guard < 16; guard++) {
            boolean roomy = r.canSplit() && r.getMaxChunkX() > r.getMinChunkX() && r.getMaxChunkZ() > r.getMinChunkZ();
            if (roomy) return r;
            WorldRegion merged = null;
            for (WorldRegion x : List.copyOf(c.dr.getGrid().getAllRegions())) {
                if (x == r) continue;
                merged = c.dr.getSplitManager().doMerge(r, x, "core-mailbox-setup");
                if (merged != null) break;
            }
            if (merged == null) return r;
            r = merged;
        }
        return r;
    }

    private static RegionMessage<RegionMessage.EntityMutate> mutate(Ctx c, WorldRegion dest, UUID target, long opId) {
        return RegionMessage.entityMutate(null, dest, c.server.getTickCount(),
                new RegionMessage.EntityMutate(target, new EntityMutationOp.Ignite(1), 0, opId));
    }

    /** Waits for the outcome to appear, then a settle window in which no SECOND outcome may appear. */
    private static MailboxLifecycleTestHooks.Outcome settle(MailboxLifecycleTestHooks.Outcome o) throws InterruptedException {
        waitFor(() -> o.outcomes() >= 1, 10_000);
        Thread.sleep(1_000);
        return o;
    }

    /** Common post-conditions: C10 on every retired region and C11 accounting (if the audit is on). */
    private static void invariants(Ctx c, String name) throws Exception {
        waitFor(() -> MailboxLifecycle.orphanCount() == 0, 5_000);
        String c10 = c.onMain(MailboxLifecycle::c10Sweep);
        c.check(name + ".C10", c.onMain(MailboxLifecycle::c10Holds), c10);
        String counters = c.onMain(MailboxLifecycle::counterConsistencySweep);
        c.check(name + ".counters", counters.endsWith(" consistent"), counters);
        if (MailboxAudit.ENABLED) {
            String acc = c.onMain(() -> MailboxAudit.accountingReport(MailboxLifecycle.reachableAuditIds()));
            long unacc = c.onMain(() -> MailboxAudit.unaccounted(MailboxLifecycle.reachableAuditIds()));
            c.check(name + ".C11", unacc == 0, acc);
        } else {
            c.out.add("NOTE " + name + ".C11 not evaluated: run with -Dnestworld.mailboxAudit=true");
        }
    }

    private static boolean exactlyOneApply(MailboxLifecycleTestHooks.Outcome o) {
        return o.applied.get() == 1 && o.terminals.get() == 0 && o.appliedOnRetired.get() == 0;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    static List<String> run(NestworldRegionSystem sys, String scenario) {
        Out out = new Out();
        Ctx c = new Ctx(sys, out);
        out.add("=== CORE-MAILBOX-MERGE-001 fixtures: " + scenario + " (audit=" + MailboxAudit.ENABLED
                + ", freeRunning=" + NestworldTuning.FREE_RUNNING_REGIONS_ENABLED + ") ===");
        MailboxLifecycleTestHooks.disarmAll();
        MailboxLifecycleTestHooks.ENABLED = true;
        boolean traceBefore = ApiOpTrace.ENABLED;
        ApiOpTrace.ENABLED = true; // STRESS-001: fixtures trace for their own duration only
        try {
            List<String> todo = scenario.equals("all")
                    ? List.of("l5", "l1-split", "l1-merge", "l2a", "l2b", "l2b-consumed", "l3", "l4", "transfer", "transfer-owned", "notfound", "redeliverylimit", "parked-inbound", "parked-pending", "parked-idle", "l2c")
                    : List.of(scenario);
            for (String s : todo) {
                out.add("--- " + s + " ---");
                try {
                    switch (s) {
                        case "l1-split" -> l1Split(c);
                        case "l1-merge" -> l1Merge(c);
                        case "l2a" -> l2a(c);
                        case "l2b" -> l2b(c);
                        case "l2b-consumed" -> l2bConsumed(c);
                        case "l2c" -> l2c(c);
                        case "l3" -> l3(c);
                        case "l4" -> l4(c);
                        case "l5" -> l5(c);
                        case "transfer" -> transfer(c, false);
                        case "transfer-owned" -> transfer(c, true);
                        case "notfound" -> notFound(c);
                        case "parked-inbound" -> parked(c, false);
                        case "parked-pending" -> parked(c, true);
                        case "parked-idle" -> parkedIdle(c);
                        case "parked-demotion" -> parkedDemotion(c);
                        case "redeliverylimit" -> redeliveryLimit(c);
                        default -> c.skipped(s, "unknown scenario");
                    }
                } catch (Throwable t) {
                    c.fail++;
                    out.add("FAIL " + s + " -- exception " + t);
                    LOG.warn("[CORE-MAILBOX-DET] {} threw", s, t);
                } finally {
                    MailboxLifecycleTestHooks.disarmAll();
                    MailboxLifecycleTestHooks.ENABLED = true;
                    EntityMutationTestHooks.disarmAll();
                    EntityMutationTestHooks.ENABLED = false;
                    EntityMutationTestHooks.ONLY_OP = null; // l2a filters barriers to Ignite; never leak that filter
                }
            }
        } finally {
            MailboxLifecycleTestHooks.disarmAll();
            MailboxLifecycleTestHooks.ENABLED = false;
            ApiOpTrace.ENABLED = traceBefore;
        }
        out.add("lifecycle: " + MailboxLifecycle.summary());
        out.add("RESULT pass=" + c.pass + " fail=" + c.fail + " skip=" + c.skip
                + (c.fail == 0 ? " -- ALL CHECKS PASS" : " -- FAILURES PRESENT"));
        return out;
    }

    // ------------------------------------------------------------------
    // L1 -- accepted, then the destination retires before its next drain (no race needed)
    // ------------------------------------------------------------------

    private static void l1Split(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long drainedBefore = MailboxLifecycle.drainedOnRetire.get();
        int[] ids = c.onMain(() -> {
            WorldRegion r = c.regionAtChunk(0, 0);
            r.nestworldPostMessage(mutate(c, r, target, opId));                  // ACCEPTED by r
            WorldRegion[] kids = c.dr.getSplitManager().doSplit(r, "core-mailbox-l1");  // same task: r never drains
            return kids == null ? null : new int[]{r.getId(), kids[0].getId(), kids[1].getId()};
        });
        if (ids == null) { c.skipped("l1-split", "region at chunk (0,0) could not be split"); return; }
        settle(o);
        WorldRegion owner = c.dr.getGrid().findOwningRegion(target);
        c.check("l1-split.exactlyOneApply", exactlyOneApply(o) && o.orphaned.get() == 1,
                "retired r" + ids[0] + " -> [r" + ids[1] + ", r" + ids[2] + "], outcome " + o + ", entity owner now r"
                        + (owner == null ? "null" : owner.getId()) + ", drainedOnRetire+" + (MailboxLifecycle.drainedOnRetire.get() - drainedBefore));
        c.check("l1-split.appliedAtSuccessorOwner", owner != null && o.appliedRegionId == owner.getId(),
                "applied@r" + o.appliedRegionId);
        invariants(c, "l1-split");
    }

    private static void l1Merge(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        // Task 1 (no message yet): split so the entity's region has a guaranteed tree sibling.
        WorldRegion[] pair = c.onMain(() -> c.dr.getSplitManager().doSplit(c.dr.getGrid().findOwningRegion(target), "core-mailbox-l1m-setup"));
        if (pair == null) { c.skipped("l1-merge", "could not create a sibling pair"); return; }
        Thread.sleep(200);
        // Task 2: post to the entity's owner, then merge it with its sibling in the same task.
        int[] ids = c.onMain(() -> {
            WorldRegion owner = c.dr.getGrid().findOwningRegion(target);
            WorldRegion sibling = owner == pair[0] ? pair[1] : pair[0];
            owner.nestworldPostMessage(mutate(c, owner, target, opId));
            WorldRegion merged = c.dr.getSplitManager().doMerge(pair[0], pair[1], "core-mailbox-l1m");
            return merged == null ? new int[]{-1} : new int[]{owner.getId(), sibling.getId(), merged.getId()};
        });
        if (ids.length == 1) { c.check("l1-merge.merged", false, "doMerge of fresh siblings returned null after posting"); return; }
        settle(o);
        c.check("l1-merge.exactlyOneApply", exactlyOneApply(o) && o.appliedRegionId == ids[2] && o.orphaned.get() == 1,
                "posted to r" + ids[0] + ", merged [r" + ids[0] + ", r" + ids[1] + "] -> r" + ids[2] + ", outcome " + o);
        invariants(c, "l1-merge");
    }

    // ------------------------------------------------------------------
    // L2 -- the ENQUEUE race and the C4 remove()==false branch
    // ------------------------------------------------------------------

    /** L2a: producer resolved R, R retires, producer enqueues -> REJECTED_RETIRED -> orphan -> delivered. */
    private static void l2a(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        var entity = c.onMain(() -> c.level.getEntity(target));
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long rejectedBefore = MailboxLifecycle.rejectedRetired.get();
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ONLY_OP = EntityMutationOp.Ignite.class;
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armA();
        Thread producer = new Thread(() -> EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.Ignite(1), opId),
                "CORE-MAILBOX-l2a-producer");
        producer.start();
        boolean hit = EntityMutationTestHooks.awaitHitA(10);
        int resolved = c.dr.getGrid().findOwningRegion(target).getId();
        int[] kids = c.onMain(() -> {
            WorldRegion r = c.dr.getGrid().findOwningRegion(target);
            WorldRegion[] k = c.dr.getSplitManager().doSplit(r, "core-mailbox-l2a");
            return k == null ? null : new int[]{k[0].getId(), k[1].getId()};
        });
        EntityMutationTestHooks.releaseA();
        producer.join(5_000);
        EntityMutationTestHooks.ENABLED = false;
        if (kids == null) { c.skipped("l2a", "owner region could not be split"); return; }
        settle(o);
        c.check("l2a.rejectedThenDelivered", hit && exactlyOneApply(o) && o.orphaned.get() == 1
                        && MailboxLifecycle.rejectedRetired.get() - rejectedBefore == 1,
                "producer paused after resolving r" + resolved + " (hit=" + hit + "), r" + resolved + " retired -> [r" + kids[0]
                        + ", r" + kids[1] + "], rejectedRetired+" + (MailboxLifecycle.rejectedRetired.get() - rejectedBefore) + ", outcome " + o);
        invariants(c, "l2a");
    }

    /** L2b: producer adds, pauses BEFORE its RETIRED re-check; retirement drain takes the message;
     *  producer's remove() fails -> ACCEPTED; exactly one redelivery + one APPLY. The add and the
     *  retirement happen inside one main-thread task, so (barrier mode) no region tick can drain
     *  the message in between. */
    private static void l2b(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long removeFailedBefore = MailboxLifecycle.acceptedRemoveFailed.get();
        String r = c.onMain(() -> {
            WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
            RegionMessage<?> msg = mutate(c, dest, target, opId);
            MailboxLifecycleTestHooks.armAfterAdd(msg.auditId());
            Thread producer = new Thread(() -> dest.nestworldPostMessage(msg), "CORE-MAILBOX-l2b-producer");
            producer.start();
            boolean hit = MailboxLifecycleTestHooks.awaitAfterAddHit(5);
            WorldRegion[] k = c.dr.getSplitManager().doSplit(dest, "core-mailbox-l2b");
            MailboxLifecycleTestHooks.releaseAfterAdd();
            producer.join(5_000);
            return "hit=" + hit + " r" + dest.getId() + (k == null ? " NOT split" : " -> [r" + k[0].getId() + ", r" + k[1].getId() + "]");
        });
        if (r.contains("NOT split")) { c.skipped("l2b", r); return; }
        settle(o);
        long rf = MailboxLifecycle.acceptedRemoveFailed.get() - removeFailedBefore;
        c.check("l2b.removeFailedHandedOff", r.startsWith("hit=true") && rf == 1 && o.orphaned.get() == 1 && exactlyOneApply(o),
                r + ", acceptedRemoveFailed+" + rf + " (remove()==false, ACCEPTED), outcome " + o
                        + " -- retirement drain owned it: orphaned once, applied once");
        invariants(c, "l2b");
    }

    /** L2b variant: the pre-retirement consumer (region worker) took the message before retirement;
     *  producer's remove() fails -> ACCEPTED; exactly one APPLY, no orphan, no redelivery. */
    private static void l2bConsumed(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long removeFailedBefore = MailboxLifecycle.acceptedRemoveFailed.get();
        WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
        RegionMessage<?> msg = mutate(c, dest, target, opId);
        MailboxLifecycleTestHooks.armAfterAdd(msg.auditId());
        Thread producer = new Thread(() -> dest.nestworldPostMessage(msg), "CORE-MAILBOX-l2bc-producer");
        producer.start();
        boolean hit = MailboxLifecycleTestHooks.awaitAfterAddHit(5);
        boolean consumed = waitFor(() -> o.applied.get() == 1, 10_000); // the region worker drains it on its tick
        int[] k = c.onMain(() -> {
            WorldRegion[] kids = c.dr.getSplitManager().doSplit(dest, "core-mailbox-l2bc");
            return kids == null ? null : new int[]{kids[0].getId(), kids[1].getId()};
        });
        MailboxLifecycleTestHooks.releaseAfterAdd();
        producer.join(5_000);
        if (k == null) { c.skipped("l2b-consumed", "region could not be split"); return; }
        Thread.sleep(1_500);
        long rf = MailboxLifecycle.acceptedRemoveFailed.get() - removeFailedBefore;
        c.check("l2b-consumed.preRetirementConsumer", hit && consumed && rf == 1 && o.orphaned.get() == 0 && exactlyOneApply(o),
                "hit=" + hit + " consumedBeforeRetire=" + consumed + " r" + dest.getId() + " -> [r" + k[0] + ", r" + k[1]
                        + "], acceptedRemoveFailed+" + rf + ", outcome " + o + " -- applied once by r" + dest.getId() + " before RETIRED, never orphaned");
        invariants(c, "l2b-consumed");
    }

    /** L2c (C3): the old worker is blocked right before its region lock while the region retires;
     *  after the lock is released it must consume and tick NOTHING; a forced join timeout is
     *  watched, never treated as a reason to skip/undo the handoff. Needs free-running regions. */
    private static void l2c(Ctx c) throws Exception {
        if (!NestworldTuning.FREE_RUNNING_REGIONS_ENABLED) {
            c.skipped("l2c", "needs -Dnestworld.freeRunningRegions=true (a barrier-mode worker only runs while main waits on it)");
            return;
        }
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
        long skippedBefore = MailboxLifecycle.workerSkippedRetired.get();
        long lateBefore = MailboxLifecycle.workerLateExit.get();
        long failedBefore = MailboxLifecycle.retirementFailed.get();
        c.onMain(() -> { dest.nestworldSetFreeRunning(true); return null; });
        MailboxLifecycleTestHooks.armWorkerLock(dest.getId());
        boolean hit = MailboxLifecycleTestHooks.awaitWorkerHit(10);
        int[] k = c.onMain(() -> {
            dest.nestworldPostMessage(mutate(c, dest, target, opId)); // the paused worker cannot drain it
            MailboxLifecycleTestHooks.setForceJoinTimeout(true);
            WorldRegion[] kids = c.dr.getSplitManager().doSplit(dest, "core-mailbox-l2c");
            MailboxLifecycleTestHooks.setForceJoinTimeout(false);
            return kids == null ? null : new int[]{kids[0].getId(), kids[1].getId()};
        });
        MailboxLifecycleTestHooks.releaseWorker();
        if (k == null) { c.skipped("l2c", "region could not be split"); return; }
        settle(o);
        boolean exited = waitFor(() -> MailboxLifecycle.workerLateExit.get() > lateBefore, 5_000);
        long skipped = MailboxLifecycle.workerSkippedRetired.get() - skippedBefore;
        c.check("l2c.oldWorkerExcluded", hit && skipped == 1 && exactlyOneApply(o) && o.appliedRegionId != dest.getId(),
                "worker of r" + dest.getId() + " paused before lock (hit=" + hit + "), retired -> [r" + k[0] + ", r" + k[1]
                        + "], workerSkippedRetired+" + skipped + ", outcome " + o);
        c.check("l2c.joinTimeoutWatched", exited && MailboxLifecycle.retirementFailed.get() == failedBefore,
                "forced join timeout -> exit watch -> workerLateExit+" + (MailboxLifecycle.workerLateExit.get() - lateBefore)
                        + ", retirementFailed+" + (MailboxLifecycle.retirementFailed.get() - failedBefore));
        invariants(c, "l2c");
    }

    // ------------------------------------------------------------------
    // L3/L4 -- sender-side backpressure pending queue
    // ------------------------------------------------------------------

    /** L3: a send held in SENDER r's pending queue; r retires -> PENDING_SENDER_RETIRED -> delivered. */
    private static void l3(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long before = MailboxLifecycle.pendingSenderRetired.get();
        String r = c.onMain(() -> {
            WorldRegion sender = c.dr.getGrid().findOwningRegion(target);
            MailboxLifecycleTestHooks.setThresholdOverride(0); // every send is held at the sender
            sender.nestworldSendOrQueue(sender, mutate(c, sender, target, opId));
            int pending = sender.nestworldOutboundPendingSize();
            WorldRegion[] k = c.dr.getSplitManager().doSplit(sender, "core-mailbox-l3");
            MailboxLifecycleTestHooks.setThresholdOverride(-1);
            return "sender r" + sender.getId() + " pending=" + pending + (k == null ? " NOT split" : " -> [r" + k[0].getId() + ", r" + k[1].getId() + "]");
        });
        if (r.contains("NOT split")) { c.skipped("l3", r); return; }
        settle(o);
        c.check("l3.senderPendingHandedOff", r.contains("pending=1") && MailboxLifecycle.pendingSenderRetired.get() - before == 1 && exactlyOneApply(o),
                r + ", pendingSenderRetired+" + (MailboxLifecycle.pendingSenderRetired.get() - before) + ", outcome " + o);
        invariants(c, "l3");
    }

    /** L4: a send held at live sender A for destination D; D retires; A's next flush orphans it
     *  (PENDING_DESTINATION_RETIRED) -> re-resolved -> delivered. */
    private static void l4(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        long before = MailboxLifecycle.pendingDestinationRetired.get();
        String r = c.onMain(() -> {
            WorldRegion sender = c.dr.getGrid().findOwningRegion(target);
            WorldRegion d = null;
            for (WorldRegion x : c.dr.getGrid().getAllRegions()) if (x != sender && x.canSplit()) { d = x; break; }
            if (d == null) return "NOT split: no second splittable region";
            MailboxLifecycleTestHooks.setThresholdOverride(0);
            sender.nestworldSendOrQueue(d, mutate(c, d, target, opId)); // held at sender, addressed to d
            WorldRegion[] k = c.dr.getSplitManager().doSplit(d, "core-mailbox-l4");
            MailboxLifecycleTestHooks.setThresholdOverride(-1);
            return "sender r" + sender.getId() + " held send to r" + d.getId() + (k == null ? " NOT split" : ", r" + d.getId() + " retired -> [r" + k[0].getId() + ", r" + k[1].getId() + "]");
        });
        if (r.contains("NOT split")) { c.skipped("l4", r); return; }
        settle(o);
        c.check("l4.destinationRetiredWhilePending", MailboxLifecycle.pendingDestinationRetired.get() - before == 1 && exactlyOneApply(o),
                r + ", pendingDestinationRetired+" + (MailboxLifecycle.pendingDestinationRetired.get() - before) + ", outcome " + o);
        invariants(c, "l4");
    }

    // ------------------------------------------------------------------
    // L5 -- main-drained types; EXPLOSION_APPLY partition (C8)
    // ------------------------------------------------------------------

    private static void l5(Ctx c) throws Exception {
        List<BlockPos> input = List.of(new BlockPos(8, 200, 8), new BlockPos(9, 200, 8),
                new BlockPos(24, 200, 8), new BlockPos(25, 200, 8), new BlockPos(26, 200, 8));
        BlockPos writePos = new BlockPos(24, 201, 8);
        AtomicReference<List<RegionMessage<?>>> children = new AtomicReference<>();
        AtomicReference<RegionMessage<?>> parentSeen = new AtomicReference<>();
        MailboxRetargeting.EXPLOSION_SPLIT_OBSERVER = (parent, kids) -> {
            parentSeen.set(parent);
            children.set(kids);
            for (RegionMessage<?> k : kids) MailboxLifecycleTestHooks.trackAudit(k.auditId());
        };
        long noOwnerBefore = MailboxRetargeting.explosionPositionsNoOwner.get();
        Explosion[] explosionRef = new Explosion[1];
        long[] ids = c.onMain(() -> {
            loadFixtureChunks(c);
            WorldRegion r = c.regionAtChunk(0, 0);
            if (r != c.regionAtChunk(1, 0) || !r.canSplit()) return null; // need chunks 0 and 1 in ONE splittable region
            for (BlockPos p : input) c.level.setBlock(p, Blocks.GLASS.defaultBlockState(), 3);
            c.level.setBlock(writePos, Blocks.AIR.defaultBlockState(), 3);
            Explosion ex = new Explosion(c.level, null, 16.0, 200.0, 8.0, 4.0f, false, Explosion.BlockInteraction.DESTROY);
            explosionRef[0] = ex;
            var batchMsg = RegionMessage.explosionApply(null, r, c.server.getTickCount(),
                    new Explosion.NestworldExplosionBatch(ex, input, true));
            var writeMsg = RegionMessage.blockWrite(null, r, c.server.getTickCount(),
                    new RegionMessage.BlockWrite(writePos, Blocks.GOLD_BLOCK.defaultBlockState(), 3, 512));
            MailboxLifecycleTestHooks.trackAudit(batchMsg.auditId());
            MailboxLifecycleTestHooks.trackAudit(writeMsg.auditId());
            r.nestworldPostMessage(batchMsg);
            r.nestworldPostMessage(writeMsg);
            // cut at chunk X=0: child A = [.. 0], child B = [1 ..] -> the batch spans both
            WorldRegion[] k = c.dr.getSplitManager().doSplitAtForTest(r, SplitAxis.X, 0, "core-mailbox-l5");
            if (k == null) return new long[]{-1};
            return new long[]{batchMsg.auditId(), writeMsg.auditId(), r.getId(), k[0].getId(), k[1].getId()};
        });
        try {
            if (ids == null) { c.skipped("l5", "chunks (0,0) and (1,0) are not in one splittable region"); return; }
            if (ids.length == 1) { c.check("l5.split", false, "doSplitAtForTest returned null after posting"); return; }
            var parent = MailboxLifecycleTestHooks.BY_AUDIT.get(ids[0]);
            var write = MailboxLifecycleTestHooks.BY_AUDIT.get(ids[1]);
            waitFor(() -> parent.outcomes() >= 1 && children.get() != null
                    && children.get().stream().allMatch(k -> MailboxLifecycleTestHooks.BY_AUDIT.get(k.auditId()).outcomes() >= 1)
                    && write.outcomes() >= 1, 10_000);
            Thread.sleep(1_000);
            List<RegionMessage<?>> kids = children.get() == null ? List.of() : children.get();
            // C8: multiset equality, disjointness, context preservation
            Map<BlockPos, Integer> union = new HashMap<>();
            boolean contextKept = true, eachAppliedOnce = true;
            StringBuilder perChild = new StringBuilder();
            for (RegionMessage<?> k : kids) {
                Explosion.NestworldExplosionBatch b = (Explosion.NestworldExplosionBatch) k.payload();
                for (BlockPos p : b.positions()) union.merge(p, 1, Integer::sum);
                contextKept &= b.explosion() == explosionRef[0] && b.spawnDrops();
                var ko = MailboxLifecycleTestHooks.BY_AUDIT.get(k.auditId());
                eachAppliedOnce &= ko != null && exactlyOneApply(ko) && ko.appliedRegionId == k.destinationRegion().getId();
                perChild.append(" r").append(k.destinationRegion().getId()).append(b.positions()).append('{').append(ko).append('}');
            }
            Map<BlockPos, Integer> in = new HashMap<>();
            for (BlockPos p : input) in.merge(p, 1, Integer::sum);
            boolean disjoint = union.values().stream().allMatch(v -> v == 1);
            long noOwner = MailboxRetargeting.explosionPositionsNoOwner.get() - noOwnerBefore;
            c.check("l5.explosionPartition", kids.size() == 2 && union.equals(in) && disjoint && noOwner == 0,
                    "r" + ids[2] + " -> [r" + ids[3] + ", r" + ids[4] + "]; parent " + parent + "; children:" + perChild
                            + "; input==union:" + union.equals(in) + " disjoint:" + disjoint + " noOwner:" + noOwner);
            c.check("l5.explosionContextAndApply", parent.terminals.get() == 1 && "SPLIT_INTO_CHILDREN".equals(parent.terminalKind)
                            && parent.applied.get() == 0 && contextKept && eachAppliedOnce,
                    "parent closed SPLIT_INTO_CHILDREN; same Explosion object + spawnDrops in every child: " + contextKept
                            + "; each child applied exactly once at its own owner: " + eachAppliedOnce);
            boolean worldOk = c.onMain(() -> {
                boolean ok = true;
                for (BlockPos p : input) ok &= c.level.getBlockState(p).isAir();
                return ok && c.level.getBlockState(writePos).is(Blocks.GOLD_BLOCK);
            });
            c.check("l5.blockWriteAndWorldState", exactlyOneApply(write) && write.appliedRegionId == (int) ids[4] && worldOk,
                    "BLOCK_WRITE outcome " + write + " (expected @r" + ids[4] + "), all exploded positions air + gold written: " + worldOk);
            invariants(c, "l5");
        } finally {
            MailboxRetargeting.EXPLOSION_SPLIT_OBSERVER = null;
        }
    }

    // ------------------------------------------------------------------
    // C9 -- ENTITY_TRANSFER stranded by retirement
    // ------------------------------------------------------------------

    /** A transfer in flight exactly as BoundaryEntityTransfer leaves it (entity removed from its old
     *  owner, ENTITY_TRANSFER posted to the new one), then the new owner retires before the transfer
     *  drains. {@code alreadyOwned=false}: redelivered by the entity's live position, the successor
     *  ends up the SOLE owner. {@code alreadyOwned=true}: a live region already owns the entity at
     *  redelivery -> explicit TRANSFER_ALREADY_OWNED, never a second owner. */
    private static void transfer(Ctx c, boolean alreadyOwned) throws Exception {
        String name = alreadyOwned ? "transfer-owned" : "transfer";
        UUID target = spawnTarget(c);
        long[] ids = c.onMain(() -> {
            WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
            for (WorldRegion x : c.dr.getGrid().getAllRegions()) x.removeEntity(target); // in transfer: unowned
            var msg = RegionMessage.entityTransfer(null, dest, c.server.getTickCount(), target);
            MailboxLifecycleTestHooks.trackAudit(msg.auditId());
            dest.nestworldPostMessage(msg);
            WorldRegion[] k = c.dr.getSplitManager().doSplit(dest, "core-mailbox-" + name);
            if (k == null) return null;
            WorldRegion atPos = c.regionAtChunk(0, 0);
            if (alreadyOwned) atPos.addEntity(target); // e.g. a rescan assigned it before redelivery
            return new long[]{msg.auditId(), dest.getId(), atPos.getId()};
        });
        if (ids == null) { c.skipped(name, "region could not be split"); return; }
        var o = settle(MailboxLifecycleTestHooks.BY_AUDIT.get(ids[0]));
        int[] owners = c.onMain(() -> {
            int n = 0, id = -1;
            for (WorldRegion x : c.dr.getGrid().getAllRegions()) if (x.ownsEntity(target)) { n++; id = x.getId(); }
            return new int[]{n, id};
        });
        boolean ok = alreadyOwned
                ? o.applied.get() == 0 && o.terminals.get() == 1 && "TRANSFER_ALREADY_OWNED".equals(o.terminalKind)
                : exactlyOneApply(o) && o.appliedRegionId == (int) ids[2];
        c.check(name + ".outcome", ok && owners[0] == 1 && owners[1] == (int) ids[2],
                "r" + ids[1] + " retired with the transfer queued; outcome " + o + "; owners of entity now: count=" + owners[0]
                        + " region=r" + owners[1] + " (expected exactly r" + ids[2] + ", the region at its position)");
        invariants(c, name);
    }

    // ------------------------------------------------------------------
    // CORE-PARKED-REGION-001 deterministic reproductions (evidence only; no expectation of a fix)
    // ------------------------------------------------------------------

    private static final long PARKED_WINDOW_MS = Long.getLong("nestworld.parkedWindowMs", 15_000L);
    /** CORE-PARKED-REGION-001 DoD: bounded, tick-scale delivery without any trigger (40 ticks). */
    private static final long PARKED_DELIVERY_MS = Long.getLong("nestworld.parkedDeliveryMs", 2_000L);

    /** A region guaranteed to own no entities: the leaf around a far chunk, cut by explicit
     *  splits until it excludes the origin (unloaded chunks hold no entities). Main thread. */
    private static WorldRegion farEmptyRegion(Ctx c) {
        final int far = 3000;
        WorldRegion r = c.regionAtChunk(far, far);
        for (int i = 0; i < 4 && r != null && (r.getMinChunkX() < far || r.getMinChunkZ() < far); i++) {
            SplitAxis axis = r.getMinChunkX() < far ? SplitAxis.X : SplitAxis.Z;
            WorldRegion[] k = c.dr.getSplitManager().doSplitAtForTest(r, axis, far - 1, "core-parked-isolate");
            if (k == null) break;
            r = c.regionAtChunk(far, far);
        }
        return r;
    }

    /**
     * P1 (parked-inbound): a stale-owner ENTITY_MUTATE sits in the inbound mailbox of a region with
     * no owned entities. P2 (parked-pending): a backpressure-held send sits in the outbound pending
     * queue of such a region (sender side). Both are consumed only by that region's entity round.
     * Observe for PARKED_WINDOW_MS with NO trigger, then make the region dispatch-eligible
     * (one owned entity id) and measure the time to the outcome.
     */
    private static void parked(Ctx c, boolean pendingSide) throws Exception {
        String name = pendingSide ? "parked-pending" : "parked-inbound";
        c.onMain(() -> farEmptyRegion(c)); // isolate first: its splits must not race the target's ownership
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        WorldRegion[] holder = new WorldRegion[1];
        String setup = c.onMain(() -> {
            WorldRegion r = farEmptyRegion(c);
            WorldRegion owner = c.dr.getGrid().findOwningRegion(target);
            if (r == null || owner == null || r == owner || !r.getOwnedEntityIds().isEmpty()) return null;
            holder[0] = r;
            // Pinned: an auto-merge of this idle region would retire it, and the CORE-MAILBOX
            // retirement drain would then deliver the work -- an incidental trigger (observed),
            // which must not decide this reproduction.
            r.pinned = true;
            if (pendingSide) {
                MailboxLifecycleTestHooks.setThresholdOverride(0);
                r.nestworldSendOrQueue(owner, mutate(c, owner, target, opId)); // held at sender r
                MailboxLifecycleTestHooks.setThresholdOverride(-1);
            } else {
                r.nestworldPostMessage(mutate(c, r, target, opId));            // stale owner r
            }
            return "r" + r.getId() + " " + r.getMinChunkX() + ".." + r.getMaxChunkX() + "," + r.getMinChunkZ() + ".." + r.getMaxChunkZ()
                    + " (target owned by r" + owner.getId() + ")";
        });
        if (setup == null) { c.skipped(name, "could not isolate an entity-free region"); return; }
        c.out.add(name + " setup: " + setup + " -- " + c.onMain(MailboxLifecycle::queuedRegionsReport));
        long t0 = System.currentTimeMillis();
        boolean delivered = waitFor(() -> o.outcomes() >= 1, PARKED_DELIVERY_MS);
        long latency = System.currentTimeMillis() - t0;
        Thread.sleep(1_000); // settle: no second outcome may appear
        String after = c.onMain(() -> {
            String inc = holder[0].nestworldCounterInconsistency();
            return MailboxLifecycle.queuedRegionsReport() + " | r" + holder[0].getId() + " inboundCounter="
                    + holder[0].nestworldRegionTickInboundCount() + " pendingCounter=" + holder[0].nestworldOutboundPendingCount()
                    + " mailbox=" + holder[0].nestworldMailboxSize() + " pending=" + holder[0].nestworldOutboundPendingSize()
                    + " consistent=" + (inc == null) + " runnable=" + holder[0].hasRunnableWork();
        });
        c.out.add(name + " NO trigger: " + (delivered ? "outcome after " + latency + " ms" : "NOT delivered within " + PARKED_DELIVERY_MS + " ms")
                + ", outcome " + o + " -- " + after);
        c.check(name + ".deliveredWithoutTrigger", delivered && latency <= PARKED_DELIVERY_MS,
                "work in an entity-free region delivered with no external trigger in " + latency + " ms (bound " + PARKED_DELIVERY_MS + " ms)");
        c.check(name + ".exactlyOnceCorrectOwner", o.outcomes() == 1 && o.applied.get() == 1 && o.appliedOnRetired.get() == 0
                        && o.appliedRegionId != holder[0].getId(), "outcome " + o + " (applied at the target's owner, not at the parked region)");
        c.check(name + ".queueAndCountersReturnToZero", after.contains("inboundCounter=0 pendingCounter=0 mailbox=0 pending=0 consistent=true runnable=false"),
                "parked region after delivery: " + after.substring(after.indexOf(" | ") + 3));
        c.onMain(() -> { holder[0].pinned = false; return null; });
        invariants(c, name);
    }

    /** Negative: an isolated region with NO entities, NO deferred work, NO inbound and NO outbound
     *  work must stay NOT dispatch-eligible, and must not be ticked (no accidental busy loop). */
    private static void parkedIdle(Ctx c) throws Exception {
        WorldRegion r = c.onMain(() -> farEmptyRegion(c));
        if (r == null || !r.getOwnedEntityIds().isEmpty()) { c.skipped("parked-idle", "could not isolate an entity-free region"); return; }
        c.onMain(() -> { r.pinned = true; return null; });
        Thread.sleep(500);
        long tick0 = r.getLocalTickCount();
        long dispatch0 = c.dr.getPool().nestworldDispatchesWithoutEntities();
        boolean runnable0 = c.onMain(r::hasRunnableWork);
        Thread.sleep(10_000);
        long ticked = r.getLocalTickCount() - tick0;
        long dispatchedEmpty = c.dr.getPool().nestworldDispatchesWithoutEntities() - dispatch0;
        boolean runnable1 = c.onMain(r::hasRunnableWork);
        c.out.add("parked-idle r" + r.getId() + ": runnable=" + runnable0 + "/" + runnable1 + " localTicks+" + ticked
                + " in 10000 ms; pool entity-round dispatches of entity-less regions (all regions) +" + dispatchedEmpty);
        c.check("parked-idle.notEligible", !runnable0 && !runnable1 && ticked == 0,
                "idle region: hasRunnableWork=" + runnable0 + "/" + runnable1 + ", local ticks +" + ticked + " (expected false/false, 0)");
        c.onMain(() -> { r.pinned = false; return null; });
    }

    /** Snapshot of the two free-running-only queues of a region plus its dispatch inputs. */
    private static String demotionState(WorldRegion r, java.util.concurrent.atomic.AtomicInteger ranS,
                                        java.util.concurrent.atomic.AtomicInteger ranF) throws Exception {
        java.lang.reflect.Field f = WorldRegion.class.getDeclaredField("pendingFreeRunningWork");
        f.setAccessible(true);
        Object pending = ((java.util.concurrent.atomic.AtomicReference<?>) f.get(r)).get();
        int owned = r.getOwnedEntityIds().size();
        boolean selfTicking = NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && r.isFreeRunning();
        RegionThread t = r.owningThread;
        int deferred = t == null ? -1 : t.getLastWorkDeferred();
        return "r" + r.getId() + "{freeRunning=" + r.isFreeRunning() + ",scheduledTicks=" + r.scheduledTicks.size()
                + ",pendingFreeRunningWork=" + (pending == null ? 0 : ((java.util.List<?>) pending).size())
                + ",ownedEntities=" + owned + ",deferredWork=" + deferred
                + ",dispatchEligible=" + (selfTicking || owned > 0 || deferred > 0)
                + ",localTick=" + r.getLocalTickCount() + "} executed{scheduled=" + ranS.get() + ",freeRunningWork=" + ranF.get() + "}";
    }

    /**
     * P3 + P4 (parked-demotion): work sits in the two FREE-RUNNING-only queues (scheduledTicks,
     * pendingFreeRunningWork) of a region at the moment it is demoted to barrier mode. The worker is
     * paused at the top of its loop iteration, so posting and demotion happen with no drain between.
     * Stage 1: no trigger. Stage 2: a real entity enters, so the region is dispatched in barrier mode
     * (does barrier runOneTick consume these queues?). Stage 3: re-promote to free-running.
     */
    private static void parkedDemotion(Ctx c) throws Exception {
        if (!NestworldTuning.FREE_RUNNING_REGIONS_ENABLED) {
            c.skipped("parked-demotion", "needs -Dnestworld.freeRunningRegions=true");
            return;
        }
        WorldRegion r = c.onMain(() -> farEmptyRegion(c));
        if (r == null || !r.getOwnedEntityIds().isEmpty()) { c.skipped("parked-demotion", "could not isolate an entity-free region"); return; }
        java.util.concurrent.atomic.AtomicInteger ranS = new java.util.concurrent.atomic.AtomicInteger(), ranF = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> threadS = new java.util.concurrent.atomic.AtomicReference<>(), threadF = new java.util.concurrent.atomic.AtomicReference<>();
        c.onMain(() -> { r.pinned = true; r.nestworldSetFreeRunning(true); return null; });
        Thread.sleep(1_000); // the worker is now in its free-running loop
        MailboxLifecycleTestHooks.armLoop(r.getId());
        boolean paused = MailboxLifecycleTestHooks.awaitLoopHit(10);
        long postedAt = System.currentTimeMillis();
        String atDemotion = c.onMain(() -> {
            // Real representation: production SCHEDULED_TICK work is always a Tagged BLOCK/FLUID entry
            // (RegionScheduledTicks.offerAll silently drops untagged runnables -- latent hazard, see audit).
            Runnable s1 = ScheduledTickHandoffMetrics.tagBlock(
                    () -> { ranS.incrementAndGet(); threadS.set(Thread.currentThread().getName()); },
                    new net.minecraft.core.BlockPos(r.getMinChunkX() * 16 + 8, 100, r.getMinChunkZ() * 16 + 8));
            Runnable f1 = () -> { ranF.incrementAndGet(); threadF.set(Thread.currentThread().getName()); };
            r.nestworldPostScheduledTicks(new java.util.ArrayList<>(java.util.List.of(s1)), c.server.getTickCount(), c.level.getGameTime());
            r.nestworldPostFreeRunningWork(new java.util.ArrayList<>(java.util.List.of(f1)), c.server.getTickCount(), c.level.getGameTime());
            r.nestworldSetFreeRunning(false); // demotion: barrier mode from here on
            return demotionState(r, ranS, ranF);
        });
        MailboxLifecycleTestHooks.releaseLoop();
        c.out.add("parked-demotion worker paused=" + paused + "; at demotion: " + atDemotion);

        // Stage 1: no trigger.
        Thread.sleep(PARKED_WINDOW_MS);
        String s1 = c.onMain(() -> demotionState(r, ranS, ranF));
        c.out.add("parked-demotion stage1 after " + (System.currentTimeMillis() - postedAt) + " ms, no trigger: " + s1);
        int s1S = ranS.get(), s1F = ranF.get();
        c.check("parked-demotion.P3.scheduledTicks.stage1", paused && s1S == 1,
                "scheduledTicks queued at demotion executed with NO trigger: " + s1S + " (1 = recovered by the existing demotion backlog drain)");
        c.check("parked-demotion.P4.pendingFreeRunningWork.stage1", paused && s1F == 0,
                "pendingFreeRunningWork queued at demotion executed with no trigger: " + s1F + " (0 = stalled)");

        // Stage 2: a real entity enters -> the region is dispatched in BARRIER mode.
        UUID entity = c.onMain(() -> {
            c.level.setChunkForced(3001, 3001, true);
            c.level.getChunk(3001, 3001);
            ArmorStand a = new ArmorStand(c.level, 3001 * 16 + 8.5, 120.0, 3001 * 16 + 8.5);
            a.setNoGravity(true);
            a.setInvulnerable(true);
            c.level.addFreshEntity(a);
            Api1A7DeterministicTests.assignSole(c.dr.getGrid().getAllRegions().toArray(new WorldRegion[0]), r, a.getUUID());
            return a.getUUID();
        });
        long tick0 = r.getLocalTickCount();
        Thread.sleep(PARKED_WINDOW_MS);
        long ticked = r.getLocalTickCount() - tick0;
        String s2 = c.onMain(() -> demotionState(r, ranS, ranF));
        c.out.add("parked-demotion stage2 (real entity, barrier-dispatched: +" + ticked + " local ticks in " + PARKED_WINDOW_MS + " ms): " + s2);
        c.check("parked-demotion.P4.stage2.dispatchedButNotConsumed", ticked > 0 && ranF.get() == 0,
                "region dispatched " + ticked + " barrier rounds; pendingFreeRunningWork executed " + ranF.get()
                        + " (0 = the barrier path does not consume this queue; a liveness-only fix cannot reach it)");

        // Stage 3: re-promote to free-running.
        long t3 = System.currentTimeMillis();
        c.onMain(() -> { r.nestworldSetFreeRunning(true); return null; });
        boolean ran = waitFor(() -> ranS.get() >= 1 && ranF.get() >= 1, 10_000);
        Thread.sleep(1_000);
        String s3 = c.onMain(() -> demotionState(r, ranS, ranF));
        c.out.add("parked-demotion stage3 (re-promoted): " + (ran ? "executed after " + (System.currentTimeMillis() - t3 - 1_000) + " ms" : "not executed within 10 s")
                + " on threads scheduled=" + threadS.get() + " freeRunningWork=" + threadF.get() + " -- " + s3);
        c.check("parked-demotion.stage3.exactlyOnceAfterRepromotion", ranS.get() == 1 && ranF.get() == 1,
                "scheduled=" + ranS.get() + " freeRunningWork=" + ranF.get());
        c.onMain(() -> {
            r.nestworldSetFreeRunning(false);
            var e = c.level.getEntity(entity);
            if (e != null) e.discard();
            c.level.setChunkForced(3001, 3001, false);
            r.pinned = false;
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Negative: explicit terminals, never silence
    // ------------------------------------------------------------------

    private static void notFound(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        String r = c.onMain(() -> {
            WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
            dest.nestworldPostMessage(mutate(c, dest, target, opId));
            var e = c.level.getEntity(target);
            if (e != null) e.discard();
            for (WorldRegion x : c.dr.getGrid().getAllRegions()) x.removeEntity(target); // gone AND unowned
            WorldRegion[] k = c.dr.getSplitManager().doSplit(dest, "core-mailbox-notfound");
            return k == null ? "NOT split" : "r" + dest.getId() + " retired";
        });
        if (r.equals("NOT split")) { c.skipped("notfound", "region could not be split"); return; }
        settle(o);
        c.check("notfound.explicitTerminal", o.applied.get() == 0 && o.terminals.get() == 1 && "TARGET_NOT_FOUND".equals(o.terminalKind),
                r + ", target discarded before re-resolution, outcome " + o);
        invariants(c, "notfound");
    }

    private static void redeliveryLimit(Ctx c) throws Exception {
        UUID target = spawnTarget(c);
        long opId = OPIDS.incrementAndGet();
        var o = MailboxLifecycleTestHooks.trackOp(opId);
        String r = c.onMain(() -> {
            WorldRegion dest = c.dr.getGrid().findOwningRegion(target);
            RegionMessage<?> m = mutate(c, dest, target, opId);
            for (int i = 0; i < NestworldTuning.MAILBOX_MAX_REDELIVERIES; i++) m = m.redeliveredTo(dest); // already at the cap
            dest.nestworldPostMessage(m);
            WorldRegion[] k = c.dr.getSplitManager().doSplit(dest, "core-mailbox-redeliverylimit");
            return k == null ? "NOT split" : "r" + dest.getId() + " retired";
        });
        if (r.equals("NOT split")) { c.skipped("redeliverylimit", "region could not be split"); return; }
        settle(o);
        c.check("redeliverylimit.explicitTerminal", o.applied.get() == 0 && o.terminals.get() == 1
                        && "REDELIVERY_LIMIT_DROPPED".equals(o.terminalKind),
                r + ", message already redelivered " + NestworldTuning.MAILBOX_MAX_REDELIVERIES + "x, outcome " + o);
        invariants(c, "redeliverylimit");
    }
}
