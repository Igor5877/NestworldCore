package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;

/** Grid back-reference for services and interest managers that AE2 constructs without one. */
public interface NwGridBound {
    Grid nw$boundGrid();

    void nw$bindGrid(Grid grid);
}
