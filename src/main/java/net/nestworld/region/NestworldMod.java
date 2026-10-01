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
        // PKG-002 (docs/contracts/PKG-002-distribution-model.md): report which Mod API provider FML
        // selected. Acceptance itself is FML's: Core's mods.toml declares the verified range for
        // nestworld_modapi, so an out-of-range provider is rejected before this class loads.
        // Deliberately read through FML's ModList only -- no net.nestworld.modapi type is touched
        // here, so this line can never be the first thing to link an incompatible provider.
        net.minecraftforge.fml.ModList.get().getModContainerById("nestworld_modapi").ifPresentOrElse(
                c -> org.apache.logging.log4j.LogManager.getLogger("NestWorld/ModAPI").info(
                        "Mod API provider nestworld_modapi {} selected by FML from {}",
                        c.getModInfo().getVersion(), c.getModInfo().getOwningFile().getFile().getFileName()),
                () -> org.apache.logging.log4j.LogManager.getLogger("NestWorld/ModAPI").warn(
                        "Mod API provider nestworld_modapi not loaded as a mod (expected only in dev runs)"));
        MinecraftForge.EVENT_BUS.register(NestworldRegionSystem.class);
    }
}
