package net.nestworld.region;

import org.slf4j.Logger;

/**
 * Guaranteed forced exit if the JVM is somehow still alive N seconds after a shutdown attempt
 * (crash-driven OR graceful {@code /stop}) — a safety net, not the primary shutdown path.
 *
 * <p><b>Why this exists</b>: {@code stopServer()} (world save, region-thread shutdown) is
 * expected to finish in well under this window even on a heavy modpack. Live-reproduced
 * 2026-09-23/24 on the skyblock-team-island-of-airisminhistory deployment: BOTH the
 * crash-driven path AND a plain graceful {@code /stop} hung indefinitely afterward with "Server
 * thread" fully finished but the JVM process still alive — root-caused via {@code jstack} to a
 * leaked NON-DAEMON thread pool from a third-party (or, in that specific case, first-party
 * {@code nestworld-mods-server}) mod's {@code Executors.newSingleThreadScheduledExecutor()} call
 * with no custom {@code ThreadFactory} (Java's default factory does not mark threads daemon).
 * Neither {@link Runtime#halt} being reachable nor the shutdown sequence completing matters if
 * something else already holds the JVM open — this watchdog exists precisely for "something we
 * don't control and can't enumerate" cases like that, not as a substitute for fixing the leak at
 * its source.
 *
 * <p>Only ONE call site previously armed this (the crash-catch block) — a graceful {@code /stop}
 * had zero protection and hung just as often in practice. This class is shared by both call
 * sites so a future third path (e.g. mod-loading failure) can arm the same guarantee with one
 * line instead of duplicating the sleep-then-halt logic a third time.
 */
public final class NestworldShutdownWatchdog {
    private NestworldShutdownWatchdog() {}

    private static final long DEFAULT_DELAY_MS =
            Long.getLong("nestworld.shutdownWatchdogMs", 90_000L);

    /**
     * Starts a daemon thread that force-halts the JVM after {@code delayMs} unless the JVM has
     * already exited naturally by then. Runs on a daemon thread so it can never itself be what
     * keeps the JVM alive; a normal/successful exit races it harmlessly (the process is gone
     * before the sleep elapses, so the thread simply never wakes to do anything).
     *
     * @param reason short label identifying WHICH shutdown path armed this (appears in the log
     *               line if it actually fires), e.g. {@code "crash-driven shutdown"} or
     *               {@code "graceful /stop"}.
     */
    public static void arm(String reason, Logger logger) {
        arm(reason, logger, DEFAULT_DELAY_MS);
    }

    public static void arm(String reason, Logger logger, long delayMs) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                return;
            }
            logger.error("NestWorld: JVM still alive {}ms after a {} attempt -- forcing exit "
                    + "(see orphaned-region-threads-crash-shutdown-gap; run jstack on a future "
                    + "hang to find the actual leaked non-daemon thread before it recurs)",
                    delayMs, reason);
            Runtime.getRuntime().halt(1);
        }, "NestWorld-Shutdown-Forced-Exit-Watchdog[" + reason + "]");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
