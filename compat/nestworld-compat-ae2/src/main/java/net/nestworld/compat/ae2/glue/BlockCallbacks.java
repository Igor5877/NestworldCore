package net.nestworld.compat.ae2.glue;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-PIN-LEAK-001 D2 (owner decisions 2026-10-30 (15)): the approved void AE2 block callbacks — crafting unit / QNB /
 * spatial pylon neighbour updates (I3), cable bus neighbour updates (I4) and the cable bus updateShape side effect
 * (I5; updateShape itself still returns its BlockState synchronously) — run their AE2 host mutation in the host's
 * CURRENT BE execution domain through {@link HostDomainCallbackDelivery} (channel BLOCK): inline when already there,
 * else deferred with lifecycle revalidation, exact FIFO, no coalescing. Domain-generic (pinned host → MAIN).
 * Hooked at the AE2 method each block callback delegates to (single caller each), so the Minecraft call itself and
 * its return value are untouched.
 */
public final class BlockCallbacks {
    /** Routing switch (default on); fixture-mode negative control flips it at runtime. */
    public static volatile boolean ROUTING = !Boolean.getBoolean("nestworld.compatAe2.blockCallbackRoutingOff");

    private BlockCallbacks() {
    }

    public static void route(BlockEntity host, String label, Runnable call) {
        if (!ROUTING || host == null || host.getLevel() == null || host.getLevel().isClientSide()) {
            HostDomainCallbackDelivery.Observer obs = ROUTING ? null : HostDomainCallbackDelivery.observer;
            if (obs != null) obs.emitted(HostDomainCallbackDelivery.Channel.BLOCK, null, label, host, false);
            call.run();
            if (obs != null) obs.delivered(HostDomainCallbackDelivery.Channel.BLOCK, null, label, host);
            return;
        }
        HostDomainCallbackDelivery.deliverForHost(HostDomainCallbackDelivery.Channel.BLOCK, null, host, label, call);
    }
}
