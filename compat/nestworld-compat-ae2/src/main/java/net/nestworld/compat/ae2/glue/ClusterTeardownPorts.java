package net.nestworld.compat.ae2.glue;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.Platform;
import java.util.ArrayList;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * AE2-PIN-LEAK-001 D4a: teardown bodies run by {@link ClusterTeardownCoordinator}, operating on the CAPTURED cluster
 * (never on a requester's cluster field at delivery time — a requester such as a crafting core can already belong to a
 * re-formed cluster by then). Faithful port of AE2 15.4.10 {@code CraftingBlockEntity.breakCluster} (version-locked by
 * the addon's AE2 manifest gate; a later admitted release must leave CraftingBlockEntity — a mixin target — unchanged,
 * see StartupGate); the only change: each {@code Platform.spawnDrops} becomes an exactly-once drop intent.
 */
public final class ClusterTeardownPorts {
    private ClusterTeardownPorts() {
    }

    /** {@code CraftingBlockEntity.breakCluster} of {@code requester} for the captured {@code cluster}. */
    public static void craftingBreakCluster(CraftingBlockEntity requester, CraftingCPUCluster cluster) {
        cluster.cancelJob();
        var inv = cluster.craftingLogic.getInventory();
        Level level = requester.getLevel();

        // Drop stacks
        var places = new ArrayList<BlockPos>();
        for (var blockEntity : (Iterable<CraftingBlockEntity>) cluster::getBlockEntities) {
            if (requester == blockEntity) {
                places.add(requester.getBlockPos());
            } else {
                for (var d : Direction.values()) {
                    var p = blockEntity.getBlockPos().relative(d);
                    if (level.isEmptyBlock(p)) {
                        places.add(p);
                    }
                }
            }
        }

        if (places.isEmpty()) {
            throw new IllegalStateException(cluster + " does not contain any kind of blocks, which were destroyed.");
        }

        for (var entry : inv.list) {
            var position = Util.getRandom(places, level.getRandom());
            var stacks = new ArrayList<ItemStack>();
            entry.getKey().addDrops(entry.getLongValue(), stacks, level, position);
            ClusterTeardownCoordinator.drop(level, position, stacks, () -> Platform.spawnDrops(level, position, stacks));
        }

        inv.clear(); // Ensure items only ever get dropped once

        cluster.destroy();
    }
}
