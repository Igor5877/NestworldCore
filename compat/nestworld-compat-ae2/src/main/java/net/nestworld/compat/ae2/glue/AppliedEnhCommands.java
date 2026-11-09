package net.nestworld.compat.ae2.glue;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** APPLIEDENH-PERF-001 commands: {@code /nwappliedenh status} (read-only, always); fixture subcommands only with
 *  -Dnestworld.compatAe2.appliedEnhFixtures=true. Separate from the shared /nwcompatae2 tree. */
@Mod.EventBusSubscriber(modid = "nestworld_compat_ae2", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AppliedEnhCommands {
    private AppliedEnhCommands() { }

    @SubscribeEvent
    public static void onAboutToStart(ServerAboutToStartEvent e) {
        if (AppliedEnhMixinPlugin.present()) {
            AppliedEnhRecheckElision.sophState(); // hash the sophisticated jars once, off the hot path
            Ae2Compat.LOG.info("[nestworld-compat-ae2] {}", AppliedEnhRecheckElision.status());
        } else {
            Ae2Compat.LOG.info("[nestworld-compat-ae2] APPLIEDENH-PERF-001 not applied yet (target applies on class load): {}", AppliedEnhMixinPlugin.detail());
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (AppliedEnhFixtures.ENABLED && e.phase == TickEvent.Phase.END) AppliedEnhFixtures.onServerTick();
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent e) {
        var root = Commands.literal("nwappliedenh").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(c -> say(c.getSource(), AppliedEnhRecheckElision.status())));
        if (AppliedEnhFixtures.ENABLED) {
            root = root
                    .then(Commands.literal("on").executes(c -> say(c.getSource(), AppliedEnhRecheckElision.setEnabled(true))))
                    .then(Commands.literal("off").executes(c -> say(c.getSource(), AppliedEnhRecheckElision.setEnabled(false))))
                    .then(Commands.literal("reset").executes(c -> { AppliedEnhRecheckElision.resetCounters(); return say(c.getSource(), "counters reset"); }))
                    .then(Commands.literal("trace").then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), AppliedEnhFixtures.trace(c.getSource().getLevel(), BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("benchstatus").executes(c -> say(c.getSource(), AppliedEnhFixtures.benchStatus())))
                    .then(Commands.literal("bench").then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 72000))
                                    .then(Commands.argument("iters", IntegerArgumentType.integer(1, 100000))
                                            .executes(c -> say(c.getSource(), AppliedEnhFixtures.benchStart(c.getSource().getLevel(),
                                                    BlockPosArgument.getBlockPos(c, "pos"), IntegerArgumentType.getInteger(c, "ticks"),
                                                    IntegerArgumentType.getInteger(c, "iters"))))))));
        }
        e.getDispatcher().register(root);
    }

    private static int say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
