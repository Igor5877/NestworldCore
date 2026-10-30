package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import appeng.me.GridNode;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.mixin.GridNodeAccessor;

/**
 * Generic transport for AE2 node-listener callbacks owned by a world host (owner decisions 2026-10-29 (13)): if the
 * current thread is the host's CURRENT block-entity execution domain (Core canonical resolver) → invoke inline;
 * otherwise enqueueForBlockEntityDomain → re-resolve (Core) → revalidate host lifecycle (not removed, still the BE at
 * its position, node still ready) → invoke. Exact emission, exact per-domain FIFO, no coalescing. Callers stay narrow
 * and audited: {@link Channel#TOPOLOGY} = R1-a scope 3 (listener intents recorded under T0); {@link Channel#STATE} =
 * AE2-NODE-LISTENER-ENDTICK-001 (GRID_BOOT / POWER / CHANNEL from AE2's end-of-tick emitters only);
 * {@link Channel#BLOCK} = AE2-PIN-LEAK-001 D2 (void AE2 block callbacks: crafting / QNB / spatial pylon neighbour
 * updates, cable bus neighbour updates and its updateShape side effect).
 * Accounting per channel: emitted = inline + deferred; deferred = ranInDomain + stale + dropped (+ in flight).
 */
public final class HostDomainCallbackDelivery {
    public enum Channel { TOPOLOGY, STATE, BLOCK }

    /** True while a routed callback is being delivered on this thread (observable by the race detector, by name). */
    public static final ThreadLocal<boolean[]> DELIVERING = ThreadLocal.withInitial(() -> new boolean[1]);

    /** Fixture-only observer (null in production). */
    public interface Observer {
        void emitted(Channel ch, GridNode node, String label, BlockEntity host, boolean deferred);

        void delivered(Channel ch, GridNode node, String label, BlockEntity host);

        void stale(Channel ch, GridNode node, String label, BlockEntity host);
    }

    public static volatile Observer observer;

    private HostDomainCallbackDelivery() {
    }

    static BlockEntity hostOf(Object owner) {
        if (owner instanceof BlockEntity be) return be;
        if (owner instanceof appeng.parts.AEBasePart part) return part.getBlockEntity();
        return null;
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

    private static LongAdder c(Channel ch, LongAdder topology, LongAdder state, LongAdder block) {
        return ch == Channel.TOPOLOGY ? topology : ch == Channel.STATE ? state : block;
    }

    /** Node-owned callback (TOPOLOGY, STATE): host = the node owner's block entity; node must still be ready. */
    public static void deliver(Channel ch, GridNode node, String label, Runnable call) {
        deliverForHost(ch, node, hostOf(node.getOwner()), label, call);
    }

    /** Host-owned callback; {@code node} may be null (BLOCK). */
    public static void deliverForHost(Channel ch, GridNode node, BlockEntity host, String label, Runnable call) {
        var k = COUNTERS;
        if (ch == Channel.STATE) k.stEmitted.increment();
        if (ch == Channel.BLOCK) k.bkEmitted.increment();
        Observer obs = observer;
        if (!BeDomainReady.primitive() || host == null || !(host.getLevel() instanceof ServerLevel level)) {
            c(ch, k.cbNoHost, k.stNoHost, k.bkNoHost).increment();
            if (obs != null) obs.emitted(ch, node, label, host, false);
            runMarked(call);
            if (obs != null) obs.delivered(ch, node, label, host);
            return;
        }
        BlockPos pos = host.getBlockPos();
        String type = BeDomainReady.typeOf(host);
        if (BeDomainReady.currentThreadIn(level, pos, type)) {
            c(ch, k.cbInDomain, k.stInDomain, k.bkInDomain).increment();
            if (obs != null) obs.emitted(ch, node, label, host, false);
            runMarked(call);
            if (obs != null) obs.delivered(ch, node, label, host);
            return;
        }
        c(ch, k.cbDeferred, k.stDeferred, k.bkDeferred).increment();
        if (obs != null) obs.emitted(ch, node, label, host, true);
        Consumer<Object> onTerminal = kind -> {
            c(ch, k.cbDropped, k.stDropped, k.bkDropped).increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] AE2 {} callback {} for {} at {} dropped by the BE domain: {}",
                    ch, label, type, pos.toShortString(), kind);
        };
        try {
            BeDomainReady.enqueueTo(level, pos, type, () -> runInDomain(ch, node, label, host, level, pos, type, call), onTerminal);
        } catch (Throwable t) {
            c(ch, k.cbEnqueueFailed, k.stEnqueueFailed, k.bkEnqueueFailed).increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] BE-domain enqueue failed for an AE2 {} callback at {}: {} -- delivered inline",
                    ch, pos.toShortString(), t.toString());
            runMarked(call);
            if (obs != null) obs.delivered(ch, node, label, host);
        }
    }

    private static void runInDomain(Channel ch, GridNode node, String label, BlockEntity host, ServerLevel level, BlockPos pos,
                                    String type, Runnable call) {
        var k = COUNTERS;
        Observer obs = observer;
        if (host.isRemoved() || level.getBlockEntity(pos) != host
                || (host instanceof NwAeBlockEntity life && life.nw$removed())
                || (node != null && !((GridNodeAccessor) node).nw$ready())) {
            c(ch, k.cbStale, k.stStale, k.bkStale).increment();
            if (obs != null) obs.stale(ch, node, label, host);
            return;
        }
        c(ch, k.cbRanInDomain, k.stRanInDomain, k.bkRanInDomain).increment();
        try {
            runMarked(call);
            if (obs != null) obs.delivered(ch, node, label, host);
        } catch (Throwable t) {
            c(ch, k.cbFailed, k.stFailed, k.bkFailed).increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] AE2 {} callback {} for {} at {} failed in its BE domain on {}",
                    ch, label, type, pos.toShortString(), Thread.currentThread().getName(), t);
            throw t;
        }
    }
}
