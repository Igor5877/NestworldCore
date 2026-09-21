package net.nestworld.gmeil;

import java.util.Map;
import java.util.Objects;

/** Bootstrap snapshot; unresolved opaque callbacks always resolve to MAIN_COMPAT. */
public final class PolicyRegistry {
    private final Map<ExecutionKey, ExecutionPolicy> policies;

    public PolicyRegistry(Map<ExecutionKey, ExecutionPolicy> policies) {
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
    }

    public static PolicyRegistry conservative() {
        return new PolicyRegistry(Map.of());
    }

    public ExecutionPolicy resolve(ExecutionKey key) {
        return policies.getOrDefault(Objects.requireNonNull(key, "key"), ExecutionPolicy.MAIN_COMPAT);
    }
}
