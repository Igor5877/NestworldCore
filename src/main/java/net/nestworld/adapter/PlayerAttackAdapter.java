package net.nestworld.adapter;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * §41.C reference split (see docs/CORE_ADAPTER_CLASSIFICATION.md,
 * docs/DEPENDENCY_DIRECTION_AUDIT.md #96): the single Minecraft 1.20.1 vanilla method call that
 * used to live inline in {@link net.nestworld.region.PlayerAttackDispatcher#applyQueued}. Core's
 * dispatcher decides WHETHER and WHERE a queued attack executes (ownership, routing, mailbox,
 * reroute); this class is the only place that knows HOW to execute one on this Minecraft version.
 *
 * <p>Deliberately a single static method, not an interface/factory/registry: per the ТЗ's hot-path
 * rule (no reflection, no dynamic lookup, no capability resolution per-call), a direct static call
 * is as thin as a patch and fully inlinable by the JIT. A future Minecraft version whose combat
 * entry point changes shape gets a new adapter class with the same method signature; Core's
 * dispatcher does not change.
 */
public final class PlayerAttackAdapter {
    private PlayerAttackAdapter() {}

    /** Executes vanilla {@code Player.attack(Entity)} exactly as the unsplit code did. */
    public static void invoke(ServerPlayer attacker, Entity target) {
        attacker.attack(target);
    }
}
