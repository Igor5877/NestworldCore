package net.nestworld.gmeil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counters for OBSERVE mode. Failure means the callback threw, not that it was contained. */
public final class ExecutionMetrics {
    public record Snapshot(long calls, long failures, long nanos, long mainCompatOffMain) {}

    private static final class Counters {
        final LongAdder calls = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder mainCompatOffMain = new LongAdder();

        Snapshot snapshot() {
            return new Snapshot(calls.sum(), failures.sum(), nanos.sum(), mainCompatOffMain.sum());
        }
    }

    private final ConcurrentHashMap<ExecutionKey, Counters> byKey = new ConcurrentHashMap<>();

    void record(ExecutionKey key, ExecutionPolicy policy, boolean onMainThread,
                boolean failed, long durationNanos) {
        Counters counters = byKey.computeIfAbsent(key, ignored -> new Counters());
        counters.calls.increment();
        if (failed) counters.failures.increment();
        counters.nanos.add(durationNanos);
        if (policy == ExecutionPolicy.MAIN_COMPAT && !onMainThread) {
            counters.mainCompatOffMain.increment();
        }
    }

    public Map<ExecutionKey, Snapshot> snapshot() {
        Map<ExecutionKey, Snapshot> result = new java.util.HashMap<>();
        byKey.forEach((key, counters) -> result.put(key, counters.snapshot()));
        return Map.copyOf(result);
    }
}
