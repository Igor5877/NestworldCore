package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.me.GridNode;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.mixin.GridNodeAccessor;

/**
 * AE2-CROSS-REGION-TOPOLOGY-001 / R1-a scope 3 (owner decisions 2026-10-28 (3), (5), (8)): node-listener callbacks
 * ({@code onInWorldConnectionChanged}, {@code onGridChanged}, {@code onStateChanged}, {@code onOwnerChanged}) that AE2
 * fires inside a guarded section are recorded as intents (kernel M1 deferral: FIFO, delivered at the outermost exit
 * after the locks are released) and delivered here in the host's CURRENT block-entity execution domain, resolved by
 * Core's canonical policy — never under T0. Same thread already in the domain → inline (the M1 behaviour). Otherwise
 * the callback is enqueued to that domain and the host is revalidated there (not removed, still the block entity at
 * its position, node still live). Hosts: a block-entity owner itself, or an AE2 part's block entity; an owner with no
 * world host (e.g. a cluster) is delivered inline. Domain-generic: a pinned host resolves to MAIN like any other host.
 */
public final class TopologyCallbacks {
    /** True while a recorded intent is being delivered on this thread (observable by the race detector, by name). */
    public static final ThreadLocal<boolean[]> DELIVERING = ThreadLocal.withInitial(() -> new boolean[1]);

    private TopologyCallbacks() {
    }

    private static void runMarked(Runnable call) {
        boolean[] d = DELIVERING.get();
        boolean prev = d[0];
        d[0] = true;
        try {
            call.run();
        } finally {
            d[0] = prev;
        }
    }

    /** Intent recording point: inside a section → deferred; outside → unchanged (inline). */
    public static boolean record(GridNode node, Runnable call) {
        if (!Ae2Compat.KERNEL.defer(() -> deliver(node, call))) return false;
        COUNTERS.cbRecorded.increment();
        return true;
    }

    static BlockEntity hostOf(Object owner) {
        if (owner instanceof BlockEntity be) return be;
        if (owner instanceof appeng.parts.AEBasePart part) return part.getBlockEntity();
        return null;
    }

    static void deliver(GridNode node, Runnable call) {
        BlockEntity host = hostOf(node.getOwner());
        if (!BeDomainReady.primitive() || host == null || !(host.getLevel() instanceof ServerLevel level)) {
            COUNTERS.cbNoHost.increment();
            runMarked(call);
            return;
        }
        BlockPos pos = host.getBlockPos();
        String type = BeDomainReady.typeOf(host);
        if (BeDomainReady.currentThreadIn(level, pos, type)) {
            COUNTERS.cbInDomain.increment();
            runMarked(call);
            return;
        }
        COUNTERS.cbDeferred.increment();
        Consumer<Object> onTerminal = kind -> {
            COUNTERS.cbDropped.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] AE2 node callback for {} at {} dropped by the BE domain: {}",
                    type, pos.toShortString(), kind);
        };
        try {
            BeDomainReady.enqueueTo(level, pos, type, () -> runInDomain(node, host, level, pos, type, call), onTerminal);
        } catch (Throwable t) {
            COUNTERS.cbEnqueueFailed.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] BE-domain enqueue failed for an AE2 node callback at {}: {} -- delivered inline",
                    pos.toShortString(), t.toString());
            runMarked(call);
        }
    }

    private static void runInDomain(GridNode node, BlockEntity host, ServerLevel level, BlockPos pos, String type, Runnable call) {
        if (host.isRemoved() || level.getBlockEntity(pos) != host || !((GridNodeAccessor) node).nw$ready()) {
            COUNTERS.cbStale.increment();
            return;
        }
        COUNTERS.cbRanInDomain.increment();
        try {
            runMarked(call);
        } catch (Throwable t) {
            COUNTERS.cbFailed.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] AE2 node callback for {} at {} failed in its BE domain on {}",
                    type, pos.toShortString(), Thread.currentThread().getName(), t);
            throw t;
        }
    }
}
