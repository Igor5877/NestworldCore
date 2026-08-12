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

    /** Called from the main thread (RegionThreadPool). */
    public void nestworldPostFreeRunningWork(java.util.List<Runnable> work) {
        pendingFreeRunningWork.set(work);
    }

    /** Called from this region's own thread only; non-blocking. */
    public java.util.List<Runnable> nestworldPollFreeRunningWork() {
        return pendingFreeRunningWork.getAndSet(null);
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
    // syncGhostZones() (main thread, called right after the region barrier — every
    // region thread has released its write lock and is parked waiting for the next
    // tick signal at that point, so this publish never races a region's own writes).
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

    /** Posts a GENUINELY NEW message to this region's inbound mailbox. Safe from any
     *  thread. Counted by {@link MailboxAudit} as one "sent" event — for re-queuing an
     *  EXISTING message (e.g. Part 3's cascade-guard lock-timeout retry), use {@link
     *  #nestworldRequeueMessage} instead, or the same real send gets double-counted. */
    public void nestworldPostMessage(RegionMessage<?> message) {
        MailboxAudit.recordSent(message.auditId(), message.sourceRegion(), this, message.messageType());
        nestworldMailbox.add(message);
    }

    /** Re-queues a message this region already accepted once (its "sent" event was
     *  already counted) — e.g. a border-band write whose cascade-guard lock acquisition
     *  timed out this pass and must wait for a later one. Does NOT record a new "sent"
     *  event; the message's original {@link MailboxAudit} entry stays untouched, still
     *  correctly tracked as pending until it's actually applied. */
    public void nestworldRequeueMessage(RegionMessage<?> message) {
        nestworldMailbox.add(message);
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
     */
    public void nestworldDrainMailboxBudgeted(RegionMessage.Type type, long deadlineNanos,
                                               java.util.function.Consumer<RegionMessage<?>> applier) {
        if (nestworldMailbox.isEmpty()) return;
        java.util.Iterator<RegionMessage<?>> it = nestworldMailbox.iterator();
        int applied = 0;
        while (it.hasNext()) {
            RegionMessage<?> m = it.next();
            if (m.messageType() != type) continue;
            it.remove();
            applier.accept(m);
            applied++;
            if ((applied & 15) == 0 && System.nanoTime() >= deadlineNanos) break;
        }
    }

    /** Approximate current mailbox depth, all message types combined — an O(n) queue
     *  walk, so call only from low-frequency diagnostics (e.g. {@code /nestworld
     *  status}), never from a per-tick path. Non-zero across a tick boundary is only
     *  expected under a genuine flood exceeding {@link NestworldTuning#MAILBOX_DRAIN_BUDGET_NANOS}. */
    public int nestworldMailboxSize() {
        return nestworldMailbox.size();
    }

    public WorldRegion(int id, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        this.id = id;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
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

    /** Called by RegionThread at the end of each tick with measured duration. */
    public void recordTickDuration(long durationNs) {
        long old = tickDurationsNs[tickIndex];
        tickDurationsNs[tickIndex] = durationNs;
        tickIndex = (tickIndex + 1) % tickDurationsNs.length;
        windowSumNs.addAndGet(durationNs - old);
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
