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
        var provider = net.minecraftforge.fml.ModList.get().getModContainerById("nestworld_modapi");
        String providerDescription = provider
                .map(c -> c.getModInfo().getVersion() + " (" + c.getModInfo().getOwningFile().getFile().getFileName() + ")")
                .orElse("<not loaded as a mod>");
        provider.ifPresentOrElse(
                c -> org.apache.logging.log4j.LogManager.getLogger("NestWorld/ModAPI").info(
                        "Mod API provider nestworld_modapi {} selected by FML", providerDescription),
                () -> org.apache.logging.log4j.LogManager.getLogger("NestWorld/ModAPI").warn(
                        "Mod API provider nestworld_modapi not loaded as a mod (expected only in dev runs)"));
        // PKG-002 Gate 2 (finding S5): Gate 1 (FML version range) trusts the provider's declared
        // version. Verify the provider's physical ABI against the one this Core was compiled
        // against BEFORE NestworldRegionSystem (the first SPI-dependent Core class) is touched.
        ModApiAbiGate.verifyOrFail(providerDescription, net.minecraftforge.fml.loading.FMLLoader.isProduction());
        MinecraftForge.EVENT_BUS.register(NestworldRegionSystem.class);
    }
}
