package net.nestworld.modapi.domain;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * <b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> Same token-guarded install/uninstall discipline as
 * {@code VelocityBackendRegistry}: installed by Core on ServerStartingEvent, uninstalled on ServerStoppingEvent; a stale
 * uninstall from a previous server generation (same JVM) is ignored.
 */
public final class DomainBackendRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DomainBackendRegistry.class);
    private static volatile DomainBackend backend;
    private static volatile Object token;

    private DomainBackendRegistry() {}

    public static synchronized void install(Object serverToken, DomainBackend b) {
        if (backend != null) LOGGER.warn("DomainBackendRegistry: install over an existing backend (missed uninstall?)");
        backend = b;
        token = serverToken;
    }

    public static synchronized void uninstall(Object serverToken) {
        if (token == serverToken) {
            backend = null;
            token = null;
        }
    }

    public static DomainBackend current() {
        return backend;
    }
}
