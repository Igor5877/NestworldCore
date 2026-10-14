package net.nestworld.compat.ae2.kernel;

/**
 * M2 (AE2-COMPAT-DESIGN-001.1 §5): a nested synchronous entry needed a domain lock it could not take
 * without breaking the global order. Nothing has been applied by the nested entry; the caller returns
 * the contract-valid "nothing moved" result. Preallocated, no stack trace.
 */
public final class NestedRefusal extends RuntimeException {
    static final NestedRefusal INSTANCE = new NestedRefusal();

    private NestedRefusal() {
        super("nested AE2 domain acquisition refused (lower order, lock busy)", null, false, false);
    }
}
