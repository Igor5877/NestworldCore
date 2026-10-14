package net.nestworld.compat.ae2.glue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Duck interface added to {@code appeng.blockentity.AEBaseBlockEntity} (AE2-COMPAT-QUEUE-001.1 §4). */
public interface NwAeBlockEntity {
    /** setChanged protocol word: phase (2 low bits) | seq (rest). */
    AtomicLong nw$sc();

    /** Callback generation, stamped at C-claim; identifies this block entity's running callback. */
    int nw$cbGen();

    int nw$nextCbGen();

    /** Per-block-entity lifecycle lock: initialization XOR removal. Order: nw$life → T0 → domains. */
    ReentrantLock nw$life();

    boolean nw$removed();

    void nw$markRemoved();

    /** AE2's legacy-orientation marker set by loadTag ("forward"/"up" NBT), or null. */
    appeng.api.orientation.BlockOrientation nw$pendingOrientation();

    void nw$clearPendingOrientation();

    /** Re-enqueue one follow-up end-of-tick callback (main thread). */
    void nw$requeueSetChanged();
}
