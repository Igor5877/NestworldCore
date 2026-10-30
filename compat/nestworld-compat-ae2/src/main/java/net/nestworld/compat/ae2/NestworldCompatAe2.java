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
import net.nestworld.compat.ae2.glue.GridPhaseFixtures;
import net.nestworld.compat.ae2.glue.IntegrationFixtures;
import net.nestworld.compat.ae2.glue.LegacyFixtures;
import net.nestworld.compat.ae2.glue.P2PFixtures;
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
                    GridPhaseFixtures.onServerTickEnd(e.getServer().getTickCount());
                    P2PFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.TopologyFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.TopologyLifecycleFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.EndTickStateFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.NeighborCallbackFixtures.onServerTickEnd(e.getServer().getTickCount());
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
            var lx = Commands.literal("legacy")
                    .then(Commands.literal("loaded").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), LegacyFixtures.loaded(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("check").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), LegacyFixtures.check(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("savednbt").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), LegacyFixtures.savedNbt(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("inject").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .then(Commands.argument("forward", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .then(Commands.argument("up", com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .executes(c -> say(c.getSource(), LegacyFixtures.inject(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "forward"),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "up"))))))));
            var tx = Commands.literal("topology")
                    .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TopologyFixtures.status())))
                    .then(Commands.literal("neighbor")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.NeighborCallbackFixtures.status())))
                            .then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.NeighborCallbackFixtures.run(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
                    .then(Commands.literal("endtick")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.EndTickStateFixtures.status())))
                            .then(Commands.argument("a", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.EndTickStateFixtures.run(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "a"))))))
                    .then(Commands.literal("lifecycle")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TopologyLifecycleFixtures.status())))
                            .then(Commands.argument("a", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("cycles", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200))
                                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TopologyLifecycleFixtures.run(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "a"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "cycles")))))))
                    .then(Commands.literal("ensure").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TopologyFixtures.ensure(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos"))))));
            var gx = Commands.literal("gridphase")
                    .then(Commands.literal("status").executes(c -> say(c.getSource(), GridPhaseFixtures.status())))
                    .then(Commands.literal("staging")
                            .then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(c -> say(c.getSource(), GridPhaseFixtures.staging(
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"), c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos")))))))
                    .then(Commands.literal("nested").then(Commands.argument("drive", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), GridPhaseFixtures.nested(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "drive"))))))
                    .then(Commands.literal("replayfresh").then(Commands.argument("drive", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), GridPhaseFixtures.replayFresh(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "drive"))))))
                    .then(Commands.literal("multigrid").then(Commands.argument("host", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 3600))
                                    .executes(c -> say(c.getSource(), GridPhaseFixtures.multiGrid(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "host"),
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds")))))))
                    .then(Commands.literal("overlap")
                            .then(Commands.argument("drive", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("target", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(c -> say(c.getSource(), GridPhaseFixtures.overlap(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "drive"),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "target")))))))
                    .then(Commands.literal("stress")
                            .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 3600))
                                            .executes(c -> say(c.getSource(), GridPhaseFixtures.stress(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds")))))));
            var px = Commands.literal("p2p")
                    .then(Commands.literal("status").executes(c -> say(c.getSource(), P2PFixtures.status())))
                    .then(Commands.literal("h2stress").then(Commands.argument("loop", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 3600))
                                    .executes(c -> say(c.getSource(), P2PFixtures.h2Stress(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "loop"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds")))))))
                    .then(Commands.literal("h2loop").then(Commands.argument("base", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c -> {
                        String r = P2PFixtures.h2LoopSetup(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "base"));
                        return say(c.getSource(), r == null ? "h2loop OK" : "h2loop FAILED: " + r);
                    })))
                    .then(Commands.literal("h2loopbind").then(Commands.argument("base", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.h2LoopBind(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "base"))))))
                    .then(Commands.literal("h2recursion").then(Commands.argument("base", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.h2Recursion(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "base"))))))
                    .then(Commands.literal("h2concurrent").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("in", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("out", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1_000_000))
                                            .executes(c -> say(c.getSource(), P2PFixtures.h2Concurrent(c.getSource().getLevel(),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "in"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "out"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "amount")))))))))
                    .then(Commands.literal("h2throw").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("in", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("out", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1_000_000))
                                            .executes(c -> say(c.getSource(), P2PFixtures.h2Throw(c.getSource().getLevel(),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "in"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "out"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "amount")))))))))
                    .then(Commands.literal("h1stress").then(Commands.argument("g1", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("g2", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 3600))
                                    .executes(c -> say(c.getSource(), P2PFixtures.h1Stress(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g1"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g2"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"))))))))
                    .then(Commands.literal("h1setup").then(Commands.argument("g1", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("g2", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> {
                                String r = P2PFixtures.h1Setup(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g1"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g2"));
                                return say(c.getSource(), r == null ? "h1setup OK" : "h1setup FAILED: " + r);
                            }))))
                    .then(Commands.literal("h1").then(Commands.argument("case", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("g1", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("g2", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), P2PFixtures.h1(c.getSource().getLevel(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "case"),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g1"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "g2"))))))))
                    .then(Commands.literal("rscut").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.rsCut(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("rsreconnect").then(Commands.argument("gap", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.rsReconnect(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "gap"))))))
                    .then(Commands.literal("owner").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.owner(c.getSource().getLevel(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"))))))
                    .then(Commands.literal("setup").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("in", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("out", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> {
                                        String r = P2PFixtures.setup(c.getSource().getLevel(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
                                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "in"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "out"));
                                        return say(c.getSource(), r == null ? "setup OK" : "setup FAILED: " + r);
                                    })))))
                    .then(Commands.literal("bind").then(Commands.argument("in", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("out", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), P2PFixtures.bind(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "in"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "out")))))))
                    .then(Commands.literal("conserve").then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word()).then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("in", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("out", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1_000_000))
                                            .executes(c -> say(c.getSource(), P2PFixtures.conserve(c.getSource().getLevel(),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "label"), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "in"), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "out"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "amount"))))))))));
            root = root.then(fx).then(qx).then(ix).then(lx).then(gx).then(px).then(tx);
        }
        d.register(root);
    }

    private static int say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    public static String stats() {
        StringBuilder sb = new StringBuilder();
        sb.append("active=").append(Ae2CompatMixinPlugin.isActive()).append(" mode=").append(Ae2Compat.KERNEL.mode())
                .append(" manifestEligible=").append(Ae2Compat.manifestEligible);
        append(sb, Ae2Compat.KERNEL.metrics);
        append(sb, Ae2Compat.COUNTERS);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object counters) {
        for (Field f : counters.getClass().getFields()) {
            try {
                Object v = f.get(counters);
                if (v instanceof LongAdder a) sb.append(' ').append(f.getName()).append('=').append(a.sum());
                else if (v instanceof java.util.concurrent.atomic.LongAccumulator a) sb.append(' ').append(f.getName()).append('=').append(a.get());
            } catch (IllegalAccessException ignored) {
                // public fields only
            }
        }
    }
}
