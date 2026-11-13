package net.nestworld.compat.mm.glue;

import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Added to MasterfulMachinery's controller by {@code MachineControllerTrackMixin}. */
public interface NwMmController {
    /** The controller's last gathered port block entities (MM replaces the list, never mutates it in place). */
    List<BlockEntity> nw$ports();

    /** Lets the controller register again after it was dropped from tracking (unload/reload). */
    void nw$untracked();
}
