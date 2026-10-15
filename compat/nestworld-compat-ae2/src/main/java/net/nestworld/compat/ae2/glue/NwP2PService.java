package net.nestworld.compat.ae2.glue;

import appeng.parts.p2p.P2PTunnelPart;
import java.util.List;

/** Implemented by P2PServiceMixin (P2P-MAP-STATE-001). */
public interface NwP2PService {
    P2PState nw$state();

    /**
     * One linearizable writer transaction: under the guard, use {@code candidate} if it is still free
     * (or already this tunnel's), otherwise choose another unused frequency; bind {@code tunnel} as the
     * input of it; generation++. Returns the bound frequency. {@code newFrequency()} itself is unchanged.
     */
    short nw$allocateAndBind(P2PTunnelPart<?> tunnel, short candidate);

    /** Fixture / diagnostics: every frequency key under which {@code tunnel} is a member (inputs or outputs). */
    List<Short> nw$membership(P2PTunnelPart<?> tunnel);

    /** Atomic (under the read guard): null if {@code tunnel}'s membership is exactly [its freq] or empty
     *  (not registered), else a description of the inconsistency. */
    String nw$check(P2PTunnelPart<?> tunnel);
}
