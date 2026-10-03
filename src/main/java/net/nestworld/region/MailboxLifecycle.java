package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CORE-MAILBOX-MERGE-001 (docs/contracts/CORE-MAILBOX-MERGE-001-fix-spec.md): region mailbox
 * lifecycle state that outlives any single {@link WorldRegion} — the per-dimension ORPHAN queue,
 * the lifecycle counters, and the bounded registry of recently retired regions the C10
 * emptiness invariant is checked against.
 *
 * <p>An ORPHAN is transport state, not a failure: a message whose previously resolved
 * destination is no longer authoritative (that region was RETIRED by split/merge), and which
 * therefore needs owner re-resolution ({@link MailboxRetargeting}). It is neither failed nor
 * dropped; its outcome is decided by re-resolution — redelivery to the current owner, or an
 * explicit terminal.
 *
 * <p>Static, like {@link MailboxAudit}: keyed by dimension, reset on server stop via {@link
 * #resetAll}. One MinecraftServer per JVM is assumed here exactly as everywhere else in the
 * region package (multi-server JVM is an open, separate API-1B item).
 */
public final class MailboxLifecycle {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/MailboxLifecycle");

    private MailboxLifecycle() {}

    /** Why a message entered the orphan queue. */
    enum OrphanReason {
        /** Accepted by the old destination, then moved out by that destination's retirement drain. */
        DRAINED_ON_RETIRE,
        /** Enqueue attempted on an already-RETIRED destination (C4 first check, or post-add re-check
         *  with a successful remove) — never accepted by the dead region. */
        REJECTED_RETIRED,
        /** A sender-side backpressure-pending entry whose destination was retired before flush. */
        PENDING_DESTINATION_RETIRED,
        /** A sender-side backpressure-pending entry held by a region that was itself retired. */
        PENDING_SENDER_RETIRED
    }

    /** Explicit terminal dispositions decided during re-resolution. Never silent: each is counted,
     *  recorded in {@link MailboxAudit}, and (for ENTITY_MUTATE) traced. */
    enum Terminal {
        /** The message's target (entity/player) no longer exists, or no region covers its position. */
        TARGET_NOT_FOUND,
        /** Redelivered {@link NestworldTuning#MAILBOX_MAX_REDELIVERIES} times without being consumed. */
        REDELIVERY_LIMIT_DROPPED,
        /** ENTITY_TRANSFER whose entity is ALREADY owned by a live region at redelivery time — adding
         *  it again would create a second owner. Decided by checking the actual ownership state, not
         *  by assuming a later rescan. */
        TRANSFER_ALREADY_OWNED,
        /** EXPLOSION_APPLY batch whose positions now belong to several regions: the parent is closed
         *  and one child batch per owner carries the positions on (each child individually audited). */
        SPLIT_INTO_CHILDREN,
        /** No dimension region exists any more to resolve against (dimension shut down). */
        DIMENSION_GONE
    }

    // --- counters (all monotonic; reset only between independent test runs) ---
    static final AtomicLong retiredRegions = new AtomicLong();
    static final AtomicLong drainedRegions = new AtomicLong();
    static final AtomicLong drainedOnRetire = new AtomicLong();
    static final AtomicLong rejectedRetired = new AtomicLong();
    static final AtomicLong pendingDestinationRetired = new AtomicLong();
    static final AtomicLong pendingSenderRetired = new AtomicLong();
    /** C4 last branch: re-check saw RETIRED, {@code remove()} failed, enqueue reported ACCEPTED
     *  (the message was taken by the retirement drain or a pre-retirement consumer). */
    static final AtomicLong acceptedRemoveFailed = new AtomicLong();
    static final AtomicLong orphaned = new AtomicLong();
    static final AtomicLong redelivered = new AtomicLong();
    /** C3: an old worker acquired its region's lock after retirement and executed nothing. */
    static final AtomicLong workerSkippedRetired = new AtomicLong();
    static final AtomicLong workerExitedInTime = new AtomicLong();
    static final AtomicLong workerLateExit = new AtomicLong();
    static final AtomicLong retirementFailed = new AtomicLong();
    static final AtomicLong explosionChildBatches = new AtomicLong();
    static final Map<Terminal, AtomicLong> terminals = new ConcurrentHashMap<>();
    static {
        for (Terminal t : Terminal.values()) terminals.put(t, new AtomicLong());
    }

    // --- orphan queues ---

    /** One orphan: the message as last addressed, plus why and when it was orphaned. */
    record Orphan(RegionMessage<?> message, OrphanReason reason, int fromRegionId) {}

    private static final Object NO_DIMENSION = new Object();
    private static final Map<Object, Queue<Orphan>> ORPHANS = new ConcurrentHashMap<>();

    private static Object key(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        return dim != null ? dim : NO_DIMENSION;
    }

    /** Hands a message to its dimension's orphan queue. Safe from any thread. */
    static void orphan(WorldRegion from, RegionMessage<?> message, OrphanReason reason) {
        ORPHANS.computeIfAbsent(key(from.getDimension()), k -> new ConcurrentLinkedQueue<>())
                .add(new Orphan(message, reason, from.getId()));
        orphaned.incrementAndGet();
        switch (reason) {
            case DRAINED_ON_RETIRE -> drainedOnRetire.incrementAndGet();
            case REJECTED_RETIRED -> rejectedRetired.incrementAndGet();
            case PENDING_DESTINATION_RETIRED -> pendingDestinationRetired.incrementAndGet();
            case PENDING_SENDER_RETIRED -> pendingSenderRetired.incrementAndGet();
        }
        MailboxAudit.recordOrphaned(message.auditId());
        MailboxLifecycleTestHooks.onOrphan(message);
    }

    static Queue<Orphan> orphanQueue(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        return ORPHANS.computeIfAbsent(key(dim), k -> new ConcurrentLinkedQueue<>());
    }

    static int orphanCount() {
        int n = 0;
        for (Queue<Orphan> q : ORPHANS.values()) n += q.size();
        return n;
    }

    static void forEachOrphanAuditId(java.util.function.LongConsumer c) {
        for (Queue<Orphan> q : ORPHANS.values()) for (Orphan o : q) c.accept(o.message().auditId());
    }

    static void terminal(Terminal t) {
        terminals.get(t).incrementAndGet();
    }

    // --- retired-region registry (C10 sweep) + worker exit watch (C3 join policy) ---

    private static final int RETIRED_REGISTRY_CAP = 1024;
    private static final ArrayDeque<WorldRegion> RECENTLY_RETIRED = new ArrayDeque<>();

    private record ExitWatch(RegionThread thread, int regionId, long deadlineNanos) {}
    private static final ConcurrentLinkedQueue<ExitWatch> EXIT_WATCH = new ConcurrentLinkedQueue<>();

    static void noteRetired(WorldRegion region) {
        retiredRegions.incrementAndGet();
        synchronized (RECENTLY_RETIRED) {
            RECENTLY_RETIRED.addLast(region);
            while (RECENTLY_RETIRED.size() > RETIRED_REGISTRY_CAP) RECENTLY_RETIRED.removeFirst();
        }
    }

    static List<WorldRegion> recentlyRetired() {
        synchronized (RECENTLY_RETIRED) {
            return new ArrayList<>(RECENTLY_RETIRED);
        }
    }

    /**
     * C3 join policy. Called by the retiring (main) thread AFTER it released the region locks —
     * a join while holding them could deadlock with an old worker blocked in {@code writeLock()}.
     * Correctness of the retirement drain does NOT rest on this join: it rests on lock exclusion
     * plus the worker's post-lock RETIRED check ({@code RegionThread.runOneTick}). The join only
     * proves the worker actually terminated; a worker that has not exited within
     * {@link NestworldTuning#RETIRE_JOIN_TIMEOUT_MS} is watched, and if still alive after
     * {@link NestworldTuning#RETIRE_EXIT_DEADLINE_MS} is reported as RETIREMENT_FAILED (the region
     * stays RETIRED; it is never re-activated).
     */
    static void awaitWorkerExit(List<RegionThread> removed) {
        for (RegionThread t : removed) {
            long timeout = MailboxLifecycleTestHooks.forcedJoinTimeout() ? 0 : NestworldTuning.RETIRE_JOIN_TIMEOUT_MS;
            if (timeout > 0) {
                try {
                    t.join(timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!t.isAlive() && !MailboxLifecycleTestHooks.forcedJoinTimeout()) {
                workerExitedInTime.incrementAndGet();
            } else {
                EXIT_WATCH.add(new ExitWatch(t, t.getRegion().getId(),
                        System.nanoTime() + NestworldTuning.RETIRE_EXIT_DEADLINE_MS * 1_000_000L));
            }
        }
    }

    /** Main thread, once per dimension tick: resolves pending exit watches. */
    static void pollExitWatches() {
        if (EXIT_WATCH.isEmpty()) return;
        long now = System.nanoTime();
        for (java.util.Iterator<ExitWatch> it = EXIT_WATCH.iterator(); it.hasNext(); ) {
            ExitWatch w = it.next();
            if (!w.thread().isAlive()) {
                it.remove();
                workerLateExit.incrementAndGet();
            } else if (now >= w.deadlineNanos()) {
                it.remove();
                retirementFailed.incrementAndGet();
                LOGGER.warn("RETIREMENT_FAILED: worker {} of retired region {} still alive {}ms after retirement. "
                        + "Region stays RETIRED (never re-activated); its mailbox handoff already happened under "
                        + "lock exclusion, so no queued message depends on this worker.",
                        w.thread().getName(), w.regionId(), NestworldTuning.RETIRE_EXIT_DEADLINE_MS);
            }
        }
    }

    static int pendingExitWatches() {
        return EXIT_WATCH.size();
    }

    /**
     * C10 sweep over the bounded registry of recently retired regions. A violation is a DRAINED
     * region whose depth counter or actual queues are non-empty — which, since the retirement drain
     * is the last consumer a retired region ever has, is exactly an "accepted after RETIRED" message.
     * Meaningful at quiescence (a producer mid-way through the C4 add/re-check/remove sequence is
     * transiently visible).
     */
    static String c10Sweep() {
        int checked = 0, violations = 0;
        long residualDepth = 0, residualMailbox = 0, residualPending = 0;
        StringBuilder detail = new StringBuilder();
        for (WorldRegion r : recentlyRetired()) {
            checked++;
            int depth = r.nestworldMailboxDepthFast();
            int mailbox = r.nestworldMailboxSize();
            int pending = r.nestworldOutboundPendingSize();
            if (!r.nestworldIsDrained() || depth != 0 || mailbox != 0 || pending != 0
                    || r.nestworldRegionTickInboundCount() != 0 || r.nestworldOutboundPendingCount() != 0) {
                violations++;
                residualDepth += depth;
                residualMailbox += mailbox;
                residualPending += pending;
                if (detail.length() < 400) {
                    detail.append(" r").append(r.getId()).append("{drained=").append(r.nestworldIsDrained())
                            .append(",depth=").append(depth).append(",mailbox=").append(mailbox)
                            .append(",pending=").append(pending).append(",inboundCounter=").append(r.nestworldRegionTickInboundCount())
                            .append(",pendingCounter=").append(r.nestworldOutboundPendingCount()).append('}');
                }
            }
        }
        return "C10 retiredChecked=" + checked + " violations=" + violations
                + " residualDepth=" + residualDepth + " residualMailbox=" + residualMailbox
                + " residualPending=" + residualPending + detail;
    }

    static boolean c10Holds() {
        for (WorldRegion r : recentlyRetired()) {
            if (!r.nestworldIsDrained() || r.nestworldMailboxDepthFast() != 0
                    || r.nestworldMailboxSize() != 0 || r.nestworldOutboundPendingSize() != 0
                    || r.nestworldRegionTickInboundCount() != 0 || r.nestworldOutboundPendingCount() != 0) {
                return false;
            }
        }
        return true;
    }

    /** Every audit id currently present in live transport: live regions' mailboxes and pending
     *  queues in every managed dimension, plus all orphan queues. Diagnostic walk (main thread,
     *  meaningful at quiescence) — the "still reachable" term of the C11 accounting invariant. */
    static java.util.Set<Long> reachableAuditIds() {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        if (NestworldRegionSystem.isInitialised()) {
            for (NestworldDimensionRegion dr : NestworldRegionSystem.get().allDimensionRegions()) {
                for (WorldRegion r : dr.getGrid().getAllRegions()) r.nestworldForEachQueuedAuditId(ids::add);
            }
        }
        forEachOrphanAuditId(ids::add);
        return ids;
    }

    /**
     * CORE-PARKED-REGION-001 evidence: every live region that still has queued transport work, with
     * the inputs of {@code RegionThreadPool.tickAllRegions}' dispatch rule (a non-free-running
     * region is dispatched only if it owns entities or carries deferred work). Read-only.
     */
    static String queuedRegionsReport() {
        if (!NestworldRegionSystem.isInitialised()) return "queued-regions: system not initialised";
        StringBuilder sb = new StringBuilder("queued-regions:");
        int n = 0;
        for (NestworldDimensionRegion dr : NestworldRegionSystem.get().allDimensionRegions()) {
            for (WorldRegion r : dr.getGrid().getAllRegions()) {
                int depth = r.nestworldMailboxSize(), pend = r.nestworldOutboundPendingSize();
                if (depth == 0 && pend == 0) continue;
                n++;
                RegionThread t = r.owningThread;
                int deferred = t == null ? -1 : t.getLastWorkDeferred();
                int owned = r.getOwnedEntityIds().size();
                boolean selfTicking = NestworldTuning.FREE_RUNNING_REGIONS_ENABLED && r.isFreeRunning();
                boolean eligible = selfTicking || r.hasRunnableWork();
                long age = r.nestworldOldestQueuedAgeNanos();
                sb.append(" r").append(r.getId()).append("{mailbox=").append(depth).append(",pending=").append(pend)
                        .append(",ownedEntities=").append(owned).append(",deferredWork=").append(deferred)
                        .append(",inboundCounter=").append(r.nestworldRegionTickInboundCount())
                        .append(",pendingCounter=").append(r.nestworldOutboundPendingCount())
                        .append(",freeRunning=").append(r.isFreeRunning()).append(",dispatchEligible=").append(eligible)
                        .append(",oldestAgeMs=").append(age < 0 ? -1 : age / 1_000_000).append('}');
            }
        }
        return n == 0 ? "queued-regions: none" : sb.toString();
    }

    /** CORE-PARKED-REGION-001: counter/queue consistency over every LIVE region (exact at
     *  quiescence). "consistent" or the list of mismatching regions. */
    static String counterConsistencySweep() {
        if (!NestworldRegionSystem.isInitialised()) return "counters: system not initialised";
        int checked = 0;
        StringBuilder bad = new StringBuilder();
        for (NestworldDimensionRegion dr : NestworldRegionSystem.get().allDimensionRegions()) {
            for (WorldRegion r : dr.getGrid().getAllRegions()) {
                checked++;
                String m = r.nestworldCounterInconsistency();
                if (m != null) bad.append(' ').append(m);
            }
        }
        return "counters liveRegionsChecked=" + checked + (bad.length() == 0 ? " consistent" : " MISMATCH" + bad);
    }

    /** Lifecycle counters + C10 sweep + C11 accounting, one block (diagnostic command output). */
    static String fullReport() {
        long entityLess = 0;
        if (NestworldRegionSystem.isInitialised()) {
            for (NestworldDimensionRegion dr : NestworldRegionSystem.get().allDimensionRegions()) {
                entityLess += dr.getPool().nestworldDispatchesWithoutEntities();
            }
        }
        return "MailboxLifecycle " + summary() + "\n" + queuedRegionsReport() + "\n" + counterConsistencySweep()
                + " entityLessDispatches=" + entityLess + "\n" + c10Sweep() + "\n"
                + MailboxAudit.accountingReport(reachableAuditIds());
    }

    static String summary() {
        StringBuilder t = new StringBuilder();
        for (Terminal k : Terminal.values()) {
            long v = terminals.get(k).get();
            if (v != 0) t.append(' ').append(k).append('=').append(v);
        }
        return "retired=" + retiredRegions.get() + " drained=" + drainedRegions.get()
                + " drainedOnRetire=" + drainedOnRetire.get() + " rejectedRetired=" + rejectedRetired.get()
                + " pendingDestRetired=" + pendingDestinationRetired.get() + " pendingSenderRetired=" + pendingSenderRetired.get()
                + " acceptedRemoveFailed=" + acceptedRemoveFailed.get()
                + " orphaned=" + orphaned.get() + " redelivered=" + redelivered.get() + " orphansNow=" + orphanCount()
                + " explosionChildBatches=" + explosionChildBatches.get()
                + " workerSkippedRetired=" + workerSkippedRetired.get() + " workerExitedInTime=" + workerExitedInTime.get()
                + " workerLateExit=" + workerLateExit.get() + " exitWatches=" + pendingExitWatches()
                + " retirementFailed=" + retirementFailed.get()
                + " terminals{" + (t.length() == 0 ? "" : t.substring(1)) + "}";
    }

    /** Clears counters (NOT the orphan queues: an orphan is live transport state). */
    static void resetCounters() {
        for (AtomicLong a : List.of(retiredRegions, drainedRegions, drainedOnRetire, rejectedRetired,
                pendingDestinationRetired, pendingSenderRetired, acceptedRemoveFailed, orphaned, redelivered,
                workerSkippedRetired, workerExitedInTime, workerLateExit, retirementFailed, explosionChildBatches)) {
            a.set(0);
        }
        terminals.values().forEach(a -> a.set(0));
    }

    /** Server stop: drops everything, including orphans of a world that no longer exists. */
    static void resetAll() {
        resetCounters();
        ORPHANS.clear();
        EXIT_WATCH.clear();
        synchronized (RECENTLY_RETIRED) {
            RECENTLY_RETIRED.clear();
        }
    }
}
