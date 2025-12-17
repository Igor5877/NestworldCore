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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod("skyblock")
public class SkyBlockMod {
    public static final Logger LOGGER = LogManager.getLogger();

    public SkyBlockMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // Реєструємо обробник події FMLCommonSetupEvent
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // Реєструємо звичайні обробники подій гри
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PlayerEventHandler());
    }

    // Цей метод буде викликано на ранньому етапі завантаження
    private void commonSetup(final FMLCommonSetupEvent event) {
        // Виконуємо наш блокуючий запит ДО того, як сервер повністю запуститься
        // Це гарантує, що дані будуть готові для FTB Quests
        TeamDataManager.loadTeamData();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Server is starting, registering commands...");
        CommandDispatcher<CommandSourceStack> dispatcher = event.getServer().getCommands().getDispatcher();
        IslandCommand.register(dispatcher);
    }
}