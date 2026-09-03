package net.nestworld.region.jfr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only (2026-09-03, "PROMOTE transition" investigation -- see project memory).
 * Log-based transport for the exact same read-only snapshot data the {@code nestworld.*} JFR
 * events already collect at every call site in this investigation -- added as a SECOND,
 * independent delivery path after JFR's custom event types were confirmed (by direct bytecode
 * inspection: the classes load, {@code FlightRecorder.register()} runs, {@code commit()}
 * throws nothing) to never actually register in this specific Forge environment, for reasons
 * that weren't worth chasing further. The JFR event code stays in place unmodified (kept for a
 * future environment where it might work); this is an ADDITIONAL path, not a replacement, and
 * is NOT gated behind the JFR event's own (currently always-false) {@code shouldCommit()} --
 * only behind the same {@link PromoteDiagnosticWindow} the JFR call sites already check.
 *
 * <p>Every line: a stable {@code NWJFR} prefix (so {@code grep NWJFR server.log} recovers the
 * full timeline), a global monotonic sequence number (so emission ORDER is recoverable even
 * when two lines share a millisecond-resolution timestamp -- log4j's own timestamp is still
 * present in the surrounding log line as usual), and a fixed field order per event kind.
 */
public final class NwjfrLog {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/NWJFR");
    private static final AtomicLong SEQ = new AtomicLong();

    private NwjfrLog() {}

    /** PROMOTE_BEGIN / PROMOTE_COMMIT -- read-only snapshot of every scheduled-tick transient
     *  queue depth at that instant, per {@code RegionSplitManager#promoteFreeRunning}. */
    public static void promote(String phase, int regionId, String oldMode, String newMode,
                                int scheduledDepth, int carriedDepth, int pendingDepth,
                                String threadName, long threadId) {
        LOGGER.info("NWJFR seq={} event=PROMOTE_{} region={} mode={}->{} scheduled={} carried={} pending={} thread={} threadId={}",
                SEQ.incrementAndGet(), phase, regionId, oldMode, newMode,
                scheduledDepth, carriedDepth, pendingDepth, threadName, threadId);
    }

    /** REGION_TICK -- one RegionThread tick iteration, before/after depths. */
    public static void regionTick(int regionId, String mode,
                                   int scheduledBefore, int scheduledAfter,
                                   int carriedBefore, int carriedAfter,
                                   String threadName, long threadId) {
        LOGGER.info("NWJFR seq={} event=REGION_TICK region={} mode={} scheduledBefore={} scheduledAfter={} carriedBefore={} carriedAfter={} thread={} threadId={}",
                SEQ.incrementAndGet(), regionId, mode,
                scheduledBefore, scheduledAfter, carriedBefore, carriedAfter, threadName, threadId);
    }

    /** SCHEDULED_TICK -- one lifecycle stage (POST/POLL/EXECUTE/HANDOFF) of a scheduled
     *  block/fluid tick. {@code position}/{@code tickType} may be null (HANDOFF has neither
     *  cheaply available -- logged as "-"). */
    public static void scheduledTick(String stage, int regionId, int queueDepth,
                                      String position, String tickType,
                                      String threadName, long threadId) {
        LOGGER.info("NWJFR seq={} event=SCHEDULED_TICK type={} region={} queueDepth={} pos={} tickType={} thread={} threadId={}",
                SEQ.incrementAndGet(), stage, regionId, queueDepth,
                position == null ? "-" : position, tickType == null ? "-" : tickType, threadName, threadId);
    }
}
