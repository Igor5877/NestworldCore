package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Propagates redstone signals across region boundaries with a maximum
 * delay of 1 game tick (imperceptible in normal gameplay).
 *
 * <p>When a redstone component inside Region A updates a block state
 * that lies on the boundary (within {@link BoundaryManager#GHOST_DEPTH}
 * chunks of another region), it enqueues a {@link SignalEntry} here instead
 * of directly updating the neighbouring region's chunk data.
 *
 * <p>At the start of the next tick (before region threads begin),
 * {@link #flush()} is called by the main thread.  It applies every
 * pending signal update to the live ServerLevel, so Region B's thread
 * will see the updated block state during that tick.
 *
 * <p>Thread-safety: enqueue() is called from RegionThread contexts
 * (concurrent) while flush() is called from the main thread while all
 * region threads are paused at the tick barrier.
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
