package net.nestworld.compat.ae2.glue;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Fixture hooks around first-tick initialization (QUEUE-LIFE-001); null in production. */
public final class LifeHooks {
    public interface Hook {
        void at(String point, BlockEntity be);
    }

    public static volatile Hook hook;

    private LifeHooks() {
    }
}
