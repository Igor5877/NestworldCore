package com.skyblock.dynamic;

import com.mojang.brigadier.CommandDispatcher;
import com.skyblock.dynamic.commands.IslandCommand;
import com.skyblock.dynamic.events.PlayerEventHandler;
import com.skyblock.dynamic.utils.TeamDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.nestworld.region.NestworldRegionSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("skyblock")
public class SkyBlockMod {
    public static final Logger LOGGER = LogManager.getLogger();

    public SkyBlockMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PlayerEventHandler());

        // Register region sharding system lifecycle hooks
        MinecraftForge.EVENT_BUS.register(NestworldRegionSystem.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        TeamDataManager.loadTeamData();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Server is starting, registering commands...");
        CommandDispatcher<CommandSourceStack> dispatcher = event.getServer().getCommands().getDispatcher();
        IslandCommand.register(dispatcher);
    }
}