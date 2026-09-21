package mekanism.common.content.transporter;

import net.minecraft.core.BlockPos;
import java.util.List;

public record PathfinderCache$CachedPath(List<BlockPos> path, double cost) {}
