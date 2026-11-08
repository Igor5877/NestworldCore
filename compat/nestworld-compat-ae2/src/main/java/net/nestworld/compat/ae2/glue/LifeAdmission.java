package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * AE2-PIN-LEAK-001 D1 (owner decisions 2026-10-30 (15)/(16); lock-order proof docs/compat/AE2-PIN-LEAK-001-D1-lock-proof.md):
 * per-host lifecycle admission for the removal/replacement of a PINNED AE2 host from a thread that is not the host's
 * execution domain. {@code LevelChunk.setBlockState} takes the host's {@code nw$life} BEFORE the section write and holds
 * it through {@code onRemove} and {@code setRemoved}, so main readiness (which holds the same lock for revalidation +
 * onReady) can never observe the block already written while the host is not yet removed.
 *
 * <p>Admitted only when ALL hold (otherwise unchanged, unlocked, as before): the existing block entity has the lifecycle
 * protocol ({@link NwAeBlockEntity}); the write removes/replaces it (vanilla's removal predicate); the current thread is
 * not the host's BE execution domain; the host type is pinned by the compatibility policy; the thread is not inside a
 * kernel section (order nw$life → T0 — refused, counted); the thread holds no other host's nw$life (one host only —
 * refused, counted). This is NOT a general region→main wait: the region thread waits only for one host's short lifecycle
 * critical section.
 *
 * <p>Invariants the proof depends on (keep true): main never takes a region chunk lock while it may hold an AE2 nw$life;
 * main never waits for a region round / mailbox reply from inside AE2 readiness or removal; kernel order nw$life → T0 →
 * domains; no two hosts' nw$life nested on one thread; a pinned host's block is written only by its chunk-owning region
 * thread or by main.
 */
public final class LifeAdmission {
    /** Admission switch (default on); fixture-mode negative control flips it at runtime. */
    public static volatile boolean ENABLED = !Boolean.getBoolean("nestworld.compatAe2.lifeAdmissionOff");

    static final long LONG_WAIT_NANOS = 50_000_000L, SECOND_WAIT_NANOS = 1_000_000_000L;

    /** Threads currently waiting for or holding a D1 admission (observable by the race detector, by name). */
    public static final AtomicInteger IN_ADMISSION = new AtomicInteger();
    public static final AtomicLong LAST_END_NANOS = new AtomicLong();

    /** nw$life acquisitions held by the current thread (readiness, removal, D1) — the one-host-only contract. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** Wait histogram: bucket b counts waits in [2^b, 2^(b+1)) ns (b = 0..47); uncontended admissions are not recorded. */
    private static final AtomicLongArray WAIT_BUCKETS = new AtomicLongArray(48);
    /** Hold histogram: how long an admitted thread OWNED nw$life (= how long MAIN could be excluded), same buckets. */
    private static final AtomicLongArray HOLD_BUCKETS = new AtomicLongArray(48);

    /** Fixture-only hook after the section write, before onRemove (null in production). */
    public interface SectionHook {
        void afterSectionWrite(BlockPos pos);
    }

    public static volatile SectionHook sectionHook;

    private LifeAdmission() {
    }

    public static void enterLife() {
        DEPTH.get()[0]++;
    }

    public static void exitLife() {
        DEPTH.get()[0]--;
    }

    /** Around {@code LevelChunk.setBlockState(pos, newState, isMoving)}. */
    public static BlockState around(LevelChunk chunk, BlockPos pos, BlockState newState, Supplier<BlockState> op) {
        if (!ENABLED || !(chunk.getLevel() instanceof ServerLevel level)) return op.get();
        BlockEntity existing = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (!(existing instanceof NwAeBlockEntity life)) return op.get();
        BlockState old = chunk.getBlockState(pos);
        // vanilla removal predicate (LevelChunk.setBlockState / BlockBehaviour.onRemove): block changes, or the new
        // state carries no block entity
        if (old.is(newState.getBlock()) && newState.hasBlockEntity()) return op.get();
        String type = BeDomainReady.typeOf(existing);
        if (BeDomainReady.currentThreadIn(level, pos, type) || !BeDomainReady.pinnedByPolicy(type)) return op.get();
        if (Ae2Compat.KERNEL.inSection()) {
            COUNTERS.lifeAdmissionSkippedInSection.increment();
            warnSkipped("inSection", level, pos, type, life, newState);
            return op.get();
        }
        if (DEPTH.get()[0] > 0) {
            COUNTERS.lifeAdmissionSkippedNested.increment();
            warnSkipped("nested(depth=" + DEPTH.get()[0] + ")", level, pos, type, life, newState);
            return op.get();
        }
        COUNTERS.lifeAdmissionAttempts.increment();
        ReentrantLock lock = life.nw$life();
        IN_ADMISSION.incrementAndGet();
        boolean locked = false;
        try {
            if (lock.tryLock()) {
                locked = true;
            } else {
                COUNTERS.lifeAdmissionContended.increment();
                long t0 = System.nanoTime();
                lock.lock();
                locked = true;
                long waited = System.nanoTime() - t0;
                COUNTERS.lifeAdmissionWaitNanos.add(waited);
                COUNTERS.lifeAdmissionMaxWaitNanos.accumulate(waited);
                WAIT_BUCKETS.incrementAndGet(Math.min(47, 63 - Long.numberOfLeadingZeros(Math.max(1, waited))));
                if (waited >= LONG_WAIT_NANOS) COUNTERS.lifeAdmissionLongWaits.increment();
                if (waited >= SECOND_WAIT_NANOS) {
                    COUNTERS.lifeAdmissionSecondWaits.increment();
                    Ae2Compat.LOG.warn("[nestworld-compat-ae2] D1 admission for {} at {} waited {} ms on {}",
                            type, pos.toShortString(), waited / 1_000_000, Thread.currentThread().getName());
                }
            }
            enterLife();
            long held = System.nanoTime();
            try {
                BlockState r = op.get();
                COUNTERS.lifeAdmissionCompleted.increment();
                return r;
            } finally {
                exitLife();
                recordHold(System.nanoTime() - held, type, pos);
            }
        } finally {
            if (locked) lock.unlock();
            IN_ADMISSION.decrementAndGet();
            LAST_END_NANOS.set(System.nanoTime());
        }
    }

    /** Rate limit for the skipped-admission WARN: first {@link #SKIP_WARN_BURST} with stack, then one per minute. */
    static final int SKIP_WARN_BURST = 5;
    static final long SKIP_WARN_INTERVAL_NANOS = 60_000_000_000L;
    private static final AtomicInteger SKIP_WARNS = new AtomicInteger();
    private static final AtomicLong SKIP_WARN_LAST = new AtomicLong(Long.MIN_VALUE / 2);

    /**
     * Owner contract 2026-11-09 (closure gate): an unexpected nested / in-section removal keeps the existing fallback
     * (unlocked, as before — no gameplay change) but leaves full attribution. Any non-zero skipped counter FAILS the
     * closure / production gate.
     */
    private static void warnSkipped(String reason, ServerLevel level, BlockPos pos, String type, NwAeBlockEntity life,
            BlockState newState) {
        long now = System.nanoTime();
        int n = SKIP_WARNS.incrementAndGet();
        if (n > SKIP_WARN_BURST) {
            long last = SKIP_WARN_LAST.get();
            if (now - last < SKIP_WARN_INTERVAL_NANOS || !SKIP_WARN_LAST.compareAndSet(last, now)) return;
        } else {
            SKIP_WARN_LAST.set(now);
        }
        String msg = "[nestworld-compat-ae2] D1_ADMISSION_SKIPPED reason=" + reason + " type=" + type + " dim="
                + level.dimension().location() + " pos=" + pos.toShortString() + " newState=" + newState + " cbGen="
                + life.nw$cbGen() + " removed=" + life.nw$removed() + " thread=" + Thread.currentThread().getName()
                + " hostDomain=" + BeDomainReady.domainName(level, pos, type) + " skippedInSection="
                + COUNTERS.lifeAdmissionSkippedInSection.sum() + " skippedNested=" + COUNTERS.lifeAdmissionSkippedNested.sum()
                + " (closure gate: non-zero = FAIL)";
        if (n <= SKIP_WARN_BURST) Ae2Compat.LOG.warn(msg, new Throwable("D1 admission skipped here"));
        else Ae2Compat.LOG.warn(msg + " (rate-limited, no stack)");
    }

    private static void recordHold(long held, String type, BlockPos pos) {
        COUNTERS.lifeAdmissionHoldNanos.add(held);
        COUNTERS.lifeAdmissionHoldMaxNanos.accumulate(held);
        HOLD_BUCKETS.incrementAndGet(Math.min(47, 63 - Long.numberOfLeadingZeros(Math.max(1, held))));
        if (held >= LONG_WAIT_NANOS) COUNTERS.lifeAdmissionHoldOver50ms.increment();
        if (held >= SECOND_WAIT_NANOS) {
            COUNTERS.lifeAdmissionHoldOver1s.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] D1 admission for {} at {} held nw$life {} ms on {}",
                    type, pos.toShortString(), held / 1_000_000, Thread.currentThread().getName());
        }
    }

    /** Fixture point after the section write (inside setBlockState, before onRemove). */
    public static void afterSectionWrite(BlockPos pos) {
        SectionHook h = sectionHook;
        if (h != null) h.afterSectionWrite(pos);
    }

    /** p-th percentile upper bound (ns) of contended waits, from the histogram; -1 when no contended wait. */
    public static long percentileNanos(double p) {
        return percentile(WAIT_BUCKETS, p);
    }

    public static long holdPercentileNanos(double p) {
        return percentile(HOLD_BUCKETS, p);
    }

    private static long percentile(AtomicLongArray buckets, double p) {
        long total = 0;
        for (int b = 0; b < buckets.length(); b++) total += buckets.get(b);
        if (total == 0) return -1;
        long rank = (long) Math.ceil(p * total), seen = 0;
        for (int b = 0; b < buckets.length(); b++) {
            seen += buckets.get(b);
            if (seen >= rank) return 1L << (b + 1);
        }
        return Long.MAX_VALUE;
    }

    public static String summary() {
        return "lifeAdmission{enabled=" + ENABLED + " attempts=" + COUNTERS.lifeAdmissionAttempts.sum() + " contended="
                + COUNTERS.lifeAdmissionContended.sum() + " completed=" + COUNTERS.lifeAdmissionCompleted.sum()
                + " totalWaitMs=" + COUNTERS.lifeAdmissionWaitNanos.sum() / 1_000_000 + " maxWaitMs="
                + COUNTERS.lifeAdmissionMaxWaitNanos.get() / 1_000_000 + " maxWait=" + us(COUNTERS.lifeAdmissionMaxWaitNanos.get())
                + " wait p50<=" + us(percentileNanos(0.50)) + " p95<="
                + us(percentileNanos(0.95)) + " p99<=" + us(percentileNanos(0.99)) + " longWaits(>=50ms)="
                + COUNTERS.lifeAdmissionLongWaits.sum() + " secondWaits(>=1s)=" + COUNTERS.lifeAdmissionSecondWaits.sum()
                + " hold{totalMs=" + COUNTERS.lifeAdmissionHoldNanos.sum() / 1_000_000 + " maxMs="
                + COUNTERS.lifeAdmissionHoldMaxNanos.get() / 1_000_000 + " p50<=" + us(holdPercentileNanos(0.50)) + " p95<="
                + us(holdPercentileNanos(0.95)) + " p99<=" + us(holdPercentileNanos(0.99)) + " over50ms="
                + COUNTERS.lifeAdmissionHoldOver50ms.sum() + " over1s=" + COUNTERS.lifeAdmissionHoldOver1s.sum() + "}"
                + " skippedInSection=" + COUNTERS.lifeAdmissionSkippedInSection.sum() + " skippedNested="
                + COUNTERS.lifeAdmissionSkippedNested.sum() + " windowObserved=" + COUNTERS.lifeWindowObserved.sum() + "}";
    }

    private static String us(long ns) {
        return ns < 0 ? "-" : (ns / 1000) + "us";
    }
}
