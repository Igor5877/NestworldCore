package net.nestworld.compat.mm.glue;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("nestworld_compat_mm")
public final class NestworldCompatMm {
    public NestworldCompatMm() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> MmControllers.evaluateGate());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) MmControllers.onServerTick();
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> MmControllers.onServerStopping());
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("nwcompatmm").requires(s -> s.hasPermission(2))
                        .executes(c -> { c.getSource().sendSuccess(() -> Component.literal(MmControllers.status()), false); return 1; })
                        .then(Commands.literal("groups").executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(MmControllers.groups()), false);
                            return 1;
                        }))));
    }
}
