package net.nestworld.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * `docs/MODULE0_AUDIT.md` §D/§11: the Core-owned, per-module-generation record of every
 * {@link ResourceHandle} that module's registrations produced. This is the concrete mechanism
 * that makes {@code ModuleContext} a lifecycle ownership boundary, not just a DI convenience
 * object -- per the architecture owner's explicit framing of MODULE-1's most important test.
 *
 * <pre>
 * Responsibility:
 *   Track every ModuleContext-owned resource a module acquires, in registration order, and
 *   revoke them ALL in REVERSE order on quiesce or failure -- so a resource registered last
 *   (and therefore most likely to depend on something registered earlier) is torn down first.
 *
 * Does:
 *   - record(handle) -- append, called by ModuleContextImpl on every successful registration
 *   - revokeAll() -- revoke every recorded handle in reverse order, clear the ledger
 *   - isEmpty() -- the observable post-condition MODULE-1's central test asserts
 *
 * Does NOT:
 *   - guarantee the UNDERLYING resource is actually gone from Core's perspective beyond what
 *     ResourceHandle.revoke() itself does -- this class only guarantees every recorded handle's
 *     revoke() is CALLED, exactly once, in the right order. Whether revoke() is a REAL teardown
 *     (event-bus unregister) or a soft/logical one (see ModuleContextImpl's command handles) is
 *     the handle's own concern, not this ledger's.
 *   - catch and swallow a throwing revoke() silently -- see revokeAll()'s own contract.
 *
 * Threading:
 *   Not thread-safe by design -- MODULE-1 drives all lifecycle transitions (including
 *   revokeAll()) from the main/server thread only (onRegisterCommands/onServerStopping), the
 *   same threading discipline VelocityBackendRegistry's install/uninstall already uses.
 *
 * Invariant:
 *   revokeAll() always empties the ledger, even if an individual handle's revoke() throws --
 *   one misbehaving handle must not leave the rest of the module's resources leaked.
 *
 * Failure:
 *   A throwing revoke() is caught, logged with full attribution (which handle, which kind), and
 *   revocation continues for the remaining handles -- never abandons the sweep partway.
 *
 * Lifecycle:
 *   One instance per ModuleRuntime (one per loaded module generation). Not reused across
 *   generations -- a new ENABLE gets a fresh, empty ledger.
 *
 * Evidence / tests:
 *   MODULE-1's central test (register A,B,C -> QUIESCING -> revoke C,B,A -> ledger empty) is
 *   this class's own acceptance test -- see ExampleModule/FailingExampleModule.
 * </pre>
 */
final class ResourceLedger {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceLedger.class);

    private final Deque<ResourceHandle> handles = new ArrayDeque<>();
    private final ModuleId owner;

    ResourceLedger(ModuleId owner) {
        this.owner = owner;
    }

    void record(ResourceHandle handle) {
        handles.addLast(handle);
    }

    boolean isEmpty() {
        return handles.isEmpty();
    }

    int size() {
        return handles.size();
    }

    /** Revoke every recorded handle in REVERSE (most-recently-registered-first) order. */
    void revokeAll() {
        while (!handles.isEmpty()) {
            ResourceHandle handle = handles.removeLast();
            try {
                handle.revoke();
            } catch (Exception e) {
                LOGGER.error("NestWorld module {}: resource handle [{}] threw during revoke() -- "
                        + "continuing to revoke the rest of the ledger, this resource may be leaked",
                        owner.value(), handle.kind(), e);
            }
        }
    }
}
