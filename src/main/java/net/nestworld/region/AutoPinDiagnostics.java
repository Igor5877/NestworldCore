package net.nestworld.region;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CORE-AUTOPIN-DIAGNOSTICS-001 (owner 2026-11-09): when the Core decides "N failures → AUTO-PIN" it must leave the
 * evidence of why. Per auto-pin key ({@code kind:key}, e.g. {@code be:mm:auto_sieve_controller}):
 * <ul>
 * <li>1st failure seen in this JVM → one WARN line with context, root cause, full cause chain and the full stack;</li>
 * <li>2nd .. (threshold-1)th → counted only (the call sites keep their existing one-line warning);</li>
 * <li>threshold-triggering failure → {@code AUTO_PIN_<KIND>} WARN with a {@code reasonId}, context, root cause, chain,
 * first-failure time and the full stack; the same {@code reasonId} is stored in the pin-reasons file so a pin can be
 * matched with its log line after rotation.</li>
 * </ul>
 * Diagnostics only — the pin decision itself is unchanged.
 */
final class AutoPinDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/Pins");
    private static final Map<String, Long> FIRST_FAILURE_MILLIS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FIRST_LOGGED = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_CHAIN = 16;

    private AutoPinDiagnostics() {
    }

    /** Context string for a failure: {@code dim=… pos=… thread=…} (region id is part of the region thread's name). */
    static String context(String dimension, String pos) {
        return "dim=" + (dimension == null ? "?" : dimension) + " pos=" + (pos == null ? "?" : pos)
                + " thread=" + Thread.currentThread().getName();
    }

    /** Every counted failure. Logs the first one per key with its full stack. */
    static void onFailure(String kind, String key, String context, Throwable t, int count, int threshold) {
        String k = kind + ":" + key;
        FIRST_FAILURE_MILLIS.putIfAbsent(k, System.currentTimeMillis());
        if (FIRST_LOGGED.putIfAbsent(k, Boolean.TRUE) == null) {
            Throwable root = rootCause(t);
            LOGGER.warn("AUTO_PIN_FIRST_FAILURE kind={} key={} failures={}/{} {} rootCause={}: {} chain={}", kind, key,
                    count, threshold, context, root == null ? "-" : root.getClass().getName(),
                    root == null ? "-" : root.getMessage(), chain(t), t);
        }
    }

    /** The threshold-triggering failure: logs the AUTO-PIN decision with its full stack; returns the reason id. */
    static String onPin(String kind, String key, String context, Throwable t, int count) {
        String k = kind + ":" + key;
        String reasonId = "AP-" + LocalDateTime.now().format(ID_FORMAT) + "-" + String.format("%04d", SEQ.incrementAndGet());
        Throwable root = rootCause(t);
        Long first = FIRST_FAILURE_MILLIS.get(k);
        LOGGER.warn("AUTO_PIN_{} type={} failures={} reasonId={} rootCause={}: {} firstFailureMillis={} {} chain={}",
                kind.toUpperCase(java.util.Locale.ROOT), key, count, reasonId, root == null ? "-" : root.getClass().getName(),
                root == null ? "-" : root.getMessage(), first == null ? "-" : first, context, chain(t), t);
        return reasonId;
    }

    static Long firstFailureMillis(String kind, String key) {
        return FIRST_FAILURE_MILLIS.get(kind + ":" + key);
    }

    /** Deepest cause (cycle-safe, bounded). */
    static Throwable rootCause(Throwable t) {
        if (t == null) return null;
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable cur = t;
        for (int i = 0; i < MAX_CHAIN && cur.getCause() != null && seen.put(cur, Boolean.TRUE) == null; i++) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** {@code Outer: msg -> Cause: msg -> Root: msg} (cycle-safe, bounded). */
    static String chain(Throwable t) {
        if (t == null) return "-";
        List<String> parts = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable cur = t; cur != null && parts.size() < MAX_CHAIN && seen.put(cur, Boolean.TRUE) == null; cur = cur.getCause()) {
            parts.add(cur.getClass().getName() + (cur.getMessage() == null ? "" : ": " + cur.getMessage()));
        }
        return String.join(" -> ", parts).replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
