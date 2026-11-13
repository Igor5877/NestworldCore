package net.nestworld.compat.ae2.glue;

import appeng.blockentity.ServerTickingBlockEntity;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.Grid;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-GRID-DOMAIN-001 model A, HOST_ESCAPE_MAIN (owner 2026-11-12): a claimed host whose tick reached a MAIN-only
 * operation (ExtendedAE wireless / AE2 QNB {@code updateStatus}: remote lookup, global Locatables, connection
 * create/destroy) is NOT run on the AE2 domain thread and does NOT demote its grid. Instead:
 * <ol>
 * <li>worker: at most ONE outstanding escape per host (identity); further requests coalesce; while pending the worker
 *     skips that host's tick (its tick does work only under the flag, which stays set);</li>
 * <li>MAIN, at the ownership barrier right after the domain await (domain idle — the same point MAIN topology already
 *     uses): revalidate identity (not removed, still the BE at its position), run the UNCHANGED original serverTick;</li>
 * <li>the host returns to the domain automatically (pending cleared). Only a failing body demotes the grid (explicit).</li>
 * </ol>
 * Ordinary power/channel/boot transitions therefore never change grid ownership. Latency: ≤ 1 tick (next barrier).
 */
public final class HostEscape {
    private HostEscape() {}

    private static final Map<BlockEntity, Boolean> PENDING = new ConcurrentHashMap<>();   // BE: identity equals
    private static final ConcurrentLinkedQueue<BlockEntity> QUEUE = new ConcurrentLinkedQueue<>();
    static final LongAdder requested = new LongAdder(), coalesced = new LongAdder(), executed = new LongAdder(),
            stale = new LongAdder(), failures = new LongAdder(), deferredBarrier = new LongAdder();
    private static final Map<String, LongAdder> BY_HOST = new ConcurrentHashMap<>();
    private static boolean draining;   // MAIN only

    /** AE2 worker: request a one-shot MAIN escape for this host; true = handled here (skip the tick on the worker). */
    public static boolean request(BlockEntity be) {
        if (PENDING.putIfAbsent(be, Boolean.TRUE) != null) {
            coalesced.increment();
            return true;
        }
        requested.increment();
        BY_HOST.computeIfAbsent(be.getClass().getSimpleName() + (be.getLevel() == null ? "" : " " + be.getLevel().dimension().location())
                + " " + be.getBlockPos().toShortString(), k -> new LongAdder()).increment();
        QUEUE.add(be);
        return true;
    }

    /** MAIN, domain idle (GridWorker.awaitAll after the await). */
    static void drainOnMain() {
        if (draining || QUEUE.isEmpty()) return;
        if (BusTransit.commitPassRunning()) { deferredBarrier.increment(); return; }   // next barrier
        draining = true;
        try {
            for (BlockEntity be; (be = QUEUE.poll()) != null; ) {
                try {
                    if (be.isRemoved() || be.getLevel() == null || be.getLevel().getBlockEntity(be.getBlockPos()) != be
                            || !(be instanceof ServerTickingBlockEntity ticking)) {
                        stale.increment();
                        continue;
                    }
                    ticking.serverTick();          // original body (the defer mixins pass through off the worker)
                    executed.increment();
                } catch (Throwable t) {
                    failures.increment();
                    Ae2Compat.LOG.error("[nestworld-compat-ae2] host escape failed at {} — grid demoted", be.getBlockPos(), t);
                    if (be instanceof AENetworkBlockEntity n && n.getMainNode().getNode() != null
                            && n.getMainNode().getNode().getGrid() instanceof Grid g) GridWorker.requestDemotion(g);
                } finally {
                    PENDING.remove(be);
                }
            }
        } finally {
            draining = false;
        }
    }

    static String status() {
        var l = new ArrayList<>(BY_HOST.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        StringBuilder sb = new StringBuilder("hostEscape{requested=" + requested.sum() + " coalesced=" + coalesced.sum()
                + " executed=" + executed.sum() + " stale=" + stale.sum() + " failures(demote)=" + failures.sum()
                + " deferredBarrier=" + deferredBarrier.sum() + " pending=" + PENDING.size() + "} top:");
        for (int i = 0; i < Math.min(8, l.size()); i++) sb.append("\n  ").append(l.get(i).getValue().sum()).append("  ").append(l.get(i).getKey());
        return sb.toString();
    }
}
