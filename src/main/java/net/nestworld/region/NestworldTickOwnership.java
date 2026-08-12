package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NestWorld DIAG (2026-08-11): ownership assertion for vanilla's {@code LevelTicks} --
 * built while chasing a real crash (NullPointerException in {@code
 * Long2LongOpenHashMap$MapIterator.nextEntry}, uncaught on the main thread inside
 * {@code NestworldRegionSystem.runScheduledTicksPhase}) found under Layer 13.1's heavy
 * sustained chunk-gen load. See project memory: leveticks-race-under-heavy-chunkgen.
 *
 * <p>Vanilla's {@code LevelTicks} marks {@code addContainer}/{@code removeContainer}/
 * {@code schedule} as {@code synchronized}, but its own {@code tick()} (and every private
 * helper it calls -- {@code sortContainersToTick}, {@code drainContainers}, etc., the code
 * that ACTUALLY iterates the same backing maps those synchronized methods mutate) is NOT
 * synchronized. This is a half-synchronized scheme: safe only if every caller of the
 * synchronized methods AND every caller of {@code tick()} happen to be the same single
 * thread, by convention -- which vanilla enforces implicitly (one dimension-tick thread)
 * but which NestWorldCore's own region-parallel architecture has more surface area to
 * accidentally violate (mailbox routing, free-running regions, off-thread continuations).
 *
 * <p>Rather than guess which specific call site is the violator, this asserts the
 * invariant directly at every mutation/iteration point: the FIRST caller (expected: main
 * thread, very early during boot) is captured as the owner; every subsequent call from a
 * DIFFERENT thread throws immediately with full context, instead of silently corrupting
 * the map and surfacing as a confusing NPE possibly many calls later. Diagnostic-only --
 * doesn't change any correct-path behavior, only converts an eventual, hard-to-place
 * failure into an immediate, precisely-located one.
 */
public final class NestworldTickOwnership {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private static volatile Thread ownerThread = null;
    private static volatile long lastKnownServerTick = -1;

    private NestworldTickOwnership() {}

    /** Called once per runScheduledTicksPhase invocation so violation reports can include
     * "as of which server tick" context. */
    public static void noteServerTick(long tick) {
        lastKnownServerTick = tick;
    }

    /**
     * Assert the calling thread is the same one that has made every previous call here
     * (across ALL LevelTicks instances / dimensions -- deliberately global, since vanilla
     * itself only ever expects ONE thread to ever touch ANY LevelTicks in the whole
     * server). Throws (and logs first, so the log has the context even if something
     * upstream swallows the exception) on violation.
     */
    public static void assertOwnerThread(String operation, Object context) {
        Thread current = Thread.currentThread();
        // Gemini-reviewed 2026-08-11 (false-positive analysis, see project memory
        // leveticks-nonfreerunning-falsepositive): a non-free-running RegionThread's
        // scheduleTick() calls are safe by construction. RegionThreadPool.runWorkRound
        // fully joins (CountDownLatch) every such thread's dispatched work -- including
        // any scheduleTick() calls made during it -- before the main thread can advance
        // to the next tick phase, so these calls can never overlap LevelTicks.tick()'s
        // own unsynchronized iteration on the main thread, even though the calling
        // Thread object differs from the captured owner. Free-running regions are the
        // one case that genuinely can race (self-paced, never latch-joined) -- already
        // routed around this entirely via the scheduleTick mailbox (see
        // nestworldIsFreeRunningRegionThread), so they never reach here at all.
        if (current instanceof RegionThread rt && !rt.getRegion().isFreeRunning()) {
            return;
        }
        Thread expected = ownerThread;
        if (expected == null) {
            ownerThread = current;
            return;
        }
        if (expected != current) {
            // Diagnostic-only enrichment (no behavior change): region.getId() is globally
            // unique and monotonically increasing across every split/merge (grid.nextId()
            // never reuses an id), so it doubles as a de-facto epoch -- lets a future
            // occurrence be correlated against exactly which region instance made the call,
            // whether it was free-running, and how far its own local tick had drifted from
            // the server tick this violation was logged against. See project memory:
            // region-split-scheduletick-race.md for why this was added instead of a fix.
            String callerRegionInfo = "n/a";
            if (current instanceof RegionThread rt) {
                WorldRegion r = rt.getRegion();
                callerRegionInfo = String.format("region=%d isFreeRunning=%b localTick=%d",
                        r.getId(), r.isFreeRunning(), r.getLocalTickCount());
            }
            String message = String.format(
                    "LevelTicks OWNERSHIP VIOLATION: operation=%s context=%s thread='%s' (id=%d) "
                            + "expectedOwnerThread='%s' (id=%d) lastKnownServerTick=%d callerRegion=[%s]",
                    operation, context, current.getName(), current.getId(),
                    expected.getName(), expected.getId(), lastKnownServerTick, callerRegionInfo);
            RuntimeException violation = new RuntimeException(message);
            LOGGER.error(message, violation);
            throw violation;
        }
    }
}
