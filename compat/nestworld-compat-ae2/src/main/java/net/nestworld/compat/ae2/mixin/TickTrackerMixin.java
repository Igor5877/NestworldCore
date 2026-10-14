package net.nestworld.compat.ae2.mixin;

import appeng.me.service.helpers.TickTracker;
import net.nestworld.compat.ae2.glue.NwTickTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Scheduling generation of a tracker (001.5 §3.1); only written under the owning grid's domain. */
@Mixin(value = TickTracker.class, remap = false)
public abstract class TickTrackerMixin implements NwTickTracker {
    @Unique private long nw$gen;

    @Override
    public long nw$gen() {
        return nw$gen;
    }

    @Override
    public void nw$bump() {
        nw$gen++;
    }
}
