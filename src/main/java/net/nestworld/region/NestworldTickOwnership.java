package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NestWorld ownership assertion for vanilla's {@code LevelTicks} (2026-08-11, rewritten
 * 2026-08-12 after a SECOND live crash exposed a blind spot in the first version). Built
 * while chasing a real crash (NullPointerException in {@code
 * Long2LongOpenHashMap$MapIterator.nextEntry}, uncaught on the main thread inside {@code
 * NestworldRegionSystem.runScheduledTicksPhase}). See project memory:
 * leveticks-race-under-heavy-chunkgen, leveticks-race-second-confirmed-crash-fixed.
 *
 * <p>Vanilla's {@code LevelTicks} marks {@code addContainer}/{@code removeContainer}/
 * {@code schedule} as {@code synchronized}, but its own {@code tick()} (and every private
 * helper it calls -- the code that ACTUALLY iterates the same backing maps those
 * synchronized methods mutate) is NOT synchronized. This is a half-synchronized scheme:
 * safe only if every caller of the synchronized methods AND every caller of {@code tick()}
 * happen to be the same single thread, by convention -- which vanilla enforces implicitly
 * (one dimension-tick thread) but which NestWorldCore's own region-parallel architecture
 * has more surface area to accidentally violate.
 *
 * <p><b>Ownership model (2026-08-12):</b> LevelTicks mutation is Server-thread-only, full
 * stop. Every region-thread path that needs to mutate it goes: region thread -> mailbox /
 * scheduled-tick queue -> main-thread dispatch -> LevelTicks.schedule(). All three real
 * mutation paths were audited: {@code schedule()} (fixed 2026-08-12, see
 * {@code LevelAccessor.scheduleTick}'s 4 overloads -- now redirects EVERY region thread,
 * not just free-running ones), {@code addContainer()} (only ever called from
 * {@code ChunkMap.protoChunkToFullChunk}'s main-thread-routed continuation, confirmed via
 * the {@code mainThreadMailbox} trace in project memory p1-1-protochunktofullchunk-commonpool-finding),
 * {@code removeContainer()} (only ever called from {@code ChunkMap.processUnloads()}'s
 * drain of {@code unloadQueue}, itself only ever called from {@code ChunkMap.tick()} on
 * the main thread -- the concurrent-safe queue only makes ENQUEUE safe from any thread,
 * the actual container removal always runs on main).
 *
 * <p><b>The blind spot this rewrite closes:</b> the original version had
 * {@code if (current instanceof RegionThread rt && !rt.getRegion().isFreeRunning()) return;}
 * -- an unconditional exemption for any non-free-running region thread, reasoning that
 * {@code RegionThreadPool.runWorkRound} always fully joins (CountDownLatch) before the next
 * phase, so such a call could never overlap {@code LevelTicks.tick()}'s iteration. The P2
 * barrier audit (docs/P2_AUDIT_RESULTS.md, Q6) independently disproved this: {@code
 * awaitLatch()} has no cancellation on its 30s timeout, and the pool has no busy-check
 * before re-dispatching -- a region thread CAN still be executing after the main thread
 * believes it is safe to proceed. This exemption is why the second live crash surfaced as a
 * raw, hard-to-place {@code NullPointerException} instead of an immediate, fully-contextual
 * ownership violation -- the one diagnostic built specifically to catch this was blind to
 * exactly this scenario. Removed; every call now goes through the same check, no exemption.
 */
public final class NestworldTickOwnership {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private static volatile Thread ownerThread = null;
    private static volatile long lastKnownServerTick = -1;
    private static final AtomicLong violationCount = new AtomicLong();

    private NestworldTickOwnership() {}

    /** Called once per runScheduledTicksPhase invocation so violation reports can include
     * "as of which server tick" context. */
    public static void noteServerTick(long tick) {
        lastKnownServerTick = tick;
    }

    public static long getViolationCount() {
        return violationCount.get();
    }

    /**
     * Assert the calling thread is the same one that has made every previous call here
     * (across ALL LevelTicks instances / dimensions -- deliberately global, since vanilla
     * itself only ever expects ONE thread to ever touch ANY LevelTicks in the whole
     * server). In STRICT mode ({@link NestworldTuning#LEVELTICKS_OWNERSHIP_STRICT}), throws
     * immediately on violation (after logging, so the log has context even if something
     * upstream swallows the exception). In DIAGNOSTIC mode (default), logs a full-context
     * report and counts the violation, but returns normally -- the caller proceeds into
     * the same operation vanilla would have executed anyway, same risk profile as running
     * without this diagnostic at all, just now with a paper trail instead of silence.
     */
    public static void assertOwnerThread(String operation, Object context) {
        Thread current = Thread.currentThread();
        Thread expected = ownerThread;
        if (expected == null) {
            ownerThread = current;
            return;
        }
        if (expected != current) {
            violationCount.incrementAndGet();
            String report = buildViolationReport(operation, context, current, expected);
            LOGGER.error(report);
            if (NestworldTuning.LEVELTICKS_OWNERSHIP_STRICT) {
                throw new RuntimeException(report);
            }
        }
    }

    /** Builds the "---- NestWorld Concurrency ----" style report: everything useful to
     *  pinpoint which region/thread/operation raced the owner, without needing a second
     *  live repro to find out. */
    private static String buildViolationReport(String operation, Object context, Thread current, Thread expected) {
        StringBuilder sb = new StringBuilder();
        sb.append("---- NestWorld Concurrency Violation ----\n");
        sb.append("Operation: LevelTicks.").append(operation).append('\n');
        sb.append("Context: ").append(context).append('\n');
        sb.append("Expected owner thread: '").append(expected.getName()).append("' (id=").append(expected.getId()).append(")\n");
        sb.append("Actual caller thread: '").append(current.getName()).append("' (id=").append(current.getId()).append(")\n");
        sb.append("Global gameTime (last known): ").append(lastKnownServerTick).append('\n');

        if (current instanceof RegionThread rt) {
            WorldRegion r = rt.getRegion();
            long localTick = r.getLocalTickCount();
            long delta = lastKnownServerTick - localTick;
            sb.append("Region: ").append(r.getId()).append('\n');
            sb.append("Free-running: ").append(r.isFreeRunning()).append('\n');
            sb.append("Region localTick: ").append(localTick).append('\n');
            sb.append("Region tick lag (globalTick - localTick): ").append(delta).append('\n');
        } else {
            sb.append("Region: n/a (not a RegionThread)\n");
        }

        sb.append("Mailbox audit (sent/applied/pending/duplicates/misrouted): ")
                .append(MailboxAudit.summary()).append('\n');
        sb.append("Total ownership violations this run: ").append(violationCount.get()).append('\n');
        sb.append("------------------------------------------\n");
        return sb.toString();
    }
}
