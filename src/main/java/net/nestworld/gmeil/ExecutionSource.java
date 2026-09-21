package net.nestworld.gmeil;

/** Loader-independent origin of a callback into game or mod code. */
public enum ExecutionSource {
    BLOCK_ENTITY_TICK,
    ENTITY_TICK,
    FORGE_EVENT_LISTENER,
    CAPABILITY_CALL,
    NETWORK_CALLBACK,
    PLAYER_ACTION,
    SCHEDULED_MOD_WORK,
    SERVER_LIFECYCLE,
    LEVEL_LIFECYCLE,
    COMMAND_CALLBACK,
    CUSTOM_TICK_HANDLER,
    EXTERNAL_ASYNC
}
