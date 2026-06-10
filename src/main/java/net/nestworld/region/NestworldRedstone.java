package net.nestworld.region;

import alternate.current.wire.WireHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Thread-aware entry points for Alternate Current wire updates, called from
 * the RedStoneWireBlock patch hooks.
 *
 * <p>On the main thread the level's unbounded {@code nestworldWireHandler}
 * is used directly. On a region thread the call is routed to that thread's
 * own handler, which is bounded to the region's chunks: if the wire network
 * reaches outside the region, the handler aborts with
 * {@link WireHandler.NetworkOutOfBounds} and the update is re-queued to run
 * on the main thread at the start of the next tick (the same 1-tick boundary
 * latency model as {@link BoundarySignalQueue}).
 *
 * <p>Wire calls re-enter through neighborChanged while the handler is mid
 * update. An out-of-bounds abort must unwind all the way to the outermost
 * frame before the handler is reset — resetting from a nested frame would
 * pull the queues out from under the outer update.
 */
public final class NestworldRedstone {

    private NestworldRedstone() {}

    /** @return true when the update was (or will be) handled by AC and vanilla must skip. */
    public static boolean wireUpdated(ServerLevel level, BlockPos pos) {
        if (Thread.currentThread() instanceof RegionThread rt) {
            WireHandler handler = rt.acWireHandler();
            rt.wireCallDepth++;
            try {
                return handler.onWireUpdated(pos);
            } catch (WireHandler.NetworkOutOfBounds oob) {
                if (rt.wireCallDepth > 1) throw oob; // unwind to outermost frame
                handler.nestworldReset();
                NestworldRegionSystem.get().deferWireUpdate(pos);
                return true;
            } finally {
                rt.wireCallDepth--;
            }
        }
        return level.nestworldWireHandler.onWireUpdated(pos);
    }

    public static void wireAdded(ServerLevel level, BlockPos pos) {
        if (Thread.currentThread() instanceof RegionThread rt) {
            WireHandler handler = rt.acWireHandler();
            rt.wireCallDepth++;
            try {
                handler.onWireAdded(pos);
            } catch (WireHandler.NetworkOutOfBounds oob) {
                if (rt.wireCallDepth > 1) throw oob;
                handler.nestworldReset();
                NestworldRegionSystem.get().deferWireUpdate(pos);
            } finally {
                rt.wireCallDepth--;
            }
            return;
        }
        level.nestworldWireHandler.onWireAdded(pos);
    }

    public static void wireRemoved(ServerLevel level, BlockPos pos, BlockState state) {
        if (Thread.currentThread() instanceof RegionThread rt) {
            WireHandler handler = rt.acWireHandler();
            rt.wireCallDepth++;
            try {
                handler.onWireRemoved(pos, state);
            } catch (WireHandler.NetworkOutOfBounds oob) {
                if (rt.wireCallDepth > 1) throw oob;
                handler.nestworldReset();
                NestworldRegionSystem.get().deferWireUpdate(pos);
            } finally {
                rt.wireCallDepth--;
            }
            return;
        }
        level.nestworldWireHandler.onWireRemoved(pos, state);
    }
}
