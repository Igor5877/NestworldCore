package net.nestworld.compat.ae2.kernel;

/**
 * A guarded section reached a path the design proves unreachable for audited code (for example T0
 * requested while domains are held, AE2-COMPAT-DESIGN-001.2 §4 / M4). The kernel has already switched
 * to QUIESCING so that later operations run serialized; this operation fails loud.
 */
public final class UnauditedPathException extends IllegalStateException {
    public UnauditedPathException(String message) {
        super(message);
    }
}
