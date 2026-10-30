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
            return op.get();
        }
        if (DEPTH.get()[0] > 0) {
            COUNTERS.lifeAdmissionSkippedNested.increment();
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
            try {
                BlockState r = op.get();
                COUNTERS.lifeAdmissionCompleted.increment();
                return r;
            } finally {
                exitLife();
            }
        } finally {
            if (locked) lock.unlock();
            IN_ADMISSION.decrementAndGet();
            LAST_END_NANOS.set(System.nanoTime());
        }
    }

    /** Fixture point after the section write (inside setBlockState, before onRemove). */
    public static void afterSectionWrite(BlockPos pos) {
        SectionHook h = sectionHook;
        if (h != null) h.afterSectionWrite(pos);
    }

    /** p-th percentile upper bound (ns) of contended waits, from the histogram; -1 when no contended wait. */
    public static long percentileNanos(double p) {
        long total = 0;
        for (int b = 0; b < WAIT_BUCKETS.length(); b++) total += WAIT_BUCKETS.get(b);
        if (total == 0) return -1;
        long rank = (long) Math.ceil(p * total), seen = 0;
        for (int b = 0; b < WAIT_BUCKETS.length(); b++) {
            seen += WAIT_BUCKETS.get(b);
            if (seen >= rank) return 1L << (b + 1);
        }
        return Long.MAX_VALUE;
    }

    public static String summary() {
        return "lifeAdmission{enabled=" + ENABLED + " attempts=" + COUNTERS.lifeAdmissionAttempts.sum() + " contended="
                + COUNTERS.lifeAdmissionContended.sum() + " completed=" + COUNTERS.lifeAdmissionCompleted.sum()
                + " totalWaitMs=" + COUNTERS.lifeAdmissionWaitNanos.sum() / 1_000_000 + " maxWaitMs="
                + COUNTERS.lifeAdmissionMaxWaitNanos.get() / 1_000_000 + " p50<=" + us(percentileNanos(0.50)) + " p95<="
                + us(percentileNanos(0.95)) + " p99<=" + us(percentileNanos(0.99)) + " longWaits(>=50ms)="
                + COUNTERS.lifeAdmissionLongWaits.sum() + " secondWaits(>=1s)=" + COUNTERS.lifeAdmissionSecondWaits.sum()
                + " skippedInSection=" + COUNTERS.lifeAdmissionSkippedInSection.sum() + " skippedNested="
                + COUNTERS.lifeAdmissionSkippedNested.sum() + " windowObserved=" + COUNTERS.lifeWindowObserved.sum() + "}";
    }

    private static String us(long ns) {
        return ns < 0 ? "-" : (ns / 1000) + "us";
    }
}
