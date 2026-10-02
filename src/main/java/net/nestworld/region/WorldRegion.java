package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Represents a rectangular chunk-range of the world handled by one RegionThread.
 * The StampedLock protects cross-region chunk reads: any thread may hold a read stamp,
 * but only the owning RegionThread acquires write stamps during ticking.
 */
public class WorldRegion {

    private final int id;
    /** Stage 1 (region-sharding for Nether/End plan): the dimension this region belongs to,
     *  copied from its owning {@link WorldGrid} at construction. Region IDs are per-grid
     *  (each {@code WorldGrid} restarts its counter at 0), so once a second dimension is
     *  managed, "region #0" can legitimately exist in more than one dimension at once — this
     *  field is what lets callers holding only a {@code WorldRegion} (no separate level
     *  reference in scope, e.g. {@link EntityOwnershipRecheck}) disambiguate. */
    private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX; // inclusive
    private final int maxChunkZ; // inclusive

    private final StampedLock chunkLock = new StampedLock();

    // Entity UUIDs whose ticking is owned by this region's thread
    private final Set<UUID> ownedEntityIds = ConcurrentHashMap.newKeySet();

    // Region-Owned Chunk Scheduler, Phase 2 (docs/REGION_CHUNK_SCHEDULER_SPEC.md) -- SHADOW MODE
    // ONLY: observes the same chunk requests DistanceManager already sees, does no real work.
    // See net.nestworld.chunk.ChunkSchedulerShadow for the deferred observation hook.
    private final net.nestworld.chunk.RegionChunkScheduler chunkScheduler = new net.nestworld.chunk.RegionChunkScheduler();

    public net.nestworld.chunk.RegionChunkScheduler getChunkScheduler() { return this.chunkScheduler; }

    // NestWorld: 2026-08-30 per-region entity-tick fairness diagnostics (point-hotspot
    // investigation) -- lives on the region so it naturally resets on split/merge (a fresh
    // child region starts fresh tracking, no migration attempted, same convention as
    // SplitMergeEventLog). Only ever touched by this region's own owning thread.
    public final EntityFairnessMetrics fairness = new EntityFairnessMetrics();

    // Rolling TPS window: last 100 tick durations in nanoseconds
    private final long[] tickDurationsNs = new long[100];
    private int tickIndex = 0;
    private final AtomicLong windowSumNs = new AtomicLong(0);

    // NestWorld: Stage 5 tick-scheduler architecture, Part 1 (docs/LOCAL_TICK_STAGE4.md,
    // "Stage 5 tick-scheduler architecture — DECIDED"). Distinct from server.tickCount —
    // once regions go free-running (Part 5, not yet implemented), this becomes each
    // region's own monotonic tick counter, no longer advancing in lockstep with the
    // shared global tick. Under TODAY's still-barrier-synchronized model this simply
    // increments once per region tick round — a region's own count stays equal to
    // server.tickCount in practice today (additive-only, no behavior change), but
    // established now as ITS OWN counter so later parts don't need to touch every
    // call site that wants "which local tick is this region on." volatile: only the
    // owning RegionThread ever increments it (holds the write stamp for the whole
    // round), but diagnostics/future cross-region logic may read it from other threads.
    private volatile long localTickCount = 0;

    /** Called once per region tick round by the owning RegionThread only. */
    public void nestworldAdvanceLocalTick() { localTickCount++; }

    /** Safe to read from any thread (volatile) — this region's own monotonic tick counter. */
    public long getLocalTickCount() { return localTickCount; }

    // -----------------------------------------------------------------------
    // Step 3 (docs/LOCAL_TICK_STAGE4.md, "Step 3 — Single Free-Running Region"):
    // free-running mode. Off by default everywhere; toggled per-region only via
    // /nestworld freerun, and only reachable at all when NESTWORLD_FREE_RUNNING_REGIONS
    // is set — see NestworldTuning.FREE_RUNNING_REGIONS_ENABLED.
    // -----------------------------------------------------------------------

    private volatile boolean freeRunning = false;

    public boolean isFreeRunning() { return freeRunning; }

    /**
     * Step 5 (docs/LOCAL_TICK_STAGE4.md, "Step 5 — N free-running regions"): true only
     * when the automatic promotion policy (RegionSplitManager.evaluateAutoFreeRunning)
     * itself turned free-running on for this region — never set by a manual
     * {@code /nestworld freerun} toggle. Lets automatic demotion touch only what it
     * itself promoted, so it can never fight or silently undo an operator's explicit
     * choice. Guarded by RegionSplitManager's own single-threaded main-thread
     * evaluation, same as {@link #splitPressureChecks}/{@link #mergePressureChecks}.
     */
    volatile boolean autoFreeRunning = false;

    /** Consecutive once-per-second evaluations this region has qualified for automatic
     *  free-running promotion/demotion — see {@link #splitPressureChecks} for the same
     *  hysteresis idiom applied to split/merge. */
    int freeRunPromoteChecks = 0;
    int freeRunDemoteChecks = 0;

    /**
     * Only called from the main thread via the /nestworld freerun command. Wakes the
     * owning thread if it's currently blocked in its old barrier-dispatch wait — found
     * necessary live during Step 3's first test: once a region is free-running, the
     * pool stops dispatching to it entirely, so nothing else would ever wake a thread
     * already parked from a PRIOR barrier-dispatched iteration.
     */
    public void nestworldSetFreeRunning(boolean value) {
        freeRunning = value;
        RegionThread thread = owningThread;
        if (thread != null) thread.nestworldWakeForFreeRunningToggle();
    }

    /**
     * Queue-based work-round handoff for a free-running region (design point 2):
     * the main thread posts this region's due-scheduled-tick bucket here INSTEAD of
     * the latch-blocking RegionThread.requestWork() dispatch every other region still
     * uses, and does not wait for it. The region's own free-running loop drains this
     * non-blockingly at the start of each of its own local ticks. A `set()` overwriting
     * a not-yet-drained previous batch is a deliberate "drop if backlogged" policy —
     * same one this codebase already applies elsewhere (RegionThreadPool
     * .runWorkRoundDropIfBacklogged, used for block-entity ticks) — Gemini's review of
     * this design flagged this as a real behavioral-fidelity tradeoff (a severely
     * backlogged free-running region SKIPS old scheduled ticks rather than delaying
     * them) worth observing/documenting during Step 3's own validation, not a
     * correctness bug.
     */
    private final java.util.concurrent.atomic.AtomicReference<java.util.List<Runnable>> pendingFreeRunningWork =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 2026-08-31: instrumentation for the handoff below AND for {@link #scheduledTicks}
     *  (see {@link ScheduledTickHandoffMetrics}) -- originally added to measure the "latest
     *  batch wins" policy's loss rate; now also records the lossless path's produced/consumed
     *  counts for the same regression proof going forward. */
    public final ScheduledTickHandoffMetrics scheduledTickHandoff = new ScheduledTickHandoffMetrics();

    /** Phase 2.1 (docs/SCHEDULED_TICK_COMPATIBILITY_CONTRACT.md) -- lossless, append-only queue
     *  for BLOCK/FLUID scheduled ticks specifically. Replaces {@link #pendingFreeRunningWork} for
     *  {@link RegionPhase#SCHEDULED_TICK} only; that field stays in use, unchanged, for
     *  {@link RegionPhase#RANDOM_TICK}/{@link RegionPhase#BLOCK_EVENT}. */
    public final RegionScheduledTicks scheduledTicks = new RegionScheduledTicks();

    /** Called from the main thread (RegionThreadPool). */
    public void nestworldPostFreeRunningWork(java.util.List<Runnable> work, long producerTick, long gameTime) {
        java.util.List<Runnable> overwritten = pendingFreeRunningWork.getAndSet(work);
        scheduledTickHandoff.recordPost(work, overwritten, producerTick, id, gameTime);
    }

    /** Phase 2.1: lossless counterpart of {@link #nestworldPostFreeRunningWork} for
     *  {@link RegionPhase#SCHEDULED_TICK} only. Called from the main thread. Reuses
     *  {@link ScheduledTickHandoffMetrics#recordPost} with {@code overwritten=null} -- nothing
     *  is ever discarded on this path, so the existing produced/batches-posted counters keep
     *  working unchanged and OVERWRITTEN stays structurally 0 for regression verification. */
    public void nestworldPostScheduledTicks(java.util.List<Runnable> work, long producerTick, long gameTime) {
        scheduledTicks.offerAll(work);
        scheduledTickHandoff.recordPost(work, null, producerTick, id, gameTime);
        // Diagnostic-only (2026-09-03, "PROMOTE transition" JFR ТЗ -- see project memory): POST
        // stage. Gated FIRST behind PromoteDiagnosticWindow (this runs every real tick for every
        // free-running region -- per the project owner's explicit requirement, must not become a
        // per-tick global cost) -- one volatile read outside the window, no event construction.
        if (net.nestworld.region.jfr.PromoteDiagnosticWindow.isOpen()) {
            int nestworldQueueDepth = scheduledTicks.size();
            Thread nestworldT = Thread.currentThread();
            // JFR attempt kept in place (see NwjfrLog's class javadoc) -- not the sole path.
            net.nestworld.region.jfr.ScheduledTickLifecycleEvent nestworldEvt =
                    new net.nestworld.region.jfr.ScheduledTickLifecycleEvent();
            if (nestworldEvt.shouldCommit()) {
                nestworldEvt.regionId = id;
                nestworldEvt.eventType = "POST";
                nestworldEvt.queueDepth = nestworldQueueDepth;
                nestworldEvt.threadName = nestworldT.getName();
                nestworldEvt.threadId = nestworldT.getId();
                nestworldEvt.commit();
            }
            net.nestworld.region.jfr.NwjfrLog.scheduledTick("POST", id, nestworldQueueDepth,
                    null, null, nestworldT.getName(), nestworldT.getId());
        }
    }

    /** Phase 2.1: lossless counterpart of {@link #nestworldPollFreeRunningWork}. Called from
     *  this region's own thread only; non-blocking; drains everything currently pending. */
    public java.util.List<Runnable> nestworldPollScheduledTicks() {
        // Diagnostic-only (2026-09-03, "PROMOTE transition" JFR ТЗ -- see project memory): POLL
        // stage. Gated FIRST behind PromoteDiagnosticWindow -- this is called every free-running
        // tick iteration regardless of whether there's anything to drain, so per the project
        // owner's explicit requirement it must not run unconditionally on every scheduled tick
        // globally. Depth captured BEFORE the drain (what's about to move to execution) --
        // reading size() here, before drainAll(), does not change drainAll()'s own behavior.
        boolean nestworldDiag = net.nestworld.region.jfr.PromoteDiagnosticWindow.isOpen();
        int nestworldDepthBefore = nestworldDiag ? scheduledTicks.size() : 0;

        java.util.List<Runnable> work = scheduledTicks.drainAll();
        if (!work.isEmpty()) {
            scheduledTickHandoff.recordConsumed(work, localTickCount);
        }

        if (nestworldDiag && !work.isEmpty()) {
            Thread nestworldT = Thread.currentThread();
            // JFR attempt kept in place (see NwjfrLog's class javadoc) -- not the sole path.
            net.nestworld.region.jfr.ScheduledTickLifecycleEvent nestworldEvt =
                    new net.nestworld.region.jfr.ScheduledTickLifecycleEvent();
            if (nestworldEvt.shouldCommit()) {
                nestworldEvt.regionId = id;
                nestworldEvt.eventType = "POLL";
                nestworldEvt.queueDepth = nestworldDepthBefore;
                nestworldEvt.threadName = nestworldT.getName();
                nestworldEvt.threadId = nestworldT.getId();
                nestworldEvt.commit();
            }
            net.nestworld.region.jfr.NwjfrLog.scheduledTick("POLL", id, nestworldDepthBefore,
                    null, null, nestworldT.getName(), nestworldT.getId());
        }
        return work;
    }

    /** Called from this region's own thread only; non-blocking. */
    public java.util.List<Runnable> nestworldPollFreeRunningWork() {
        java.util.List<Runnable> work = pendingFreeRunningWork.getAndSet(null);
        if (work != null && !work.isEmpty()) {
            scheduledTickHandoff.recordConsumed(work, localTickCount);
        }
        return work;
    }

    /**
     * Save-specific rendezvous (design point 3): world-save is safe today only because
     * every region thread is parked behind the tick barrier when saveEverything() runs
     * — a free-running region isn't. Rather than a general entity-NBT-snapshot
     * mechanism, this is a narrow, infrequent (every 6000 ticks) synchronous pause:
     * the main thread requests it, the free-running region's own loop checks it at the
     * one genuinely safe point (between its own local ticks — never mid-tick), pauses
     * there, and the main thread proceeds with the save only once every requested
     * region has confirmed it's paused.
     */
    private volatile java.util.concurrent.CountDownLatch saveRendezvousPaused;
    private volatile java.util.concurrent.CountDownLatch saveRendezvousResume;

    /** Called from the main thread. Returns the latch to await (region confirms pause). */
    public java.util.concurrent.CountDownLatch nestworldRequestSaveRendezvous() {
        java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        saveRendezvousResume = new java.util.concurrent.CountDownLatch(1);
        saveRendezvousPaused = paused;
        return paused;
    }

    /** Called from the main thread once the save is complete, to release the region. */
    public void nestworldReleaseSaveRendezvous() {
        java.util.concurrent.CountDownLatch resume = saveRendezvousResume;
        if (resume != null) resume.countDown();
    }

    /** Fix for "#3" (2026-09-03, persistence-contract gap -- see project memory): cheap,
     *  side-effect-free peek, true if a save rendezvous has been requested but not yet
     *  honored. Lets {@link RegionThread#runFreeRunningTick()} fully drain {@link
     *  #scheduledTicks} (and its own carried-over budget remainder) to empty BEFORE
     *  actually pausing -- otherwise a lossless one-shot scheduled tick already removed
     *  from vanilla's own persistence structures could sit here, invisible to save(),
     *  across the pause. {@link #nestworldCheckSaveRendezvous()} still owns the actual
     *  consume+block. */
    public boolean nestworldSaveRendezvousPending() {
        return saveRendezvousPaused != null;
    }

    /**
     * Called from this region's own free-running loop, ONLY between local ticks (never
     * mid-tick — this is the safe point Gemini's review specifically flagged as the
     * critical detail). If a rendezvous is pending, blocks here until the main thread
     * releases it. Returns true if it paused (caller should skip the rest of this loop
     * iteration and re-check on the next one), false if there was nothing to do.
     */
    public boolean nestworldCheckSaveRendezvous() {
        java.util.concurrent.CountDownLatch paused = saveRendezvousPaused;
        if (paused == null) return false;
        saveRendezvousPaused = null;
        java.util.concurrent.CountDownLatch resume = saveRendezvousResume;
        paused.countDown();
        if (resume != null) {
            try { resume.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return true;
    }

    // Counters for split/merge hysteresis (guarded by RegionSplitManager's lock)
    int splitPressureChecks = 0;
    int mergePressureChecks = 0;

    volatile RegionThread owningThread;

    /** Pinned regions (manual /nestworld split) are exempt from automatic merge. */
    public volatile boolean pinned = false;

    // NestWorld: Folia-style Stage 1 "Region Mailbox" pilot (see project memory
    // folia-actor-model-staged-plan.md). Published once per tick by BoundaryManager.
    // syncGhostZones() (main thread, called right after the region barrier).
    // Free-running regions do not join that barrier; BoundaryManager holds their
    // chunk read lock while collecting and serializing the live block entities.
    // Keyed by BlockPos.asLong() -> a full NBT snapshot (BlockEntity.saveWithFullMetadata())
    // of every block entity in this region's chunks within BoundaryManager.GHOST_DEPTH
    // of a border. Readers on OTHER region threads reconstruct a DETACHED copy via
    // BlockEntity.loadStatic(pos, state, tag) — never touch the live, owner-mutated
    // instance. Replaces the old "ghost zone" live unsynchronized chunk.getBlockEntity()
    // pass-through, which despite this class's javadoc promising "1-tick-stale" data was
    // actually a genuine, unprotected data race on the live BlockEntity's mutable fields
    // (unlike PalettedContainer's own getBlockState() reads, which vanilla already makes
    // safe via a lock-free volatile-snapshot design — verified by reading PalettedContainer
    // directly, not assumed; see the class's own nestworldContainerLock comment).
    // AtomicReference gives safe publication (JMM) for the whole map as one unit — readers
    // never see a partially-populated snapshot.
    private final java.util.concurrent.atomic.AtomicReference<java.util.Map<Long, net.minecraft.nbt.CompoundTag>>
            nestworldGhostBeSnapshot = new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());

    /** Called only from BoundaryManager.syncGhostZones() (main thread, post-barrier). */
    void nestworldPublishGhostSnapshot(java.util.Map<Long, net.minecraft.nbt.CompoundTag> snapshot) {
        this.nestworldGhostBeSnapshot.set(snapshot);
    }

    /** Safe to call from any region thread — lock-free read of the last-published snapshot. */
    public java.util.Map<Long, net.minecraft.nbt.CompoundTag> nestworldGetGhostSnapshot() {
        return this.nestworldGhostBeSnapshot.get();
    }

    /**
     * Stage 5 "Entity Safety Layer" (docs/LOCAL_TICK_STAGE4.md): Tier 1 for entities,
     * same shape as {@link #nestworldGhostBeSnapshot} above but for live {@code Entity}
     * fields instead of BlockEntity NBT. {@code BoundaryEntityTransfer}'s main-thread
     * scan used to read {@code entity.getX()/xo/yo/zo} etc. directly, relying on the
     * barrier's "no region thread is concurrently moving anything" guarantee — a
     * guarantee that stops holding once a region goes free-running. Published once per
     * tick by THIS region's own thread ({@code RegionThread.publishEntitySnapshot()},
     * called after {@code tickEntities()} completes, so it reflects that tick's final,
     * settled positions), keyed by entity UUID. Readers on any other thread get a
     * detached copy, up to one cycle stale — same tolerance the ghost-zone snapshot
     * already accepts.
     */
    public record EntitySnapshot(double x, double y, double z, double xo, double yo, double zo, boolean removed) {}

    private final java.util.concurrent.atomic.AtomicReference<java.util.Map<java.util.UUID, EntitySnapshot>>
            nestworldEntitySnapshot = new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());

    /** Called only by THIS region's own RegionThread, at the end of its own tick. */
    void nestworldPublishEntitySnapshot(java.util.Map<java.util.UUID, EntitySnapshot> snapshot) {
        this.nestworldEntitySnapshot.set(snapshot);
    }

    /** Safe to call from any thread — lock-free read of the last-published snapshot. */
    public java.util.Map<java.util.UUID, EntitySnapshot> nestworldGetEntitySnapshot() {
        return this.nestworldEntitySnapshot.get();
    }

    // NestWorld: Stage 2/3 (see docs/LOCAL_TICK_STAGE4.md) — inbound cross-region message
    // queue, now shared by more than one message type (ENTITY_TRANSFER, EXPLOSION_APPLY —
    // see RegionMessage.Type) drained at DIFFERENT barrier-safe points in the tick pipeline
    // (entity transfer before the region-tick round, explosion batches after it — see
    // NestworldRegionSystem.tickAllRegions()'s phase comments). Posted to by any thread (a
    // message's destination may differ from the poster's own region/thread).
    //
    // nestworldDrainMailbox(Type) is TYPE-FILTERED, not drain-everything: an earlier version
    // drained the whole queue and filtered afterward, which would have SILENTLY DROPPED any
    // message of a type that particular call site didn't handle, if it happened to still be
    // queued when a DIFFERENT type's drain ran. Each consumer only removes messages of its
    // own type, leaving the rest for their own drain site — safe because both existing drain
    // sites only ever run while every region thread is parked (no concurrent posting).
    private final java.util.Queue<RegionMessage<?>> nestworldMailbox = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Diagnostic-only, temporary: identity probe for the #31.4 step2 investigation -- lets a
     *  poster and a drainer compare whether they're touching the SAME {@code WorldRegion}/
     *  mailbox object, not just the same numeric id (a stale grid snapshot could hand out a
     *  different object claiming the same id). */
    public int nestworldMailboxIdentity() {
        return System.identityHashCode(nestworldMailbox);
    }

    /** Posts a GENUINELY NEW message to this region's inbound mailbox. Safe from any
     *  thread. Counted by {@link MailboxAudit} as one "sent" event — for re-queuing an
     *  EXISTING message (e.g. Part 3's cascade-guard lock-timeout retry), use {@link
     *  #nestworldRequeueMessage} instead, or the same real send gets double-counted. */
    public void nestworldPostMessage(RegionMessage<?> message) {
        MailboxAudit.recordSent(message.auditId(), message.sourceRegion(), this, message.messageType());
        nestworldEnqueueOrOrphan(message);
    }

    // -----------------------------------------------------------------------
    // CORE-MAILBOX-MERGE-001 (docs/contracts/CORE-MAILBOX-MERGE-001-fix-spec.md): mailbox
    // destination lifecycle. Conceptual states:
    //   ACTIVE   -- an enqueue may be accepted
    //   RETIRED  -- no new enqueue is accepted; the retirement drain may still be running
    //   DRAINED  -- the old worker is excluded/stopped and both transport queues were handed off
    // nestworldRetired is a one-way volatile PUBLICATION flag only -- it does not make the
    // mailbox lifecycle atomic by itself. Correctness comes from the combination of: this
    // publication (written AFTER the new topology, so whoever sees RETIRED can re-resolve to a
    // successor), CLQ add/remove atomicity (exactly-once ownership of a message), the single
    // one-way transition, old-worker exclusion (region write lock + the worker's post-lock
    // RETIRED check in RegionThread.runOneTick), the retirement drain, and orphan re-resolution
    // (MailboxRetargeting). Removing any one of these reopens a loss path.
    // -----------------------------------------------------------------------

    private volatile boolean nestworldRetired = false;
    private volatile boolean nestworldDrained = false;

    public boolean nestworldIsRetired() { return nestworldRetired; }
    public boolean nestworldIsDrained() { return nestworldDrained; }

    /**
     * C4 safe enqueue -- the ONLY path that adds to {@link #nestworldMailbox}.
     * <pre>
     * if RETIRED                       -> not accepted; orphaned for re-resolution
     * add; depth++
     * if !RETIRED                      -> ACCEPTED (a later retirement drain owns it if needed)
     * if remove(msg) succeeds          -> not accepted; orphaned for re-resolution
     * else                             -> ACCEPTED: after RETIRED the only possible takers are the
     *                                     retirement drain (which orphans it) or a consumer that ran
     *                                     BEFORE retirement (C3), which yields APPLY/TERMINAL
     * </pre>
     * A message can therefore never be left in, or vanish from, a retired region's queue.
     */
    private void nestworldEnqueueOrOrphan(RegionMessage<?> message) {
        if (nestworldRetired) {
            MailboxLifecycle.orphan(this, message, MailboxLifecycle.OrphanReason.REJECTED_RETIRED);
            return;
        }
        nestworldMailbox.add(message);
        nestworldMailboxDepth.incrementAndGet();
        MailboxLifecycleTestHooks.afterAdd(message);
        if (!nestworldRetired) return;
        if (nestworldMailbox.remove(message)) {
            nestworldMailboxDepth.decrementAndGet();
            MailboxLifecycle.orphan(this, message, MailboxLifecycle.OrphanReason.REJECTED_RETIRED);
            return;
        }
        MailboxLifecycle.acceptedRemoveFailed.incrementAndGet();
    }

    /**
     * C2 step 2 -- RETIREMENT PUBLICATION. Caller (RegionSplitManager, main thread) holds this
     * region's write lock and has ALREADY published the successor topology (tree.split/merge),
     * so any thread that observes RETIRED also observes a topology that re-resolves to the
     * successor owner. One-way: never cleared.
     */
    void nestworldMarkRetired() {
        if (nestworldRetired) return;
        nestworldRetired = true;
        MailboxLifecycle.noteRetired(this);
    }

    /**
     * C2 step 4 -- RETIREMENT DRAIN. Caller holds this region's write lock (so this region's own
     * worker cannot consume concurrently, C3) and has already called {@link #nestworldMarkRetired}
     * (so any producer that adds after this drain's poll sees RETIRED on its re-check and removes
     * its own message, C4). Hands EVERY inbound message (all types) and EVERY sender-side pending
     * entry to the dimension's orphan queue. Marks DRAINED.
     */
    void nestworldDrainOnRetire() {
        if (!nestworldRetired) throw new IllegalStateException("drain before RETIRED publication: region " + id);
        RegionMessage<?> m;
        while ((m = nestworldMailbox.poll()) != null) {
            nestworldMailboxDepth.decrementAndGet();
            MailboxLifecycle.orphan(this, m, MailboxLifecycle.OrphanReason.DRAINED_ON_RETIRE);
        }
        NestworldPendingSend p;
        while ((p = nestworldOutboundPending.poll()) != null) {
            MailboxLifecycle.orphan(this, p.message(), MailboxLifecycle.OrphanReason.PENDING_SENDER_RETIRED);
        }
        nestworldDrained = true;
        MailboxLifecycle.drainedRegions.incrementAndGet();
    }

    /** Audit ids currently reachable in this region's transport queues (diagnostic walk, used by
     *  the C11 quiescence report). */
    void nestworldForEachQueuedAuditId(java.util.function.LongConsumer c) {
        for (RegionMessage<?> m : nestworldMailbox) c.accept(m.auditId());
        for (NestworldPendingSend p : nestworldOutboundPending) c.accept(p.message().auditId());
    }

    /** Re-queues a message this region already accepted once (its "sent" event was
     *  already counted) — e.g. a border-band write whose cascade-guard lock acquisition
     *  timed out this pass and must wait for a later one. Does NOT record a new "sent"
     *  event; the message's original {@link MailboxAudit} entry stays untouched, still
     *  correctly tracked as pending until it's actually applied. */
    public void nestworldRequeueMessage(RegionMessage<?> message) {
        nestworldEnqueueOrOrphan(message);
    }

    /** Drains and returns every currently-queued message of the given type, leaving
     *  messages of other types in the mailbox for their own drain site. Main-thread only,
     *  called at whichever barrier-safe point is correct for that message type. */
    public java.util.List<RegionMessage<?>> nestworldDrainMailbox(RegionMessage.Type type) {
        if (nestworldMailbox.isEmpty()) return java.util.List.of();
        java.util.List<RegionMessage<?>> drained = new java.util.ArrayList<>();
        java.util.Iterator<RegionMessage<?>> it = nestworldMailbox.iterator();
        while (it.hasNext()) {
            RegionMessage<?> m = it.next();
            if (m.messageType() == type) {
                drained.add(m);
                it.remove();
                nestworldMailboxDepth.decrementAndGet();
            }
        }
        return drained;
    }

    /**
     * Stage 5.3 design v3 (docs/LOCAL_TICK_STAGE4.md, "budgeted drain" fix): applies
     * queued messages of the given type via {@code applier}, in this queue's FIFO
     * order, until {@code deadlineNanos} passes — only messages actually handed to
     * {@code applier} are removed; anything left once the deadline hits stays queued
     * for a later drain call, same barrier-safe callers as {@link
     * #nestworldDrainMailbox}. Bounds the real cost (the apply, not the queue walk)
     * because the caller's expensive work runs synchronously inside {@code applier}
     * before this loop checks the clock again. Deadline is shared across a whole
     * barrier pass (all regions, both mailbox-draining message types) by the caller,
     * so it only ever tightens — never resets — as the pass progresses.
     *
     * <p>Stage 5.3 v3 Fix 3 correction (2026-08-14): the caller iterates every region
     * for both message types, and this method used to apply AT LEAST 16 messages
     * unconditionally before its first clock check (the {@code applied & 15} check
     * only fires AFTER 16 applies) — a fixed per-call floor that was harmless under
     * the old barrier model (this apply was always uncontended, so 16 applies cost
     * microseconds) but, once Step 5 made {@link
     * NestworldDimensionRegion#applyWithCascadeGuard}'s lock acquisition
     * genuinely contended, meant EVERY region got its own ~16-message floor of
     * possibly-slow work regardless of the shared deadline already being blown by an
     * earlier region in the same pass — the actual root cause of a real
     * ServerHangWatchdog crash (mailbox growing 43,821 -> 139,541 while MSPT spiralled
     * 42 -> 424ms across a soak). Checking the deadline HERE, before touching this
     * region's queue at all, caps the real worst case at "one region's floor," not
     * "region-count times the floor."
     */
    public void nestworldDrainMailboxBudgeted(RegionMessage.Type type, long deadlineNanos,
                                               java.util.function.Consumer<RegionMessage<?>> applier) {
        if (nestworldMailbox.isEmpty()) return;
        if (System.nanoTime() >= deadlineNanos) return;
        java.util.Iterator<RegionMessage<?>> it = nestworldMailbox.iterator();
        int applied = 0;
        while (it.hasNext()) {
            RegionMessage<?> m = it.next();
            if (m.messageType() != type) continue;
            it.remove();
            nestworldMailboxDepth.decrementAndGet();
            applier.accept(m);
            applied++;
            if ((applied & 3) == 0 && System.nanoTime() >= deadlineNanos) break;
        }
    }

    /**
     * Batch-apply Phase 1 (docs/BATCH_APPLY_COALESCING_DESIGN.md): forms up to {@code
     * maxBatchSize} messages of the given type, in FIFO order, from this region's
     * mailbox — pure dequeue, no lock/apply work. The caller is expected to acquire ONE
     * combined lock for the whole returned batch (see {@code
     * NestworldDimensionRegion#applyBatchWithCascadeGuard}) instead of one lock per
     * message, which is the actual point of this method existing separately from
     * {@link #nestworldDrainMailboxBudgeted}. A batch smaller than {@code maxBatchSize}
     * means the mailbox has no more messages of this type left (the scan reached the
     * end), NOT that the caller should stop looking — a full-size batch means there may
     * be more. Empty list if nothing of this type is queued. Main-thread only, same
     * barrier-safe contract as {@link #nestworldDrainMailboxBudgeted}.
     */
    public java.util.List<RegionMessage<?>> nestworldDrainMailboxBatch(RegionMessage.Type type, int maxBatchSize) {
        if (nestworldMailbox.isEmpty()) return java.util.List.of();
        java.util.List<RegionMessage<?>> batch = new java.util.ArrayList<>(Math.min(maxBatchSize, 32));
        java.util.Iterator<RegionMessage<?>> it = nestworldMailbox.iterator();
        while (it.hasNext() && batch.size() < maxBatchSize) {
            RegionMessage<?> m = it.next();
            if (m.messageType() != type) continue;
            it.remove();
            nestworldMailboxDepth.decrementAndGet();
            batch.add(m);
        }
        return batch;
    }

    /** Approximate current mailbox depth, all message types combined — an O(n) queue
     *  walk, so call only from low-frequency diagnostics (e.g. {@code /nestworld
     *  status}), never from a per-tick path. Non-zero across a tick boundary is only
     *  expected under a genuine flood exceeding {@link NestworldTuning#MAILBOX_DRAIN_BUDGET_NANOS}. */
    public int nestworldMailboxSize() {
        return nestworldMailbox.size();
    }

    // -----------------------------------------------------------------------
    // Stage 5.3 v3, Fix 3 (docs/LOCAL_TICK_STAGE4.md, "backpressure without message
    // loss"): sender-side scheduler pause. O(1) depth counter (NOT nestworldMailboxSize(),
    // which is an O(n) queue walk -- calling that per send attempt would turn exactly the
    // overload scenario this exists to prevent into an O(n^2) death spiral) tracked
    // alongside every mailbox mutation, checked by every SENDER before posting. Reused by
    // the manual sender path (nestworldSendOrQueue) as well as this region's own
    // outbound-pending retry queue below.
    // -----------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicInteger nestworldMailboxDepth =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** O(1) approximation of current mailbox depth for hot-path backpressure checks —
     *  see {@link #nestworldMailboxSize()} for the exact (but O(n)) diagnostic version. */
    public int nestworldMailboxDepthFast() {
        return nestworldMailboxDepth.get();
    }

    /** A message this region wanted to send but couldn't yet because the destination was
     *  over the backpressure threshold — held here (at the SENDER) instead of being lost
     *  or forced onto the destination. Retried from {@link #nestworldFlushOutboundPending}. */
    private record NestworldPendingSend(WorldRegion destination, RegionMessage<?> message) {}

    private final java.util.Queue<NestworldPendingSend> nestworldOutboundPending =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Number of this region's own outbound sends currently held back by backpressure —
     *  0 in the overwhelmingly common case; diagnostic use only (e.g. {@code /nestworld
     *  status}'s {@code pending=N}), same O(n) caveat as {@link #nestworldMailboxSize()}. */
    public int nestworldOutboundPendingSize() {
        return nestworldOutboundPending.size();
    }

    /**
     * Stage 5.3 v3 Fix 3: the gated send path every cross-region message producer should
     * use instead of calling {@code destination.nestworldPostMessage()} directly. Fast
     * path (destination healthy): identical to a direct post, zero extra cost. Slow path
     * (destination over {@link NestworldTuning#MAILBOX_BACKPRESSURE_THRESHOLD}): the
     * message is NOT dropped and NOT forced onto the destination — it is held on THIS
     * (the sender's) side and retried on this region's own next local tick via {@link
     * #nestworldFlushOutboundPending}, exactly the "skip producing new outbound messages
     * to that destination until its depth drops" design from docs/LOCAL_TICK_STAGE4.md.
     * Safe to call from any thread (mirrors {@link #nestworldPostMessage}); the audit
     * "sent" event is recorded exactly once here regardless of which path is taken, so a
     * message that spends several ticks pending before actually being delivered is not
     * double-counted when {@link #nestworldFlushOutboundPending} later hands it off via
     * {@link #nestworldRequeueMessage}.
     */
    public void nestworldSendOrQueue(WorldRegion destination, RegionMessage<?> message) {
        if (destination.nestworldRetired
                || destination.nestworldMailboxDepthFast() < MailboxLifecycleTestHooks.backpressureThreshold()) {
            // A retired destination is handled inside the safe enqueue (orphaned, never accepted).
            destination.nestworldPostMessage(message);
        } else {
            MailboxAudit.recordSent(message.auditId(), message.sourceRegion(), destination, message.messageType());
            // Same C4 shape on the sender-side pending queue: if THIS (the sender) was retired
            // concurrently, either our own remove() wins (we orphan it) or the retirement drain
            // already took it (it orphaned it). Never left in a drained queue.
            NestworldPendingSend pending = new NestworldPendingSend(destination, message);
            nestworldOutboundPending.add(pending);
            if (nestworldRetired && nestworldOutboundPending.remove(pending)) {
                MailboxLifecycle.orphan(this, message, MailboxLifecycle.OrphanReason.PENDING_SENDER_RETIRED);
            }
        }
    }

    /**
     * Retries this region's own backlog of backpressure-deferred sends, up to {@code
     * maxAttempts} entries per call (same budgeted-work-slice idiom as {@link
     * #nestworldDrainMailboxBudgeted} and the entity round's other per-tick budgets) —
     * called once per own local tick (both barrier and free-running) from {@link
     * RegionThread#runOneTick}. A pending send whose destination is still over threshold
     * is left in place (not lost, not reordered ahead of anything) and re-attempted next
     * time this runs; one that succeeds is handed to the destination via {@link
     * #nestworldRequeueMessage} (already-recorded "sent", so no double count).
     */
    public void nestworldFlushOutboundPending(int maxAttempts) {
        if (nestworldOutboundPending.isEmpty()) return;
        java.util.Iterator<NestworldPendingSend> it = nestworldOutboundPending.iterator();
        int attempts = 0;
        while (it.hasNext() && attempts < maxAttempts) {
            NestworldPendingSend pending = it.next();
            attempts++;
            if (pending.destination().nestworldRetired) {
                // Destination retired while the send was held here: its address is no longer
                // authoritative -- re-resolve instead of waiting on a dead region's depth forever.
                it.remove();
                MailboxLifecycle.orphan(this, pending.message(), MailboxLifecycle.OrphanReason.PENDING_DESTINATION_RETIRED);
            } else if (pending.destination().nestworldMailboxDepthFast() < MailboxLifecycleTestHooks.backpressureThreshold()) {
                it.remove();
                pending.destination().nestworldRequeueMessage(pending.message());
            }
        }
    }

    public WorldRegion(int id, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                        int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.id = id;
        this.dimension = dimension;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getDimension() {
        return dimension;
    }

    // --- Spatial queries ---

    public boolean containsChunk(int cx, int cz) {
        return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
    }

    public boolean containsChunk(ChunkPos pos) {
        return containsChunk(pos.x, pos.z);
    }

    public boolean containsBlock(BlockPos pos) {
        return containsChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Stage 5 "Blocker 3" fix (docs/LOCAL_TICK_STAGE4.md, "border-band under
     * independent local time"): true when the given chunk is within {@link
     * NestworldTuning#BORDER_BAND_CHUNKS} of one of THIS region's own edges —
     * same margin and rationale {@code NestworldRegionSystem.interiorRegionFor}/
     * {@code queueRandomTicksFor} already use for the 4 vanilla tick phases
     * (vanilla cascades — pistons, comparators, redstone — can reach a few
     * blocks past their origin, so anything within the band is kept off region
     * threads regardless of which of the 4 phases, or {@code Level.setBlock()},
     * triggered it). Does NOT check whether the chunk is inside this region at
     * all — callers combine this with their own bounds check as needed.
     */
    public boolean isInBorderBand(int cx, int cz) {
        int band = NestworldTuning.BORDER_BAND_CHUNKS;
        return cx < minChunkX + band || cx > maxChunkX - band
                || cz < minChunkZ + band || cz > maxChunkZ - band;
    }

    // --- Entity ownership ---

    public void addEntity(UUID id) { ownedEntityIds.add(id); }
    public void removeEntity(UUID id) { ownedEntityIds.remove(id); }
    public boolean ownsEntity(UUID id) { return ownedEntityIds.contains(id); }
    public Set<UUID> getOwnedEntityIds() { return ownedEntityIds; }

    // --- TPS tracking ---

    /** Wall-clock-windowed history (10s/1m) for this region's own tick, feeding
     *  {@code /nestworld status}'s per-free-running-region "Tick" rows -- same idea as
     *  {@link TickDurationHistory#SERVER}, one instance per region since free-running
     *  regions tick independently of the main server loop and of each other. */
    public final TickDurationHistory tickHistory = new TickDurationHistory();

    /** Called by RegionThread at the end of each tick with measured duration. */
    public void recordTickDuration(long durationNs) {
        long old = tickDurationsNs[tickIndex];
        tickDurationsNs[tickIndex] = durationNs;
        tickIndex = (tickIndex + 1) % tickDurationsNs.length;
        windowSumNs.addAndGet(durationNs - old);
        tickHistory.record(System.currentTimeMillis(), durationNs);
    }

    /** Returns estimated TPS (0–20). */
    public double getCurrentTps() {
        long avgNs = windowSumNs.get() / Math.max(1, tickDurationsNs.length);
        if (avgNs <= 0) return 20.0;
        return Math.min(20.0, 1_000_000_000.0 / avgNs);
    }

    /**
     * Average duration of this region's own tick work in milliseconds.
     *
     * <p>Unlike {@link #getCurrentTps()} (which caps at 20 and therefore reads
     * "healthy" for any duration under the full 50 ms budget) this is the raw
     * cost the region contributes to the server tick — the split/merge
     * heuristic compares it against a share of the budget.
     */
    public double getAvgTickMs() {
        return windowSumNs.get() / (double) Math.max(1, tickDurationsNs.length) / 1_000_000.0;
    }

    /**
     * Stage 4 (docs/LOCAL_TICK_STAGE4.md, "Stage 4.5" conclusion): the barrier's wait
     * time tracks the SLOWEST region's tick, not the average — so a region whose
     * AVERAGE looks healthy but whose tick cost spikes intermittently (long tail) can
     * still be the tick's bottleneck on the ticks that matter, invisibly to an
     * average-only split heuristic. {@code tickDurationsNs} already stores the raw
     * per-tick samples (100-tick ring buffer, same window {@link #getAvgTickMs()}
     * uses) — this reads that existing data, no new tracking added. Sorts a COPY;
     * called at most once per region per real second (RegionSplitManager's
     * evaluation cadence), not from any hot per-tick path.
     *
     * @param percentile in [0.0, 1.0], e.g. 0.95 for p95
     */
    public double getPercentileTickMs(double percentile) {
        long[] sorted = tickDurationsNs.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.floor(percentile * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1_000_000.0;
    }

    /** Worst single tick in the current 100-tick window, in milliseconds. */
    public double getMaxTickMs() {
        long max = 0;
        for (long v : tickDurationsNs) if (v > max) max = v;
        return max / 1_000_000.0;
    }

    // --- Split eligibility ---

    /** A region can be split only when it covers more than 1 chunk in at least one axis. */
    public boolean canSplit() {
        return (maxChunkX - minChunkX) > 0 || (maxChunkZ - minChunkZ) > 0;
    }

    /** Returns the axis along which to cut (always prefer the longer axis for balance). */
    public SplitAxis preferredSplitAxis() {
        return (maxChunkX - minChunkX) >= (maxChunkZ - minChunkZ) ? SplitAxis.X : SplitAxis.Z;
    }

    public void resetThresholdCounters() {
        splitPressureChecks = 0;
        mergePressureChecks = 0;
    }

    // --- Accessors ---

    public int getId() { return id; }
    public int getMinChunkX() { return minChunkX; }
    public int getMinChunkZ() { return minChunkZ; }
    public int getMaxChunkX() { return maxChunkX; }
    public int getMaxChunkZ() { return maxChunkZ; }
    public int getChunkSpanX() { return maxChunkX - minChunkX + 1; }
    public int getChunkSpanZ() { return maxChunkZ - minChunkZ + 1; }
    public StampedLock getChunkLock() { return chunkLock; }

    @Override
    public String toString() {
        return String.format("Region{id=%d, chunks=(%d,%d)–(%d,%d), cost=%.1fms}",
                id, minChunkX, minChunkZ, maxChunkX, maxChunkZ, getAvgTickMs());
    }
}
