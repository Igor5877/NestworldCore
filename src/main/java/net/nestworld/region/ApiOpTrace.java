package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * STRESS-001: the single switch for per-operation Mod API phase tracing ({@code [API1A7]} lines:
 * SUBMIT / OWNER_RESOLVED / ENQUEUE / APPLY / REROUTE / REDELIVER / TERMINAL).
 *
 * <p>Default OFF. Every trace call site is written {@code if (ApiOpTrace.ENABLED) LOG.info(...)},
 * so with tracing off the hot path pays one volatile read: no message formatting, no varargs array,
 * no boxing, no logger lookup. Turned on by {@code -Dnestworld.apiOpTrace=true}, by
 * {@code /nestworld apitrace on}, or by a deterministic fixture for its own duration.
 *
 * <p>Tracing is diagnostic only: it never participates in routing, ordering, delivery or any
 * primitive's semantics. Operational signals that must survive with tracing off are counters
 * (below, {@link MailboxLifecycle}, {@link InventorySetTelemetry}) plus rate-limited WARNs.
 */
public final class ApiOpTrace {
    private ApiOpTrace() {}

    public static volatile boolean ENABLED = Boolean.getBoolean("nestworld.apiOpTrace");

    static final Logger LOG = LogManager.getLogger("NestWorld/DIAG");

    /** ENTITY_MUTATE dropped at the reroute-hop limit (always on; any op type). */
    static final AtomicLong rerouteLimitDropped = new AtomicLong();

    /** Operational WARN for a reroute-limit drop without per-op flooding: the first 10, then every
     *  1000th, each carrying the running total. */
    static void warnRerouteLimitDropped(long opId, Object target, int hops) {
        long n = rerouteLimitDropped.incrementAndGet();
        if (n <= 10 || n % 1000 == 0) {
            LOG.warn("ENTITY_MUTATE dropped at reroute limit (hops={}, target={}, opId={}) -- {} total since start",
                    hops, target, opId, n);
        }
    }
}
