package net.nestworld.modapi.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * PERF-1B instrumentation for the ordinary-Forge peer (docs/contracts/PERF-1B-spec.md, owner
 * decision D1). Package-private and not part of the public Mod API: no ABI surface. It records
 * the same accounting as the NestWorldCore peer's VelocityTelemetry, and no execution decision
 * reads it.
 *
 * <p>Off unless {@code -Dnestworld.apiStats=true}. With {@code -Dnestworld.apiStatsFile=<path>}
 * a daemon thread appends one JSON line per second. Accounting after quiescence:
 * {@code accepted == applied + stale_dropped + target_not_found + no_server + other_terminal}.
 */
final class ForgeVelocityTelemetry {
    private ForgeVelocityTelemetry() {}

    static final boolean ENABLED = Boolean.getBoolean("nestworld.apiStats");
    private static final String FILE = System.getProperty("nestworld.apiStatsFile");
    private static final long SAMPLE_MASK = 63L;
    private static final AtomicLong SEQ = new AtomicLong();

    static final LongAdder accepted = new LongAdder();
    static final LongAdder pathSameThread = new LongAdder();   // called on the server thread: applied inline
    static final LongAdder pathMarshal = new LongAdder();      // other thread: server.execute(...)
    static final LongAdder applied = new LongAdder();
    static final LongAdder staleDropped = new LongAdder();
    static final LongAdder targetNotFound = new LongAdder();
    static final LongAdder noServer = new LongAdder();
    static final LongAdder otherTerminal = new LongAdder();
    private static final LongAdder[] LAT = new LongAdder[48];
    static {
        for (int i = 0; i < LAT.length; i++) LAT[i] = new LongAdder();
        if (ENABLED && FILE != null) {
            Thread t = new Thread(ForgeVelocityTelemetry::writeLoop, "nestworld-api-stats-writer");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Counts the acceptance; returns a start timestamp for a sampled operation, else 0. */
    static long onAccepted() {
        accepted.increment();
        return (SEQ.incrementAndGet() & SAMPLE_MASK) == 0 ? System.nanoTime() : 0L;
    }

    static void onTerminal(LongAdder kind, long startNanos) {
        kind.increment();
        if (startNanos != 0L) {
            long ns = Math.max(1L, System.nanoTime() - startNanos);
            LAT[Math.min(LAT.length - 1, 63 - Long.numberOfLeadingZeros(ns))].increment();
        }
    }

    static String json() {
        StringBuilder h = new StringBuilder("[");
        for (int i = 0; i < LAT.length; i++) h.append(i == 0 ? "" : ",").append(LAT[i].sum());
        h.append(']');
        return "{\"ts\":" + System.currentTimeMillis()
                + ",\"accepted\":" + accepted.sum()
                + ",\"path_forge_same_thread\":" + pathSameThread.sum()
                + ",\"path_forge_marshal\":" + pathMarshal.sum()
                + ",\"applied\":" + applied.sum()
                + ",\"stale_dropped\":" + staleDropped.sum()
                + ",\"target_not_found\":" + targetNotFound.sum()
                + ",\"no_server\":" + noServer.sum()
                + ",\"other_terminal\":" + otherTerminal.sum()
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
