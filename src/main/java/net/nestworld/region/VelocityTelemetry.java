package net.nestworld.region;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * PERF-1B instrumentation (docs/contracts/PERF-1B-spec.md, owner decision D1, 2026-10-05):
 * aggregated path / outcome counters and sampled end-to-end latency for the setVelocity
 * primitive on the NestWorldCore peer. Instrumentation only -- no routing, ordering or apply
 * decision reads any of this state.
 *
 * <p>Off unless {@code -Dnestworld.apiStats=true} (a static final flag, so the disabled hooks
 * fold away in compiled code; PERF-1B TZ section 27 measures the on/off overhead). With
 * {@code -Dnestworld.apiStatsFile=<path>} a daemon thread appends one JSON line per second.
 *
 * <p>Accounting: every accepted operation ends in exactly one terminal counter, so after
 * quiescence {@code accepted == applied + stale + targetRemoved + targetNotFound +
 * rerouteLimitDropped + redeliveryLimitDropped + otherTerminal}. A lost operation makes the sum
 * smaller and a duplicate application makes it larger, so the closure check catches both.
 * Same counter style as {@link InventorySetTelemetry} for setSlot.
 */
final class VelocityTelemetry {
    private VelocityTelemetry() {}

    static final boolean ENABLED = Boolean.getBoolean("nestworld.apiStats");
    private static final String FILE = System.getProperty("nestworld.apiStatsFile");
    /** Latency is sampled for opIds with these low bits clear (1 in 64). */
    private static final long SAMPLE_MASK = 63L;

    // ---- accepted and paths
    static final LongAdder accepted = new LongAdder();
    static final LongAdder pathOwnerFast = new LongAdder();       // caller is the owner's region thread
    static final LongAdder pathMailboxCross = new LongAdder();    // caller is another region's thread
    static final LongAdder pathMailboxFromMain = new LongAdder(); // caller is not a region thread
    static final LongAdder pathBackpressureDirect = new LongAdder();
    static final LongAdder pathUnmanagedDirect = new LongAdder();
    static final LongAdder pathOwnerNullDirect = new LongAdder();
    static final LongAdder reroutes = new LongAdder();            // applyQueued hop to the new owner
    static final LongAdder redeliveries = new LongAdder();        // retirement orphan redelivery

    // ---- terminals
    static final LongAdder applied = new LongAdder();
    static final LongAdder staleDropped = new LongAdder();
    static final LongAdder targetRemoved = new LongAdder();
    static final LongAdder targetNotFound = new LongAdder();
    static final LongAdder rerouteLimitDropped = new LongAdder();
    static final LongAdder redeliveryLimitDropped = new LongAdder();
    static final LongAdder otherTerminal = new LongAdder();

    // ---- sampled latency, accepted -> terminal, log2(ns) buckets
    private static final ConcurrentHashMap<Long, Long> START = new ConcurrentHashMap<>();
    private static final LongAdder[] LAT = new LongAdder[48];
    static {
        for (int i = 0; i < LAT.length; i++) LAT[i] = new LongAdder();
        if (ENABLED && FILE != null) {
            Thread t = new Thread(VelocityTelemetry::writeLoop, "nestworld-api-stats-writer");
            t.setDaemon(true);
            t.start();
        }
    }

    static void onAccepted(long opId) {
        accepted.increment();
        if ((opId & SAMPLE_MASK) == 0) START.put(opId, System.nanoTime());
    }

    static void onTerminal(long opId, EntityMutationDispatcher.ApplyOutcome outcome) {
        switch (outcome) {
            case APPLIED -> applied.increment();
            case STALE_INTENT_DROPPED -> staleDropped.increment();
            case TARGET_REMOVED -> targetRemoved.increment();
            default -> otherTerminal.increment();
        }
        latency(opId);
    }

    static void onTerminal(long opId, LongAdder kind) {
        kind.increment();
        latency(opId);
    }

    private static void latency(long opId) {
        if ((opId & SAMPLE_MASK) != 0) return;
        Long t0 = START.remove(opId);
        if (t0 == null) return;
        long ns = Math.max(1L, System.nanoTime() - t0);
        LAT[Math.min(LAT.length - 1, 63 - Long.numberOfLeadingZeros(ns))].increment();
    }

    static String json() {
        StringBuilder h = new StringBuilder("[");
        for (int i = 0; i < LAT.length; i++) h.append(i == 0 ? "" : ",").append(LAT[i].sum());
        h.append(']');
        return "{\"ts\":" + System.currentTimeMillis()
                + ",\"accepted\":" + accepted.sum()
                + ",\"path_owner_fast\":" + pathOwnerFast.sum()
                + ",\"path_mailbox_cross\":" + pathMailboxCross.sum()
                + ",\"path_mailbox_from_main\":" + pathMailboxFromMain.sum()
                + ",\"path_backpressure_direct\":" + pathBackpressureDirect.sum()
                + ",\"path_unmanaged_direct\":" + pathUnmanagedDirect.sum()
                + ",\"path_owner_null_direct\":" + pathOwnerNullDirect.sum()
                + ",\"reroutes\":" + reroutes.sum()
                + ",\"redeliveries\":" + redeliveries.sum()
                + ",\"applied\":" + applied.sum()
                + ",\"stale_dropped\":" + staleDropped.sum()
                + ",\"target_removed\":" + targetRemoved.sum()
                + ",\"target_not_found\":" + targetNotFound.sum()
                + ",\"reroute_limit_dropped\":" + rerouteLimitDropped.sum()
                + ",\"redelivery_limit_dropped\":" + redeliveryLimitDropped.sum()
                + ",\"other_terminal\":" + otherTerminal.sum()
                + ",\"latency_sampled_pending\":" + START.size()
                + ",\"latency_log2ns\":" + h + "}";
    }

    private static void writeLoop() {
        Path out = Path.of(FILE);
        while (true) {
            try {
                Thread.sleep(1000L);
                Files.writeString(out, json() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (InterruptedException e) {
                return;
            } catch (IOException ignored) {
                // the harness detects gaps in the series; never disturb the server
            }
        }
    }
}
