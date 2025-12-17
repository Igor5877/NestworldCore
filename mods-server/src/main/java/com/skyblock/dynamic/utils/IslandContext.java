package com.skyblock.dynamic.utils;

/**
 * Represents the context of an island server.
 */
public class IslandContext {
    private final boolean isIslandServer;
    private final String ownerUuid;

    /**
     * Constructs a new IslandContext.
     *
     * @param isIslandServer Whether the server is an island server.
     * @param ownerUuid      The UUID of the island owner.
     */
    public IslandContext(boolean isIslandServer, String ownerUuid) {
        this.isIslandServer = isIslandServer;
        this.ownerUuid = ownerUuid;
    }

    /**
     * Checks if the server is an island server.
     *
     * @return True if the server is an island server, false otherwise.
     */
    public boolean isIslandServer() {
        return isIslandServer;
    }

    /**
     * Gets the UUID of the island owner.
     *
     * @return The UUID of the island owner.
     */
    public String getOwnerUuid() {
        return ownerUuid;
    }

    /**
     * Gets the default island context.
     *
     * @return The default island context.
     */
    public static IslandContext getDefault() {
        return new IslandContext(false, null);
    }
}
