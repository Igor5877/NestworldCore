/*
 * NestworldCore - Island data model
 */
package net.nestworld.island;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a SkyBlock island. Each island maps to exactly one Forge server process
 * when in WARM or ACTIVE state. In COLD state no process exists.
 *
 * Thread-safe: state mutations happen on main thread, reads may come from async threads.
 */
public final class Island {

    private final UUID islandId;
    private final UUID ownerId;

    private volatile IslandState state;
    private volatile long stateChangedAt;
    private volatile long lastPlayerLeaveAt;

    /** OS PID of the Forge process, -1 when no process is running. */
    private volatile long processPid;

    /** Port this island's Forge server is listening on, -1 when not running. */
    private volatile int serverPort;

    private final Set<UUID> onlinePlayers = new CopyOnWriteArraySet<>();

    /** Quarantine: island cannot be warmed up until this timestamp. 0 = no quarantine. */
    private volatile long quarantineUntil;

    public Island(UUID islandId, UUID ownerId) {
        this.islandId = islandId;
        this.ownerId = ownerId;
        this.state = IslandState.COLD;
        this.stateChangedAt = System.currentTimeMillis();
        this.processPid = -1;
        this.serverPort = -1;
    }

    // --- State transitions (called by IslandLifecycleManager) ---

    void transitionTo(IslandState newState) {
        this.state = newState;
        this.stateChangedAt = System.currentTimeMillis();
    }

    void assignProcess(long pid, int port) {
        this.processPid = pid;
        this.serverPort = port;
    }

    void clearProcess() {
        this.processPid = -1;
        this.serverPort = -1;
    }

    void playerJoined(UUID playerId) {
        onlinePlayers.add(playerId);
    }

    void playerLeft(UUID playerId) {
        onlinePlayers.remove(playerId);
        if (onlinePlayers.isEmpty()) {
            lastPlayerLeaveAt = System.currentTimeMillis();
        }
    }

    void quarantine(long durationMs) {
        this.quarantineUntil = System.currentTimeMillis() + durationMs;
    }

    // --- Reads ---

    public UUID getIslandId()          { return islandId; }
    public UUID getOwnerId()           { return ownerId; }
    public IslandState getState()      { return state; }
    public long getStateChangedAt()    { return stateChangedAt; }
    public long getLastPlayerLeaveAt() { return lastPlayerLeaveAt; }
    public long getProcessPid()        { return processPid; }
    public int getServerPort()         { return serverPort; }
    public int getPlayerCount()        { return onlinePlayers.size(); }
    public boolean isEmpty()           { return onlinePlayers.isEmpty(); }
    public Set<UUID> getOnlinePlayers(){ return Collections.unmodifiableSet(onlinePlayers); }

    public boolean isQuarantined() {
        return quarantineUntil > 0 && System.currentTimeMillis() < quarantineUntil;
    }

    public long getQuarantineRemainingMs() {
        long remaining = quarantineUntil - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /** How long this island has been in current state, in ms. */
    public long getStateAgeMs() {
        return System.currentTimeMillis() - stateChangedAt;
    }

    @Override
    public String toString() {
        return "Island{id=" + islandId + ", state=" + state + ", players=" + onlinePlayers.size() + "}";
    }
}
