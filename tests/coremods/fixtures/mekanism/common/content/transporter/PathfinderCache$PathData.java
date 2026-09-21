package mekanism.common.content.transporter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record PathfinderCache$PathData(BlockPos startTransporter, BlockPos end, Direction endSide) {}
