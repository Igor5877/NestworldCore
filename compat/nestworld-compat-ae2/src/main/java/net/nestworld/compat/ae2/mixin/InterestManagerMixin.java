package net.nestworld.compat.ae2.mixin;

import appeng.me.Grid;
import appeng.me.helpers.InterestManager;
import net.nestworld.compat.ae2.glue.NwGridBound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = InterestManager.class, remap = false)
public abstract class InterestManagerMixin implements NwGridBound {
    @Unique private volatile Grid nw$grid;

    @Override
    public Grid nw$boundGrid() {
        return nw$grid;
    }

    @Override
    public void nw$bindGrid(Grid grid) {
        nw$grid = grid;
    }
}
