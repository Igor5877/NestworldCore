package net.nestworld.modapi.domain;

/**
 * Facade for dedicated execution domains (docs/contracts/ExecutionDomainPrimitive.md). Without NestWorld Core (or with
 * domains disabled) every handle is INLINE: {@code submit} returns false and the caller runs its work in place.
 */
public final class NestWorldDomains {
    private NestWorldDomains() {}

    /** Opens (or returns the existing) domain named {@code name}; never null. Call on MAIN after server start. */
    public static DomainHandle open(String name) {
        DomainBackend b = DomainBackendRegistry.current();
        if (b == null) return InlineDomainHandle.INSTANCE;
        DomainHandle h = b.open(name);
        return h == null ? InlineDomainHandle.INSTANCE : h;
    }
}
