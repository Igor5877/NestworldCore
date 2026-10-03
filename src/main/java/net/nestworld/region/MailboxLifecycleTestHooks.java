package net.nestworld.region;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CORE-MAILBOX-MERGE-001 TEST-ONLY interleaving control for {@link MailboxLifecycleTests}. Same
 * shape as {@link EntityMutationTestHooks}: every production call site is a single volatile read
 * of {@link #ENABLED} when disarmed.
 *
 * <ul>
 *   <li>{@link #afterAdd}: pauses a producer between {@code mailbox.add} and the RETIRED re-check
 *       of the C4 safe enqueue (proves the {@code remove()==false} branch, L2b).</li>
 *   <li>{@link #beforeWorkerLock}: pauses a region worker right before it takes its region's write
 *       lock in {@code runOneTick} (proves C3: after retirement it must execute nothing, L2c).</li>
 *   <li>{@link #forcedJoinTimeout}: makes the retirement join report a timeout (L2c).</li>
 *   <li>{@link #backpressureThreshold}: lowers the mailbox backpressure threshold so the sender-side
 *       pending queue can be exercised deterministically (L3/L4).</li>
 * </ul>
 */
final class MailboxLifecycleTestHooks {
    private MailboxLifecycleTestHooks() {}

    static volatile boolean ENABLED = false;

    private static volatile long afterAddAuditId = Long.MIN_VALUE;
    private static volatile CountDownLatch afterAddHit, afterAddRelease;
    private static volatile int workerLockRegionId = Integer.MIN_VALUE;
    private static volatile CountDownLatch workerHit, workerRelease;
    private static volatile boolean forceJoinTimeout = false;
    private static volatile int thresholdOverride = -1;

    // --- production call sites ---

    static void afterAdd(RegionMessage<?> message) {
        if (!ENABLED || message.auditId() != afterAddAuditId) return;
        await(afterAddHit, afterAddRelease);
    }

    /** Called only for ENTITY rounds ({@code work == null}) -- the rounds that consume the mailbox. */
    static void beforeWorkerLock(WorldRegion region) {
        if (!ENABLED || region.getId() != workerLockRegionId) return;
        workerLockRegionId = Integer.MIN_VALUE; // one-shot
        await(workerHit, workerRelease);
    }

    /** CORE-PARKED-REGION-001 P3/P4: pauses a region worker at the top of its run() loop iteration,
     *  BEFORE it checks free-running vs barrier mode -- so a test can post work and flip the mode with
     *  no drain in between, and the worker then takes the post-demotion branch. One-shot. */
    static void beforeLoopIteration(WorldRegion region) {
        if (!ENABLED || region.getId() != loopRegionId) return;
        loopRegionId = Integer.MIN_VALUE;
        await(loopHit, loopRelease);
    }

    private static volatile int loopRegionId = Integer.MIN_VALUE;
    private static volatile CountDownLatch loopHit, loopRelease;

    static void armLoop(int regionId) {
        loopHit = new CountDownLatch(1);
        loopRelease = new CountDownLatch(1);
        loopRegionId = regionId;
    }

    static boolean awaitLoopHit(long seconds) throws InterruptedException {
        return loopHit.await(seconds, TimeUnit.SECONDS);
    }

    static void releaseLoop() {
        loopRelease.countDown();
    }

    static boolean forcedJoinTimeout() {
        return ENABLED && forceJoinTimeout;
    }

    static int backpressureThreshold() {
        if (ENABLED) {
            int o = thresholdOverride;
            if (o >= 0) return o;
        }
        return NestworldTuning.MAILBOX_BACKPRESSURE_THRESHOLD;
    }

    private static void await(CountDownLatch hit, CountDownLatch release) {
        if (hit == null || release == null) return;
        hit.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- test-side arming API ---

    static void armAfterAdd(long auditId) {
        afterAddHit = new CountDownLatch(1);
        afterAddRelease = new CountDownLatch(1);
        afterAddAuditId = auditId;
    }

    static boolean awaitAfterAddHit(long seconds) throws InterruptedException {
        return afterAddHit.await(seconds, TimeUnit.SECONDS);
    }

    static void releaseAfterAdd() {
        afterAddRelease.countDown();
    }

    static void armWorkerLock(int regionId) {
        workerHit = new CountDownLatch(1);
        workerRelease = new CountDownLatch(1);
        workerLockRegionId = regionId;
    }

    static boolean awaitWorkerHit(long seconds) throws InterruptedException {
        return workerHit.await(seconds, TimeUnit.SECONDS);
    }

    static void releaseWorker() {
        workerRelease.countDown();
    }

    static void setForceJoinTimeout(boolean v) {
        forceJoinTimeout = v;
    }

    static void setThresholdOverride(int v) {
        thresholdOverride = v;
    }

    // --- outcome observation (fixtures assert "exactly one observable outcome") ---

    /** Observed outcomes of one tracked message (by audit id) or one tracked entity mutation
     *  (by opId -- a reroute is a NEW message carrying the same opId). */
    static final class Outcome {
        final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger terminals = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger orphaned = new java.util.concurrent.atomic.AtomicInteger();
        /** Applied while executing on a RETIRED region -- the wrong-owner mutation this fix must prevent. */
        final java.util.concurrent.atomic.AtomicInteger appliedOnRetired = new java.util.concurrent.atomic.AtomicInteger();
        volatile int appliedRegionId = Integer.MIN_VALUE;
        volatile String terminalKind = null;

        int outcomes() { return applied.get() + terminals.get(); }

        @Override public String toString() {
            return "applied=" + applied.get() + (applied.get() > 0 ? "@r" + appliedRegionId : "")
                    + " terminals=" + terminals.get() + (terminalKind != null ? "(" + terminalKind + ")" : "")
                    + " orphaned=" + orphaned.get() + " appliedOnRetired=" + appliedOnRetired.get();
        }
    }

    static final java.util.Map<Long, Outcome> BY_AUDIT = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<Long, Outcome> BY_OPID = new java.util.concurrent.ConcurrentHashMap<>();

    static Outcome trackAudit(long auditId) { return BY_AUDIT.computeIfAbsent(auditId, k -> new Outcome()); }
    static Outcome trackOp(long opId) { return BY_OPID.computeIfAbsent(opId, k -> new Outcome()); }

    /** MailboxAudit.recordApplied -- the single apply-site call every consumer already makes. */
    static void onAuditApplied(long auditId, WorldRegion at) {
        if (!ENABLED) return;
        Outcome o = BY_AUDIT.get(auditId);
        if (o == null) return;
        o.applied.incrementAndGet();
        o.appliedRegionId = at.getId();
        if (at.nestworldIsRetired()) o.appliedOnRetired.incrementAndGet();
    }

    static void onAuditTerminal(long auditId, String kind) {
        if (!ENABLED) return;
        Outcome o = BY_AUDIT.get(auditId);
        if (o == null) return;
        o.terminals.incrementAndGet();
        o.terminalKind = kind;
    }

    /** EntityMutationDispatcher.applyQueued APPLY branch (a real mutation, not a reroute). */
    static void onEntityApplied(long opId, WorldRegion at) {
        if (!ENABLED) return;
        Outcome o = BY_OPID.get(opId);
        if (o == null) return;
        o.applied.incrementAndGet();
        o.appliedRegionId = at.getId();
        if (at.nestworldIsRetired()) o.appliedOnRetired.incrementAndGet();
    }

    static void onEntityTerminal(long opId, String kind) {
        if (!ENABLED) return;
        Outcome o = BY_OPID.get(opId);
        if (o == null) return;
        o.terminals.incrementAndGet();
        o.terminalKind = kind;
    }

    static void onOrphan(RegionMessage<?> m) {
        if (!ENABLED) return;
        Outcome o = BY_AUDIT.get(m.auditId());
        if (o != null) o.orphaned.incrementAndGet();
        if (m.payload() instanceof RegionMessage.EntityMutate em) {
            Outcome p = BY_OPID.get(em.opId());
            if (p != null) p.orphaned.incrementAndGet();
        }
    }

    static void disarmAll() {
        afterAddAuditId = Long.MIN_VALUE;
        if (afterAddRelease != null) afterAddRelease.countDown();
        afterAddHit = afterAddRelease = null;
        workerLockRegionId = Integer.MIN_VALUE;
        if (workerRelease != null) workerRelease.countDown();
        workerHit = workerRelease = null;
        loopRegionId = Integer.MIN_VALUE;
        if (loopRelease != null) loopRelease.countDown();
        loopHit = loopRelease = null;
        forceJoinTimeout = false;
        thresholdOverride = -1;
        BY_AUDIT.clear();
        BY_OPID.clear();
    }
}
