package net.nestworld.compat.ae2.glue;

import appeng.api.orientation.BlockOrientation;
import appeng.me.helpers.IGridConnectedBlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * LEGACY-ORIENTATION-001.1 fixtures on the real disk path: with the chunk unloaded, legacy
 * "forward"/"up" keys are written into the saved block-entity NBT through the same ChunkMap storage the
 * loader reads; reloading the chunk then runs promotePendingBlockEntity. Fixture mode only.
 */
public final class LegacyFixtures {
    private static Optional<CompoundTag> read(ServerLevel level, ChunkPos cp) {
        return level.getChunkSource().chunkMap.read(cp).join();
    }

    private static CompoundTag beEntry(CompoundTag chunk, BlockPos pos) {
        ListTag list = chunk.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag be = list.getCompound(i);
            if (be.getInt("x") == pos.getX() && be.getInt("y") == pos.getY() && be.getInt("z") == pos.getZ()) return be;
        }
        return null;
    }

    public static String loaded(ServerLevel level, BlockPos pos) {
        return "loaded=" + (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null);
    }

    public static String inject(ServerLevel level, BlockPos pos, String forward, String up) {
        ChunkPos cp = new ChunkPos(pos);
        if (level.getChunkSource().getChunkNow(cp.x, cp.z) != null) return "chunk " + cp + " is still loaded";
        Optional<CompoundTag> tag = read(level, cp);
        if (tag.isEmpty()) return "no saved data for " + cp;
        CompoundTag be = beEntry(tag.get(), pos);
        if (be == null) return "no saved block entity at " + pos;
        be.putString("forward", forward);
        be.putString("up", up);
        level.getChunkSource().chunkMap.write(cp, tag.get());
        return "injected legacy forward=" + forward + " up=" + up + " into " + be.getString("id") + " at " + pos;
    }

    public static String savedNbt(ServerLevel level, BlockPos pos) {
        Optional<CompoundTag> tag = read(level, new ChunkPos(pos));
        if (tag.isEmpty()) return "no saved data";
        CompoundTag be = beEntry(tag.get(), pos);
        if (be == null) return "no saved block entity";
        return "saved legacyKeys=" + (be.contains("forward") || be.contains("up")) + " id=" + be.getString("id");
    }

    public static String check(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        String node = be instanceof IGridConnectedBlockEntity g && g.getMainNode().getNode() != null
                ? "node=present grid=" + (g.getMainNode().getGrid() != null ? g.getMainNode().getGrid().size() + "nodes" : "none")
                : "node=absent";
        String orient = be == null ? "-" : BlockOrientation.get(be).toString();
        String pending = be instanceof NwAeBlockEntity ae ? String.valueOf(ae.nw$pendingOrientation()) : "-";
        return "state=" + level.getBlockState(pos) + " orientation=" + orient + " pending=" + pending + " " + node
                + " migrated=" + LegacyOrientation.MIGRATED.sum() + " unchanged=" + LegacyOrientation.UNCHANGED.sum()
                + " liveSkipped=" + LegacyOrientation.LIVE_WORLD_SKIPPED.sum();
    }

    private LegacyFixtures() {
    }
}
