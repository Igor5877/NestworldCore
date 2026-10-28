package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * R1-a scope 3 deterministic fixture for ensureConnection (fixtures flag only; main thread; MAIN-domain positions):
 * two adjacent {@code ae2:energy_cell}s, then EXISTING / INVARIANT / CREATED / REJECTED_STALE on their live nodes,
 * each checked by result and counter delta.
 */
public final class TopologyFixtures {
    private static volatile String status = "idle";
    private static volatile Runnable pending;
    private static volatile int evaluateAt = Integer.MAX_VALUE;

    private TopologyFixtures() {
    }

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        Runnable p = pending;
        if (p == null || tick < evaluateAt) return;
        pending = null;
        evaluateAt = Integer.MAX_VALUE;
        p.run();
    }

    public static String ensure(ServerLevel level, BlockPos pa) {
        if (pending != null) return "a fixture is running: " + status;
        BlockPos pb = pa.east();
        if (!level.getBlockState(pa).isAir() || !level.getBlockState(pb).isAir()) return pa + " and its east neighbour must be air";
        Block unit = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "energy_cell"));
        level.setBlockAndUpdate(pa, unit.defaultBlockState());
        level.setBlockAndUpdate(pb, unit.defaultBlockState());
        String type = String.valueOf(net.minecraft.world.level.block.entity.BlockEntityType.getKey(level.getBlockEntity(pa).getType()));
        if (!BeDomainReady.currentThreadIn(level, pa, type) || !BeDomainReady.currentThreadIn(level, pb, type)) {
            level.removeBlock(pa, false);
            level.removeBlock(pb, false);
            return "positions are not in the MAIN execution domain (" + type + "); pick a band position or run with ae2 pinned";
        }
        status = "ENSURE: running";
        evaluateAt = level.getServer().getTickCount() + 20;
        pending = () -> {
            List<String> f = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            try {
                run(level, pa, pb, f, notes);
            } catch (Throwable t) {
                f.add("fixture threw " + t);
            } finally {
                level.removeBlock(pa, false);
                level.removeBlock(pb, false);
            }
            status = "ENSURE" + (f.isEmpty() ? ": PASS" : ": FAIL " + f) + " " + String.join(" ", notes);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] topology fixture {}", status);
        };
        return "ENSURE: started at " + pa + " / " + pb;
    }

    @SuppressWarnings("unchecked")
    private static void run(ServerLevel level, BlockPos pa, BlockPos pb, List<String> f, List<String> notes) throws Exception {
        if (!(level.getBlockEntity(pa) instanceof IGridConnectedBlockEntity ba) || !(level.getBlockEntity(pb) instanceof IGridConnectedBlockEntity bb)) {
            f.add("no AE2 block entities");
            return;
        }
        GridNode a = (GridNode) ba.getMainNode().getNode(), b = (GridNode) bb.getMainNode().getNode();
        if (a == null || b == null) { f.add("nodes not ready (a=" + a + " b=" + b + ")"); return; }
        GridConnection c0 = between(a, b);
        if (c0 == null || between(b, a) != c0) { f.add("AE2 did not connect the two cells"); return; }
        Direction dir = c0.getDirection(a);
        notes.add("dir=" + dir);

        // EXISTING: a repeated create (the TOCTOU loser) returns the existing connection, no ISE, nothing added
        long ex0 = COUNTERS.topoExisting.sum();
        int na = a.getConnections().size(), nb = b.getConnections().size();
        GridConnection r1 = GridConnection.create(a, b, dir);
        if (r1 != c0) f.add("EXISTING: returned " + r1 + " not the existing " + c0);
        if (COUNTERS.topoExisting.sum() - ex0 != 1) f.add("EXISTING: topoExisting not +1");
        if (a.getConnections().size() != na || b.getConnections().size() != nb) f.add("EXISTING: connection count changed");

        // INVARIANT: one-sided connection state is an invariant failure, thrown and counted, never masked
        java.lang.reflect.Field conns = GridNode.class.getDeclaredField("connections");
        conns.setAccessible(true);
        List<GridConnection> bl = (List<GridConnection>) conns.get(b);
        bl.remove(c0);
        long inv0 = COUNTERS.topoInvariantFailure.sum();
        boolean threw = false;
        try {
            GridConnection.create(a, b, dir);
        } catch (IllegalStateException e) {
            threw = e.getMessage() != null && e.getMessage().contains("AE2 topology invariant violated");
        } finally {
            bl.add(c0);                                   // restore the consistent state
        }
        if (!threw) f.add("INVARIANT: no invariant failure for a one-sided connection");
        if (COUNTERS.topoInvariantFailure.sum() - inv0 != 1) f.add("INVARIANT: topoInvariantFailure not +1");

        // CREATED: after a real destroy, create makes exactly one new connection
        c0.destroy();
        if (between(a, b) != null || between(b, a) != null) f.add("CREATED: destroy left a connection");
        long cr0 = COUNTERS.topoCreated.sum();
        GridConnection c1 = GridConnection.create(a, b, dir);
        if (c1 == null || c1 == c0 || between(a, b) != c1 || between(b, a) != c1) f.add("CREATED: connection not created exactly once (" + c1 + ")");
        if (COUNTERS.topoCreated.sum() - cr0 != 1) f.add("CREATED: topoCreated not +1");

        // REJECTED_STALE: a node destroyed between discovery and T0 is not accepted
        bb.getMainNode().destroy();
        long st0 = COUNTERS.topoRejectedStale.sum();
        GridConnection r3 = GridConnection.create(a, b, dir);
        if (r3 != null) f.add("STALE: created " + r3 + " to a destroyed node");
        if (between(a, b) != null) f.add("STALE: a still connected to the destroyed node");
        if (COUNTERS.topoRejectedStale.sum() - st0 != 1) f.add("STALE: topoRejectedStale not +1");
        level.setBlockAndUpdate(pb, Blocks.AIR.defaultBlockState());
    }

    private static GridConnection between(GridNode from, GridNode to) {
        for (var c : from.getConnections()) {
            if (c.getOtherSide(from) == to) return (GridConnection) c;
        }
        return null;
    }
}
