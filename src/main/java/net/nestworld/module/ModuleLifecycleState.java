package net.nestworld.module;

/**
 * `docs/MODULE0_AUDIT.md` §C's revised lifecycle chain, MODULE-1 scope: legal path is
 * {@code DISCOVERED -> LOADED -> ENABLED -> RUNNING -> QUIESCING -> DISABLED}. {@code FAILED} is
 * reachable from any state on an unhandled exception. {@code UNLOADED} is deliberately NOT
 * implemented this pass -- MODULE-1 has no classloader isolation (`docs/MODULE0_AUDIT.md` §7:
 * "no ModuleClassLoader implementation"), so process restart remains the only physical unload
 * boundary; {@code DISABLED} is this pass's terminal non-failure state. No state may skip
 * {@code QUIESCING} on the way to {@code DISABLED} if it ever reached {@code RUNNING} -- the one
 * hard rule §C asserted with confidence, precisely because Core's own shutdown (mailbox not
 * drained, `docs/RACE_CLASSIFICATION.md` item 15) shows what happens when that step is skipped.
 */
public enum ModuleLifecycleState {
    DISCOVERED,
    LOADED,
    ENABLED,
    RUNNING,
    QUIESCING,
    DISABLED,
    FAILED
}
