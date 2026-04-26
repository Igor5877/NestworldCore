/*
 * NestworldCore - Per-island tick profiling
 */
package net.nestworld.tick;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks tick performance for a single island's Forge process.
 *
 * In the process-per-island architecture, this monitors the whole server's
 * tick budget (not per-dimension — there is only one island per process).
 *
 * Adaptive escalation:
 *   5  violations  → SKIP_HALF  (tick every other tick)
 *   10 violations  → SKIP_THREE (tick 1 of 4)
 *   20 violations  → EMERGENCY  (island shutdown triggered)
 *
 * "Skip" here means NestworldCore suppresses entity/block updates for the
 * excess ticks via Forge events — the server tick itself still runs but
 * expensive operations are deferred.
 */
public final class IslandTickProfile {

    /** Number of ticks kept in the rolling window for TPS calculation. */
    private static final int WINDOW = 100;

    public enum AdaptiveState {
        NORMAL,
        SKIP_HALF,
        SKIP_THREE,
        EMERGENCY
    }

    private final UUID islandId;
    private final long budgetNanos;

    private volatile long tickStartNanos;
    private final long[] tickDurationsNanos = new long[WINDOW];

    private final AtomicLong totalTicks         = new AtomicLong(0);
    private final AtomicInteger violations      = new AtomicInteger(0);
    private final AtomicInteger consecutiveVio  = new AtomicInteger(0);
    private volatile AdaptiveState adaptiveState = AdaptiveState.NORMAL;
    private volatile int skipCounter;

    public IslandTickProfile(UUID islandId, long budgetNanos) {
        this.islandId     = islandId;
        this.budgetNanos  = budgetNanos;
    }

    // -------------------------------------------------------------------------
    // Called from Forge LevelTickEvent.Pre / Post on the main thread
    // -------------------------------------------------------------------------

    public void recordTickStart() {
        tickStartNanos = System.nanoTime();
    }

    public void recordTickEnd() {
        long elapsed = System.nanoTime() - tickStartNanos;
        long tick    = totalTicks.getAndIncrement();
        tickDurationsNanos[(int)(tick % WINDOW)] = elapsed;

        if (elapsed > budgetNanos) {
            int v = violations.incrementAndGet();
            consecutiveVio.incrementAndGet();
            escalate(v);
        } else {
            consecutiveVio.set(0);
            // Gradual de-escalation: reduce one step every 20 clean ticks
            if (tick % 20 == 0) deescalate();
        }
    }

    private void escalate(int totalViolations) {
        if (totalViolations >= 20)      adaptiveState = AdaptiveState.EMERGENCY;
        else if (totalViolations >= 10) adaptiveState = AdaptiveState.SKIP_THREE;
        else if (totalViolations >= 5)  adaptiveState = AdaptiveState.SKIP_HALF;
    }

    private void deescalate() {
        adaptiveState = switch (adaptiveState) {
            case SKIP_THREE -> AdaptiveState.SKIP_HALF;
            case SKIP_HALF  -> AdaptiveState.NORMAL;
            default         -> adaptiveState;
        };
    }

    // -------------------------------------------------------------------------
    // Used by TickBudgetManager to decide whether to suppress heavy work
    // -------------------------------------------------------------------------

    /**
     * Returns true if this tick's heavy work (entity AI, block ticks) should
     * be skipped to protect TPS during adaptive cooldown phase.
     * Always false in NORMAL state.
     */
    public boolean shouldSuppressHeavyWork() {
        return switch (adaptiveState) {
            case NORMAL    -> false;
            case SKIP_HALF  -> (++skipCounter & 1) != 0;     // odd ticks suppressed
            case SKIP_THREE -> (++skipCounter % 4) != 0;     // 3 of every 4 suppressed
            case EMERGENCY  -> true;
        };
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    /**
     * Returns current TPS based on the rolling window of recent tick durations.
     * Capped at 20.0.
     */
    public double getCurrentTps() {
        long count = Math.min(totalTicks.get(), WINDOW);
        if (count == 0) return 20.0;

        long sumNanos = 0;
        for (int i = 0; i < count; i++) sumNanos += tickDurationsNanos[i];

        double avgMs = (sumNanos / (double) count) / 1_000_000.0;
        // A tick that takes 50ms = 20 TPS. avgMs > 50ms = lagging.
        return Math.min(20.0, 1_000.0 / Math.max(avgMs, 50.0));
    }

    public long getLastTickMs() {
        long elapsed = System.nanoTime() - tickStartNanos;
        return elapsed / 1_000_000;
    }

    public void resetViolations() {
        violations.set(0);
        consecutiveVio.set(0);
        adaptiveState = AdaptiveState.NORMAL;
        skipCounter = 0;
    }

    public UUID getIslandId()           { return islandId; }
    public long getBudgetNanos()        { return budgetNanos; }
    public int getViolationCount()      { return violations.get(); }
    public AdaptiveState getAdaptiveState() { return adaptiveState; }
    public long getTotalTicks()         { return totalTicks.get(); }
}
