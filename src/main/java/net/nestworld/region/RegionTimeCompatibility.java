package net.nestworld.region;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diagnostic-only, no behavior change. A free-running region's own {@link
 * WorldRegion#getLocalTickCount()} can drift far behind (or ahead of) the
 * server's global tick count (from an overload episode) while {@code
 * Level.getGameTime()}/{@code getDayTime()} stay fully global (unpatched
 * vanilla, verified by reading {@code Level.java} directly -- there is no
 * per-region virtualization of game time anywhere in this codebase). Any
 * mod code that compares its own remembered gameTime/tick-count against the
 * live value on each tick -- rather than trusting a monotonic per-call
 * delta of exactly 1 -- can misbehave once a region's local tick lags the
 * global one and then catches up.
 *
 * <p>This class does not attempt to instrument arbitrary mod-internal
 * scheduler state (not generically observable). It watches the one thing
 * NestWorld itself controls and can measure cheaply: the batch of
 * cross-thread work items ({@link WorldRegion#nestworldPollFreeRunningWork()})
 * a free-running region drains and executes in a single local tick. A batch
 * that is both large in absolute terms and a large multiple of that
 * region's own rolling-average batch size, while the region is measurably
 * behind global game time, is exactly the "recovering from a large backlog"
 * moment the user's hypothesis calls out as the highest-risk window for
 * mod compatibility. This is a proxy signal for correlation after an
 * incident (which mod's tick/block-entity threw, at what delta), not a
 * behavior change and not a claim of root-causing every possible mod bug.
 */
public final class RegionTimeCompatibility {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/TimeCompat");

    private static final double EMA_ALPHA = 0.1;
    private static final int MIN_SAMPLES_BEFORE_ALERT = 20;
    private static final int BURST_ABS_THRESHOLD = 200;
    private static final double BURST_RATIO_THRESHOLD = 8.0;
    private static final long DELTA_THRESHOLD_TICKS = 500;

    private static final Map<Integer, Double> emaBatchSize = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> sampleCount = new ConcurrentHashMap<>();

    private RegionTimeCompatibility() {}

    /**
     * Called once per free-running local tick, right before the drained work
     * batch is executed. {@code globalGameTime} is the dimension's live
     * {@code Level.getGameTime()} at the moment of the call -- the same
     * value every other thread/mod sees, per the class-level note above.
     */
    public static void noteWorkBatch(WorldRegion region, int batchSize, long globalGameTime) {
        if (batchSize <= 0) {
            return;
        }
        int id = region.getId();
        long localTick = region.getLocalTickCount();
        long delta = localTick - globalGameTime;

        int samples = sampleCount.merge(id, 1, Integer::sum);
        Double prevEma = emaBatchSize.get(id);
        double ema = (prevEma == null) ? batchSize : (EMA_ALPHA * batchSize + (1 - EMA_ALPHA) * prevEma);
        emaBatchSize.put(id, ema);

        if (samples <= MIN_SAMPLES_BEFORE_ALERT) {
            // Let the per-region baseline settle before judging anything a burst.
            return;
        }
        if (delta > -DELTA_THRESHOLD_TICKS) {
            // Only flag bursts that occur while genuinely behind global time --
            // that's the catch-up window the hypothesis is about.
            return;
        }
        if (batchSize < BURST_ABS_THRESHOLD) {
            return;
        }
        if (ema <= 0 || batchSize < ema * BURST_RATIO_THRESHOLD) {
            return;
        }

        LOGGER.warn(
                "REGION_CATCHUP_BURST region={} globalGameTime={} localTick={} delta={} batchSize={} rollingAvg={}",
                id, globalGameTime, localTick, delta, batchSize, String.format("%.1f", ema));
    }

    /** Test/reset hook -- clears all rolling state (e.g. after a region split/merge). */
    static void reset(int regionId) {
        emaBatchSize.remove(regionId);
        sampleCount.remove(regionId);
    }
}
