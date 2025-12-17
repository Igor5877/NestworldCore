package com.skyblock.dynamic.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class IslandCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("myisland")
            .executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("This is your island!"), false);
                return 1;
            });
        dispatcher.register(command);
    }
}