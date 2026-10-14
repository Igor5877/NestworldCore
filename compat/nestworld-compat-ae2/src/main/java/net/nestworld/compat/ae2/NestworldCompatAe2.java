package net.nestworld.compat.ae2;

import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.Ae2CompatMixinPlugin;
import net.nestworld.compat.ae2.glue.IntegrationFixtures;
import net.nestworld.compat.ae2.glue.QueueFixtures;
import net.nestworld.compat.ae2.glue.T2Fixtures;

/** nestworld-compat-ae2: AE2 energy concurrency domain for NestWorld (docs/compat/AE2-COMPAT-DESIGN-001*.md). */
@Mod(NestworldCompatAe2.MOD_ID)
public final class NestworldCompatAe2 {
    public static final String MOD_ID = "nestworld_compat_ae2";

    public NestworldCompatAe2() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> register(e.getDispatcher()));
        if (T2Fixtures.ENABLED) {
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
                if (e.phase == TickEvent.Phase.END) {
                    T2Fixtures.onServerTickEnd();
                    QueueFixtures.onServerTickEnd(e.getServer().getTickCount());
                    IntegrationFixtures.onServerTickEnd(e.getServer().getTickCount());
                }
            });
        }
    }

    /** Read-only counters (001 §9: the addon publishes its counters through a read-only command). */
    private static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("nwcompatae2").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(stats()), false);
                    return 1;
                }));
        if (T2Fixtures.ENABLED) {
            var fx = Commands.literal("fixture").then(Commands.literal("status").executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal(T2Fixtures.status()), false);
                return 1;
            }));
            for (T2Fixtures.Kind k : T2Fixtures.Kind.values()) {
                fx = fx.then(Commands.literal(k.name().toLowerCase()).executes(c -> {
                    String r = T2Fixtures.start(k, c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.literal(r), false);
                    return 1;
                }));
            }
            var qx = Commands.literal("queuefixture").then(Commands.literal("status").executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal(QueueFixtures.status()), false);
                return 1;
            }));
            for (QueueFixtures.Kind k : QueueFixtures.Kind.values()) {
                qx = qx.then(Commands.literal(k.name().toLowerCase())
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(c -> {
                                    String r = QueueFixtures.start(k, c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos"));
                                    c.getSource().sendSuccess(() -> Component.literal(r), false);
                                    return 1;
                                })));
            }
            var ix = Commands.literal("integration")
                    .then(Commands.literal("status").executes(c -> {
                        c.getSource().sendSuccess(() -> Component.literal(IntegrationFixtures.status()), false);
                        return 1;
                    }));
            for (IntegrationFixtures.Life k : IntegrationFixtures.Life.values()) {
                ix = ix.then(Commands.literal("life_" + k.name().toLowerCase())
                        .then(Commands.argument("target", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(c -> {
                                    String r = IntegrationFixtures.life(k, c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "target"));
                                    c.getSource().sendSuccess(() -> Component.literal(r), false);
                                    return 1;
                                })));
            }
            ix = ix.then(Commands.literal("staging")
                    .then(Commands.argument("target", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 600))
                                    .executes(c -> {
                                        String r = IntegrationFixtures.staging(c.getSource().getLevel(),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "target"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"));
                                        c.getSource().sendSuccess(() -> Component.literal(r), false);
                                        return 1;
                                    }))));
            root = root.then(fx).then(qx).then(ix);
        }
        d.register(root);
    }

    public static String stats() {
        StringBuilder sb = new StringBuilder();
        sb.append("active=").append(Ae2CompatMixinPlugin.isActive()).append(" mode=").append(Ae2Compat.KERNEL.mode());
        append(sb, Ae2Compat.KERNEL.metrics);
        append(sb, Ae2Compat.COUNTERS);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object counters) {
        for (Field f : counters.getClass().getFields()) {
            try {
                if (f.get(counters) instanceof LongAdder a) sb.append(' ').append(f.getName()).append('=').append(a.sum());
            } catch (IllegalAccessException ignored) {
                // public fields only
            }
        }
    }
}
