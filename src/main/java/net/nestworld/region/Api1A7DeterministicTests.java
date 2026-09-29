package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.nestworld.modapi.entity.SetVelocityIntentTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API-1A.7 TEST-ONLY (2026-09-29). Deterministic Phase B/C harness for the setVelocity
 * verification follow-up -- replaces the baseline pass's probabilistic RCON-timed attempts
 * (NW-002/NW-003 in docs/contracts/api-1a7-findings.md) with test-only barriers
 * ({@link EntityMutationTestHooks}) that control interleaving directly. NOT part of any public
 * or Core-internal API -- to be removed or promoted after the gate closes.
 *
 * <p>Per explicit instruction: this class must never apply a production semantic fix. It only
 * observes and reports.
 */
final class Api1A7DeterministicTests {
    private static final Logger LOG = LogManager.getLogger("NestWorld/DIAG");
    private static final AtomicLong OPIDS = new AtomicLong(1_000_000_000L); // disjoint range from MailboxAudit ids

    private Api1A7DeterministicTests() {}

    /** Ensures at least 2 registered regions exist in the overworld grid, splitting region 0
     *  if needed. Returns {A, B}. */
    private static WorldRegion[] ensureTwoRegions(NestworldRegionSystem sys) {
        NestworldDimensionRegion dr = sys.getDimensionRegion(sys.getOverworld());
        List<WorldRegion> existing = new ArrayList<>(dr.getGrid().getAllRegions());
        if (existing.size() >= 2) {
            return new WorldRegion[]{existing.get(0), existing.get(1)};
        }
        WorldRegion region0 = existing.get(0);
        WorldRegion[] children = dr.getSplitManager().doSplit(region0);
        if (children == null) {
            throw new IllegalStateException("could not split region 0 to get a second region");
        }
        children[0].pinned = true;
        children[1].pinned = true;
        return children;
    }

    private static Entity spawnTestEntity(ServerLevel level) {
        // API-1A.7 TEST-HARNESS FIX (not a production change): first run of NW-002-DET found the
        // spawned entity became unreachable via level.getEntity(uuid) within ~2s with no player
        // nearby and no force-load -- force-loading the spawn chunk rules out "chunk unloaded
        // under an untended test entity" as the cause before treating anything as a dispatcher
        // finding.
        level.setChunkForced(0, 0, true);
        ArmorStand stand = new ArmorStand(level, 0.5, 65.0, 0.5);
        stand.setPos(0.5, 65.0, 0.5);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        return stand;
    }

    /**
     * API-1A.7 TEST-HARNESS FIX (2026-09-29, this pass, NOT a production change): a live,
     * directly-demonstrated finding (see Finding API1A7-NW-PARKED-REGION in
     * docs/contracts/api-1a7-findings.md) showed RegionThreadPool.tickAllRegions() (:227)
     * excludes any region with zero owned entities AND zero deferred work from the per-tick
     * dispatch round entirely -- its RegionThread is left parked, with no bound, until some
     * UNRELATED event (a new entity arriving) happens to wake it. A migration/removal
     * scenario that leaves the "losing" region with zero entities right when its queued
     * EntityMutationDispatcher message needs to be drained hits this wall every time -- not
     * a dispatcher routing bug, a separate region-dispatch liveness gap this pass is
     * DELIBERATELY working around (per the hard "no production semantic fixes" rule) so the
     * ACTUAL dispatcher/reroute/ordering behavior can be observed uncontaminated by it. Fix:
     * keep one permanent, never-discarded "anchor" entity resident in EACH region for the
     * whole test session, so both region threads keep receiving normal per-tick dispatch
     * regardless of what the scenario's own test entity does.
     */
    private static void ensureAnchors(WorldRegion[] regions, ServerLevel level) {
        for (WorldRegion r : regions) {
            if (!r.getOwnedEntityIds().isEmpty()) continue;
            // Place anchors far apart so each unambiguously falls inside its own region's
            // bounding box regardless of exact split geometry -- region 0 is the negative-X
            // half, region 1 (or later regions) the positive-X half in this test world.
            double x = r.getId() == 0 ? -500.0 : 500.0 + (r.getId() * 32.0);
            ArmorStand anchor = new ArmorStand(level, x, 64.0, 0.5);
            anchor.setPos(x, 64.0, 0.5);
            anchor.setNoGravity(true);
            anchor.setInvulnerable(true);
            level.addFreshEntity(anchor);
            r.addEntity(anchor.getUUID());
        }
    }

    private static void assignSole(WorldRegion[] all, WorldRegion owner, UUID id) {
        for (WorldRegion r : all) r.removeEntity(id);
        owner.addEntity(id);
    }

    // ------------------------------------------------------------------
    // NW-002 (deterministic): migration A->B between owner-resolution and enqueue.
    // ------------------------------------------------------------------
    static List<String> runMigrationTest(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== NW-002-DET: deterministic migration/reroute ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0], b = regions[1];
        Entity entity = spawnTestEntity(level);
        UUID id = entity.getUUID();
        assignSole(regions, a, id);
        out.add("entity=" + id + " initialOwner=" + a.getId() + " migrationTarget=" + b.getId());

        long opId = OPIDS.incrementAndGet();
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armA();
        Vec3 requested = new Vec3(3.0, 0.0, 0.0);
        long intentVersion = SetVelocityIntentTracker.assignVersion(
                new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
        Thread dispatcher = new Thread(() ->
                EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(requested, intentVersion), opId),
                "API1A7-det-dispatcher");
        dispatcher.start();
        boolean hitA = EntityMutationTestHooks.awaitHitA(10);
        out.add("barrierA hit (owner resolved, pre-enqueue): " + hitA);
        // Force migration A -> B while dispatch() is paused right after resolving owner=A.
        assignSole(regions, b, id);
        out.add("forced ownership A(" + a.getId() + ") -> B(" + b.getId() + ") while paused at BARRIER A");
        EntityMutationTestHooks.releaseA();
        dispatcher.join(5000);
        EntityMutationTestHooks.ENABLED = false;

        // Give the async mailbox drain + reroute a few real ticks to complete.
        Thread.sleep(2000);
        Vec3 observed = entity.getDeltaMovement();
        out.add("observed entity.getDeltaMovement() after settle = " + observed);
        out.add("expected: mutation applied on region B, NOT on region A (A no longer owns it)");
        out.add("NOTE: see server log for '[API1A7] opId=" + opId + "' phase trace (SUBMIT/OWNER_RESOLVED/ENQUEUE/REROUTE/APPLY/TERMINAL) -- "
                + "this report cannot grep the log itself; correlate manually by opId=" + opId);
        entity.discard();
        return out;
    }

    // ------------------------------------------------------------------
    // NW-003 (deterministic): remove entity between enqueue and reacquire.
    // ------------------------------------------------------------------
    static List<String> runRemovedBeforeApplyTest(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== NW-003-DET: deterministic removed-before-apply ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0];
        Entity entity = spawnTestEntity(level);
        UUID id = entity.getUUID();
        assignSole(regions, a, id);
        out.add("entity=" + id + " owner=" + a.getId());

        long opId = OPIDS.incrementAndGet();
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armB();
        // Dispatch from the "main thread queue" path -- guarantees a real ENQUEUE (this is not
        // a RegionThread), so applyQueued runs later on region a's own thread, where barrierB
        // actually pauses it.
        long intentVersion = SetVelocityIntentTracker.assignVersion(
                new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
        EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(new Vec3(3.0, 0.0, 0.0), intentVersion), opId);
        boolean hitB = EntityMutationTestHooks.awaitHitB(10);
        out.add("barrierB hit (about to reacquire, before removal): " + hitB);
        entity.discard();
        out.add("entity discarded while paused at BARRIER B");
        EntityMutationTestHooks.releaseB();
        Thread.sleep(1500);
        EntityMutationTestHooks.ENABLED = false;
        out.add("expected: TARGET_NOT_FOUND terminal, no apply, no crash (check log for opId=" + opId + ")");
        return out;
    }

    // ------------------------------------------------------------------
    // Section 10 (deterministic): force reroute-limit exhaustion via repeated ping-pong.
    // ------------------------------------------------------------------
    static List<String> runRerouteLimitTest(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== SEC10-DET: deterministic reroute-limit exhaustion ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0], b = regions[1];
        Entity entity = spawnTestEntity(level);
        UUID id = entity.getUUID();
        assignSole(regions, a, id);
        int limit = NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS;
        out.add("entity=" + id + " initialOwner=" + a.getId() + " hopLimit=" + limit);

        long opId = OPIDS.incrementAndGet();
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armB();
        long intentVersion = SetVelocityIntentTracker.assignVersion(
                new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
        EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(new Vec3(3.0, 0.0, 0.0), intentVersion), opId);

        boolean current = false; // false=A owns at check time will be forced to B, true=B->forced to A
        int hits = 0;
        for (int hop = 0; hop <= limit; hop++) {
            boolean hit = EntityMutationTestHooks.awaitHitB(10);
            hits++;
            if (!hit) {
                out.add("TIMEOUT waiting for barrierB hit at loop iteration " + hop + " -- aborting loop early");
                break;
            }
            // Force a mismatch: assign to whichever region is NOT about to be checked as owner.
            WorldRegion forceTo = current ? a : b;
            assignSole(regions, forceTo, id);
            current = !current;
            out.add("hit #" + hop + ": forced ownership -> region " + forceTo.getId());
            EntityMutationTestHooks.releaseB();
            if (hop < limit) {
                EntityMutationTestHooks.armB(); // re-arm for the next hop's drain
            }
        }
        EntityMutationTestHooks.ENABLED = false;
        Thread.sleep(1500);
        out.add("total barrierB hits observed=" + hits + " (expected " + (limit + 1) + ": hops 0.." + limit + ")");
        out.add("expected final state: TERMINAL result=REROUTE_LIMIT_DROPPED at hop=" + limit
                + ", no apply, entity.getDeltaMovement() still (0,0,0)");
        out.add("observed entity.getDeltaMovement() = " + entity.getDeltaMovement());
        entity.discard();
        return out;
    }

    // ------------------------------------------------------------------
    // Section 11 (deterministic): ordering -- V1 delayed across a migration, V2 applies first.
    // ------------------------------------------------------------------
    static List<String> runOrderingTest(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== SEC11-DET: submission order vs. apply order across a migration ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0], b = regions[1];
        Entity entity = spawnTestEntity(level);
        UUID id = entity.getUUID();
        assignSole(regions, a, id);

        long opIdV1 = OPIDS.incrementAndGet();
        long opIdV2 = OPIDS.incrementAndGet();
        SetVelocityIntentTracker.TargetKey key = new SetVelocityIntentTracker.TargetKey(level.dimension(), id);
        // API-1A.8: versions assigned HERE, in real submission order (V1 before V2), matching
        // exactly what NestWorldEntities.setVelocity does at its own call site -- this is the
        // regression fixture for the API-1A.7 ordering-inversion finding, so the version
        // assignment must reflect the SAME interleaving that finding was built on, unchanged.
        long v1Version = SetVelocityIntentTracker.assignVersion(key);
        out.add("entity=" + id + " initialOwner=" + a.getId());
        out.add("submissionOrdinal 1 -> V1 (opId=" + opIdV1 + ", velocity=(1,0,0), intentVersion=" + v1Version + ")");

        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armA();
        Vec3 v1 = new Vec3(1.0, 0.0, 0.0);
        Thread v1Thread = new Thread(() ->
                EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(v1, v1Version), opIdV1),
                "API1A7-det-V1");
        v1Thread.start();
        boolean hitA = EntityMutationTestHooks.awaitHitA(10);
        out.add("V1 paused at BARRIER A (owner=" + a.getId() + " resolved, not yet enqueued): hit=" + hitA);

        // Migrate while V1 is paused.
        assignSole(regions, b, id);
        out.add("migrated entity A(" + a.getId() + ") -> B(" + b.getId() + ") while V1 paused");

        // Submit V2 now -- entity is owned by B, so V2 resolves owner=B directly.
        // API-1A.7 TEST-HARNESS FIX (this pass, NOT a production change): barrierA() is called
        // UNCONDITIONALLY inside dispatch() for every caller while EntityMutationTestHooks.
        // ENABLED is true, regardless of which entity/opId the call is for -- V2's own dispatch()
        // call was hitting the SAME armed barrier V1 is paused at, deadlocking THIS controller
        // thread inside its own synchronous dispatch(v2) call for the barrier's full 30s
        // hardcoded timeout (confirmed by log evidence: V1's and V2's ENQUEUE lines both landed
        // exactly 30.000s after their OWNER_RESOLVED lines). Root-caused, not guessed: the
        // barrier mechanism has no per-call scoping, only a single global on/off switch. Fix:
        // disable the switch around V2's call only -- V1's already-blocked await() call holds
        // its OWN local reference to the CountDownLatch captured when it entered the barrier, so
        // toggling ENABLED here cannot affect a wait already in progress, only NEW barrierA()
        // calls (i.e. exactly V2's).
        EntityMutationTestHooks.ENABLED = false;
        Vec3 v2 = new Vec3(9.0, 0.0, 0.0);
        long v2Version = SetVelocityIntentTracker.assignVersion(key);
        out.add("submissionOrdinal 2 -> V2 (opId=" + opIdV2 + ", velocity=(9,0,0), intentVersion=" + v2Version + ")");
        EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(v2, v2Version), opIdV2);
        EntityMutationTestHooks.ENABLED = true;
        Thread.sleep(500); // let V2 apply (should be fast: enqueue to B, B drains, applies)
        Vec3 afterV2 = entity.getDeltaMovement();
        out.add("observed velocity after V2 alone had a chance to apply: " + afterV2);

        // Now release V1 -- it will ENQUEUE to A (the owner it resolved before migration), A will
        // drain, find current owner=B, reroute. Pre-API-1A.8 this used to APPLY V1 last, silently
        // overwriting V2's newer state. Post-API-1A.8: V1's intentVersion (assigned BEFORE V2's,
        // real submission order) loses arbitration to V2's already-applied newer version at the
        // SetVelocityIntentTracker.tryAccept() check inside applyDirect -- V1 must be dropped
        // (STALE_INTENT_DROPPED), not applied, regardless of it reaching APPLY-phase routing.
        EntityMutationTestHooks.releaseA();
        v1Thread.join(5000);
        EntityMutationTestHooks.ENABLED = false;
        Thread.sleep(1500);
        Vec3 finalVelocity = entity.getDeltaMovement();
        out.add("observed FINAL velocity after V1 released and rerouted: " + finalVelocity);
        out.add("submission order: V1 (opId=" + opIdV1 + ", intentVersion=" + v1Version
                + ") then V2 (opId=" + opIdV2 + ", intentVersion=" + v2Version + ")");
        boolean looksLikeV2AppliedLast = Math.abs(finalVelocity.x - v2.x * 0.91) < 0.05; // drag factor per tick, rough
        boolean looksLikeV1AppliedLast = Math.abs(finalVelocity.x - v1.x * 0.91) < 0.05;
        // API-1A.8 REGRESSION CHECK: the state observed right after V2 applied (afterV2) and the
        // FINAL state after V1 is released should now be numerically consistent with EACH OTHER
        // (both decaying from the same V2 base), NOT with V1 ever having mutated state again --
        // this is the strongest same-run evidence that V1 was actually dropped, not just that it
        // happened to apply "before" V2 arithmetically.
        boolean finalStateConsistentWithV2Only = Math.abs(finalVelocity.x - afterV2.x * Math.pow(0.91, 3)) < 0.1;
        out.add("final velocity x=" + finalVelocity.x + " -- consistent with V2-applied-last=" + looksLikeV2AppliedLast
                + ", consistent with V1-applied-last=" + looksLikeV1AppliedLast
                + ", consistent with V1-never-reapplied-after-afterV2-reading=" + finalStateConsistentWithV2Only
                + " (note: vanilla drag/gravity + elapsed ticks between measurements make ALL THREE of these ROUGH "
                + "arithmetic indicators only -- the AUTHORITATIVE evidence is the opId-tagged TERMINAL-phase log line "
                + "for opId=" + opIdV1 + ": it MUST read 'result=STALE_INTENT_DROPPED', not 'result=APPLIED', for this "
                + "regression to be considered fixed. Check opId=" + opIdV2 + "'s TERMINAL line too -- it MUST read "
                + "'result=APPLIED')");
        out.add("MECHANISM NOTE (pre-API-1A.8 behavior, kept for historical/regression-fixture reference -- this is what "
                + "API-1A.7's SEC11-ORDERING finding originally observed, BEFORE the fix below existed): V2 took the "
                + "direct/fast enqueue-to-current-owner path while V1 had to complete a full reroute hop after its "
                + "delayed release, so V1's APPLY log line used to land AFTER V2's and used to silently overwrite V2's "
                + "state -- NOT a FIFO guarantee, a side effect of V1 being deliberately delayed longer.");
        out.add("API-1A.8 FIX NOTE: EntityMutationDispatcher.applyDirect's SetVelocity case now checks "
                + "SetVelocityIntentTracker.tryAccept(key, intentVersion) immediately before the mutation. V1 still "
                + "reaches its own APPLY-phase routing (reroute still happens, unchanged) but the tracker rejects its "
                + "intentVersion=" + v1Version + " because version=" + v2Version + " (V2's, strictly greater) already "
                + "won for this target -- so V1's own applyDirect call now returns false and the mutation never "
                + "executes. LATEST_INTENT_WINS, not FIFO: the STALE intent is dropped, not reordered.");
        entity.discard();
        return out;
    }

    // ------------------------------------------------------------------
    // Section 12: visibility / read-after-write matrix (NestWorld side only -- Forge-side rows
    // are the parallel Forge-track fork's responsibility).
    // ------------------------------------------------------------------
    static List<String> runVisibilityMatrix(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== SEC12-DET: visibility / read-after-write (NestWorld paths only) ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0], b = regions[1];

        // Row 1: same-owner/direct path (main thread calling dispatch() while entity is owned by
        // a region OTHER than any RegionThread the caller is on -- main thread always queues
        // per current code, so "direct" here means: unmanaged/uninitialised or owner==null case
        // is not realistically triggerable from a live region-sharded server; the TRUE same-
        // thread-direct case in production only happens when the CALLER is itself the owning
        // RegionThread, which a main-thread test command cannot be. Reporting this honestly
        // rather than faking a same-thread call.)
        out.add("row same-owner/direct: NOT independently measurable from a main-thread RCON test command "
                + "(the direct-apply branch requires the CALLER to already be the owning RegionThread; "
                + "an RCON command always executes on the main/command thread) -- would need a test hook "
                + "INSIDE a RegionThread's own tick to measure this row properly. Not attempted this pass.");

        // Row 2: mailbox path (cross-region, main thread caller).
        {
            Entity e = spawnTestEntity(level);
            UUID id = e.getUUID();
            assignSole(regions, a, id);
            Vec3 req = new Vec3(5.0, 0.0, 0.0);
            long opId = OPIDS.incrementAndGet();
            long ver = SetVelocityIntentTracker.assignVersion(new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
            long callStart = System.nanoTime();
            EntityMutationDispatcher.dispatch(e, new EntityMutationOp.SetVelocity(req, ver), opId);
            long callEnd = System.nanoTime();
            Vec3 immediateRead = e.getDeltaMovement();
            boolean immediateSeesRequested = immediateRead.x == req.x;
            out.add("row mailbox: dispatch() call itself took " + (callEnd - callStart) / 1000 + "us (returns immediately, "
                    + "does NOT block for apply -- return does not mean applied)");
            out.add("row mailbox: immediate read right after return sees requested value? " + immediateSeesRequested
                    + " (read=" + immediateRead + ", requested=" + req + ") -- expected FALSE, mailbox path is async");
            Thread.sleep(1500);
            Vec3 laterRead = e.getDeltaMovement();
            out.add("row mailbox: read ~1.5s later = " + laterRead + " (should show drag-decayed requested value by now)");
            e.discard();
        }

        // Row 3: reroute path.
        {
            Entity e = spawnTestEntity(level);
            UUID id = e.getUUID();
            assignSole(regions, a, id);
            long opId = OPIDS.incrementAndGet();
            EntityMutationTestHooks.disarmAll();
            EntityMutationTestHooks.ENABLED = true;
            EntityMutationTestHooks.armA();
            Vec3 req = new Vec3(7.0, 0.0, 0.0);
            long ver = SetVelocityIntentTracker.assignVersion(new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
            long callStart = System.nanoTime();
            Thread t = new Thread(() -> EntityMutationDispatcher.dispatch(e, new EntityMutationOp.SetVelocity(req, ver), opId));
            t.start();
            EntityMutationTestHooks.awaitHitA(10);
            assignSole(regions, b, id); // force reroute
            EntityMutationTestHooks.releaseA();
            long callEnd = System.nanoTime();
            t.join(5000);
            EntityMutationTestHooks.ENABLED = false;
            Vec3 immediateRead = e.getDeltaMovement();
            out.add("row reroute: dispatch() call (incl. barrier stall) took " + (callEnd - callStart) / 1000
                    + "us; immediate read after join = " + immediateRead + " (requested=" + req
                    + ") -- expected to NOT show requested value yet, reroute+apply happens on region B's own later tick");
            Thread.sleep(1500);
            out.add("row reroute: read ~1.5s later = " + e.getDeltaMovement());
            e.discard();
        }
        return out;
    }

    /**
     * API-1A.7 follow-up (2026-09-29, this pass): the run above correctly flagged the
     * same-owner/direct visibility row as unmeasurable from a main-thread RCON command. This
     * measures it for real by reusing {@link RegionThread#requestWork}, an ALREADY-EXISTING
     * production mechanism (used elsewhere for scheduled-block-tick batches) to run the
     * dispatch+read INSIDE the owning region's own thread -- satisfying
     * {@code Thread.currentThread() instanceof RegionThread && self == owner} for real, not
     * simulated. No new production mechanism added; this is a new caller of an existing one.
     */
    static List<String> runSameOwnerVisibilityTest(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== SEC12-DET follow-up: same-owner/direct visibility (measured for real) ===");
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = ensureTwoRegions(sys);
        ensureAnchors(regions, level);
        WorldRegion a = regions[0];
        Entity e = spawnTestEntity(level);
        UUID id = e.getUUID();
        assignSole(regions, a, id);
        Vec3 req = new Vec3(3.0, 0.0, 0.0);
        long ver = SetVelocityIntentTracker.assignVersion(new SetVelocityIntentTracker.TargetKey(level.dimension(), id));
        final Vec3[] immediateRead = new Vec3[1];
        final long[] tookNanos = new long[1];
        final String[] observedThread = new String[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable work = () -> {
            observedThread[0] = Thread.currentThread().getName();
            long t0 = System.nanoTime();
            EntityMutationDispatcher.dispatch(e, new EntityMutationOp.SetVelocity(req, ver), OPIDS.incrementAndGet());
            tookNanos[0] = System.nanoTime() - t0;
            immediateRead[0] = e.getDeltaMovement(); // read on the SAME region thread, same call stack
        };
        a.owningThread.requestWork(java.util.List.of(work), done);
        boolean completed = done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        out.add("row same-owner/direct: work dispatched via RegionThread.requestWork, completed=" + completed
                + " observedThread=" + observedThread[0] + " (expected a real NestWorld-Region-N thread name, "
                + "confirming Thread.currentThread() instanceof RegionThread was genuinely true at dispatch time)");
        boolean matches = immediateRead[0] != null && immediateRead[0].x == req.x;
        out.add("row same-owner/direct: dispatch() call took " + tookNanos[0] / 1000
                + "us; immediate read on the SAME thread, same call stack, right after dispatch() returned = "
                + immediateRead[0] + " (requested=" + req + ") matchesRequested=" + matches
                + " -- this is the ONE path where the direct-apply branch actually fires synchronously");
        e.discard();
        return out;
    }

    // ------------------------------------------------------------------
    // Section 18: raw control-flow inventory (observations only, no naming/classification).
    // ------------------------------------------------------------------
    static List<String> controlFlowInventory() {
        List<String> out = new ArrayList<>();
        out.add("=== SEC18: raw control-flow inventory (NestWorld peer, observations only) ===");
        out.add("condition=target not found/removed at applyQueued | code path=EntityMutationDispatcher.applyQueued "
                + "target==null||isRemoved() branch | caller knows synchronously? NO (dispatch() already returned before "
                + "this runs, on a different thread for the queued case) | request accepted at submission? YES (enqueue "
                + "always succeeds if not backpressure-dropped) | dispatcher itself later knows outcome? Only via a log "
                + "line (no state retained) | observable today? ONLY via log (added this pass); no side effect, no return value");
        out.add("condition=hop limit reached | code path=applyQueued's rerouteHops>=LIMIT branch | caller knows "
                + "synchronously? NO | request accepted at submission? YES | dispatcher later knows? Only via WARN log "
                + "line (pre-existing, this pass's phase-log adds a second TERMINAL line) | observable today? log only");
        out.add("condition=successful apply, direct (same-thread or unmanaged/owner-null) | code path=dispatch()'s "
                + "applyDirect() calls | caller knows synchronously? YES for this ONE case -- dispatch() returns after "
                + "applyDirect() has already run, on the CALLING thread | request accepted? n/a (never queued) | "
                + "dispatcher later knows? n/a, already done | observable today? the mutation's own side effect is "
                + "immediately visible to the caller in this case only");
        out.add("condition=successful apply, queued (cross-region, no reroute) | code path=applyQueued's final "
                + "applyDirect() call | caller knows synchronously? NO -- dispatch() already returned before this runs "
                + "| request accepted? YES at enqueue | dispatcher later knows? only via log | observable today? log "
                + "only; the CALLER has no way to learn this happened, only a third party reading logs/re-querying "
                + "entity state later would see it");
        out.add("condition=successful apply, after 1+ reroutes | same as queued case above, plus an intermediate "
                + "REROUTE log line per hop | caller knows synchronously? NO | observable today? log only");
        out.add("condition=backpressure fallback (main-thread caller, mailbox depth >= threshold) | code path="
                + "dispatch()'s owner.nestworldMailboxDepthFast() >= MAILBOX_BACKPRESSURE_THRESHOLD branch -> applyDirect "
                + "| caller knows synchronously? YES (same as direct-apply case, dispatch() returns after it ran) | "
                + "observable today? immediately visible to caller, same as any direct-apply case -- this is a real, "
                + "existing case where the PUBLIC caller DOES get synchronous knowledge, worth noting for future "
                + "submission-semantics design: today's behavior is inconsistent (sometimes sync, sometimes not) "
                + "depending on an internal backpressure threshold the caller cannot see or predict");
        out.add("GENERAL OBSERVATION: today, whether a caller gets synchronous knowledge of the outcome is an "
                + "ACCIDENTAL side effect of which internal branch fires (same-thread vs main-thread-under-backpressure "
                + "vs queued), not a designed submission-vs-application distinction. No outcome is EVER surfaced back "
                + "to the caller via return value/exception/callback in any branch -- the caller's only signal, when "
                + "one exists at all, is the mutation's own observable side effect happening to be visible before the "
                + "call returns.");
        return out;
    }
}
