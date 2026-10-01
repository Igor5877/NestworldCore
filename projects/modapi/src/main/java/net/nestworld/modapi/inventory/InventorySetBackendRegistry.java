package net.nestworld.modapi.inventory;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors {@code net.nestworld.modapi.entity.VelocityBackendRegistry}'s exact shape (token-check
 * install/uninstall state machine) for `setSlot`'s own backend -- a separate, parallel registry,
 * not a shared/generalized one, matching this whole primitive's "reuse the pattern, keep the
 * instance separate" discipline.
 */
public final class InventorySetBackendRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventorySetBackendRegistry.class);

    private static volatile InventorySetBackend backend;
    private static volatile MinecraftServer installedFor;

    private InventorySetBackendRegistry() {}

    public static void install(MinecraftServer server, InventorySetBackend b) {
        if (backend != null) {
            LOGGER.warn("NestWorld modapi: InventorySetBackend already installed (for {}) -- "
                    + "replacing with a new install for {}.", installedFor, server);
        }
        installedFor = server;
        backend = b;
    }

    public static void uninstall(MinecraftServer server) {
        if (installedFor == server) {
            backend = null;
            installedFor = null;
        }
    }

    static InventorySetBackend current() {
        return backend;
    }
}
