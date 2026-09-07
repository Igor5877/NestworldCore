package net.nestworld.region;

import java.util.Arrays;

/**
 * Rolling wall-clock-windowed history of per-tick durations, feeding the "Tick
 * (min/med/95%/max ms)" rows in {@code /nestworld status}/{@code lag}. Vanilla's own
 * {@code MinecraftServer.tickTimes} only covers the last 100 ticks (~5s at 20 TPS) --
 * too short for the 10s/1m windows spark-style UX wants. This is a small, presentation-
 * feeding addition, not a change to any existing metric or threshold: it just remembers
 * more of the same per-tick duration that's already computed every tick, whether that's
 * vanilla's own server tick (see {@link NestworldRegionSystem#onServerTick}, a pure Forge
 * event, no vanilla source touched) or a free-running region's own tick (see {@link
 * WorldRegion#recordTickDuration}, the exact same call site that already feeds the
 * region's 100-tick ring buffer).
 *
 * <p>Instantiable, not static: {@link #SERVER} is the one global instance for the main
 * server tick; each {@link WorldRegion} owns its own instance so free-running regions get
 * the same 10s/1m breakdown independently of the server-wide one.
 */
public final class TickDurationHistory {

    /** The single global instance tracking the main server tick. */
    public static final TickDurationHistory SERVER = new TickDurationHistory();

    // 2400 entries covers 2 minutes even at a sustained 20/s (both vanilla's own tick
    // loop and free-running regions target NestworldTuning.FREE_RUNNING_TARGET_TICK_NANOS
    // = 50ms = 20/s -- same pacing) -- a generous upper bound, not a precise one; window
    // selection below is by wall-clock timestamp, not slot count.
    private static final int CAPACITY = 2400;
    private final long[] timestampsMs = new long[CAPACITY];
    private final long[] durationsNanos = new long[CAPACITY];
    private int writeIndex = 0;
    private int size = 0;

    public synchronized void record(long nowMillis, long durationNanos) {
        timestampsMs[writeIndex] = nowMillis;
        durationsNanos[writeIndex] = durationNanos;
        writeIndex = (writeIndex + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    public record Stats(double minMs, double medianMs, double p95Ms, double maxMs, int sampleCount) {}

    /** Stats over the last {@code windowMillis} of wall-clock time (not ticks). Returns
     *  {@code null} if no samples fall in that window yet (e.g. server/region just started). */
    public synchronized Stats compute(long windowMillis) {
        if (size == 0) return null;
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;
        double[] msValues = new double[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (timestampsMs[i] >= cutoff) {
                msValues[count++] = durationsNanos[i] / 1_000_000.0;
            }
        }
        if (count == 0) return null;
        double[] sorted = Arrays.copyOf(msValues, count);
        Arrays.sort(sorted);
        double min = sorted[0];
        double max = sorted[count - 1];
        double median = sorted[count / 2];
        double p95 = sorted[Math.min(count - 1, (int) (count * 0.95))];
        return new Stats(min, median, p95, max, count);
    }
}
