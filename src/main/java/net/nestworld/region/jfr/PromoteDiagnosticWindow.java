package net.nestworld.region.jfr;

/**
 * Diagnostic-only (2026-09-03, "PROMOTE transition" JFR ТЗ -- see project memory). Per the
 * project owner's explicit refinement: detailed per-tick/per-scheduled-tick tracing must NOT
 * run unconditionally on every region tick or every scheduled tick globally -- only in a short
 * window around an actual promotion, so the diagnostic itself never becomes a cost factor on a
 * server with a large scheduled-tick volume.
 *
 * <p>A single {@code volatile long} deadline -- no locks, no synchronization, no queue
 * operations, satisfying the instrumentation's hard "no synchronized/locks/waits" constraint by
 * construction. {@link #isOpen()} is one volatile read + one comparison; every other
 * diagnostic-event call site in this investigation checks this FIRST, before constructing an
 * event object or reading any queue depth, so the cost outside an active window is that one
 * comparison, nothing more.
 */
public final class PromoteDiagnosticWindow {

    private PromoteDiagnosticWindow() {}

    private static volatile long deadlineNanos = 0L;

    /** Called once, from {@code RegionSplitManager#promoteFreeRunning}'s PROMOTE_BEGIN point.
     *  Opens (or extends) the window {@code durationNanos} out from now. */
    public static void open(long durationNanos) {
        deadlineNanos = System.nanoTime() + durationNanos;
    }

    /** Cheap, side-effect-free check -- safe to call from any thread, any hot path. */
    public static boolean isOpen() {
        return System.nanoTime() < deadlineNanos;
    }
}
