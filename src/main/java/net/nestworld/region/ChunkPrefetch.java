package net.nestworld.region;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Prepare the core for it" (2026-08-22, live ATM9 session): safe, in-core equivalent
 * of what an external mod (a Forge-Client-Reset-Packet / FastLoginMod fork) was doing
 * via {@code @Overwrite}-replacing {@code RegionFileStorage.read()} and bypassing
 * {@code IOWorker}'s mailbox entirely with its own parallel pool -- fast, but an
 * untested new concurrent-access surface layered on top of a region-sharded core that
 * has found dozens of subtle races from exactly this shape of change (a second,
 * independently-developed source of concurrency touching the same vanilla chunk-
 * loading internals our own region threads also touch).
 *
 * <p>This does the same underlying thing (read chunk NBT off the vanilla mailbox
 * thread, in parallel, ahead of need) but WITHOUT bypassing {@code IOWorker}'s
 * mailbox-serialization guarantee against in-flight writes: a prefetched chunk is
 * only ever consumed from inside {@code IOWorker.loadAsync()}'s own mailbox-
 * serialized task, AFTER the existing {@code pendingWrites} check (a pending write
 * always wins -- see {@code IOWorker.loadAsync}'s javadoc). The prefetch only removes
 * the cost of the disk read itself from that mailbox task -- the read already
 * happened earlier, on one of this class's own worker threads, calling {@link
 * net.minecraft.world.level.chunk.storage.RegionFileStorage#nestworldReadConcurrent}
 * (safe for true concurrent multi-caller use, unlike {@code read()} -- see its
 * javadoc for why).
 *
 * <p>Exposed publicly via {@code net.nestworld.api.NestworldApi#prefetchChunks} so an
 * external mod that wants this speed benefit can call OUR tested implementation
 * instead of reaching into vanilla internals itself with its own Mixin.
 */
public final class ChunkPrefetch {
    private ChunkPrefetch() {}

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    /** Sized like the external mod's own pool was (min(8, cores/2)) -- same reasoning:
     *  enough to saturate NVMe random-read IOPS without thrashing OS cache. Daemon so
     *  a lingering prefetch task can never block JVM shutdown. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)),
            (ThreadFactory) r -> {
                Thread t = new Thread(r, "NestWorld-ChunkPrefetch-" + THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private static final AtomicLong prefetched = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();

    public static long prefetchedTotal() { return prefetched.get(); }
    public static long failedTotal() { return failed.get(); }
    public static long missesTotal() { return misses.get(); }

    /**
     * Reads {@code positions}' raw chunk NBT off {@code level}'s disk storage in
     * parallel, ahead of need, and offers each result into the IOWorker's own
     * prefetch cache so a subsequent {@code loadAsync()} call for that position
     * (typically triggered moments later by normal chunk-loading demand, e.g. a
     * joining player's view-distance area) resolves near-instantly instead of
     * queueing a fresh disk read behind the mailbox.
     *
     * <p>Fire-and-forget: does not return a future, since the whole point is that the
     * caller doesn't need to wait on this -- normal chunk loading proceeds exactly as
     * it always did, this just tends to already have the answer cached by the time
     * that normal path gets around to asking. Safe to call redundantly (e.g. for a
     * chunk that's already loaded, or gets requested before the prefetch finishes) --
     * worst case is a wasted disk read, never a correctness issue.
     */
    public static void prefetch(ServerLevel level, Collection<ChunkPos> positions) {
        IOWorker worker = level.getChunkSource().chunkMap.nestworldIOWorker();
        var storage = worker.nestworldStorage();
        for (ChunkPos pos : positions) {
            POOL.execute(() -> {
                try {
                    CompoundTag tag = storage.nestworldReadConcurrent(pos);
                    if (tag != null) {
                        worker.nestworldOfferPrefetch(pos, tag);
                        prefetched.incrementAndGet();
                    } else {
                        misses.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                }
            });
        }
    }
}
