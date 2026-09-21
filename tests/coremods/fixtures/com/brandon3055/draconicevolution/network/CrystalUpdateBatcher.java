package com.brandon3055.draconicevolution.network;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CrystalUpdateBatcher {
    public static final Map<Integer, BlockPos> ID_CRYSTAL_MAP = new ConcurrentHashMap<>();
}
