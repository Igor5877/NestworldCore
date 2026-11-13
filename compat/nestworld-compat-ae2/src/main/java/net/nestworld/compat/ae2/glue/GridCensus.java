package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import appeng.parts.AEBasePart;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.parts.storagebus.StorageBusPart;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-GRID-TICK-DOMAIN-001 audit tool (read-only diagnostics). While {@link #on}: per-grid wall time of
 * {@code Grid.onLevelEndTick} / {@code Grid.onServerEndTick} (measured in GridMixin's phase wrappers). The report classifies
 * every grid by where its nodes live (levels, current BE execution domains) and by links that would make it unsafe to
 * tick off main (storage bus facing another grid's node, P2P tunnels, quantum bridges). Off by default; when off the
 * only cost is one volatile read per grid phase.
 */
public final class GridCensus {
    public static volatile boolean on;
    private static volatile long startNanos, startTick;
    /** grid → {levelEndNanos, serverEndNanos, levelEndCalls, serverEndCalls}; main thread only (grid phases run there). */
    private static final Map<Grid, long[]> COST = Collections.synchronizedMap(new IdentityHashMap<>());

    private GridCensus() {
    }

    public static void record(Grid grid, boolean serverEnd, long nanos) {
        long[] c = COST.computeIfAbsent(grid, g -> new long[4]);
        c[serverEnd ? 1 : 0] += nanos;
        c[serverEnd ? 3 : 2]++;
    }

    /** device class → {nanos, calls, domainMain, domainRegion}; recorded on the thread running the device tick. */
    private static final Map<String, long[]> DEVICE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordDevice(IGridNode node, long nanos) {
        Object owner = node.getOwner();
        String cls = owner == null ? "null" : owner.getClass().getName();
        BlockEntity be = owner instanceof BlockEntity b ? b
                : owner instanceof AEBasePart p && p.getHost() != null ? p.getHost().getBlockEntity() : null;
        boolean main = true;
        if (be != null && node.getLevel() != null) {
            main = BeDomainReady.domainName(node.getLevel(), be.getBlockPos(), BeDomainReady.typeOf(be)).equals("MAIN");
        }
        long[] c = DEVICE.computeIfAbsent(cls, k -> new long[4]);
        synchronized (c) {
            c[0] += nanos;
            c[1]++;
            c[main ? 2 : 3]++;
        }
        // AE2-GRID-DOMAIN-MVP-001: per-grid device cost (grid #1 census: impact per blocker class)
        var grid = node.getGrid();
        if (grid != null) {
            Map<String, long[]> per;
            synchronized (DEVICE_BY_GRID) { per = DEVICE_BY_GRID.computeIfAbsent(grid, g -> new java.util.concurrent.ConcurrentHashMap<>()); }
            long[] d = per.computeIfAbsent(cls, k -> new long[2]);
            synchronized (d) { d[0] += nanos; d[1]++; }
        }
    }

    private static final Map<Object, Map<String, long[]>> DEVICE_BY_GRID = new java.util.WeakHashMap<>();

    /** device class → {nanos, calls} of one grid since {@link #start}. */
    static Map<String, long[]> devicesOf(Object grid) {
        synchronized (DEVICE_BY_GRID) {
            var m = DEVICE_BY_GRID.get(grid);
            return m == null ? Map.of() : new java.util.HashMap<>(m);
        }
    }

    static long ticksSinceStart(int serverTick) { return Math.max(1, serverTick - startTick); }

    public static String devices(int serverTick, int top) {
        long ticks = Math.max(1, serverTick - startTick);
        List<Map.Entry<String, long[]>> l = new ArrayList<>(DEVICE.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("device ticks over " + ticks + " ticks (domain = where the device BE would tick):");
        for (int i = 0; i < Math.min(top, l.size()); i++) {
            long[] c = l.get(i).getValue();
            sb.append(String.format("%n%.3f ms/tick calls/tick=%.1f mainDomain=%d regionDomain=%d %s", c[0] / 1e6 / ticks,
                    (double) c[1] / ticks, c[2], c[3], l.get(i).getKey()));
        }
        return sb.toString();
    }

    public static String start(int serverTick) {
        COST.clear();
        synchronized (DEVICE_BY_GRID) { DEVICE_BY_GRID.clear(); }
        DEVICE.clear();
        startNanos = System.nanoTime();
        startTick = serverTick;
        on = true;
        return "grid census started";
    }

    public static String report(int serverTick, int top) {
        long ticks = Math.max(1, serverTick - startTick);
        on = false;
        List<String> rows = new ArrayList<>();
        long totalNanos = 0;
        int grids = 0, singleDomain = 0, linked = 0;
        long singleDomainNanos = 0, linkedNanos = 0;
        List<Object[]> all = new ArrayList<>();
        for (Grid g : TickHandler.instance().getGridList()) {
            grids++;
            long[] c = COST.getOrDefault(g, new long[4]);
            long nanos = c[0] + c[1];
            totalNanos += nanos;
            Map<String, Integer> domains = new TreeMap<>();
            Map<String, Integer> levels = new TreeMap<>();
            int storageBusToGrid = 0, p2p = 0, qnb = 0, nodes = 0;
            for (IGridNode n : g.getNodes()) {
                nodes++;
                Object owner = n.getOwner();
                BlockEntity be = owner instanceof BlockEntity b ? b
                        : owner instanceof AEBasePart p && p.getHost() != null ? p.getHost().getBlockEntity() : null;
                ServerLevel level = n.getLevel();
                if (level != null) levels.merge(level.dimension().location().toString(), 1, Integer::sum);
                if (be != null && level != null) {
                    domains.merge(BeDomainReady.domainName(level, be.getBlockPos(), BeDomainReady.typeOf(be)), 1, Integer::sum);
                }
                if (owner instanceof P2PTunnelPart<?>) p2p++;
                if (be != null && be.getClass().getName().contains("QuantumBridge")) qnb++;
                if (owner instanceof StorageBusPart sb && be != null && level != null) {
                    BlockPos facing = be.getBlockPos().relative(((AEBasePart) sb).getSide());
                    BlockEntity target = level.isLoaded(facing) ? level.getBlockEntity(facing) : null;
                    if (target instanceof appeng.api.networking.IInWorldGridNodeHost host) {
                        IGridNode tn = host.getGridNode(((AEBasePart) sb).getSide().getOpposite());
                        if (tn != null && tn.getGrid() != g) storageBusToGrid++;
                    }
                }
            }
            boolean multi = domains.size() > 1 || levels.size() > 1;
            boolean link = storageBusToGrid > 0 || qnb > 0;
            if (!multi && !link) {
                singleDomain++;
                singleDomainNanos += nanos;
            }
            if (link) {
                linked++;
                linkedNanos += nanos;
            }
            all.add(new Object[]{nanos, String.format("nodes=%d ms/tick=%.3f (levelEnd %.3f, serverEnd %.3f) levels=%s domains=%s"
                            + " storageBus->otherGrid=%d p2p=%d qnb=%d",
                    nodes, nanos / 1e6 / ticks, c[0] / 1e6 / ticks, c[1] / 1e6 / ticks, levels, domains, storageBusToGrid, p2p, qnb)});
        }
        all.sort((a, b) -> Long.compare((long) b[0], (long) a[0]));
        for (int i = 0; i < Math.min(top, all.size()); i++) rows.add("#" + (i + 1) + " " + all.get(i)[1]);
        return String.format("grid census over %d ticks (%.1f s): grids=%d totalGridMs/tick=%.2f | single-domain unlinked: %d grids,"
                        + " %.2f ms/tick | linked (storage bus to another grid / QNB): %d grids, %.2f ms/tick%n%s",
                ticks, (System.nanoTime() - startNanos) / 1e9, grids, totalNanos / 1e6 / ticks, singleDomain,
                singleDomainNanos / 1e6 / ticks, linked, linkedNanos / 1e6 / ticks, String.join("\n", rows));
    }
}
