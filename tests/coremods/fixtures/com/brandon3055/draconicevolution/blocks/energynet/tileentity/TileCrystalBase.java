package com.brandon3055.draconicevolution.blocks.energynet.tileentity;

import com.brandon3055.brandonscore.blocks.TileBCore;
import com.brandon3055.draconicevolution.blocks.energynet.rendering.ENetFXHandler;
import com.brandon3055.draconicevolution.network.CrystalUpdateBatcher;
import net.minecraft.core.BlockPos;

public class TileCrystalBase extends TileBCore {
    private final int id;
    public final ENetFXHandler fxHandler = new ENetFXHandler();

    public TileCrystalBase(int id, BlockPos position) {
        super(position);
        this.id = id;
    }

    public int getIDHash() {
        return id;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!CrystalUpdateBatcher.ID_CRYSTAL_MAP.containsKey(getIDHash())) {
            CrystalUpdateBatcher.ID_CRYSTAL_MAP.put(getIDHash(), f_58858_);
        }
    }

    public void onChunkUnloaded() {
        if (CrystalUpdateBatcher.ID_CRYSTAL_MAP.containsKey(getIDHash())) {
            CrystalUpdateBatcher.ID_CRYSTAL_MAP.remove(getIDHash());
        }
        fxHandler.tileUnload();
    }
}
