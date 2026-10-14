package net.nestworld.compat.ae2.glue;

import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.RelativeSide;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * AE2-COMPAT-LEGACY-ORIENTATION-001.1 (owner-approved disk path): migrate AE2's legacy "forward"/"up"
 * orientation while the block entity is created from chunk NBT and is not yet published
 * (LevelChunk.promotePendingBlockEntity, between loadStatic and addAndRegisterBlockEntity; the chunk is
 * not ticking yet). Same computation as AE2's onReady, written straight into the chunk section: no
 * onPlace/onRemove, no neighbour updates, no events. The chunk is marked unsaved so the next save
 * persists the migrated state (AE2 writes no legacy keys).
 */
public final class LegacyOrientation {
    public static final LongAdder MIGRATED = new LongAdder();
    public static final LongAdder UNCHANGED = new LongAdder();
    public static final LongAdder LIVE_WORLD_SKIPPED = new LongAdder();

    public static void migratePrePublication(LevelChunk chunk, BlockPos pos, BlockEntity be, NwAeBlockEntity ae) {
        BlockOrientation o = ae.nw$pendingOrientation();
        if (o == null) return;
        BlockState old = be.getBlockState();
        BlockState migrated = IOrientationStrategy.get(old).setOrientation(old, o.getSide(RelativeSide.FRONT), o.getSpin());
        ae.nw$clearPendingOrientation();
        if (migrated == old) {
            UNCHANGED.increment();
            return;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, migrated);
        be.setBlockState(migrated);
        chunk.setUnsaved(true);
        MIGRATED.increment();
    }

    /** onReady reached with a pending orientation: NBT loaded into a live world. Fail-closed. */
    public static void liveWorldSkipped(Object be, BlockPos pos, BlockState wanted) {
        LIVE_WORLD_SKIPPED.increment();
        Ae2Compat.LOG.warn("[nestworld-compat-ae2] legacy AE2 orientation at {} came from NBT loaded into a live world; "
                + "not applied (fail-closed until an owner-routed migration exists): wanted {}", pos, wanted);
    }

    private LegacyOrientation() {
    }
}
