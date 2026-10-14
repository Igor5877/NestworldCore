package net.nestworld.compat.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * QUEUE-001 §3: the repo's writers are synchronized on the repo; AE2's reader
 * ({@code readyBlockEntities}: keySet().toLongArray(), remove(pos)) was not. The level map is handed
 * out as a view synchronized on the same monitor, so every reader operation takes the writers' lock.
 * {@code callInit} runs outside it (the list is detached under the monitor first).
 */
@Mixin(targets = "appeng.hooks.ticking.ServerBlockEntityRepo", remap = false)
public abstract class ServerBlockEntityRepoMixin {
    @WrapMethod(method = "getBlockEntities")
    private Long2ObjectMap<?> nw$synchronizedView(LevelAccessor level, Operation<Long2ObjectMap<?>> op) {
        Long2ObjectMap<?> map;
        synchronized (this) {
            map = op.call(level);
        }
        return map == null ? null : Long2ObjectMaps.synchronize(map, this);
    }
}
