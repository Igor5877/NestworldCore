package net.nestworld.region.jfr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only (2026-09-03, "vanilla scheduling boundary" follow-up to the PROMOTE
 * investigation -- see project memory). Covers the three stages upstream of the existing NWJFR
 * POST/POLL/EXECUTE/HANDOFF instrumentation, which the PROMOTE investigation showed had never
 * once observed a real position for this redstone chain:
 *
 * <pre>
 *   Level.scheduleTick(...)        -&gt; LevelTicks.schedule()          -&gt; SCHEDULE
 *   LevelTicks.tick() -&gt; collect   -&gt; LevelTicks.scheduleForThisTick() -&gt; COLLECT
 *   NestworldDimensionRegion.runScheduledTicksPhase -&gt; routeScheduledTick() -&gt; ROUTE
 * </pre>
 *
 * Gated behind {@link RedstoneDiagFilter#matches(net.minecraft.core.BlockPos)} at every call
 * site -- narrow and position-filtered, not a global per-scheduled-tick log, per the same
 * noise concern that shaped the PROMOTE JFR instrumentation's windowing.
 *
 * <p>Separate {@code NWDIAG} prefix (own sequence counter) rather than reusing {@code NWJFR}'s,
 * to keep this investigation's grep-able output distinct from the closed PROMOTE thread's.
 */
public final class NwDiagLog {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/NWDIAG");
    private static final AtomicLong SEQ = new AtomicLong();

    private NwDiagLog() {}

    /** {@code LevelTicks.schedule()} -- a scheduled tick is enqueued for a container. */
    public static void schedule(String pos, String type, String threadName, long threadId) {
        LOGGER.info("NWDIAG seq={} event=SCHEDULE pos={} type={} thread={} threadId={}",
                SEQ.incrementAndGet(), pos, type, threadName, threadId);
    }

    /** {@code LevelTicks.scheduleForThisTick()} -- a previously scheduled tick became due and
     *  was moved into this tick's run queue. */
    public static void collect(String pos, String type, String threadName, long threadId) {
        LOGGER.info("NWDIAG seq={} event=COLLECT pos={} type={} thread={} threadId={}",
                SEQ.incrementAndGet(), pos, type, threadName, threadId);
    }

    /** {@code NestworldDimensionRegion.routeScheduledTick()} -- a due tick is bucketed to its
     *  owning region (or the main-thread band). */
    public static void route(String pos, String regionOrMain, String threadName, long threadId) {
        LOGGER.info("NWDIAG seq={} event=ROUTE pos={} dest={} thread={} threadId={}",
                SEQ.incrementAndGet(), pos, regionOrMain, threadName, threadId);
    }

    /** {@code RegionThread.runWorkBudgeted()}, immediately before {@code r.run()} for a tagged
     *  scheduled tick at a {@link RedstoneDiagFilter}-matched position (2026-09-03, "startup
     *  freeze" follow-up -- see project memory). Read-only snapshot of {@code Level.isLoaded()}
     *  for this position's own chunk plus its 6 direct block neighbors, taken at the exact
     *  instant this tick is about to execute -- narrow window into whether a cold-boot
     *  not-yet-loaded neighbor could silently break the vanilla neighbor-update chain that
     *  would otherwise call {@code scheduleTick()} for the next flip. */
    public static void chunkState(String pos, boolean self, boolean up, boolean down,
                                   boolean north, boolean south, boolean east, boolean west,
                                   String threadName, long threadId) {
        LOGGER.info("NWDIAG seq={} event=CHUNKSTATE pos={} self={} up={} down={} north={} south={} east={} west={} thread={} threadId={}",
                SEQ.incrementAndGet(), pos, self, up, down, north, south, east, west, threadName, threadId);
    }
}
