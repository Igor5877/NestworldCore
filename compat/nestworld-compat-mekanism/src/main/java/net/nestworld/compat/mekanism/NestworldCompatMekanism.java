package net.nestworld.compat.mekanism;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.nestworld.compat.mekanism.glue.MekCompat;

/** nestworld-compat-mekanism: MEKANISM-REGISTRY-RACE-001 M-A, exact-version gated. /nwcompatmek stats|status|problems */
@Mod("nestworld_compat_mekanism")
public final class NestworldCompatMekanism {
    public NestworldCompatMekanism() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("nwcompatmek").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("stats").executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(MekCompat.stats()), false);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(MekCompat.status()), false);
                            return 1;
                        }))
                        .then(Commands.literal("problems").executes(c -> {
                            String text = MekCompat.problems().isEmpty() ? "(none)" : String.join("\n", MekCompat.problems());
                            c.getSource().sendSuccess(() -> Component.literal(text), false);
                            return 1;
                        }))));
    }
}
