package net.nestworld.compat.mekanism.glue;

import java.util.Map;

/** Implemented by NetworkAcceptorCache via mixin. */
public interface PendingAcceptors {
    /** Takes the pending (transmitter → sides) generation under that cache's own lock; empty map if none. */
    @SuppressWarnings("rawtypes")
    Map nestworld$takePending();

    /** The cache's acceptor map (read by adoptAcceptors exactly as Mekanism does; not covered by the lock). */
    @SuppressWarnings("rawtypes")
    Map nestworld$cachedAcceptors();
}
