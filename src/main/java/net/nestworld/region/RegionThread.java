package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;

/**
 * One dedicated thread per active WorldRegion.
 *
 * Tick protocol each game tick:
 *   Main thread calls requestTick(latch) on every RegionThread.
 *   Each RegionThread wakes, acquires the region write-lock, ticks its entities
 *   and block-entities, then counts down the per-tick latch.
 *   The main thread waits on the latch while servicing the chunk-source task
 *   queue (ServerChunkCache.pollTask) so that any synchronous chunk request a
 *   region thread makes (getChunk().join()) is completed by the main thread
 *   instead of deadlocking.
 *
 * On crash: emergency-saves owned chunks, marks itself stopped; the
 * RegionThreadPool detects the dead thread and spawns a replacement.
 */
public class RegionThread extends Thread {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionThread");

    /** Entity tick errors that have logged a full stack (across all regions). */
    /** Test-only: region id whose thread crashes every tick (-Dnestworld.crashTestRegion). */
    private static final int CRASH_TEST_REGION =
            Integer.getInteger("nestworld.crashTestRegion", -1);

    private static final java.util.concurrent.atomic.AtomicInteger TICK_ERROR_STACKS =
            new java.util.concurrent.atomic.AtomicInteger(0);

    final WorldRegion region;
    private final ServerLevel level;

    /** Public accessor for vanilla-patched files outside this package (e.g. Explosion's
     *  Stage 3 cross-region write classification) — {@code region} itself stays
     *  package-private since most callers are within net.nestworld.region. */
    public WorldRegion getRegion() { return region; }

    private volatile boolean running = true;
    /** Latch for the tick currently being requested; null when idle. */
    private volatile CountDownLatch tickLatch = null;
    /** When set, the next round executes this instead of an entity tick. */
    private java.util.List<Runnable> pendingWork = null; // guarded by tickSignal
    private final Object tickSignal = new Object();
    /** Work-round nanos folded into the next entity round's TPS record. */
    private long workRoundNanos = 0;

    // Timing diagnostics, logged every 200 ticks
    private long entNanos = 0;
    private int timedTicks = 0, lastTickedCount = 0;

    // -----------------------------------------------------------------------
    // Per-thread chunk cache — read by ServerChunkCache.nestworldGetLoadedChunk
    // (see ServerChunkCache.java.patch). Entity ticking performs thousands of
    // block reads per tick; without this cache every read pays a chunk-holder
    // map lookup + future unwrap. Direct-mapped, 16 slots, cleared each tick.
    // -----------------------------------------------------------------------
    private final long[] chunkCacheKeys = new long[16];
    private final net.minecraft.world.level.chunk.LevelChunk[] chunkCacheVals =
            new net.minecraft.world.level.chunk.LevelChunk[16];

    public net.minecraft.world.level.chunk.LevelChunk getCachedChunk(long key) {
        int idx = (int) (key ^ (key >>> 32)) & 15;
        return chunkCacheKeys[idx] == key ? chunkCacheVals[idx] : null;
    }

    public void cacheChunk(long key, net.minecraft.world.level.chunk.LevelChunk chunk) {
        int idx = (int) (key ^ (key >>> 32)) & 15;
        chunkCacheKeys[idx] = key;
        chunkCacheVals[idx] = chunk;
    }

    private void clearChunkCache() {
        java.util.Arrays.fill(chunkCacheKeys, Long.MIN_VALUE);
        java.util.Arrays.fill(chunkCacheVals, null);
    }

    /**
     * Per-thread void-air chunk returned for a region thread's getChunk(load=true)
     * miss, so it never synchronously loads + blocks on the main thread (that is
     * the park/unpark herd). One instance per region thread: EmptyLevelChunk
     * inherits LevelChunk's mutable arrays (heightmaps/sections), so a single
     * shared instance raced across threads (AIOOBE) — a per-thread one is only
     * touched by this thread. Block reads ignore the position, so a fixed dummy
     * ChunkPos is fine.
     */
    private net.minecraft.world.level.chunk.EmptyLevelChunk nestworldEmptyChunk;

    public net.minecraft.world.level.chunk.LevelChunk nestworldEmptyChunk() {
        net.minecraft.world.level.chunk.EmptyLevelChunk c = this.nestworldEmptyChunk;
        if (c == null) {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
                    level.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            c = new net.minecraft.world.level.chunk.EmptyLevelChunk(
                    level, new net.minecraft.world.level.ChunkPos(0, 0), biome);
            this.nestworldEmptyChunk = c;
        }
        return c;
    }

    public RegionThread(WorldRegion region, ServerLevel level) {
        super("NestWorld-Region-" + region.getId());
        setDaemon(true);
        this.region = region;
        this.level = level;
        region.owningThread = this;
        clearChunkCache();
    }

    // -----------------------------------------------------------------------
    // Per-thread Alternate Current handler, bounded to this region's chunks.
    // Wire updates triggered from this thread (scheduled ticks, entities on
    // pressure plates) run here; networks reaching outside the region abort
    // and are deferred to the main thread (see NestworldRedstone).
    // -----------------------------------------------------------------------
    private alternate.current.wire.WireHandler acWireHandler;

    /**
     * Re-entrancy depth of NestworldRedstone wire calls on this thread. A
     * NetworkOutOfBounds abort may only reset the handler at depth 1 —
     * resetting mid-update from a nested neighborChanged would corrupt the
     * outer update's queues (observed as NPEs in entity ticks).
     */
    int wireCallDepth = 0;

    /**
     * Per-thread neighbor-update collector. Level's CollectingNeighborUpdater
     * is a single shared ArrayDeque+counter with zero thread safety; region
     * threads mutating blocks (scheduled/random ticks, AC) raced the main
     * thread on it, corrupting the stack so that ALL world neighbor updates
     * were silently swallowed (plants stopped popping off, water stopped
     * flowing, levers went dead). Vanilla default chain limit (1,000,000).
     */
    private net.minecraft.world.level.redstone.CollectingNeighborUpdater neighborUpdater;

    public net.minecraft.world.level.redstone.NeighborUpdater neighborUpdater(net.minecraft.world.level.Level lvl) {
        if (neighborUpdater == null) {
            neighborUpdater = new net.minecraft.world.level.redstone.CollectingNeighborUpdater(lvl, 1_000_000);
        }
        return neighborUpdater;
    }

    public alternate.current.wire.WireHandler acWireHandler() {
        if (acWireHandler == null) {
            acWireHandler = new alternate.current.wire.WireHandler(
                    level, level.nestworldWireHandler.getConfig());
            acWireHandler.setBounds(
                    region.getMinChunkX() << 4, region.getMinChunkZ() << 4,
                    ((region.getMaxChunkX() + 1) << 4) - 1, ((region.getMaxChunkZ() + 1) << 4) - 1);
        }
        return acWireHandler;
    }

    // -----------------------------------------------------------------------
    // Pool-facing control API
    // -----------------------------------------------------------------------

    /**
     * Signals this thread to execute one tick. Non-blocking.
     * The thread counts the latch down when its tick completes (or crashes).
     */
    public void requestTick(CountDownLatch latch) {
        synchronized (tickSignal) {
            tickLatch = latch;
            tickSignal.notifyAll();
        }
    }

    /**
     * Signals this thread to run an arbitrary work list (e.g. this region's
     * bucket of due scheduled block ticks) under the region write lock instead
     * of an entity tick. Non-blocking; the latch is counted down when done.
     */
    public void requestWork(java.util.List<Runnable> work, CountDownLatch latch) {
        synchronized (tickSignal) {
            pendingWork = work;
            tickLatch = latch;
            tickSignal.notifyAll();
        }
    }

    public void shutdown() {
        running = false;
        synchronized (tickSignal) { tickSignal.notifyAll(); }
    }

    public boolean isCrashed() { return !running && isAlive(); }

    // -----------------------------------------------------------------------
    // Main loop
    // -----------------------------------------------------------------------

    /** One dispatched unit of work: either an entity round ({@code work == null}) or a
     *  work round ({@code work != null}), plus the latch to count down when done. */
    private record DispatchedTick(CountDownLatch latch, java.util.List<Runnable> work) {}

    @Override
    public void run() {
        while (running) {
            if (NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && region.isFreeRunning()) {
                runFreeRunningTick();
                continue;
            }
            DispatchedTick dispatched = awaitDispatch();
            if (dispatched == null) {
                if (!running) break; // genuine shutdown
                continue; // woken by a free-running toggle instead — loop back and branch above
            }
            runOneTick(dispatched.latch(), dispatched.work());
        }
    }

    /** Persists across calls — the next scheduled local-tick deadline, self-paced. */
    private long freeRunningNextTickNanos = 0;

    /**
     * Step 3 (docs/LOCAL_TICK_STAGE4.md, "Step 3 — Single Free-Running Region"): one
     * iteration of this region's OWN self-paced loop, called repeatedly from {@link #run()}
     * instead of {@link #awaitDispatch()} while {@code region.isFreeRunning()}. No shared
     * latch or signal object — this region's pacing is entirely its own from here on.
     *
     * <p>Order matters: (1) the save rendezvous check MUST run first and ONLY here,
     * between local ticks — never mid-tick — since this is the one point guaranteed to
     * be a consistent, safe-to-serialize state (Gemini's review flagged this as the
     * critical detail to get right). (2) drain any main-thread-dispatched work batch
     * (design point 2) — queue-based, non-blocking, "drop if backlogged" same as
     * {@code RegionThreadPool.runWorkRoundDropIfBacklogged} elsewhere. (3) the self-paced
     * entity round itself, run only once {@code freeRunningNextTickNanos} has elapsed;
     * catches up without rubber-banding if behind, same philosophy vanilla's own tick
     * loop already uses; parks a short, bounded interval otherwise so this thread stays
     * responsive to shutdown/toggle-off/incoming work rather than sleeping long stretches.
     */
    private void runFreeRunningTick() {
        if (region.nestworldCheckSaveRendezvous()) {
            return;
        }

        java.util.List<Runnable> work = region.nestworldPollFreeRunningWork();
        if (work != null) {
            runOneTick(null, work);
        }

        long now = System.nanoTime();
        if (freeRunningNextTickNanos == 0) {
            freeRunningNextTickNanos = now;
        }
        if (now >= freeRunningNextTickNanos) {
            runOneTick(null, null);
            freeRunningNextTickNanos += NestworldTuning.FREE_RUNNING_TARGET_TICK_NANOS;
            // Don't rubber-band: if badly behind (e.g. this region was paused for a
            // save rendezvous, or genuinely overloaded), resync to now instead of
            // trying to burn through a backlog of missed deadlines back-to-back.
            long behindBy = now - freeRunningNextTickNanos;
            if (behindBy > NestworldTuning.FREE_RUNNING_TARGET_TICK_NANOS * 5) {
                freeRunningNextTickNanos = now;
            }
        } else {
            long parkNanos = Math.min(freeRunningNextTickNanos - now, 5_000_000L);
            if (parkNanos > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(parkNanos);
            }
        }
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 5a (docs/LOCAL_TICK_STAGE4.md, "Stage 5
     * tick-scheduler architecture — DECIDED"): pure structural extraction, ZERO behavior
     * change from the original inline synchronized/wait block this replaced — separates
     * "how does this thread learn it's time to tick" from "what does one tick actually
     * do" ({@link #runOneTick}), so a later step (free-running regions) can replace ONLY
     * this method's body (self-paced timing instead of waiting for the pool's shared
     * signal) without touching the tick body at all. Blocks until the pool requests a
     * tick/work round, or shutdown is signalled. Returns null on shutdown — any latch
     * that was already pending at that point is still counted down first, exactly as
     * the original inline code did.
     *
     * <p>Step 3: also wakes (and returns null, WITHOUT it meaning shutdown) when this
     * region gets toggled free-running WHILE this thread is blocked here from a PRIOR
     * barrier-dispatched iteration — found live during Step 3's first test: without
     * this, the pool stops dispatching to a newly-free-running region entirely (by
     * design), so nothing would ever wake a thread already parked in the old
     * indefinite {@code wait()}, and it would sit stuck forever instead of transitioning
     * into {@link #runFreeRunningTick()}. {@link WorldRegion#nestworldSetFreeRunning}
     * calls {@link #nestworldWakeForFreeRunningToggle()} specifically to cover this.
     */
    private DispatchedTick awaitDispatch() {
        CountDownLatch latch;
        java.util.List<Runnable> work;
        // Block until the pool requests a tick or a work round, or this region is
        // toggled free-running (see above).
        synchronized (tickSignal) {
            while (running && tickLatch == null
                    && !(NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && region.isFreeRunning())) {
                try { tickSignal.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            latch = tickLatch;
            tickLatch = null;
            work = pendingWork;
            pendingWork = null;
        }
        if (!running) {
            if (latch != null) latch.countDown();
            return null;
        }
        if (latch == null) {
            // Woken by a free-running toggle with nothing actually dispatched (the
            // normal shutdown case was already handled above) — tell run() to loop
            // back and re-check, not that this was a shutdown.
            return null;
        }
        return new DispatchedTick(latch, work);
    }

    /** Wakes this thread if it's blocked in {@link #awaitDispatch()} — called when this
     *  region transitions INTO free-running mode, since the pool stops dispatching to
     *  it from that point on and nothing else would ever wake an already-waiting thread. */
    public void nestworldWakeForFreeRunningToggle() {
        synchronized (tickSignal) { tickSignal.notifyAll(); }
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 5a: pure structural extraction of the
     * tick body — acquire the region's write lock, run either a work round or an entity
     * round, record timing/accounting, count down the latch. ZERO behavior change from
     * the original inline code this replaced.
     *
     * <p>Step 3: {@code latch} is nullable — a free-running region's self-paced local
     * tick has no shared latch (nobody is waiting on it), so the final countdown is
     * skipped when null. Every other caller (the barrier-dispatched path) still always
     * passes a real latch, unchanged.
     */
    private void runOneTick(CountDownLatch latch, java.util.List<Runnable> work) {
        long tickStart = System.nanoTime();
        try {
            long stamp = region.getChunkLock().writeLock();
            try {
                clearChunkCache(); // chunks may have unloaded since last tick
                // Test hook: force this region's thread to crash every tick,
                // exercising the pool's backoff + disable path.
                if (CRASH_TEST_REGION == region.getId()) {
                    throw new RuntimeException("nestworld crash-test injection (region "
                            + region.getId() + ")");
                }
                if (work != null) {
                    runWorkBudgeted(work);
                } else {
                    applyPendingEntityImpacts();
                    applyPendingEntityPushes();
                    applyPendingEntityMutations();
                    long e0 = System.nanoTime();
                    tickEntities();
                    entNanos += System.nanoTime() - e0;
                    publishEntitySnapshot();
                    if (++timedTicks >= 200) {
                        LOGGER.info("[{}] avg ms over {} ticks: entities={} ({} owned, {} ticked{}) localTick={}",
                                getName(), timedTicks,
                                String.format("%.2f", entNanos / 1e6 / timedTicks),
                                region.getOwnedEntityIds().size(), lastTickedCount,
                                (lastDeferredCount > 0 ? ", " + lastDeferredCount + " deferred by budget" : "")
                                        + (lastWorkDeferred > 0 ? ", " + lastWorkDeferred + " work deferred" : ""),
                                region.getLocalTickCount());
                        entNanos = 0; timedTicks = 0;
                    }
                }
            } finally {
                region.getChunkLock().unlockWrite(stamp);
            }
        } catch (Throwable crash) {
            handleCrash(crash);
            // after crash, running == false; latch is still counted down below
        } finally {
            // Work rounds and the entity round are halves of the same game
            // tick — fold work time into the entity round's TPS record so
            // region cost (split heuristic) reflects the full tick.
            if (work != null) {
                workRoundNanos += System.nanoTime() - tickStart;
                // Entity-less regions are skipped by the entity round, so
                // record here or their cost would never update.
                if (region.getOwnedEntityIds().isEmpty()) {
                    region.recordTickDuration(workRoundNanos);
                    region.nestworldAdvanceLocalTick();
                    workRoundNanos = 0;
                }
            } else {
                region.recordTickDuration(System.nanoTime() - tickStart + workRoundNanos);
                region.nestworldAdvanceLocalTick();
                workRoundNanos = 0;
            }
            if (latch != null) latch.countDown();
        }
    }

    // -----------------------------------------------------------------------
    // Tick logic
    // -----------------------------------------------------------------------

    /**
     * Work runnables (scheduled block/fluid ticks) deferred by the budget.
     * Run before the next work round — or at the start of the next entity
     * round if no work round arrives — so a deferred tick is late by at most
     * one game tick and vanilla relative order within the region is kept.
     * Only touched by this thread.
     */
    private java.util.ArrayList<Runnable> carriedWork = null;
    /** Work runnables deferred in the last round; main thread reads for status. */
    private volatile int lastWorkDeferred = 0;

    public int getLastWorkDeferred() { return lastWorkDeferred; }

    /** Minimum entity-round slice even when work rounds ate the whole budget. */
    private static final long MIN_ENTITY_SLICE_NANOS = 10_000_000L;

    /**
     * Runs carried-over plus newly assigned work under the per-tick budget;
     * whatever does not fit is carried to the next tick. An unsplittable
     * scheduled-tick hotspot (giant redstone machine in one chunk) thus slows
     * down locally instead of dragging the lockstep tick, mirroring the
     * entity budget.
     */
    private void runWorkBudgeted(java.util.List<Runnable> newWork) {
        java.util.ArrayList<Runnable> all;
        if (carriedWork != null) {
            all = carriedWork;
            carriedWork = null;
            if (newWork != null) all.addAll(newWork);
        } else if (newWork != null) {
            all = new java.util.ArrayList<>(newWork);
        } else {
            return;
        }
        long deadline = System.nanoTime() + NestworldTuning.REGION_ENTITY_BUDGET_NANOS;
        int done = 0;
        for (; done < all.size(); done++) {
            try {
                all.get(done).run();
            } catch (Throwable t) {
                LOGGER.warn("[{}] scheduled-tick error: {}", getName(), t.toString());
            }
            if ((done & 7) == 7 && System.nanoTime() > deadline) {
                done++;
                break;
            }
        }
        if (done < all.size()) {
            carriedWork = new java.util.ArrayList<>(all.subList(done, all.size()));
            lastWorkDeferred = all.size() - done;
        } else {
            lastWorkDeferred = 0;
        }
    }

    /**
     * Round-robin start index into the owned-entity snapshot. Carries across
     * ticks so that when the budget cuts a round short, the next round resumes
     * where this one stopped and every entity still ticks eventually.
     */
    private int entityCursor = 0;
    /** Entities deferred to the next tick by the budget in the last round.
     *  Volatile: read by the main thread for /nestworld status. */
    private volatile int lastDeferredCount = 0;

    public int getLastDeferredCount() { return lastDeferredCount; }

    /**
     * Stage 3 EXTENSION (docs/LOCAL_TICK_STAGE4.md, "Entity Safety Layer" — the
     * Explosion.explode() entity-damage race, found during the Stage 5 audit):
     * applies every ENTITY_IMPACT message posted to this region since its last tick
     * — always on THIS region's own thread, the entity's sole owner, before this
     * tick's own AI/physics processing runs (same "drain inbox first" ordering the
     * mailbox already uses elsewhere). A foreign explosion's damage/knockback on one
     * of this region's own entities is computed HERE, via a safe same-thread read of
     * the entity's live position — never by the triggering region's thread. Runs
     * every entity round unconditionally; a tick with nothing queued is a cheap
     * empty-queue check, not worth budgeting separately.
     */
    private void applyPendingEntityImpacts() {
        for (RegionMessage<?> msg : region.nestworldDrainMailbox(RegionMessage.Type.ENTITY_IMPACT)) {
            RegionMessage.EntityImpact impact = (RegionMessage.EntityImpact) msg.payload();
            try {
                net.minecraft.world.entity.Entity entity = level.getEntity(impact.entityId());
                if (entity != null && !entity.isRemoved()) {
                    impact.explosion().nestworldApplyEntityImpact(entity);
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity impact on {} failed: {}", impact.entityId(), t.toString());
            }
            MailboxAudit.recordApplied(msg.auditId(), region, RegionMessage.Type.ENTITY_IMPACT);
        }
    }

    /**
     * Stage 5 "Entity Safety Layer" follow-up (docs/LOCAL_TICK_STAGE4.md): applies every
     * ENTITY_PUSH message posted to this region since its last tick — same "owner applies,
     * sender never mutates" discipline as {@link #applyPendingEntityImpacts()}. Found via
     * {@link EntityOwnershipGuard} catching real violations on ATM9 (vanilla's entity-
     * collision push-apart, {@code Entity.push(Entity)} reached through {@code LivingEntity
     * .doPush}, mutates BOTH colliding entities' deltaMovement directly — a foreign-owned
     * entity near a region border got mutated from the wrong thread). The delta vector was
     * already fully computed by the sender from a safe snapshot read; this only re-checks
     * isVehicle()/isPushable() — the vanilla guard on the push's OWN side — since that is
     * only safe to read here, on the entity's owning thread.
     */
    private void applyPendingEntityPushes() {
        for (RegionMessage<?> msg : region.nestworldDrainMailbox(RegionMessage.Type.ENTITY_PUSH)) {
            RegionMessage.EntityPush push = (RegionMessage.EntityPush) msg.payload();
            try {
                net.minecraft.world.entity.Entity entity = level.getEntity(push.entityId());
                if (entity != null && !entity.isRemoved() && !entity.isVehicle() && entity.isPushable()) {
                    entity.push(push.dx(), push.dy(), push.dz());
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity push on {} failed: {}", push.entityId(), t.toString());
            }
            MailboxAudit.recordApplied(msg.auditId(), region, RegionMessage.Type.ENTITY_PUSH);
        }
    }

    /**
     * Entity Safety Layer "Bucket A" generic primitive (docs/LOCAL_TICK_STAGE4.md): applies
     * every ENTITY_MUTATE message posted to this region since its last tick — same "owner
     * applies" discipline as {@link #applyPendingEntityImpacts()}/{@link #applyPendingEntityPushes()}.
     * Covers the AOE-style "scan radius, mutate every entity found" call sites found beyond
     * Explosion/push (ThrownPotion, EvokerFangs, AreaEffectCloud, Guardian, Axolotl,
     * Ravager.roar(), ...) via a small closed op-code union instead of a bespoke message
     * type per site.
     */
    private void applyPendingEntityMutations() {
        for (RegionMessage<?> msg : region.nestworldDrainMailbox(RegionMessage.Type.ENTITY_MUTATE)) {
            RegionMessage.EntityMutate mutate = (RegionMessage.EntityMutate) msg.payload();
            try {
                net.minecraft.world.entity.Entity entity = level.getEntity(mutate.entityId());
                if (entity != null && !entity.isRemoved()) {
                    EntityMutationOp op = mutate.op();
                    if (op instanceof EntityMutationOp.Damage d) {
                        entity.hurt(d.source(), d.amount());
                    } else if (op instanceof EntityMutationOp.AddEffect e) {
                        if (entity instanceof net.minecraft.world.entity.LivingEntity le) {
                            le.addEffect(e.effect(), e.source());
                        }
                    } else if (op instanceof EntityMutationOp.Ignite i) {
                        entity.setSecondsOnFire(i.seconds());
                    } else if (op instanceof EntityMutationOp.ExtinguishFire) {
                        entity.extinguishFire();
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity mutation on {} failed: {}", mutate.entityId(), t.toString());
            }
            MailboxAudit.recordApplied(msg.auditId(), region, RegionMessage.Type.ENTITY_MUTATE);
        }
    }

    /**
     * Stage 5 "Entity Safety Layer" (docs/LOCAL_TICK_STAGE4.md): publishes this
     * region's {@link WorldRegion.EntitySnapshot} — a detached, immutable copy of
     * every owned entity's position fields — for {@code BoundaryEntityTransfer}'s
     * main-thread scan to read instead of the live {@code Entity} objects this
     * thread itself just finished mutating. Runs AFTER {@code tickEntities()} so the
     * snapshot reflects this tick's final, settled positions. A fresh {@code HashMap}
     * is built and handed to the region's {@code AtomicReference} as one unit —
     * readers on other threads never see a partially-populated map. A deliberately
     * SEPARATE pass over {@code ownedEntityIds} (not piggybacked on tickEntities()'s
     * own budgeted round-robin) — correctness first; folding this into that loop is
     * a possible follow-up if profiling ever shows the extra pass matters, not
     * attempted now.
     */
    private void publishEntitySnapshot() {
        java.util.Map<java.util.UUID, WorldRegion.EntitySnapshot> snapshot =
                new java.util.HashMap<>(region.getOwnedEntityIds().size());
        for (java.util.UUID uuid : region.getOwnedEntityIds()) {
            net.minecraft.world.entity.Entity e = level.getEntity(uuid);
            if (e == null) continue;
            snapshot.put(uuid, new WorldRegion.EntitySnapshot(
                    e.getX(), e.getY(), e.getZ(), e.xo, e.yo, e.zo, e.isRemoved()));
        }
        region.nestworldPublishEntitySnapshot(snapshot);
    }

    /**
     * Ticks the entities assigned to this region, stopping when the round
     * exceeds {@link NestworldTuning#REGION_ENTITY_BUDGET_NANOS}. An
     * unsplittable point hotspot (see RegionSplitManager's split guard) then
     * runs at reduced speed locally instead of dragging the lockstep tick of
     * the whole server. Entity ownership is maintained by
     * BoundaryEntityTransfer each tick.
     *
     * Note: ServerLevel's own entity-tick loop is patched (see
     * patches/minecraft/net/minecraft/server/level/ServerLevel.java.patch)
     * to skip the vanilla loop entirely while NestWorld manages the overworld —
     * preventing double-ticking.
     */
    private void tickEntities() {
        // Work deferred from a budget-cut round runs first if no work round
        // claimed it this tick (scheduled ticks precede entities in vanilla).
        long w0 = System.nanoTime();
        if (carriedWork != null) {
            runWorkBudgeted(null);
        }
        long carriedNanos = System.nanoTime() - w0;

        java.util.UUID[] ids = region.getOwnedEntityIds().toArray(new java.util.UUID[0]);
        // NestWorld: unconditional, unbudgeted cleanup pass BEFORE the budgeted
        // ticking loop below. A dead/removed UUID is only ever discovered (and
        // cleaned via region.removeEntity()) when the round-robin ticking window
        // happens to reach it -- bounded by REGION_ENTITY_BUDGET_NANOS, so under a
        // large owned-set a genuinely-dead entry can sit for many ticks before the
        // cursor cycles back to it (confirmed live: 16/100 killed entities still
        // stuck in ownedEntityIds after 300+ ticks in one measured case). Checking
        // isRemoved() is orders of magnitude cheaper than actually ticking an
        // entity, so doing it for the WHOLE set every tick (not just the budgeted
        // slice) is cheap even at thousands of owned entities, and guarantees
        // cleanup keeps pace with death rate regardless of round-robin budget
        // pressure.
        for (java.util.UUID id : ids) {
            Entity e = level.getEntity(id);
            if (e == null || e.isRemoved()) region.removeEntity(id);
        }
        ids = region.getOwnedEntityIds().toArray(new java.util.UUID[0]);
        int n = ids.length;
        if (n == 0) {
            lastTickedCount = 0;
            lastDeferredCount = 0;
            return;
        }
        // One shared per-tick budget: whatever this tick's work rounds (and
        // carried work just now) consumed comes out of the entity slice, with
        // a floor so entities always make progress.
        long remaining = Math.max(MIN_ENTITY_SLICE_NANOS,
                NestworldTuning.REGION_ENTITY_BUDGET_NANOS - workRoundNanos - carriedNanos);
        long deadline = System.nanoTime() + remaining;
        int start = entityCursor < n ? entityCursor : 0;
        int ticked = 0;
        int processed = 0;
        // Phase 2 (REGIONALIZED_TRACKER): this region tracks its own entities here, in the region
        // phase, instead of the serial main-thread ChunkMap.tick. Resolve the tracker + player list
        // + tick stamp once. Only the owning region touches a given entity's tracker state, and this
        // pass never overlaps main's tick()/move(), so no lock is needed.
        final boolean nestworldTrack = NestworldTuning.REGIONALIZED_TRACKER;
        final net.minecraft.server.level.ChunkMap nestworldChunkMap = nestworldTrack ? level.getChunkSource().chunkMap : null;
        final long nestworldTickNo = nestworldTrack ? level.getServer().getTickCount() : -1L;
        final java.util.List<net.minecraft.server.level.ServerPlayer> nestworldPlayers = nestworldTrack ? level.players() : null;
        while (processed < n) {
            int idx = start + processed;
            if (idx >= n) idx -= n;
            processed++;
            java.util.UUID uuid = ids[idx];
            Entity entity = level.getEntity(uuid);
            // NestWorld: a null/removed entity's UUID was never cleaned out of
            // ownedEntityIds -- only BoundaryEntityTransfer (border crossing) and
            // RegionSplitManager (split/merge) ever called removeEntity(); plain
            // death/discard/despawn never did. Confirmed as a real, unbounded leak
            // (not just a cosmetic /nestworld status display issue): ownedEntityIds
            // grew to 21000+ stale UUIDs on a live ATM9 region after a mass
            // falling-block kill, and this loop re-copies + re-looks-up every one of
            // them, every tick, forever, since the array is rebuilt from the live set
            // each call. isPassenger() is deliberately NOT cleaned up here -- a
            // passenger is still a live, owned entity, just skipped for direct
            // ticking this pass (its vehicle ticks it).
            if (entity == null || entity.isRemoved()) { region.removeEntity(uuid); continue; }
            if (entity.isPassenger()) continue;

            try {
                entity.checkDespawn();
                // NestWorld: checkDespawn() can itself remove the entity (natural
                // despawn) -- same cleanup as the null/isRemoved() check above.
                if (entity.isRemoved()) { region.removeEntity(uuid); continue; }
                // Mirror vanilla: only tick entities inside entity-ticking chunks,
                // otherwise idle mobs at the edge of loaded terrain burn CPU on AI.
                if (!level.isPositionEntityTicking(entity.blockPosition())) continue;
                level.tickNonPassenger(entity);
                ticked++;
                // Phase 2: track this owned entity now (region phase). Skip if the tick removed it.
                if (entity.isRemoved()) {
                    // NestWorld: the MOST common death path -- fall/fire/void/combat
                    // damage taken during the entity's own tick call. Same cleanup.
                    region.removeEntity(uuid);
                } else if (nestworldTrack) {
                    nestworldChunkMap.nestworldTrackOwned(entity, nestworldTickNo, nestworldPlayers);
                }
            } catch (Throwable t) {
                // Most of these are NPEs with a null message ("tick error: null"),
                // suspected ClassInstanceMultiMap section-content race. Full
                // stacks for the first few are the diagnostic; after that just
                // the class so the log stays readable.
                if (TICK_ERROR_STACKS.getAndIncrement() < 10) {
                    LOGGER.warn("[{}] Entity {} ({}) tick error (full stack)",
                            getName(), uuid, entity.getType().getDescriptionId(), t);
                } else {
                    LOGGER.warn("[{}] Entity {} ({}) tick error: {}: {}",
                            getName(), uuid, entity.getType().getDescriptionId(),
                            t.getClass().getSimpleName(), t.getMessage());
                }
                // Feed the auto-pin detector: a type that keeps throwing here
                // gets pinned to main (no-op unless -Dnestworld.autoPin=true).
                if (NestworldRegionSystem.isInitialised()) {
                    NestworldRegionSystem.get().getPins().noteEntityTickError(entity.getType());
                }
            }
            // Check the budget every 4 entities, not every 16: a single dense-pack
            // entity tick (movement collision among thousands) can cost ~2 ms, so a
            // 16-wide check window overshoots the budget by tens of ms — enough to
            // sink TPS under a deliberate mob crush. Tighter granularity keeps each
            // region near its REGION_ENTITY_BUDGET_NANOS so the lockstep floor holds.
            if ((processed & 3) == 0 && System.nanoTime() > deadline) break;
        }
        entityCursor = (start + processed) % n;
        lastTickedCount = ticked;
        lastDeferredCount = n - processed;
    }

    // Block entities are ticked by the vanilla path on the main thread
    // (the ServerLevel patch skips only the entity loop), which applies the
    // correct ticking-chunk gating for regions of any size.

    // -----------------------------------------------------------------------
    // Crash recovery
    // -----------------------------------------------------------------------

    private void handleCrash(Throwable t) {
        LOGGER.error("[{}] CRASH in region {} — starting emergency save", getName(), region, t);
        try {
            level.getChunkSource().save(false);
            LOGGER.info("[{}] Emergency save complete for {}", getName(), region);
        } catch (Throwable saveErr) {
            LOGGER.error("[{}] Emergency save failed for {}", getName(), region, saveErr);
        }
        running = false;
        // Pool monitors thread liveness and will spawn a replacement
    }
}
