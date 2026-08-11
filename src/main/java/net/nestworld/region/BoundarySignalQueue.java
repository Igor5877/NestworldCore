package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * <b>DEAD CODE as of 2026-08-09 — {@link #enqueue} is never called by any code in this
 * repository</b> (confirmed by grepping NestworldCore's own sources and every vanilla
 * patch). The class was designed to propagate redstone signals across region boundaries
 * with a 1-tick delay, but nothing was ever wired up to actually call {@link #enqueue};
 * {@link #flush} runs every tick as a cheap no-op ({@code queue} is always empty). The
 * REAL, actually-wired equivalent mechanism is {@link NestworldRegionSystem}'s
 * {@code deferredWireUpdates} queue (posted to by {@link NestworldRedstone} on a
 * {@code WireHandler.NetworkOutOfBounds}), which now uses the {@link RegionMessage}
 * shape (see {@code docs/LOCAL_TICK_STAGE4.md}, Stage 2). Kept for now rather than
 * deleted (still constructed and its harmless no-op {@code flush()} still runs) — a
 * cleanup candidate for a future pass, not touched here to keep this change scoped.
 *
 * <p>Original design intent, for whoever picks this up: propagate redstone signals
 * across region boundaries with a maximum delay of 1 game tick (imperceptible in normal
 * gameplay). When a redstone component inside Region A updates a block state that lies
 * on the boundary (within {@link BoundaryManager#GHOST_DEPTH} chunks of another region),
 * it would enqueue a {@link SignalEntry} here instead of directly updating the
 * neighbouring region's chunk data; {@link #flush()} (main thread, start of next tick)
 * would apply every pending update to the live ServerLevel.
 *
 * <p>Thread-safety (as designed): enqueue() from RegionThread contexts (concurrent),
 * flush() from the main thread while all region threads are paused at the tick barrier.
 */
public class BoundarySignalQueue {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SignalQueue");

    private final ServerLevel level;
    private final Queue<SignalEntry> queue = new ConcurrentLinkedQueue<>();

    public BoundarySignalQueue(ServerLevel level) {
        this.level = level;
    }

    // -----------------------------------------------------------------------
    // Called from RegionThread context
    // -----------------------------------------------------------------------

    /**
     * Enqueues a redstone-signal update that crosses a region boundary.
     *
     * @param pos          the block position that changed (in Region B's territory)
     * @param newState     the updated block state carrying the new signal
     * @param sourceRegion the region that initiated the update
     */
    public void enqueue(BlockPos pos, BlockState newState, WorldRegion sourceRegion) {
        queue.add(new SignalEntry(pos.immutable(), newState, sourceRegion));
    }

    // -----------------------------------------------------------------------
    // Called from main thread at the start of each tick (before threads start)
    // -----------------------------------------------------------------------

    /**
     * Applies all queued cross-boundary signal updates to the world.
     * Must be called while all RegionThreads are paused at the tick barrier.
     */
    public void flush() {
        if (queue.isEmpty()) return;

        int applied = 0;
        SignalEntry entry;
        while ((entry = queue.poll()) != null) {
            try {
                // setBlock with no flags so it doesn't recursively fire neighbour updates
                // that might re-enqueue and cause cascades — vanilla update logic will
                // handle propagation within the target region on its own tick.
                level.setBlock(entry.pos, entry.newState, 2);
                applied++;
            } catch (Throwable t) {
                LOGGER.warn("Failed to apply boundary signal at {}: {}", entry.pos, t.getMessage());
            }
        }

        if (applied > 0) {
            LOGGER.debug("Flushed {} cross-boundary signal(s)", applied);
        }
    }

    public boolean isEmpty() { return queue.isEmpty(); }

    // -----------------------------------------------------------------------
    // Entry record
    // -----------------------------------------------------------------------

    private record SignalEntry(BlockPos pos, BlockState newState, WorldRegion sourceRegion) {}
}
