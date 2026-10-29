package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNodeListener;
import appeng.me.GridNode;

/**
 * AE2-NODE-LISTENER-ENDTICK-001 (owner decisions 2026-10-29 (13)): the three audited end-of-tick state emitters —
 * {@code Grid.notifyAllNodes} (GRID_BOOT, POWER) and {@code GridNode/GridConnection.finalizeChannels} (CHANNEL) —
 * deliver each node's state notification through {@link HostDomainCallbackDelivery} (channel STATE). Inside a kernel
 * section the scope-3 intent path (callListener) already handles it: no double routing. Every other listener callback
 * (onOwnerChanged, onSaveChanges, …) is out of scope and unchanged.
 */
public final class EndTickStateCallbacks {
    /** Routing switch (default on); fixture-mode negative control flips it at runtime. */
    public static volatile boolean ROUTING = !Boolean.getBoolean("nestworld.compatAe2.endTickRoutingOff");

    private EndTickStateCallbacks() {
    }

    public static void route(GridNode node, IGridNodeListener.State state, Runnable call) {
        if (!ROUTING || Ae2Compat.KERNEL.inSection()) {
            HostDomainCallbackDelivery.Observer obs = ROUTING ? null : HostDomainCallbackDelivery.observer;
            var host = obs == null ? null : HostDomainCallbackDelivery.hostOf(node.getOwner());
            if (obs != null) obs.emitted(HostDomainCallbackDelivery.Channel.STATE, node, state.name(), host, false);
            call.run();
            if (obs != null) obs.delivered(HostDomainCallbackDelivery.Channel.STATE, node, state.name(), host);
            return;
        }
        HostDomainCallbackDelivery.deliver(HostDomainCallbackDelivery.Channel.STATE, node, state.name(), call);
    }
}
