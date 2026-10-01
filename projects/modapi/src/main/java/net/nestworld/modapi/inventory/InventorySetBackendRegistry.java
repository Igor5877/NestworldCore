package net.nestworld.modapi.inventory;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p><b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> This type is a contract
 * between NestWorldCore and the Mod API provider, not a promise to mods; it may change, or move to
 * a separate artifact, without notice (docs/contracts/PKG-002-distribution-model.md, D1a). Mods
 * use the facades ({@code NestWorldEntities}, {@code NestWorldInventory}) and their value types.
 *
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
