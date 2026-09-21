import net.nestworld.gmeil.ExecutionContext;
import net.nestworld.gmeil.ExecutionGateway;
import net.nestworld.gmeil.ExecutionKey;
import net.nestworld.gmeil.ExecutionPolicy;
import net.nestworld.gmeil.ExecutionSource;
import net.nestworld.gmeil.PolicyRegistry;

final class GatewayRegression {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ExecutionGateway gateway = new ExecutionGateway(PolicyRegistry.conservative());
        ExecutionKey outer = new ExecutionKey("forge", ExecutionSource.BLOCK_ENTITY_TICK,
                "test", "ExampleTicker", "tick", "()V");
        ExecutionKey inner = new ExecutionKey("forge", ExecutionSource.CAPABILITY_CALL,
                "test", "ExampleCapability", "value", "()I");
        Thread original = Thread.currentThread();

        int value = gateway.observe(outer, 12, "BLOCK_ENTITY", 3, true, () -> {
            ExecutionContext parent = ExecutionGateway.current();
            require(parent != null && parent.policy() == ExecutionPolicy.MAIN_COMPAT,
                    "unknown callback must resolve conservatively");
            require(Thread.currentThread() == original, "observe must not reschedule");
            int nested = gateway.observe(inner, 12, "BLOCK_ENTITY", 3, false, () -> {
                ExecutionContext child = ExecutionGateway.current();
                require(child != null && child.parentTaskId() == parent.taskId(),
                        "nested callback must retain causal parent");
                return 7;
            });
            require(ExecutionGateway.current() == parent, "nested callback must restore context");
            return nested + 1;
        });
        require(value == 8, "result must be preserved");
        require(ExecutionGateway.current() == null, "context must be cleared after success");

        RuntimeException expected = new RuntimeException("original failure");
        try {
            gateway.observe(outer, 13, "BLOCK_ENTITY", 3, true, (Runnable) () -> {
                throw expected;
            });
            throw new AssertionError("exception was swallowed");
        } catch (RuntimeException actual) {
            require(actual == expected, "original exception object must be propagated");
        }
        require(ExecutionGateway.current() == null, "context must be cleared after failure");
        var outerMetrics = gateway.metrics().snapshot().get(outer);
        var innerMetrics = gateway.metrics().snapshot().get(inner);
        require(outerMetrics.calls() == 2 && outerMetrics.failures() == 1,
                "outer outcome metrics incorrect");
        require(innerMetrics.calls() == 1 && innerMetrics.mainCompatOffMain() == 1,
                "off-main prediction metrics incorrect");
        System.out.println("GMEIL gateway observation regression passed");
    }
}
