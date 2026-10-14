package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.service.TickManagerService;
import appeng.me.service.helpers.TickTracker;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access for the T2 acceptance fixtures (001.5 §3.4). */
@Mixin(value = TickManagerService.class, remap = false)
public interface TickManagerServiceAccessor {
    @Accessor("awake")
    Map<IGridNode, TickTracker> nw$awake();

    @Accessor("upcomingTicks")
    Map<Level, PriorityQueue<TickTracker>> nw$upcomingTicks();
}
