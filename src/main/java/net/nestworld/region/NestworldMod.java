package net.nestworld.region;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * Mod entrypoint for the NestWorld region sharding system.
 *
 * <p>All lifecycle wiring lives in {@link NestworldRegionSystem} static event
 * handlers (ServerStartingEvent initialises the system, RegisterCommandsEvent
 * registers /nestworld and /spark); this class only puts them on the bus.
 */
@Mod("nestworld")
public class NestworldMod {

    public NestworldMod() {
        MinecraftForge.EVENT_BUS.register(NestworldRegionSystem.class);
    }
}
