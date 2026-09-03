package net.nestworld.region;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instrumentation-only (2026-08-31), per the user's explicit request to PROVE, not fix, the
 * "FREE_RUNNING Scheduled Tick Loss" hypothesis before touching {@link WorldRegion}'s
 * single-slot {@code pendingFreeRunningWork} handoff. That handoff is a deliberate
 * "latest batch wins" {@code AtomicReference.set()} (see {@code WorldRegion
 * #nestworldPostFreeRunningWork} javadoc) -- fine for block-entity tickers (re-collected every
 * tick, a skipped round self-heals), but {@code LevelTicks} block/fluid scheduled ticks are
 * one-shot: vanilla's {@code LevelTicks.tick()} has already POPPED the due entry from its own
 * queue by the time the corresponding {@link Runnable} reaches this handoff (see
 * {@code NestworldDimensionRegion#runScheduledTicksPhase}) -- if that Runnable is then
 * discarded by an overwrite before the free-running region drains it, nothing ever
 * re-schedules it. This class counts exactly how often that happens, split by block vs fluid,
 * to correlate with the user's live symptom (comparators not firing on first trigger, fluid
 * flow silently stalling) -- reported via {@code /nestworld scheduledtickloss}.
 *
 * <p>Cross-thread by construction: {@code recordPosted}/{@code recordOverwritten} are called
 * from the main thread ({@code RegionThreadPool.runWorkRound}); {@code recordConsumed} is
 * called from the owning region's own free-running thread ({@code RegionThread
 * #runFreeRunningTick} via {@code WorldRegion#nestworldPollFreeRunningWork}). All counters are
 * plain {@link AtomicLong} for that reason -- cheap, and this is off the hot per-entity path
 * (only touched once per {@code RegionPhase.SCHEDULED_TICK}/{@code RANDOM_TICK}/
 * {@code BLOCK_EVENT} work-round per region, not per entity).
 */
public final class ScheduledTickHandoffMetrics {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/ScheduledTickLoss");

    /** Tags a bucketed scheduled-tick Runnable with its kind AND position, so the free-running
     *  handoff can still tell block from fluid apart -- and log exactly WHERE -- after
     *  {@code routeScheduledTick} erases everything else about it into a plain {@link Runnable}.
     *  {@code kind} 2 = "other" (block events, or any future routeScheduledTick caller). */
    public static final class Tagged implements Runnable {
        static final int BLOCK = 0;
        static final int FLUID = 1;
        static final int OTHER = 2;

        private final Runnable delegate;
        final int kind;
        final long pos;

        Tagged(Runnable delegate, int kind, long pos) {
            this.delegate = delegate;
            this.kind = kind;
            this.pos = pos;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    public static Runnable tagBlock(Runnable r, BlockPos pos) { return new Tagged(r, Tagged.BLOCK, pos.asLong()); }
    public static Runnable tagFluid(Runnable r, BlockPos pos) { return new Tagged(r, Tagged.FLUID, pos.asLong()); }
    public static Runnable tagOther(Runnable r) { return new Tagged(r, Tagged.OTHER, 0L); }

    /** 2026-08-31: one record per genuinely-overwritten (never-drained) entry, per the user's
     *  explicit ask -- "record batch size + tick/time + region + scheduled-tick type at the
     *  moment of overwrittenUnread++, without changing behavior" -- so a specific lost tick can
     *  be cross-referenced against an observed in-game symptom (e.g. "the comparator at X,Y,Z
     *  stopped updating, and the log shows a BLOCK entry at that exact position lost at tick
     *  T"). Bounded ring, main-thread-only writer (recordPost always runs on the main thread --
     *  see class javadoc), so a plain ArrayDeque is fine. */
    public record OverwrittenEntry(int regionId, int kind, long pos, long producerTick, long gameTime) {
        public String kindName() {
            return switch (kind) {
                case Tagged.BLOCK -> "BLOCK";
                case Tagged.FLUID -> "FLUID";
                default -> "OTHER";
            };
        }
        public String posString() {
            BlockPos p = BlockPos.of(pos);
            return p.getX() + "," + p.getY() + "," + p.getZ();
        }
    }

    private static final int OVERWRITTEN_LOG_CAPACITY = 64;
    private static final Deque<OverwrittenEntry> recentOverwritten = new ArrayDeque<>(OVERWRITTEN_LOG_CAPACITY);

    private static synchronized void logOverwritten(OverwrittenEntry e) {
        if (recentOverwritten.size() >= OVERWRITTEN_LOG_CAPACITY) {
            recentOverwritten.removeFirst();
        }
        recentOverwritten.addLast(e);
        if (e.kind() != Tagged.OTHER) {
            LOGGER.warn("SCHEDULED_TICK_LOST region={} kind={} pos=({}) producerTick={} gameTime={}",
                    e.regionId(), e.kindName(), e.posString(), e.producerTick(), e.gameTime());
        }
    }

    /** Snapshot of the most recent overwritten entries across all regions, newest last. */
    public static java.util.List<OverwrittenEntry> recentOverwrittenSnapshot() {
        synchronized (ScheduledTickHandoffMetrics.class) {
            return new java.util.ArrayList<>(recentOverwritten);
        }
    }

    private final AtomicLong blockProduced = new AtomicLong();
    private final AtomicLong fluidProduced = new AtomicLong();
    private final AtomicLong otherProduced = new AtomicLong();

    private final AtomicLong blockConsumed = new AtomicLong();
    private final AtomicLong fluidConsumed = new AtomicLong();
    private final AtomicLong otherConsumed = new AtomicLong();

    private final AtomicLong blockOverwritten = new AtomicLong();
    private final AtomicLong fluidOverwritten = new AtomicLong();
    private final AtomicLong otherOverwritten = new AtomicLong();

    private final AtomicLong batchesPosted = new AtomicLong();
    /** Count of "publish over a not-yet-drained batch" events -- distinct from item-level
     *  overwritten counters above (one overwritten BATCH can contain many overwritten items). */
    private final AtomicLong batchesOverwritten = new AtomicLong();

    private volatile long lastProducerTick = -1;
    private volatile long lastConsumerTick = -1;
    private volatile int lastPostedBatchSize = 0;
    private volatile int lastOverwrittenBatchSize = 0;

    /** Called from the main thread right before {@code pendingFreeRunningWork.set(work)}.
     *  {@code overwritten} is whatever was in the slot before this set (null if none).
     *  {@code regionId}/{@code gameTime} are for the detailed per-entry loss record below --
     *  purely diagnostic, no behavior change. */
    void recordPost(List<Runnable> newBatch, List<Runnable> overwritten, long producerTick,
                     int regionId, long gameTime) {
        batchesPosted.incrementAndGet();
        lastProducerTick = producerTick;
        lastPostedBatchSize = newBatch.size();
        for (Runnable r : newBatch) {
            if (r instanceof Tagged t) {
                switch (t.kind) {
                    case Tagged.BLOCK -> blockProduced.incrementAndGet();
                    case Tagged.FLUID -> fluidProduced.incrementAndGet();
                    default -> otherProduced.incrementAndGet();
                }
            } else {
                otherProduced.incrementAndGet();
            }
        }
        if (overwritten != null && !overwritten.isEmpty()) {
            batchesOverwritten.incrementAndGet();
            lastOverwrittenBatchSize = overwritten.size();
            for (Runnable r : overwritten) {
                int kind = Tagged.OTHER;
                long pos = 0L;
                if (r instanceof Tagged t) {
                    kind = t.kind;
                    pos = t.pos;
                    switch (t.kind) {
                        case Tagged.BLOCK -> blockOverwritten.incrementAndGet();
                        case Tagged.FLUID -> fluidOverwritten.incrementAndGet();
                        default -> otherOverwritten.incrementAndGet();
                    }
                } else {
                    otherOverwritten.incrementAndGet();
                }
                logOverwritten(new OverwrittenEntry(regionId, kind, pos, producerTick, gameTime));
            }
        }
    }

    /** Called from the owning region's own thread once it actually drains a batch. */
    void recordConsumed(List<Runnable> batch, long consumerTick) {
        lastConsumerTick = consumerTick;
        for (Runnable r : batch) {
            if (r instanceof Tagged t) {
                switch (t.kind) {
                    case Tagged.BLOCK -> blockConsumed.incrementAndGet();
                    case Tagged.FLUID -> fluidConsumed.incrementAndGet();
                    default -> otherConsumed.incrementAndGet();
                }
            } else {
                otherConsumed.incrementAndGet();
            }
        }
    }

    public long blockProduced() { return blockProduced.get(); }
    public long fluidProduced() { return fluidProduced.get(); }
    public long otherProduced() { return otherProduced.get(); }
    public long blockConsumed() { return blockConsumed.get(); }
    public long fluidConsumed() { return fluidConsumed.get(); }
    public long otherConsumed() { return otherConsumed.get(); }
    public long blockOverwritten() { return blockOverwritten.get(); }
    public long fluidOverwritten() { return fluidOverwritten.get(); }
    public long otherOverwritten() { return otherOverwritten.get(); }
    public long batchesPosted() { return batchesPosted.get(); }
    public long batchesOverwritten() { return batchesOverwritten.get(); }
    public long lastProducerTick() { return lastProducerTick; }
    public long lastConsumerTick() { return lastConsumerTick; }
    public int lastPostedBatchSize() { return lastPostedBatchSize; }
    public int lastOverwrittenBatchSize() { return lastOverwrittenBatchSize; }

    public void reset() {
        blockProduced.set(0); fluidProduced.set(0); otherProduced.set(0);
        blockConsumed.set(0); fluidConsumed.set(0); otherConsumed.set(0);
        blockOverwritten.set(0); fluidOverwritten.set(0); otherOverwritten.set(0);
        batchesPosted.set(0); batchesOverwritten.set(0);
        lastProducerTick = -1; lastConsumerTick = -1;
        lastPostedBatchSize = 0; lastOverwrittenBatchSize = 0;
    }
}
