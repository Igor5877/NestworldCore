package net.nestworld.module;

/**
 * `docs/MODULE0_AUDIT.md` §D/§11: a Core-owned handle to one resource a module registered
 * through {@link ModuleContext}. Reuses {@code VelocityBackendRegistry}'s token-check discipline
 * in spirit -- Core holds the handle, Core decides when to revoke it, the module never calls
 * {@link #revoke()} on itself. {@code revoke()} must be idempotent (called at most once by
 * {@link ResourceLedger} in normal operation, but implementations must not misbehave if called
 * twice) and must not throw for an already-revoked resource.
 */
public interface ResourceHandle {
    /** What kind of resource this is, for logging/attribution -- e.g. "listener", "command". */
    String kind();

    /** Revoke this resource. Idempotent. Must not throw. */
    void revoke();
}
