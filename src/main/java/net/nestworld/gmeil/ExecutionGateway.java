package net.nestworld.gmeil;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Phase 1 observation boundary. It records the conservative policy and callback outcome while
 * preserving the caller's thread, timing, result and exception behavior. It deliberately does
 * not enforce MAIN_COMPAT until MainExecutionBridge can preserve the full temporal contract.
 */
public final class ExecutionGateway {
    private static final ThreadLocal<ExecutionContext> ACTIVE = new ThreadLocal<>();
    private final AtomicLong nextTaskId = new AtomicLong();
    private final PolicyRegistry policies;
    private final ExecutionMetrics metrics = new ExecutionMetrics();

    public ExecutionGateway(PolicyRegistry policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public static ExecutionContext current() {
        return ACTIVE.get();
    }

    public ExecutionMetrics metrics() {
        return metrics;
    }

    public void observe(ExecutionKey key, long tick, String phase, int regionId,
                        boolean onMainThread, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        observe(key, tick, phase, regionId, onMainThread, () -> {
            callback.run();
            return null;
        });
    }

    public <T> T observe(ExecutionKey key, long tick, String phase, int regionId,
                         boolean onMainThread, Supplier<T> callback) {
        Objects.requireNonNull(callback, "callback");
        ExecutionContext parent = ACTIVE.get();
        ExecutionPolicy policy = policies.resolve(key);
        ExecutionContext context = new ExecutionContext(nextTaskId.incrementAndGet(),
                parent == null ? ExecutionContext.NONE : parent.taskId(), tick,
                Objects.requireNonNull(phase, "phase"), key, policy, regionId,
                Thread.currentThread().getName(), ExecutionContext.NONE, ExecutionContext.NONE);
        ACTIVE.set(context);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            return callback.get();
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            try {
                metrics.record(key, policy, onMainThread, failed, System.nanoTime() - start);
            } finally {
                if (parent == null) ACTIVE.remove();
                else ACTIVE.set(parent);
            }
        }
    }
}
