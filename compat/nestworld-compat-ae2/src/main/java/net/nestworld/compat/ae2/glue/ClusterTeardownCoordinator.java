package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-PIN-LEAK-001 D4a (owner decisions 2026-10-31 (17); audit docs/compat/AE2-PIN-LEAK-001-D4a-audit.md): AE2 multiblock
 * cluster teardown as an exactly-once cluster-domain operation. Domain-generic — no pin knowledge:
 * <ul>
 * <li><b>claim</b>: per cluster instance (CAS, {@link NwCluster}); later requests for the same cluster are no-ops;</li>
 * <li><b>cluster-state transition</b> (job cancel, inventory capture, isDestroyed, member list) runs once in the ANCHOR
 * member's current BE execution domain (crafting core, QNB center, pylon: lowest member position) — non-cancellable;
 * a Core terminal falls back to the server thread (counted);</li>
 * <li><b>member mutation intents</b> ({@code updateStatus} of each member inside {@code *Cluster.destroy}) run in each
 * member's current domain, revalidated: the member still belongs to the torn-down cluster (else skipped — a re-formed
 * cluster is never touched) and the host is not removed;</li>
 * <li><b>drop intents</b> (cluster inventory) run in the domain owning the drop position — exactly once, never
 * stale-cancelled: capturedItems = deliveredItems + explicitly retainedItems (logged).</li>
 * </ul>
 * Inside a kernel section every request is deferred to the section exit (nothing external under T0). With
 * {@link #ENABLED} off (negative control) AE2's original teardown runs unchanged.
 */
public final class ClusterTeardownCoordinator {
    public static volatile boolean ENABLED = !Boolean.getBoolean("nestworld.compatAe2.clusterTeardownOff");

    /** Fixture-only: may hold a deferred transition (returns true = held; the fixture runs it later). Null in production. */
    public interface Hold {
        boolean hold(Object cluster, Runnable deliver);
    }

    public static volatile Hold hold;

    /** Fixture-only: sees every request (cluster identity, whether it won the claim, label, thread). Null in production. */
    public interface ClaimObserver {
        void requested(Object cluster, boolean claimed, String label);
    }

    public static volatile ClaimObserver claimObserver;

    private ClusterTeardownCoordinator() {
    }

    /** Negative-control observation: coordinator off and AE2's original teardown runs outside the anchor's domain. */
    public static void bypassed(BlockEntity anchor) {
        if (anchor != null && anchor.getLevel() instanceof ServerLevel sl
                && !BeDomainReady.currentThreadIn(sl, anchor.getBlockPos(), BeDomainReady.typeOf(anchor))) {
            COUNTERS.tdBypassedOffDomain.increment();
        }
    }

    // ------------------------------------------------------------------ claim + cluster-state transition

    /**
     * Entry from a per-type adapter. {@code anchor} = the member whose current domain owns the cluster-state transition.
     * Returns false when the cluster was already claimed (caller does nothing).
     */
    public static boolean request(Object cluster, BlockEntity anchor, String label, Runnable transition) {
        COUNTERS.tdRequests.increment();
        if (!(cluster instanceof NwCluster c)) {
            transition.run();
            return true;
        }
        boolean won = c.nw$claimTeardown();
        ClaimObserver obs = claimObserver;
        if (obs != null) obs.requested(cluster, won, label);
        if (!won) {
            COUNTERS.tdDuplicate.increment();
            return false;
        }
        COUNTERS.tdClaimed.increment();
        Runnable deliver = () -> runAt(anchor, label, transition, true);
        if (Ae2Compat.KERNEL.defer(deliver)) {
            COUNTERS.tdDeferredToSectionExit.increment();
            return true;
        }
        Hold h = hold;
        if (h != null && h.hold(cluster, deliver)) return true;
        deliver.run();
        return true;
    }

    /** Runs {@code call} in the current BE execution domain of {@code at}: inline when already there, else enqueued. */
    private static void runAt(BlockEntity at, String label, Runnable call, boolean transitionKind) {
        Level lv = at == null ? null : at.getLevel();
        if (!BeDomainReady.primitive() || !(lv instanceof ServerLevel level)) {
            if (transitionKind) COUNTERS.tdInline.increment();
            call.run();
            return;
        }
        BlockPos pos = at.getBlockPos();
        String type = BeDomainReady.typeOf(at);
        if (BeDomainReady.currentThreadIn(level, pos, type)) {
            COUNTERS.tdInline.increment();
            call.run();
            return;
        }
        COUNTERS.tdDeferred.increment();
        Runnable inDomain = () -> {
            if (!BeDomainReady.currentThreadIn(level, pos, type)) COUNTERS.tdOffDomain.increment();
            COUNTERS.tdRanInDomain.increment();
            call.run();
        };
        enqueueNonCancellable(level, pos, type, label, inDomain, COUNTERS.tdFallback);
    }

    // ------------------------------------------------------------------ member mutation intents

    /**
     * A member's {@code updateStatus} inside {@code *Cluster.destroy}: in the member's current domain, only if the member
     * still belongs to {@code cluster} at execution time.
     */
    public static void member(Object cluster, BlockEntity member, String label, Supplier<Object> memberCluster, Runnable call) {
        if (!ENABLED || member == null) {
            call.run();
            return;
        }
        COUNTERS.tmIntents.increment();
        Runnable validated = () -> {
            if (memberCluster.get() != cluster) {
                COUNTERS.tmSkippedReassigned.increment();
                return;
            }
            if (member.getLevel() instanceof ServerLevel sl
                    && !BeDomainReady.currentThreadIn(sl, member.getBlockPos(), BeDomainReady.typeOf(member))) {
                COUNTERS.tmOffDomain.increment();
            }
            call.run();
        };
        if (Ae2Compat.KERNEL.defer(() -> HostDomainCallbackDelivery.deliverForHost(HostDomainCallbackDelivery.Channel.CLUSTER, null, member, label, validated))) {
            return;
        }
        HostDomainCallbackDelivery.deliverForHost(HostDomainCallbackDelivery.Channel.CLUSTER, null, member, label, validated);
    }

    // ------------------------------------------------------------------ drop intents

    /** Cluster inventory drop at {@code pos}: exactly once, in the domain owning {@code pos}; never discarded. */
    public static void drop(Level lv, BlockPos pos, List<ItemStack> stacks, Runnable spawn) {
        long items = 0;
        for (ItemStack s : stacks) items += s.getCount();
        final long n = items;
        COUNTERS.drIntents.increment();
        COUNTERS.drItemsCaptured.add(n);
        Runnable deliver = () -> {
            spawn.run();
            COUNTERS.drItemsDelivered.add(n);
        };
        if (Ae2Compat.KERNEL.defer(() -> dropAt(lv, pos, deliver, stacks, n))) return;
        dropAt(lv, pos, deliver, stacks, n);
    }

    private static void dropAt(Level lv, BlockPos pos, Runnable deliver, List<ItemStack> stacks, long n) {
        if (!BeDomainReady.primitive() || !(lv instanceof ServerLevel level)) {
            COUNTERS.drInline.increment();
            deliver.run();
            return;
        }
        if (BeDomainReady.currentThreadIn(level, pos, null)) {
            COUNTERS.drInline.increment();
            deliver.run();
            return;
        }
        COUNTERS.drDeferred.increment();
        Runnable inDomain = () -> {
            COUNTERS.drRanInDomain.increment();
            deliver.run();
        };
        try {
            BeDomainReady.enqueueTo(level, pos, null, inDomain, kind -> {
                MinecraftServer server = level.getServer();
                if (server != null && server.isRunning()) {
                    COUNTERS.drFallback.increment();
                    server.execute(deliver);               // never discard: deliver on the server thread
                } else {
                    COUNTERS.drItemsRetained.add(n);
                    Ae2Compat.LOG.warn("[nestworld-compat-ae2] AE2 cluster drop at {} RETAINED (not delivered, {}): {}",
                            pos.toShortString(), kind, stacks);
                }
            });
        } catch (Throwable t) {
            COUNTERS.drFallback.increment();
            deliver.run();
        }
    }

    private static void enqueueNonCancellable(ServerLevel level, BlockPos pos, String type, String label, Runnable call,
                                              java.util.concurrent.atomic.LongAdder fallback) {
        try {
            BeDomainReady.enqueueTo(level, pos, type, call, kind -> {
                fallback.increment();
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] AE2 cluster teardown {} at {} fell back to the server thread: {}",
                        label, pos.toShortString(), kind);
                MinecraftServer server = level.getServer();
                if (server != null) server.execute(call);
            });
        } catch (Throwable t) {
            fallback.increment();
            call.run();
        }
    }

    public static String summary() {
        var k = COUNTERS;
        return "teardown{enabled=" + ENABLED + " requests=" + k.tdRequests.sum() + " claimed=" + k.tdClaimed.sum()
                + " duplicate=" + k.tdDuplicate.sum() + " inline=" + k.tdInline.sum() + " deferred=" + k.tdDeferred.sum()
                + " ranInDomain=" + k.tdRanInDomain.sum() + " offDomain=" + k.tdOffDomain.sum() + " fallback="
                + k.tdFallback.sum() + " sectionExit=" + k.tdDeferredToSectionExit.sum() + " bypassedOffDomain=" + k.tdBypassedOffDomain.sum() + " | members intents=" + k.tmIntents.sum() + " skippedReassigned="
                + k.tmSkippedReassigned.sum() + " offDomain=" + k.tmOffDomain.sum() + " | drops intents=" + k.drIntents.sum()
                + " inline=" + k.drInline.sum() + " deferred=" + k.drDeferred.sum() + " ranInDomain=" + k.drRanInDomain.sum()
                + " fallback=" + k.drFallback.sum() + " itemsCaptured=" + k.drItemsCaptured.sum() + " itemsDelivered="
                + k.drItemsDelivered.sum() + " itemsRetained=" + k.drItemsRetained.sum() + "}";
    }
}
