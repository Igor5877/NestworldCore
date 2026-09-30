package net.nestworld.module;

/** MODULE-0.5 §G1: this loaded instance's ordinal -- strictly increasing per {@link ModuleId},
 *  changes on EVERY load, even reloading an unchanged jar ({@link ModuleVersion} unchanged).
 *  Assigned by {@link ModuleRegistry} at the {@code LOADED -> ENABLED} transition (the
 *  linearization point, mirroring {@code SetVelocityIntentTracker.assignVersion}'s "assign
 *  before divergence" discipline) -- never earlier, never lazily on first work. MODULE-1 does
 *  not yet have generation-tagged asynchronous work (no scheduled/mailbox capabilities are
 *  implemented this pass) -- this type exists so identity is correct NOW, before it's needed,
 *  per explicit instruction not to leave it decorative. */
public record ModuleGeneration(long value) {
}
