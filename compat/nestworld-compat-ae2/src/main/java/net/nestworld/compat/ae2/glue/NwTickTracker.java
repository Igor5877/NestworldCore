package net.nestworld.compat.ae2.glue;

/** Duck interface added to {@code appeng.me.service.helpers.TickTracker}: scheduling generation (001.5 §3.1). */
public interface NwTickTracker {
    long nw$gen();

    void nw$bump();
}
