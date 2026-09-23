package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Duty-cycle limiter for the opportunistic chunk-save loop's free-running-region rendezvous
 * ({@code ChunkMap#processUnloads}, the "#4" persistence fix).
 *
 * <p><b>What it does and does not change.</b> Every chunk that loop saves is still saved INSIDE a
 * full rendezvous (all free-running regions drained their scheduled ticks and parked between local
 * ticks) -- exactly as before. This class only decides <i>how often</i> a rendezvous window is
 * opened. A tick on which the window is not opened skips the save loop entirely (it never saves
 * without a rendezvous); the chunks simply stay unsaved and are picked up by a later window.
 * Explicit saves ({@code /save-all}, autosave, shutdown: {@code ServerLevel#save}) and the
 * unload path are untouched.
 *
 * <p><b>Why it exists.</b> A rendezvous makes the main thread wait for the slowest free-running
 * region to reach a safe point between its own ticks. When a region's tick is long (thousands of
 * entities: ~40 ms) that wait is ~40 ms, and with a save candidate present nearly every tick the
 * main thread was spending >50% of its time parked in the rendezvous (measured with thread dumps).
 *
 * <p><b>Policy.</b> After a window of duration D (pause + saves) the next window may open no
 * earlier than {@code D * (100/dutyPct - 1)} later, capped at {@code maxIntervalMs}. So the main
 * thread spends at most {@code dutyPct}% of its time in rendezvous windows, while a healthy
 * server (D of a millisecond or two) is never throttled at all (the computed gap is shorter than
 * a tick). Because a throttled stretch saves nothing, the next window's chunk limit is raised in
 * proportion to the ticks skipped (bounded), so long-run save throughput is preserved; the
 * vanilla time-budget check inside the loop still bounds every window.
 *
 * <p>Main-thread only (called from {@code ChunkMap#processUnloads}); one instance per ChunkMap.
 * {@code -Dnestworld.saveRendezvousMaxDutyPct=100} disables the limiter (restores the old
 * every-tick behaviour); {@code -Dnestworld.saveRendezvousMaxIntervalMs} (default 500) caps the gap.
 */
public final class SaveRendezvousThrottle {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SaveRendezvous");

    /** Vanilla's per-tick opportunistic save cap ({@code l < 20} in ChunkMap#processUnloads). */
    static final int VANILLA_SAVE_LIMIT = 20;
    /** Never scale the per-window limit beyond this many multiples of the vanilla cap. */
    static final int MAX_LIMIT_MULTIPLE = 20;
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L;

    private final int dutyPct;
    private final long maxIntervalNanos;

    /** Until the first window closes there is nothing to throttle against (a sentinel value like
     *  Long.MIN_VALUE would overflow in {@code now - next}; System.nanoTime() may be negative). */
    private boolean gated;
    private long nextAllowedNanos;
    private int skippedSinceWindow;
    private long lastLogNanos;

    // counters (since last log line)
    private long windows;
    private long throttledTicks;
    private long noTimeTicks;
    private long savedChunks;
    private long windowNanosSum;
    private long windowNanosMax;

    public SaveRendezvousThrottle() {
        this(clampPct(Integer.getInteger("nestworld.saveRendezvousMaxDutyPct", 10)),
                Math.max(0L, Long.getLong("nestworld.saveRendezvousMaxIntervalMs", 500L)) * 1_000_000L);
    }

    SaveRendezvousThrottle(int dutyPct, long maxIntervalNanos) {
        this.dutyPct = dutyPct;
        this.maxIntervalNanos = maxIntervalNanos;
    }

    private static int clampPct(int v) {
        return Math.max(1, Math.min(100, v));
    }

    /** May a rendezvous window be opened now? (Only asked when a save candidate exists.) */
    public boolean mayOpenWindow(long nowNanos) {
        return !gated || nowNanos - nextAllowedNanos >= 0;
    }

    /** Called instead of opening a window because the throttle said no. */
    public void recordThrottled() {
        skippedSinceWindow++;
        throttledTicks++;
    }

    /** Called instead of opening a window because the tick has no time budget left (the loop would
     *  save nothing anyway). Not counted towards the per-window limit scaling. */
    public void recordNoTime() {
        noTimeTicks++;
    }

    /** Chunk-save limit for the window about to open: vanilla's 20, scaled by ticks skipped. */
    public int saveLimit() {
        int multiple = Math.min(1 + skippedSinceWindow, MAX_LIMIT_MULTIPLE);
        return VANILLA_SAVE_LIMIT * multiple;
    }

    /** Called when a window has finished (after the regions were resumed). */
    public void closeWindow(long startNanos, long endNanos, int saved) {
        long d = Math.max(0L, endNanos - startNanos);
        windows++;
        savedChunks += saved;
        windowNanosSum += d;
        if (d > windowNanosMax) windowNanosMax = d;
        skippedSinceWindow = 0;
        if (dutyPct >= 100) {
            gated = false;
            return;
        }
        long gap = d * (100L - dutyPct) / dutyPct;
        if (gap > maxIntervalNanos) gap = maxIntervalNanos;
        nextAllowedNanos = endNanos + gap;
        gated = true;
    }

    /** True at most once per 10 s, and only if there was activity to report. */
    public boolean logDue(long nowNanos) {
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) return false;
        if (windows == 0 && throttledTicks == 0 && noTimeTicks == 0) {
            lastLogNanos = nowNanos;
            return false;
        }
        return true;
    }

    public void log(String level, long nowNanos) {
        lastLogNanos = nowNanos;
        LOGGER.info("[{}] save-rendezvous last ~10s: windows={} savedChunks={} avgWindowMs={} maxWindowMs={} "
                        + "skippedByDutyCycle={} skippedNoTimeBudget={} (maxDuty={}% maxInterval={}ms)",
                level, windows, savedChunks,
                windows == 0 ? "0.0" : String.format("%.1f", windowNanosSum / 1e6 / windows),
                String.format("%.1f", windowNanosMax / 1e6),
                throttledTicks, noTimeTicks, dutyPct, maxIntervalNanos / 1_000_000L);
        windows = throttledTicks = noTimeTicks = savedChunks = windowNanosSum = windowNanosMax = 0;
    }

    // -----------------------------------------------------------------------
    // Self-test (pure logic, fake clock). Run: java net.nestworld.region.SaveRendezvousThrottle
    // -----------------------------------------------------------------------

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        final long ms = 1_000_000L;
        final long tick = 50 * ms;

        // 1. Healthy server: window of 1.5 ms, 10% duty -> gap 13.5 ms < one tick: never throttled.
        SaveRendezvousThrottle t = new SaveRendezvousThrottle(10, 500 * ms);
        long now = 0;
        for (int i = 0; i < 1000; i++) {
            check(t.mayOpenWindow(now), "healthy: window must be allowed every tick");
            t.closeWindow(now, now + 3 * ms / 2, 5);
            check(t.saveLimit() == 20, "healthy: vanilla limit");
            now += tick;
        }

        // 2. Overloaded: every window costs 45 ms. Simulate 60 s of 77 ms ticks; check the duty cycle.
        t = new SaveRendezvousThrottle(10, 500 * ms);
        now = 0;
        long inWindows = 0, wall = 60_000 * ms, windowsOpened = 0;
        int maxLimit = 0;
        long lastEnd = -1;
        while (now < wall) {
            if (t.mayOpenWindow(now)) {
                maxLimit = Math.max(maxLimit, t.saveLimit());
                if (lastEnd >= 0) check(now >= lastEnd, "window opened before previous ended");
                long end = now + 45 * ms;
                t.closeWindow(now, end, t.saveLimit());
                inWindows += 45 * ms;
                windowsOpened++;
                lastEnd = end;
                now = end + 32 * ms;      // rest of the (long) tick
            } else {
                t.recordThrottled();
                now += 77 * ms;
            }
        }
        double duty = 100.0 * inWindows / wall;
        check(duty <= 10.5, "duty cycle must be <= ~10%, was " + duty);
        check(windowsOpened > 60, "must still open windows regularly, opened " + windowsOpened);
        check(maxLimit > 20 && maxLimit <= 20 * MAX_LIMIT_MULTIPLE, "limit must scale but stay bounded: " + maxLimit);

        // 3. Cap: a 2 s window (rendezvous timeout) must not push the next window beyond maxInterval.
        t = new SaveRendezvousThrottle(10, 500 * ms);
        t.closeWindow(0, 2000 * ms, 0);
        check(!t.mayOpenWindow(2000 * ms + 499 * ms), "must wait");
        check(t.mayOpenWindow(2000 * ms + 500 * ms), "must open after cap");

        // 4. Disabled (100%): never throttles.
        t = new SaveRendezvousThrottle(100, 500 * ms);
        t.closeWindow(0, 1000 * ms, 0);
        check(t.mayOpenWindow(1000 * ms), "disabled: always allowed");

        // 5. Limit bookkeeping: skipped ticks scale the next window, reset after it.
        t = new SaveRendezvousThrottle(10, 500 * ms);
        for (int i = 0; i < 3; i++) t.recordThrottled();
        check(t.saveLimit() == 80, "3 skipped -> 4x limit");
        t.closeWindow(0, 45 * ms, 10);
        check(t.saveLimit() == 20, "reset after window");
        for (int i = 0; i < 100; i++) t.recordThrottled();
        check(t.saveLimit() == 20 * MAX_LIMIT_MULTIPLE, "bounded");
        // no-time skips never scale the limit
        t = new SaveRendezvousThrottle(10, 500 * ms);
        for (int i = 0; i < 50; i++) t.recordNoTime();
        check(t.saveLimit() == 20, "no-time skips must not scale");

        System.out.println("SaveRendezvousThrottle self-test PASS (duty=" + String.format("%.2f", duty)
                + "%, windows=" + windowsOpened + ", maxLimit=" + maxLimit + ")");
    }
}
