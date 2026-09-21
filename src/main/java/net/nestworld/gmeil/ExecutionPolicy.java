package net.nestworld.gmeil;

/** Permission to execute one opaque callback path. No enum value itself schedules work. */
public enum ExecutionPolicy {
    MAIN_COMPAT,
    SERIAL_COMPAT,
    REGION_CERTIFIED,
    CORE_OWNED,
    EXTERNAL_ASYNC,
    DENY
}
