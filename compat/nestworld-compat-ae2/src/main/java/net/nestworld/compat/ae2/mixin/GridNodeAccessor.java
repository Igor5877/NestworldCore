package net.nestworld.compat.ae2.mixin;

import appeng.me.Grid;
import appeng.me.GridNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GridNode.class, remap = false)
public interface GridNodeAccessor {
    @Accessor("myGrid")
    Grid nw$myGrid();
}
