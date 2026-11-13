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
        if (net.nestworld.compat.ae2.glue.BusTransit.FLAG) {
            // WP3-B commit scheduler: after AE2's TickHandler (server END) → LOWEST
            MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST, false,
                    TickEvent.ServerTickEvent.class, e -> {
                        if (e.phase == TickEvent.Phase.END) net.nestworld.compat.ae2.glue.BusTransit.onServerTickEnd(e.getServer());
                    });
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppingEvent e) ->
                    net.nestworld.compat.ae2.glue.BusTransit.onServerStopping());
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartingEvent e) ->
                    net.nestworld.compat.ae2.glue.BusTransit.onServerStarting());
        }
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
                    net.nestworld.compat.ae2.glue.LifeAdmissionFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.TeardownFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.TeardownClosureFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.MbLaneFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.BusTransitFixtures.onServerTickEnd(e.getServer().getTickCount());
                    net.nestworld.compat.ae2.glue.BusTransitPerf.tick(e.getServer());
                    net.nestworld.compat.ae2.glue.BusTransitMmFixtures.tick(e.getServer().getTickCount());
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
                }))
                .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.CompatStatus.render())))
                .then(Commands.literal("cost").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.CostCommand.render()))
                        .then(Commands.literal("reset").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.CostCommand.reset()))))
                .then(Commands.literal("domainat")
                        .then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.NeighborAffinity.domainAt(
                                        c.getSource().getLevel(),
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
                .then(Commands.literal("bustransit").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.summary()))
                        .then(Commands.literal("enable").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.setActive(c.getSource().getServer(), true))))
                        .then(Commands.literal("disable").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.setActive(c.getSource().getServer(), false))))
                        .then(Commands.literal("strategies").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.strategyRejections())))
                        .then(Commands.literal("inlinetypes").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.inlineTypes())))
                        .then(Commands.literal("grids").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.grids(20))))
                        .then(Commands.literal("records").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransit.records(40)))))
                .then(Commands.literal("mblane").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLane.summary()))
                        .then(Commands.literal("calculators").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLane.calculators()))))
                .then(Commands.literal("gridcensus")
                        .then(Commands.literal("start").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridCensus.start(c.getSource().getServer().getTickCount()))))
                        .then(Commands.literal("report").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridCensus.report(c.getSource().getServer().getTickCount(), 15))))
                        .then(Commands.literal("devices").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridCensus.devices(c.getSource().getServer().getTickCount(), 25))))
                        .then(Commands.literal("coverage").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridCoverageCommand.run(c.getSource().getServer()))))
                        .then(Commands.literal("tickreset").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.GridCoverageCommand.tickReset())))
                        .then(Commands.literal("chests")
                                .then(Commands.literal("start").executes(c -> say(c.getSource(),
                                        net.nestworld.compat.ae2.glue.ChestTouchCensus.start(c.getSource().getServer()))))
                                .then(Commands.literal("report").executes(c -> say(c.getSource(),
                                        net.nestworld.compat.ae2.glue.ChestTouchCensus.report()))))
                        .then(Commands.literal("grid1").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridCoverageCommand.grid1(c.getSource().getServer()))))
                        .then(Commands.literal("worker").executes(c -> say(c.getSource(),
                                net.nestworld.compat.ae2.glue.GridWorker.status())))
                        .then(Commands.literal("ingress")
                                .then(Commands.literal("start").executes(c -> say(c.getSource(),
                                        net.nestworld.compat.ae2.glue.IngressCensus.start())))
                                .then(Commands.literal("report").executes(c -> say(c.getSource(),
                                        net.nestworld.compat.ae2.glue.IngressCensus.report(30))))));
        if (net.nestworld.compat.ae2.glue.CostFixtures.ENABLED) {
            root = root.then(Commands.literal("costfx").then(Commands.literal("diff")
                    .then(Commands.argument("chests", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                    .then(Commands.argument("ops", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1000000))
                    .then(Commands.argument("seed", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.CostFixtures.diff(c.getSource().getServer(),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "chests"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "ops"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seed")))))))));
        }
        if (T2Fixtures.ENABLED) {
            root = root.then(Commands.literal("bustransitfx")
                    .then(Commands.literal("cursorstatus").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusImportCursorFixtures.status())))
                    .then(Commands.literal("mmsetup").then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitMmFixtures.setup(c.getSource().getLevel(),
                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
                    .then(Commands.literal("mmsnapshot").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitMmFixtures.snapshot())))
                    .then(Commands.literal("mmstatus").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitMmFixtures.status())))
                    .then(Commands.literal("mmpause").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> say(c.getSource(),
                            net.nestworld.compat.ae2.glue.BusTransitMmFixtures.pause("on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                    .then(Commands.literal("perf").then(Commands.literal("start").executes(c -> say(c.getSource(),
                            net.nestworld.compat.ae2.glue.BusTransitPerf.start(c.getSource().getServer()))))
                            .then(Commands.literal("report").executes(c -> say(c.getSource(),
                                    net.nestworld.compat.ae2.glue.BusTransitPerf.report(c.getSource().getServer())))))
                    .then(Commands.literal("on").executes(c -> say(c.getSource(), "transit active=" + (net.nestworld.compat.ae2.glue.BusTransit.active = true))))
                    .then(Commands.literal("off").executes(c -> say(c.getSource(), "transit active=" + (net.nestworld.compat.ae2.glue.BusTransit.active = false))))
                    .then(Commands.literal("hold").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> say(c.getSource(),
                            "hold=" + (net.nestworld.compat.ae2.glue.BusTransit.fxHold = "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                    .then(Commands.literal("recoveryoff").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> say(c.getSource(),
                            "recoveryOff=" + (net.nestworld.compat.ae2.glue.BusTransit.fxRecoveryOff = "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                    .then(Commands.literal("fifo").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> say(c.getSource(),
                            "fifo=" + (net.nestworld.compat.ae2.glue.BusTransit.fxFifo = "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                    .then(Commands.literal("budget").then(Commands.argument("us", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1000000)).executes(c -> say(c.getSource(),
                            "budgetUs=" + (net.nestworld.compat.ae2.glue.BusTransit.budgetNs = 1000L * com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "us")) / 1000))))
                    .then(Commands.literal("reset").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.resetMetrics())))
                    .then(Commands.literal("list").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.list())))
                    .then(Commands.literal("build").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                                    .then(Commands.argument("speed", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4)).then(Commands.argument("prefill", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 2000000000))
                                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.build(c.getSource().getLevel(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "speed"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "prefill"))))))))))
                    .then(Commands.literal("attach").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.attach(c.getSource().getLevel(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind"),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n"))))))))
                    .then(Commands.literal("gen").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.gen(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v"))))))))
                    .then(Commands.literal("window").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.window(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), c.getSource().getServer().getTickCount(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "ticks")))))))
                    .then(Commands.literal("wstatus").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.windowStatus(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"))))))
                    .then(Commands.literal("count").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.count(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "label")))))))
                    .then(Commands.literal("cstatus").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.countStatus(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"))))))
                    .then(Commands.literal("fill").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.fill(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"))))))
                    .then(Commands.literal("break").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("k", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.breakBuses(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "k")))))))
                    .then(Commands.literal("rebuild").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("speed", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.rebuild(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "speed")))))))
                    .then(Commands.literal("chestguard")
                            .then(Commands.literal("build").then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.build(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                            .then(Commands.literal("gen").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.gen("on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                            .then(Commands.literal("pause").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.pause("on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                            .then(Commands.literal("hopper").then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.hopper("on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))
                            .then(Commands.literal("count").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.count())))
                            .then(Commands.literal("cstatus").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.ChestGuardFixture.status()))))
                    .then(Commands.literal("pause").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.pause(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v"))))))))
                    .then(Commands.literal("split").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.split(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), "cut".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v"))))))))
                    .then(Commands.literal("replace").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.replaceTargets(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"),
                                    "restore".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v"))))))))
                    .then(Commands.literal("pintargets").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.pinTargets(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"),
                                    "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v"))))))))
                    .then(Commands.literal("dupdeliver").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.duplicateDelivery(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"))))))
                    .then(Commands.literal("power").then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).then(Commands.argument("v", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.BusTransitFixtures.power(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"), "on".equals(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "v")))))))));
            root = root.then(Commands.literal("mblanefx")
                    .then(Commands.literal("on").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLane.setEnabled(true))))
                    .then(Commands.literal("off").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLane.setEnabled(false))))
                    .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLaneFixtures.status())))
                    .then(Commands.literal("items").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLaneFixtures.items())))
                    .then(Commands.literal("seed")
                            .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100000))
                                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLaneFixtures.seed(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n")))))))
                    .then(Commands.literal("collect")
                            .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("r", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 32))
                                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLaneFixtures.collect(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "pos"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "r")))))))
                    .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("a", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .then(Commands.argument("b", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.MbLaneFixtures.run(c.getSource().getLevel(),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "a"),
                                                    net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "b"),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode"))))))));
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
                    .then(Commands.literal("teardownclosure")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TeardownClosureFixtures.status())))
                            .then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TeardownClosureFixtures.run(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
                    .then(Commands.literal("teardown")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TeardownFixtures.status())))
                            .then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.TeardownFixtures.run(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
                    .then(Commands.literal("s1")
                            .then(Commands.literal("status").executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.LifeAdmissionFixtures.status())))
                            .then(Commands.argument("p", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(c -> say(c.getSource(), net.nestworld.compat.ae2.glue.LifeAdmissionFixtures.run(c.getSource().getLevel(),
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "p"))))))
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
        sb.append(' ').append(net.nestworld.compat.ae2.glue.LifeAdmission.summary());
        sb.append(' ').append(net.nestworld.compat.ae2.glue.ClusterTeardownCoordinator.summary());
        sb.append(' ').append(net.nestworld.compat.ae2.glue.NeighborAffinity.summary());
        sb.append(' ').append(net.nestworld.compat.ae2.glue.MbLane.summary());
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
