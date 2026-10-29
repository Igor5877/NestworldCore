package net.nestworld.compat.ae2.glue;

import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * R1-a scope 3 lifecycle fixtures (fixtures flag only): an energy cell A in a region-interior chunk (REGION domain)
 * next to a cell B in the adjacent border-band chunk (MAIN domain). A is mutated in its own domain through Core's
 * enqueueForBlockEntityDomain, B on main — no foreign-thread world write. After every settled step the validator checks
 * the topology invariant: both present → exactly one shared connection object and one grid; one present → no
 * connection to the other position. Cases: interior↔band place/remove cycles, region split then merge (A's domain
 * changing each time), chunk unload/reload of both (adjacent chunks unload together).
 */
public final class TopologyLifecycleFixtures {
    private static final String TYPE = "ae2:energy_cell";
    private static volatile String status = "idle";
    private static final List<Step> steps = new ArrayList<>();
    private static volatile int stepIndex = -1;
    private static volatile int evaluateAt = Integer.MAX_VALUE;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> log = new ArrayList<>();

    private record Step(String name, int waitTicks, Runnable action, Consumer<List<String>> check) {}

    private TopologyLifecycleFixtures() {
    }

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        if (stepIndex < 0 || tick < evaluateAt) return;
        Step done = steps.get(stepIndex);
        List<String> f = new ArrayList<>();
        try {
            if (done.check() != null) done.check().accept(f);
        } catch (Throwable t) {
            f.add("check threw " + t);
        }
        for (String s : f) failures.add(done.name() + ": " + s);
        log.add(done.name() + (f.isEmpty() ? " ok" : " FAIL"));
        stepIndex++;
        if (stepIndex >= steps.size()) {
            stepIndex = -1;
            status = "LIFECYCLE" + (failures.isEmpty() ? ": PASS" : ": FAIL " + failures) + " steps=" + log.size()
                    + " counters{" + counters() + "}";
            Ae2Compat.LOG.info("[nestworld-compat-ae2] topology lifecycle fixture {}", status);
            return;
        }
        start(tick);
    }

    private static void start(int tick) {
        Step s = steps.get(stepIndex);
        status = "LIFECYCLE: running " + s.name() + " (" + (stepIndex + 1) + "/" + steps.size() + ")";
        try {
            s.action().run();
        } catch (Throwable t) {
            failures.add(s.name() + ": action threw " + t);
        }
        evaluateAt = tick + s.waitTicks();
    }

    private static String counters() {
        var c = Ae2Compat.COUNTERS;
        return "topoCreated=" + c.topoCreated.sum() + " topoExisting=" + c.topoExisting.sum() + " topoStale="
                + c.topoRejectedStale.sum() + " topoInvariant=" + c.topoInvariantFailure.sum() + " cbDeferred="
                + c.cbDeferred.sum() + " cbRanInDomain=" + c.cbRanInDomain.sum() + " cbStale=" + c.cbStale.sum()
                + " cbDropped=" + c.cbDropped.sum() + " cbFailed=" + c.cbFailed.sum();
    }

    public static String run(ServerLevel level, BlockPos a, int cycles) {
        if (stepIndex >= 0) return "a lifecycle fixture is running: " + status;
        BlockPos b = a.west();
        String da = BeDomainReady.domainName(level, a, TYPE), db = BeDomainReady.domainName(level, b, TYPE);
        if (!da.startsWith("REGION#") || !"MAIN".equals(db)) {
            return "need A in a REGION domain and its west neighbour B in MAIN (got A=" + da + " B=" + db
                    + "); use a region's first interior chunk column and run with ae2 unpinned";
        }
        if (!level.getBlockState(a).isAir() || !level.getBlockState(b).isAir()) return "A and B must be air";
        Block cell = BuiltInRegistries.BLOCK.get(new ResourceLocation("ae2", "energy_cell"));
        steps.clear();
        failures.clear();
        log.clear();
        Runnable placeA = () -> inDomain(level, a, () -> level.setBlockAndUpdate(a, cell.defaultBlockState()));
        Runnable removeA = () -> inDomain(level, a, () -> level.setBlockAndUpdate(a, Blocks.AIR.defaultBlockState()));
        Runnable placeB = () -> level.setBlockAndUpdate(b, cell.defaultBlockState());
        Runnable removeB = () -> level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
        Consumer<List<String>> both = f -> validate(level, a, b, true, true, f);
        Consumer<List<String>> onlyA = f -> validate(level, a, b, true, false, f);
        Consumer<List<String>> onlyB = f -> validate(level, a, b, false, true, f);

        steps.add(new Step("forceload", 40, () -> forceload(level, a, b, true), null));
        steps.add(new Step("place-A(region)", 20, placeA, null));
        steps.add(new Step("place-B(main)", 30, placeB, both));
        for (int k = 1; k <= cycles; k++) {
            steps.add(new Step("c" + k + "-remove-B", 20, removeB, onlyA));
            steps.add(new Step("c" + k + "-place-B", 30, placeB, both));
            steps.add(new Step("c" + k + "-remove-A", 20, removeA, onlyB));
            steps.add(new Step("c" + k + "-place-A", 30, placeA, both));
        }
        String[] before = new String[1];
        steps.add(new Step("split-A-region", 40, () -> {
            before[0] = BeDomainReady.domainName(level, a, TYPE);
            command(level, "nestworld split " + before[0].substring("REGION#".length()));
        }, f -> {
            String now = BeDomainReady.domainName(level, a, TYPE);
            log.add("split " + before[0] + " -> " + now);
            if (now.equals(before[0])) f.add("A's domain did not change after the split (" + now + ")");
            validate(level, a, b, true, true, f);
        }));
        steps.add(new Step("split-remove-B", 20, removeB, onlyA));
        steps.add(new Step("split-place-B", 30, placeB, both));
        steps.add(new Step("split-remove-A", 20, removeA, onlyB));
        steps.add(new Step("split-place-A", 30, placeA, both));
        String[] beforeMerge = new String[1];
        steps.add(new Step("merge-A-region", 40, () -> {
            beforeMerge[0] = BeDomainReady.domainName(level, a, TYPE);
            String sib = siblingId(level, Integer.parseInt(beforeMerge[0].substring("REGION#".length())));
            if (sib == null) {
                failures.add("merge: no tree sibling found for " + beforeMerge[0]);
                return;
            }
            command(level, "nestworld merge " + beforeMerge[0].substring("REGION#".length()) + " " + sib);
        }, f -> {
            String now = BeDomainReady.domainName(level, a, TYPE);
            log.add("merge " + beforeMerge[0] + " -> " + now);
            if (now.equals(beforeMerge[0])) f.add("A's domain did not change after the merge (" + now + ")");
            validate(level, a, b, true, true, f);
        }));
        steps.add(new Step("merge-remove-B", 20, removeB, onlyA));
        steps.add(new Step("merge-place-B", 30, placeB, both));
        steps.add(new Step("merge-remove-A", 20, removeA, onlyB));
        steps.add(new Step("merge-place-A", 30, placeA, both));
        steps.add(new Step("unload", 200, () -> {
            removeForceload(level, a, b);
        }, f -> {
            if (level.getChunkSource().getChunkNow(a.getX() >> 4, a.getZ() >> 4) != null) f.add("A's chunk still loaded after 200 ticks");
        }));
        steps.add(new Step("reload", 60, () -> forceload(level, a, b, true), both));
        steps.add(new Step("reload-remove-B", 20, removeB, onlyA));
        steps.add(new Step("reload-place-B", 30, placeB, both));
        steps.add(new Step("cleanup", 20, () -> {
            removeB.run();
            removeA.run();
        }, f -> validate(level, a, b, false, false, f)));
        steps.add(new Step("unforce", 1, () -> removeForceload(level, a, b), null));
        stepIndex = 0;
        start(level.getServer().getTickCount());
        return "LIFECYCLE: started A=" + a + " (" + da + ") B=" + b + " (" + db + ") steps=" + steps.size();
    }

    /** The other child of region {@code id}'s last split: same extent on one axis, adjacent on the other
     *  (Core's public grid API, reflectively — the addon has no Core compile dependency). */
    private static String siblingId(ServerLevel level, int id) {
        try {
            Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
            Object sys = sysC.getMethod("get").invoke(null);
            Object dr = sysC.getMethod("getDimensionRegion", net.minecraft.world.level.Level.class).invoke(sys, level);
            Object grid = dr.getClass().getMethod("getGrid").invoke(dr);
            java.util.Collection<?> regions = (java.util.Collection<?>) grid.getClass().getMethod("getAllRegions").invoke(grid);
            Object me = null;
            for (Object r : regions) if ((int) r.getClass().getMethod("getId").invoke(r) == id) me = r;
            if (me == null) return null;
            int[] m = bounds(me);
            for (Object r : regions) {
                if (r == me) continue;
                int[] o = bounds(r);
                boolean sameX = o[0] == m[0] && o[2] == m[2], sameZ = o[1] == m[1] && o[3] == m[3];
                boolean adjZ = o[3] + 1 == m[1] || m[3] + 1 == o[1], adjX = o[2] + 1 == m[0] || m[2] + 1 == o[0];
                if ((sameX && adjZ) || (sameZ && adjX)) return String.valueOf(r.getClass().getMethod("getId").invoke(r));
            }
        } catch (Throwable t) {
            failures.add("sibling lookup failed: " + t);
        }
        return null;
    }

    private static int[] bounds(Object r) throws Exception {
        return new int[]{(int) r.getClass().getMethod("getMinChunkX").invoke(r), (int) r.getClass().getMethod("getMinChunkZ").invoke(r),
                (int) r.getClass().getMethod("getMaxChunkX").invoke(r), (int) r.getClass().getMethod("getMaxChunkZ").invoke(r)};
    }

    private static void inDomain(ServerLevel level, BlockPos pos, Runnable op) {
        try {
            BeDomainReady.enqueueTo(level, pos, TYPE, op, k -> failures.add("domain task at " + pos + " dropped: " + k));
        } catch (Throwable t) {
            failures.add("enqueue failed at " + pos + ": " + t);
        }
    }

    private static void command(ServerLevel level, String cmd) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }

    private static void forceload(ServerLevel level, BlockPos a, BlockPos b, boolean on) {
        level.setChunkForced(a.getX() >> 4, a.getZ() >> 4, on);
        level.setChunkForced(b.getX() >> 4, b.getZ() >> 4, on);
    }

    private static void removeForceload(ServerLevel level, BlockPos a, BlockPos b) {
        forceload(level, a, b, false);
    }

    private static GridNode node(ServerLevel level, BlockPos p) {
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) return null;
        BlockEntity be = level.getBlockEntity(p);
        return be instanceof IGridConnectedBlockEntity h ? (GridNode) h.getMainNode().getNode() : null;
    }

    /** Topology invariant at a settled point (no mutation pending on either side). */
    private static void validate(ServerLevel level, BlockPos a, BlockPos b, boolean expectA, boolean expectB, List<String> f) {
        GridNode na = node(level, a), nb = node(level, b);
        if (expectA != (na != null)) f.add("A node " + (na == null ? "missing" : "present") + ", expected " + (expectA ? "present" : "absent"));
        if (expectB != (nb != null)) f.add("B node " + (nb == null ? "missing" : "present") + ", expected " + (expectB ? "present" : "absent"));
        if (na != null && nb != null) {
            int ab = count(na, nb), ba = count(nb, na);
            GridConnection c = between(na, nb);
            if (ab != 1 || ba != 1 || c == null || between(nb, na) != c) f.add("expected exactly one shared connection, a->b=" + ab + " b->a=" + ba);
            if (na.getGrid() != nb.getGrid()) f.add("A and B in different grids");
        }
        if (na != null && nb == null && connectsTo(na, b)) f.add("A still connected to a node at B's position");
        if (nb != null && na == null && connectsTo(nb, a)) f.add("B still connected to a node at A's position");
    }

    private static int count(GridNode from, GridNode to) {
        int n = 0;
        for (var c : from.getConnections()) if (c.getOtherSide(from) == to) n++;
        return n;
    }

    private static GridConnection between(GridNode from, GridNode to) {
        for (var c : from.getConnections()) if (c.getOtherSide(from) == to) return (GridConnection) c;
        return null;
    }

    private static boolean connectsTo(GridNode from, BlockPos pos) {
        for (var c : from.getConnections()) {
            if (c.getOtherSide(from).getOwner() instanceof BlockEntity be && be.getBlockPos().equals(pos)) return true;
        }
        return false;
    }
}
