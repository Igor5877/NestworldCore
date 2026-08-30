package net.nestworld.region;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Admin command for inspecting and driving the region sharding system.
 *
 * <pre>
 *   /nestworld status            — list active regions with TPS and entity counts
 *   /nestworld lag               — find the slowest region: hottest chunk, top entities, nearest player
 *   /nestworld split &lt;id&gt;        — force-split a region (testing / manual tuning)
 *   /nestworld merge &lt;a&gt; &lt;b&gt;     — force-merge two sibling regions
 *   /nestworld pins              — list entity types pinned to main-thread ticking
 *   /nestworld pin &lt;type&gt;        — pin an entity or block-entity type to main
 *   /nestworld unpin &lt;type&gt;      — resume region-thread ticking for a type
 *   /nestworld pinmod &lt;ns&gt;       — pin every entity/BE from a mod namespace
 *   /nestworld unpinmod &lt;ns&gt;     — unpin a mod namespace
 *   /nestworld hotspots           — NestWorld Inspector §4: top combined block-tick+entity hotspots
 *   /nestworld goto hotspot &lt;id&gt; — teleport to a ranked hotspot from the last /nestworld hotspots
 *   /nestworld goto region &lt;id&gt;  — teleport to a region's hottest spot (or a safe fallback)
 *   /nestworld goto chunk &lt;x&gt; &lt;z&gt; — teleport to a chunk's center
 *   /nestworld goto entity &lt;uuid&gt; — teleport to a live entity
 * </pre>
 */
public final class NestworldCommand {

    private NestworldCommand() {}

    /** Stage 1 (region-sharding for Nether/End plan): every admin command in this class
     *  operates on the Overworld's own {@link NestworldDimensionRegion} — an explicit, documented
     *  choice (not an accident) matching this project's precedent for diagnostic-only surfaces
     *  during a staged rollout (see {@code ForceLoadCommand}'s deliberately-global budget). A
     *  per-dimension command surface is a legitimate follow-up once Nether/End are actually
     *  enabled and operators need it, not required for Stage 1 itself. */
    private static NestworldDimensionRegion overworldRegion(NestworldRegionSystem sys) {
        return sys.getDimensionRegion(sys.getOverworld());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nestworld")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), false))
                        .then(Commands.literal("full").executes(ctx -> status(ctx.getSource(), true))))
                .then(Commands.literal("lag").executes(ctx -> lag(ctx.getSource())))
                .then(Commands.literal("borders").executes(ctx -> toggleBorders(ctx.getSource())))
                .then(Commands.literal("split")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> split(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("merge")
                        .then(Commands.argument("a", IntegerArgumentType.integer(0))
                                .then(Commands.argument("b", IntegerArgumentType.integer(0))
                                        .executes(ctx -> merge(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "a"),
                                                IntegerArgumentType.getInteger(ctx, "b"))))))
                .then(Commands.literal("dumpregistries")
                        .executes(ctx -> dumpRegistries(ctx.getSource(), "registry-snapshot.txt"))
                        .then(Commands.argument("path", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> dumpRegistries(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "path")))))
                .then(Commands.literal("pins").executes(ctx -> listPins(ctx.getSource())))
                .then(Commands.literal("pin")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> pin(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type")))))
                .then(Commands.literal("unpin")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> unpin(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type")))))
                .then(Commands.literal("pinmod")
                        .then(Commands.argument("namespace", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> pinMod(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "namespace")))))
                .then(Commands.literal("unpinmod")
                        .then(Commands.argument("namespace", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> unpinMod(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "namespace")))))
                .then(Commands.literal("debugsync")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> debugSyncGetChunk(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))))
                .then(Commands.literal("debugsyncburst")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> debugSyncGetChunkBurst(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("chunkstats").executes(ctx -> chunkStats(ctx.getSource())))
                .then(Commands.literal("chunkpromotion").executes(ctx -> chunkPromotion(ctx.getSource())))
                .then(Commands.literal("chunkscheduler").executes(ctx -> chunkScheduler(ctx.getSource())))
                .then(Commands.literal("tickets").executes(ctx -> ticketDistribution(ctx.getSource())))
                .then(Commands.literal("ghostzones").executes(ctx -> ghostZoneStats(ctx.getSource())))
                .then(Commands.literal("regionpool").executes(ctx -> regionPoolStats(ctx.getSource())))
                .then(Commands.literal("regionslow").executes(ctx -> regionSlowStats(ctx.getSource())))
                .then(Commands.literal("polltask").executes(ctx -> pollTaskStats(ctx.getSource())))
                .then(Commands.literal("worldgenboundary").executes(ctx -> worldgenBoundaryStats(ctx.getSource())))
                .then(Commands.literal("worldgenglue").executes(ctx -> worldgenGlueStats(ctx.getSource())))
                .then(Commands.literal("featurefails").executes(ctx -> featureFails(ctx.getSource())))
                .then(Commands.literal("tickphases").executes(ctx -> tickPhases(ctx.getSource())))
                .then(Commands.literal("leveticksownership").executes(ctx -> levelTicksOwnership(ctx.getSource())))
                .then(Commands.literal("ownershipassertions").executes(ctx -> ownershipAssertions(ctx.getSource())))
                .then(Commands.literal("playerinputstats").executes(ctx -> playerInputStats(ctx.getSource())))
                .then(Commands.literal("playertickexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerTickExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerTickExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerTickExperimentList(ctx.getSource()))))
                .then(Commands.literal("playerattackexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerAttackExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerAttackExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerAttackExperimentList(ctx.getSource()))))
                .then(Commands.literal("playerattacktiming").executes(ctx -> playerAttackTiming(ctx.getSource())))
                .then(Commands.literal("playerattacktimingreset").executes(ctx -> playerAttackTimingReset(ctx.getSource())))
                .then(Commands.literal("playerinteractiontiming").executes(ctx -> playerInteractionTiming(ctx.getSource())))
                .then(Commands.literal("playerinteractiontimingreset").executes(ctx -> playerInteractionTimingReset(ctx.getSource())))
                .then(Commands.literal("blockmainprobe")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(0))
                                .then(Commands.argument("region", IntegerArgumentType.integer(0))
                                        .executes(ctx -> blockMainProbe(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "ms"),
                                                IntegerArgumentType.getInteger(ctx, "region"))))))
                .then(Commands.literal("playerinteractionexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerInteractionExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerInteractionExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerInteractionExperimentList(ctx.getSource()))))
                .then(Commands.literal("playeruseitemexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerUseItemExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerUseItemExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerUseItemExperimentList(ctx.getSource()))))
                .then(Commands.literal("playeruseitemtiming").executes(ctx -> playerUseItemTiming(ctx.getSource())))
                .then(Commands.literal("playeruseitemtimingreset").executes(ctx -> playerUseItemTimingReset(ctx.getSource())))
                .then(Commands.literal("playeruseitemonblockexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerUseItemOnBlockExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerUseItemOnBlockExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerUseItemOnBlockExperimentList(ctx.getSource()))))
                .then(Commands.literal("playeruseitemonblocktiming").executes(ctx -> playerUseItemOnBlockTiming(ctx.getSource())))
                .then(Commands.literal("playeruseitemonblocktimingreset").executes(ctx -> playerUseItemOnBlockTimingReset(ctx.getSource())))
                .then(Commands.literal("playerusecontroltiming").executes(ctx -> playerUseControlTiming(ctx.getSource())))
                .then(Commands.literal("playerusecontroltimingreset").executes(ctx -> playerUseControlTimingReset(ctx.getSource())))
                .then(Commands.literal("playerentityinteractexperiment")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerEntityInteractExperimentAdd(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> playerEntityInteractExperimentRemove(ctx.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list").executes(ctx -> playerEntityInteractExperimentList(ctx.getSource()))))
                .then(Commands.literal("entityinteracttiming").executes(ctx -> entityInteractTiming(ctx.getSource())))
                .then(Commands.literal("entityinteracttimingreset").executes(ctx -> entityInteractTimingReset(ctx.getSource())))
                .then(Commands.literal("interactionwaitbudget").executes(ctx -> interactionWaitBudget(ctx.getSource())))
                .then(Commands.literal("interactionwaitbudgetreset").executes(ctx -> interactionWaitBudgetReset(ctx.getSource())))
                .then(Commands.literal("interactionlatencydist").executes(ctx -> interactionLatencyDist(ctx.getSource())))
                .then(Commands.literal("interactionwaitspikes").executes(ctx -> interactionWaitSpikes(ctx.getSource())))
                .then(Commands.literal("interactionslots")
                        .then(Commands.argument("k", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .executes(ctx -> interactionSlots(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "k")))))
                .then(Commands.literal("interactionmaxwaitms")
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .executes(ctx -> interactionMaxWaitMs(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x")))))
                .then(Commands.literal("splitmergesummary").executes(ctx -> splitMergeSummary(ctx.getSource())))
                .then(Commands.literal("splitmergedump").executes(ctx -> splitMergeDump(ctx.getSource(), 60))
                        .then(Commands.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> splitMergeDump(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines")))))
                .then(Commands.literal("splitmergereset").executes(ctx -> splitMergeReset(ctx.getSource())))
                .then(Commands.literal("connectiondiag").executes(ctx -> connectionDiag(ctx.getSource())))
                .then(Commands.literal("connectiondiagreset").executes(ctx -> connectionDiagReset(ctx.getSource())))
                .then(Commands.literal("connectiondiagslow").executes(ctx -> connectionDiagSlow(ctx.getSource(), 100))
                        .then(Commands.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> connectionDiagSlow(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines")))))
                .then(Commands.literal("trackingreport").executes(ctx -> trackingReport(ctx.getSource())))
                .then(Commands.literal("trackingreset").executes(ctx -> trackingReset(ctx.getSource())))
                .then(Commands.literal("recipientfanout")
                        .then(Commands.argument("ticks", DoubleArgumentType.doubleArg(0.001))
                                .executes(ctx -> recipientFanout(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "ticks")))))
                .then(Commands.literal("outboundbatch")
                        .then(Commands.literal("on").executes(ctx -> outboundBatchToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> outboundBatchToggle(ctx.getSource(), false))))
                .then(Commands.literal("outboundbatchreport").executes(ctx -> outboundBatchReport(ctx.getSource())))
                .then(Commands.literal("outboundbatchreset").executes(ctx -> outboundBatchReset(ctx.getSource())))
                .then(Commands.literal("outboundbatchmaxsize")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> outboundBatchMaxSize(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("joinburstreport").executes(ctx -> joinBurstReport(ctx.getSource())))
                .then(Commands.literal("joinburstreset").executes(ctx -> joinBurstReset(ctx.getSource())))
                .then(Commands.literal("chunkoutbound")
                        .then(Commands.literal("on").executes(ctx -> chunkOutboundToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> chunkOutboundToggle(ctx.getSource(), false))))
                .then(Commands.literal("chunkoutboundreport").executes(ctx -> chunkOutboundReport(ctx.getSource())))
                .then(Commands.literal("chunkoutboundreset").executes(ctx -> chunkOutboundReset(ctx.getSource())))
                .then(Commands.literal("chunkoutboundbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> chunkOutboundBudget(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("pairingbatch")
                        .then(Commands.literal("on").executes(ctx -> pairingBatchToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> pairingBatchToggle(ctx.getSource(), false)))
                        .then(Commands.literal("status").executes(ctx -> pairingBatchStatus(ctx.getSource()))))
                .then(Commands.literal("pairingbatchreset").executes(ctx -> pairingBatchReset(ctx.getSource())))
                .then(Commands.literal("pairingbatchbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> pairingBatchBudget(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("movedecisionreport").executes(ctx -> moveDecisionReport(ctx.getSource())))
                .then(Commands.literal("movedecisionreset").executes(ctx -> moveDecisionReset(ctx.getSource())))
                .then(Commands.literal("naturalspawnerreport").executes(ctx -> naturalSpawnerReport(ctx.getSource())))
                .then(Commands.literal("naturalspawnerreset").executes(ctx -> naturalSpawnerReset(ctx.getSource())))
                .then(Commands.literal("burstreport").executes(ctx -> burstReport(ctx.getSource())))
                .then(Commands.literal("modcallbackreport").executes(ctx -> modCallbackReport(ctx.getSource())))
                .then(Commands.literal("modcallbackreset").executes(ctx -> modCallbackReset(ctx.getSource())))
                .then(Commands.literal("modcallbackrouterreport").executes(ctx -> modCallbackRouterReport(ctx.getSource())))
                .then(Commands.literal("modcallbackrouterreset").executes(ctx -> modCallbackRouterReset(ctx.getSource())))
                .then(Commands.literal("burstreset").executes(ctx -> burstReset(ctx.getSource())))
                .then(Commands.literal("burstthreads").executes(ctx -> burstThreads(ctx.getSource())))
                .then(Commands.literal("admission")
                        .then(Commands.literal("on").executes(ctx -> admissionToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> admissionToggle(ctx.getSource(), false))))
                .then(Commands.literal("admissionreport").executes(ctx -> admissionReport(ctx.getSource())))
                .then(Commands.literal("admissionreset").executes(ctx -> admissionReset(ctx.getSource())))
                .then(Commands.literal("admissionbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> admissionBudget(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("pairingadmission")
                        .then(Commands.literal("on").executes(ctx -> pairingAdmissionToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> pairingAdmissionToggle(ctx.getSource(), false))))
                .then(Commands.literal("pairingadmissionreport").executes(ctx -> pairingAdmissionReport(ctx.getSource())))
                .then(Commands.literal("pairingadmissionreset").executes(ctx -> pairingAdmissionReset(ctx.getSource())))
                .then(Commands.literal("pairingadmissionbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> pairingAdmissionBudget(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("pairingadmissionperplayer")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> pairingAdmissionPerPlayer(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("connectionadmission")
                        .then(Commands.literal("on").executes(ctx -> connectionAdmissionToggle(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> connectionAdmissionToggle(ctx.getSource(), false))))
                .then(Commands.literal("connectionadmissionreport").executes(ctx -> connectionAdmissionReport(ctx.getSource())))
                .then(Commands.literal("connectionadmissionreset").executes(ctx -> connectionAdmissionReset(ctx.getSource())))
                .then(Commands.literal("connectionadmissionbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> connectionAdmissionBudget(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("cpuplanner").executes(ctx -> cpuPlannerReport(ctx.getSource())))
                .then(Commands.literal("nearestplayerverify").executes(ctx -> nearestPlayerVerifyReport(ctx.getSource())))
                .then(Commands.literal("nearestplayerverifyreset").executes(ctx -> nearestPlayerVerifyReset(ctx.getSource())))
                .then(Commands.literal("nearestplayermismatches").executes(ctx -> nearestPlayerMismatches(ctx.getSource())))
                .then(Commands.literal("nearestplayermismatchesdump").executes(ctx -> nearestPlayerMismatchesDump(ctx.getSource())))
                .then(Commands.literal("setgenconcurrency")
                        .then(Commands.argument("n", IntegerArgumentType.integer(0))
                                .executes(ctx -> setGenConcurrency(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("genconcurrency").executes(ctx -> genConcurrency(ctx.getSource())))
                .then(Commands.literal("setgenbudget")
                        .then(Commands.argument("n", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setGenBudget(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("genbudget").executes(ctx -> genBudget(ctx.getSource())))
                .then(Commands.literal("setmovethreshold")
                        .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(-1.0D))
                                .executes(ctx -> setMoveThreshold(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "multiplier")))))
                .then(Commands.literal("movethreshold").executes(ctx -> moveThreshold(ctx.getSource())))
                .then(Commands.literal("setmovepacketcap")
                        .then(Commands.argument("n", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setMovePacketCap(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("movepacketcap").executes(ctx -> movePacketCap(ctx.getSource())))
                .then(Commands.literal("setpumpbudget")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setPumpBudget(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ms")))))
                .then(Commands.literal("pumpbudget").executes(ctx -> pumpBudget(ctx.getSource())))
                .then(Commands.literal("setfloatingkick")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> setFloatingKick(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("floatingkick").executes(ctx -> floatingKick(ctx.getSource())))
                .then(Commands.literal("chunkprefetch")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> chunkPrefetchTest(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("chunkprefetchstats").executes(ctx -> chunkPrefetchStats(ctx.getSource())))
                .then(Commands.literal("mspt").executes(ctx -> msptPercentiles(ctx.getSource())))
                .then(Commands.literal("stresschunks")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200000))
                                .then(Commands.argument("baseX", IntegerArgumentType.integer())
                                        .then(Commands.argument("baseZ", IntegerArgumentType.integer())
                                                .executes(ctx -> stressChunks(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "baseX"),
                                                        IntegerArgumentType.getInteger(ctx, "baseZ")))))))
                .then(Commands.literal("gentest")
                        .then(Commands.literal("start")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200000))
                                        .executes(ctx -> genTestStart(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), 100000, 100000))
                                        .then(Commands.argument("baseX", IntegerArgumentType.integer())
                                                .then(Commands.argument("baseZ", IntegerArgumentType.integer())
                                                        .executes(ctx -> genTestStart(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                IntegerArgumentType.getInteger(ctx, "baseX"),
                                                                IntegerArgumentType.getInteger(ctx, "baseZ")))))))
                        .then(Commands.literal("status").executes(ctx -> genTestStatus(ctx.getSource()))))
                .then(Commands.literal("teststage3write")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> testStage3Write(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
                .then(Commands.literal("scheduler").executes(ctx -> scheduler(ctx.getSource())))
                .then(Commands.literal("mailboxaudit").executes(ctx -> mailboxAudit(ctx.getSource())))
                .then(Commands.literal("pressure").executes(ctx -> pressure(ctx.getSource())))
                .then(Commands.literal("entityguard").executes(ctx -> entityGuard(ctx.getSource())))
                .then(Commands.literal("entityrecheck").executes(ctx -> entityRecheck(ctx.getSource())))
                .then(Commands.literal("hotspots").executes(ctx -> hotspots(ctx.getSource())))
                .then(Commands.literal("modload").executes(ctx -> modLoad(ctx.getSource())))
                .then(Commands.literal("tickchunks").executes(ctx -> tickChunks(ctx.getSource())))
                .then(Commands.literal("goto")
                        .then(Commands.literal("hotspot")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(ctx -> gotoHotspot(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id")))))
                        .then(Commands.literal("tickchunk")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(ctx -> gotoTickChunk(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id")))))
                        .then(Commands.literal("region")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(ctx -> gotoRegion(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id")))))
                        .then(Commands.literal("chunk")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> gotoChunk(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(ctx -> gotoEntity(ctx.getSource(),
                                                UuidArgument.getUuid(ctx, "uuid"))))))
                .then(Commands.literal("freerun")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> freeRun(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("testmutate")
                        .then(Commands.literal("kill")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(ctx -> testMutate(ctx.getSource(),
                                                UuidArgument.getUuid(ctx, "uuid"), new EntityMutationOp.Kill()))))
                        .then(Commands.literal("discard")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(ctx -> testMutate(ctx.getSource(),
                                                UuidArgument.getUuid(ctx, "uuid"), new EntityMutationOp.Discard()))))));

        // /spark — in-game access to the spark standalone agent (see SparkBridge).
        // Only when the real spark mod is absent (dev runtime can't load it);
        // in production the mod registers its own /spark and the bridge would
        // shadow it.
        if (!net.minecraftforge.fml.ModList.get().isLoaded("spark")) {
            dispatcher.register(Commands.literal("spark")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> SparkBridge.run(ctx.getSource(), ""))
                    .then(Commands.argument("args", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .executes(ctx -> SparkBridge.run(ctx.getSource(),
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "args")))));
        }
    }

    /**
     * Stage 4.1 (docs/LOCAL_TICK_STAGE4.md): observational classification of every
     * active region — IDLE/RUNNING/DELAYED/OVERLOADED — over the existing barrier.
     * Does not change tick semantics; purely reports state RegionThread/WorldRegion
     * already track.
     */
    private static int scheduler(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        java.util.List<RegionScheduler.Classification> classes =
                RegionScheduler.classifyAll(overworldRegion(sys).getPool());
        java.util.Map<RegionScheduler.State, Integer> counts = new java.util.EnumMap<>(RegionScheduler.State.class);
        for (RegionScheduler.Classification c : classes) {
            counts.merge(c.state(), 1, Integer::sum);
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "NestWorld scheduler: %d region(s) — idle=%d running=%d delayed=%d overloaded=%d",
                classes.size(),
                counts.getOrDefault(RegionScheduler.State.IDLE, 0),
                counts.getOrDefault(RegionScheduler.State.RUNNING, 0),
                counts.getOrDefault(RegionScheduler.State.DELAYED, 0),
                counts.getOrDefault(RegionScheduler.State.OVERLOADED, 0))), false);
        for (RegionScheduler.Classification c : classes) {
            if (c.state() == RegionScheduler.State.IDLE) continue;
            src.sendSuccess(() -> Component.literal(String.format(
                    "  #%d [%s] avg=%.1fms deferredEntities=%d workDeferred=%d",
                    c.region().getId(), c.state(), c.avgTickMs(),
                    c.lastDeferredEntities(), c.lastWorkDeferred())), false);
        }
        return classes.size();
    }

    /**
     * Stage 5 prerequisite (docs/LOCAL_TICK_STAGE4.md, "Part 5 staging"): reports
     * {@link MailboxAudit}'s running totals and flags any message stuck in flight
     * longer than 5s (a generous window — real apply latency is at most a few ticks)
     * as a possible loss. No-op (reports disabled) unless NESTWORLD_MAILBOX_AUDIT=1.
     */
    private static int mailboxAudit(CommandSourceStack src) {
        if (!MailboxAudit.ENABLED) {
            src.sendFailure(Component.literal(
                    "Mailbox audit is disabled — set NESTWORLD_MAILBOX_AUDIT=1 (or -Dnestworld.mailboxAudit=true) and restart"));
            return 0;
        }
        int stale = MailboxAudit.logStaleEntries(5_000_000_000L);
        src.sendSuccess(() -> Component.literal("Mailbox audit: " + MailboxAudit.summary()
                + (stale > 0 ? String.format(" — %d stale (check log)", stale) : "")), false);
        return 1;
    }

    /** Batch-apply Phase 1 diagnostic (docs/BATCH_APPLY_COALESCING_DESIGN.md) — the
     *  {@code locks/message} (before) vs {@code locks/batch} (after) numbers the design
     *  doc's benchmark plan is built around, plus timeout/convoy rate. Always available
     *  (no ENABLED gate — the underlying counters are always-on, unlike MailboxAudit). */
    private static int pressure(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("Batch-apply: " + BatchApplyStats.summary()), false);
        src.sendSuccess(() -> Component.literal("Mailbox audit: " + MailboxAudit.summary()), false);
        return 1;
    }

    /**
     * Entity Safety Layer invariant check (docs/LOCAL_TICK_STAGE4.md, "Entity Safety
     * Layer"): reports {@link EntityOwnershipGuard}'s running violation count. Zero
     * violations across a soak run is the pass criterion for NO_FOREIGN_ENTITY_MUTATION.
     * No-op (reports disabled) unless NESTWORLD_ENTITY_GUARD=1.
     */
    private static int entityGuard(CommandSourceStack src) {
        if (!EntityOwnershipGuard.ENABLED) {
            src.sendFailure(Component.literal(
                    "Entity ownership guard is disabled — set NESTWORLD_ENTITY_GUARD=1 (or -Dnestworld.entityGuard=true) and restart"));
            return 0;
        }
        long violations = EntityOwnershipGuard.violations();
        src.sendSuccess(() -> Component.literal(violations == 0
                ? "Entity ownership guard: 0 violations (NO_FOREIGN_ENTITY_MUTATION holds)"
                : "Entity ownership guard: " + violations + " violation(s) — check log for NO_FOREIGN_ENTITY_MUTATION"), false);
        return (int) Math.min(violations, Integer.MAX_VALUE);
    }

    /**
     * Reports {@link EntityOwnershipRecheck}'s skip counter -- how many times a
     * free-running region's tickEntities() pass found its snapshot was already
     * stale for a given entity (ownership had moved to a neighbor mid-pass) and
     * skipped ticking it, rather than racing the new owner. Always active (not
     * gated like entityguard), so this reports even without NESTWORLD_ENTITY_GUARD.
     */
    private static int entityRecheck(CommandSourceStack src) {
        long skips = EntityOwnershipRecheck.skips();
        src.sendSuccess(() -> Component.literal(
                "Entity ownership recheck: " + skips + " skip(s) (stale-snapshot ticks avoided)"), false);
        return (int) Math.min(skips, Integer.MAX_VALUE);
    }

    /**
     * Step 3 (docs/LOCAL_TICK_STAGE4.md, "Step 3 — Single Free-Running Region"): toggles
     * free-running mode for one region — explicit, observable, command-driven selection
     * for this validation phase rather than an automatic heuristic. No-op (reports
     * disabled) unless NESTWORLD_FREE_RUNNING_REGIONS=1.
     */
    private static int freeRun(CommandSourceStack src, int id) {
        if (!NestworldTuning.FREE_RUNNING_REGIONS_ENABLED) {
            src.sendFailure(Component.literal(
                    "Free-running regions are disabled — set NESTWORLD_FREE_RUNNING_REGIONS=1 (or -Dnestworld.freeRunningRegions=true) and restart"));
            return 0;
        }
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        WorldRegion region = findRegion(sys, id);
        if (region == null) {
            src.sendFailure(Component.literal("No active region with id " + id));
            return 0;
        }
        boolean newState = !region.isFreeRunning();
        region.nestworldSetFreeRunning(newState);
        String warning = "";
        if (newState) {
            int cap = Math.max(0, Runtime.getRuntime().availableProcessors()
                    - NestworldTuning.FREE_RUNNING_RESERVED_CORES);
            long freeRunningNow = dr.getGrid().getAllRegions().stream()
                    .filter(WorldRegion::isFreeRunning).count();
            if (freeRunningNow > cap) {
                warning = " -- WARNING: " + freeRunningNow + " free-running region(s) now active, "
                        + "above the advisory safe cap of " + cap + " for this box's "
                        + Runtime.getRuntime().availableProcessors() + " visible core(s) "
                        + "(2026-08-13 scaling test: free-running oversubscribes and LOSES to the "
                        + "barrier model on constrained CPU -- fine on 8+ cores, not recommended here)";
            }
        }
        final String warningMsg = warning;
        src.sendSuccess(() -> Component.literal(
                "Region #" + id + " free-running: " + (newState ? "ON" : "OFF") + warningMsg), true);
        return 1;
    }

    /**
     * Lag culprit report: the single slowest region and WHY — its hottest chunk
     * (by entity count), top entity types, block-tick heat (redstone/farms), and
     * the nearest player (usual griefer). If the server lags but no region is hot,
     * the cost is on the main thread (commands / redstone / a mod tick handler) and
     * region sharding cannot help — we say so explicitly.
     */
    private static int lag(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        var regions = dr.getTree().getActiveRegions();
        double serverMspt = src.getServer().getAverageTickTime();
        double tps = Math.min(20.0, 1000.0 / Math.max(serverMspt, 0.001));
        src.sendSuccess(() -> Component.literal(String.format(
                "NestWorld lag report — server %.1f ms/tick (%.1f TPS)", serverMspt, tps)), false);

        WorldRegion slow = null;
        for (WorldRegion r : regions) {
            if (slow == null || r.getAvgTickMs() > slow.getAvgTickMs()) slow = r;
        }
        if (slow == null) {
            src.sendSuccess(() -> Component.literal("  no active regions"), false);
            return 0;
        }
        final WorldRegion hot = slow;

        // Server lagging but the worst region is cheap => the cost is on the main
        // thread, which the sharding does not touch (commands, redstone, mod ticks).
        if (serverMspt > 50.0 && hot.getAvgTickMs() < serverMspt * 0.4) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  ⚠ lag is MAIN-THREAD, not a region (worst region only %.1fms). Check "
                  + "commands (/fill,/clone,/forceload), redstone, or a mod's server-tick handler.",
                    hot.getAvgTickMs())), false);
        }

        net.minecraft.server.level.ServerLevel level = src.getServer().overworld();
        java.util.Map<Long, Integer> chunkCounts = new java.util.HashMap<>();
        java.util.Map<String, Integer> typeCounts = new java.util.HashMap<>();
        for (java.util.UUID id : hot.getOwnedEntityIds()) {
            net.minecraft.world.entity.Entity e = level.getEntity(id);
            if (e == null) continue;
            chunkCounts.merge(e.chunkPosition().toLong(), 1, Integer::sum);
            String t = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            typeCounts.merge(t, 1, Integer::sum);
        }

        src.sendSuccess(() -> Component.literal(String.format(
                "  slowest: region #%d  cost=%.1fms  entities=%d",
                hot.getId(), hot.getAvgTickMs(), hot.getOwnedEntityIds().size())), false);

        long hotChunk = 0;
        int hotCount = 0;
        for (var en : chunkCounts.entrySet()) {
            if (en.getValue() > hotCount) { hotCount = en.getValue(); hotChunk = en.getKey(); }
        }
        if (hotCount > 0) {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(hotChunk);
            final int hc = hotCount;
            src.sendSuccess(() -> Component.literal(String.format(
                    "  hottest chunk: (%d,%d) — %d entities (block %d,%d)",
                    cp.x, cp.z, hc, cp.getMinBlockX(), cp.getMinBlockZ())), false);
        }

        typeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                .forEach(en -> src.sendSuccess(() -> Component.literal(
                        String.format("    %s ×%d", en.getKey(), en.getValue())), false));

        BlockTickHeat heat = dr.getBlockTickHeat();
        double regionHeat = heat.totalInRegion(hot);
        if (regionHeat >= 1.0) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "  block-tick heat=%.0f [%s] (redstone/farms)", regionHeat, heat.hotspotSummary(hot, 3))), false);
        }

        double bx = ((hot.getMinChunkX() + hot.getMaxChunkX()) / 2) * 16 + 8;
        double bz = ((hot.getMinChunkZ() + hot.getMaxChunkZ()) / 2) * 16 + 8;
        net.minecraft.world.entity.player.Player near = level.getNearestPlayer(bx, 128.0, bz, -1.0, false);
        if (near != null) {
            double d = Math.sqrt(near.distanceToSqr(bx, near.getY(), bz));
            src.sendSuccess(() -> Component.literal(String.format(
                    "  nearest player: %s (%.0f blocks from region centre)", near.getName().getString(), d)), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    "  nearest player: none nearby (chunks kept loaded by force-load/spawn?)"), false);
        }
        return 1;
    }

    private static int toggleBorders(CommandSourceStack src) {
        NestworldRegionSystem.showBorders = !NestworldRegionSystem.showBorders;
        boolean on = NestworldRegionSystem.showBorders;
        src.sendSuccess(() -> Component.literal(
                on ? "Region borders: VISIBLE (END_ROD particles, range 96)"
                   : "Region borders: hidden"), true);
        return on ? 1 : 0;
    }

    /** NestWorld (2026-08-13, in-game readability feedback): {@code /nestworld status} used
     *  to dump every active region unconditionally -- unreadable in the in-game chat overlay
     *  once past ~15-20 regions (a common count under real multi-player load). Default is now
     *  a compact view: header + the top {@link #STATUS_COMPACT_TOP_N} regions by cost, plus a
     *  one-line "N more" trailer. {@code /nestworld status full} keeps the old unconditional
     *  per-region dump for when you actually need everything (e.g. scripted polling). */
    private static final int STATUS_COMPACT_TOP_N = 5;

    private static int status(CommandSourceStack src, boolean full) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        var regions = dr.getTree().getActiveRegions();
        // Server-wide MSPT so the per-region costs can be read against the
        // actual tick health (region cost alone says nothing about redstone
        // and other main-thread work).
        double serverMspt = src.getServer().getAverageTickTime();
        src.sendSuccess(() -> Component.literal(String.format(
                "NestWorld: %d active region(s), server %.1f ms/tick (%.1f TPS)",
                regions.size(), serverMspt, Math.min(20.0, 1000.0 / Math.max(serverMspt, 0.001)))), false);
        BlockTickHeat heat = dr.getBlockTickHeat();
        var shown = regions;
        int hidden = 0;
        if (!full && regions.size() > STATUS_COMPACT_TOP_N) {
            shown = new java.util.ArrayList<>(regions);
            shown.sort((a, b) -> Double.compare(b.getAvgTickMs(), a.getAvgTickMs()));
            hidden = shown.size() - STATUS_COMPACT_TOP_N;
            shown = shown.subList(0, STATUS_COMPACT_TOP_N);
        }
        for (WorldRegion r : shown) {
            RegionThread thread = r.owningThread;
            int deferred = thread != null ? thread.getLastDeferredCount() : 0;
            int workDeferred = thread != null ? thread.getLastWorkDeferred() : 0;
            double regionHeat = heat.totalInRegion(r);
            // NestWorld: Stage 4.5 — show p95 only when it noticeably diverges from the
            // average (long tail hiding behind a healthy-looking mean); a well-behaved
            // region's p95 tracks its average closely and would just be noise here.
            double avgMs = r.getAvgTickMs();
            double p95Ms = r.getPercentileTickMs(0.95);
            boolean showP95 = p95Ms > avgMs * 1.5 && p95Ms > 2.0;
            // NestWorld: Stage 5.3 design v3 "budgeted drain" fix — a mailbox that still
            // has messages queued AFTER the barrier pass (nestworldDrainMailboxBudgeted
            // hit its time budget) is only expected under a genuine flood; 0 the rest of
            // the time, so shown only when non-zero instead of every tick.
            int mailboxDepth = r.nestworldMailboxSize();
            // Stage 5.3 v3 Fix 3 (docs/LOCAL_TICK_STAGE4.md, "backpressure without message
            // loss"): messages THIS region wanted to send but held back at its own side
            // because the destination was over MAILBOX_BACKPRESSURE_THRESHOLD — 0 the
            // overwhelming rest of the time, same "shown only when non-zero" convention
            // as mailbox= above; a nonzero pending= is the live signal that backpressure
            // is actively engaged, not just designed-for.
            int pendingDepth = r.nestworldOutboundPendingSize();
            // Step 3 (docs/LOCAL_TICK_STAGE4.md): a free-running region's localTickCount
            // diverging from server.tickCount is expected and exactly what to watch —
            // shown only for regions actually toggled free-running, zero-cost otherwise.
            boolean freeRunning = r.isFreeRunning();
            long tickDelta = freeRunning ? r.getLocalTickCount() - src.getServer().getTickCount() : 0;
            src.sendSuccess(() -> Component.literal(String.format(
                    "  #%d chunks(%d,%d)-(%d,%d) cost=%.1fms%s entities=%d%s%s%s%s%s%s",
                    r.getId(), r.getMinChunkX(), r.getMinChunkZ(),
                    r.getMaxChunkX(), r.getMaxChunkZ(),
                    avgMs, showP95 ? String.format(" p95=%.1fms", p95Ms) : "",
                    r.getOwnedEntityIds().size(),
                    deferred > 0 ? " deferred=" + deferred : "",
                    workDeferred > 0 ? " workDeferred=" + workDeferred : "",
                    mailboxDepth > 0 ? " mailbox=" + mailboxDepth : "",
                    pendingDepth > 0 ? " pending=" + pendingDepth : "",
                    regionHeat >= 1.0
                            ? String.format(" heat=%.0f[%s]", regionHeat, heat.hotspotSummary(r, 3))
                            : "",
                    freeRunning ? String.format(" FREE-RUNNING%s localTick=%d (%+d)",
                            r.autoFreeRunning ? "(auto)" : "", r.getLocalTickCount(), tickDelta) : "")), false);
        }
        if (hidden > 0) {
            int hiddenFinal = hidden;
            src.sendSuccess(() -> Component.literal(String.format(
                    "  ... and %,d more region(s) (sorted by cost) -- /nestworld status full for all", hiddenFinal)), false);
        }
        return regions.size();
    }

    /**
     * NestWorld debug: reproduces the exact blocking call shape mods like FTBChunks
     * use for a map-click teleport ({@code Level.getChunk(chunkX, chunkZ)} — the
     * synchronous, load-and-generate-if-needed overload), run directly on the main
     * thread (same thread RCON commands execute on) so its timing matches what a
     * real player's teleport into fresh territory would experience. Exists because
     * mineflayer cannot connect to a real modded Forge server (FML2 handshake) and
     * vanilla commands (data get block, summon) do NOT force the same synchronous
     * load path — this command is the only safe, RCON-triggerable way to measure
     * the actual "Phase 1" teleport-hang risk on a real modpack. Takes BLOCK
     * coordinates (converted to chunk coordinates here) to match how a player
     * position is normally specified.
     */
    private static int debugSyncGetChunk(CommandSourceStack src, int blockX, int blockZ) {
        net.minecraft.server.level.ServerLevel level = src.getServer().overworld();
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        long start = System.nanoTime();
        level.getChunk(chunkX, chunkZ);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        src.sendSuccess(() -> Component.literal(String.format(
                "debugsync: Level.getChunk(%d, %d) [block %d,%d] took %d ms",
                chunkX, chunkZ, blockX, blockZ, elapsedMs)), false);
        return (int) elapsedMs;
    }

    /**
     * Like {@code debugsync}, but calls {@code Level.getChunk()} {@code count} times in a
     * tight loop, ALL within this ONE command invocation — i.e. guaranteed to run within
     * the SAME server tick, unlike issuing {@code count} separate {@code debugsync}
     * commands (each a separate RCON round-trip, likely landing in different ticks and
     * each getting a fresh per-tick burst budget). Reproduces the actual shape of the
     * 2026-08-10 crash (EvilCraft's WorldHelpers.foldArea calling getBlockState() -&gt;
     * getChunk() repeatedly within one event-handler invocation) to validate the
     * GETCHUNK_BURST_BUDGET_MS cumulative-per-tick cap.
     */
    private static int debugSyncGetChunkBurst(CommandSourceStack src, int blockX, int blockZ, int count) {
        net.minecraft.server.level.ServerLevel level = src.getServer().overworld();
        long totalStart = System.nanoTime();
        StringBuilder perCall = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int chunkX = (blockX >> 4) + i * 2;
            int chunkZ = blockZ >> 4;
            long start = System.nanoTime();
            level.getChunk(chunkX, chunkZ);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (i > 0) perCall.append(", ");
            perCall.append(elapsedMs);
        }
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000L;
        src.sendSuccess(() -> Component.literal(String.format(
                "debugsyncburst: %d calls in ONE tick, per-call ms=[%s], TOTAL=%d ms",
                count, perCall, totalMs)), false);
        return (int) totalMs;
    }

    /**
     * NW-CHUNK-FASTPATH telemetry (see docs/LOCAL_TICK_STAGE4.md). Reports main-thread
     * {@code ServerChunkCache.getChunk()} call accounting since server start: how many
     * calls hit the new nestworldLoadedFull fast path vs fell through to the blocking
     * managedBlock() path, and total time spent blocked. Counters are cumulative
     * (not reset per call) — compare two readings to measure a specific window (e.g.
     * before/after reproducing a known-heavy event).
     *
     * <p>Layer 10 (docs/GEN_SPIKE.md): under {@code GETCHUNK_ZERO_WAIT_MAIN_THREAD}'s
     * default (true), the gameplay-thread FULL+load=true path can no longer REACH the
     * blocking branch at all — that's now a structural guarantee (dead code under the
     * default config), not just a monitored invariant. So "total wait"/"blocking" here
     * should trend toward reflecting ONLY the still-open, out-of-scope case (internal
     * worldgen dependency resolution — non-FULL/load=false calls, which never went
     * through the FULL+load branch to begin with) — a persistently nonzero reading is
     * now a MORE useful signal than before, not a less useful one, since the biggest
     * prior contributor is gone.
     */
    private static int chunkStats(CommandSourceStack src) {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) src.getServer().overworld().getChunkSource();
        long lookups = cache.nestworldChunkLookups.get();
        long fastHits = cache.nestworldFastPathHits.get();
        long blocking = cache.nestworldBlockingCalls.get();
        long waitNanos = cache.nestworldWaitNanos.get();
        long proxies = cache.nestworldProxyCreations.get();
        long regionTimeouts = cache.nestworldRegionGetChunkTimeouts.get();
        double fastPct = lookups == 0 ? 0.0 : 100.0 * fastHits / lookups;
        double avgBlockMs = blocking == 0 ? 0.0 : (waitNanos / 1_000_000.0) / blocking;
        src.sendSuccess(() -> Component.literal("NW Chunk FastPath (main-thread getChunk, cumulative)"), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  calls: %,d   fast-path hits: %,d (%.1f%%)   blocking: %,d",
                lookups, fastHits, fastPct, blocking)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  total wait: %.0f ms   avg blocking: %.3f ms   proxy creations: %,d",
                waitNanos / 1_000_000.0, avgBlockMs, proxies)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  region-thread getChunk timeouts: %,d%s",
                regionTimeouts,
                regionTimeouts > 0 ? " (safety valve engaged — see RegionThread getChunk blocking crash fix)" : "")), false);

        // NestWorld 2026-08-11 (distancemanager-tier1-volume-freeze.md follow-up): per-tick cost of
        // rebuilding the FastPath snapshot itself -- O(all currently-tracked ChunkHolders), not just
        // "pending" ones, unconditional every tick. Surfaced here to distinguish this from
        // DistanceManager's own promotion-backlog cost (/nestworld chunkpromotion) during a live
        // incident, instead of needing a fresh jstack session to tell them apart.
        long refreshCalls = cache.nestworldRefreshCalls.get();
        long refreshTotalNanos = cache.nestworldRefreshTotalNanos.get();
        double refreshAvgMs = refreshCalls == 0 ? 0.0 : (refreshTotalNanos / 1_000_000.0) / refreshCalls;
        src.sendSuccess(() -> Component.literal("NW FastPath refresh (nestworldRefreshLoadedChunks, cumulative)"), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  calls: %,d   avg: %.3f ms   last: %.3f ms (scanned=%,d loaded=%,d)",
                refreshCalls, refreshAvgMs, cache.nestworldRefreshLastNanos / 1_000_000.0,
                cache.nestworldRefreshLastScanned, cache.nestworldRefreshLastSize)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  max: %.3f ms (scanned=%,d)",
                cache.nestworldRefreshMaxNanos / 1_000_000.0, cache.nestworldRefreshMaxScanned)), false);
        return (int) lookups;
    }

    /**
     * Layer 11 (docs/GEN_SPIKE.md, project owner's explicit request, 2026-08-11):
     * chunk-PROMOTION telemetry — distinct from {@link #chunkStats}'s getChunk() call
     * accounting. This measures the OTHER main-thread chunk-loading cost: {@code
     * DistanceManager.runAllUpdates()}'s {@code updateFutures()} calls, the "integrate
     * a chunk whose generation just finished" step that GEN_SPIKE.md's very first
     * finding named as the actual freeze mechanism behind mass-chunk-gen events (256
     * fresh chunks completing near-simultaneously produced a real 13.2s main-thread
     * stall in a same-session test, even with getChunk() itself already zero-wait).
     * {@code pending} is a live gauge (current backlog); the rest are cumulative since
     * server start.
     */
    private static int chunkPromotion(CommandSourceStack src) {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) src.getServer().overworld().getChunkSource();
        net.minecraft.server.level.DistanceManager dm = cache.chunkMap.getDistanceManager();
        int pending = dm.nestworldPromotionPending();
        long applied = dm.nestworldPromotionTotalApplied();
        double avgMs = dm.nestworldPromotionAvgMs();
        double p95 = dm.nestworldPromotionPercentileMs(0.95);
        double p99 = dm.nestworldPromotionPercentileMs(0.99);
        double maxMs = dm.nestworldPromotionMaxMs();
        src.sendSuccess(() -> Component.literal("NW Chunk Promotion (main-thread updateFutures(), cumulative)"), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  pending: %,d   applied: %,d   avg: %.3f ms", pending, applied, avgMs)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  p95: %.3f ms   p99: %.3f ms   max: %.3f ms", p95, p99, maxMs)), false);
        // NestWorld 2026-08-11 (distancemanager-tier1-volume-freeze.md, second pass): expose
        // the player/bulk split directly, and the classification validator's health, so a
        // repeat incident is diagnosable from this one command instead of a fresh jstack session.
        src.sendSuccess(() -> Component.literal(String.format(
                "  player: %,d   bulk: %,d   validator: %.1f%% mismatches=%,d batch_max=%.3fms",
                dm.nestworldPromotionPendingPlayer(), dm.nestworldPromotionPendingBulk(),
                dm.nestworldValidatorProgressPct(), dm.nestworldValidatorMismatches(),
                dm.nestworldValidatorBatchMaxMs())), false);
        // NestWorld 2026-08-11 (validator-rebuild-unbounded-scan fix): the snapshot rebuild
        // itself is now multi-tick budgeted too -- surface its own health separately from the
        // batch-walk above so a repeat 100k+-backlog incident is diagnosable without a fresh jstack.
        src.sendSuccess(() -> Component.literal(String.format(
                "  snapshot: size=%,d build=%.3fms build_ticks=%,d partial_rebuilds=%,d",
                dm.nestworldValidatorSnapshotSize(), dm.nestworldValidatorSnapshotBuildMs(),
                dm.nestworldValidatorSnapshotBuildTicks(), dm.nestworldValidatorPartialRebuilds())), false);
        // 2026-08-20 (live ATM9 crash): requested/admitted/promoted/backlog in one place --
        // requested (predictive tickets issued) vs admitted (Tier 1b+2 selected for promotion)
        // vs promoted (=applied above) is exactly the picture that showed predictive issuing
        // ~225-250/s against a ~40/s promotion budget. pump_deadline_hits>0 means the
        // CHUNK_PROMOTION_PUMP_BUDGET_MS safety clamp has actually engaged (expected/healthy
        // under a real backlog-drain, not a sign of breakage).
        long requested = 0L;
        if (NestworldRegionSystem.isInitialised()) {
            for (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> nestworldKey
                    : src.getServer().levelKeys()) {
                NestworldDimensionRegion nestworldReg =
                        NestworldRegionSystem.get().getDimensionRegionByKey(nestworldKey);
                if (nestworldReg != null) requested += nestworldReg.predictiveGen().issuedTotal();
            }
        }
        long requestedTotal = requested;
        long admitted = dm.nestworldPromotionTotalAdmitted();
        long pumpHits = dm.nestworldPumpDeadlineHits();
        src.sendSuccess(() -> Component.literal(String.format(
                "  requested(predictive): %,d   admitted(tier1b+2): %,d   pump_deadline_hits: %,d",
                requestedTotal, admitted, pumpHits)), false);
        return pending;
    }

    /**
     * Region-Owned Chunk Scheduler, Phase 2 -- SHADOW MODE diagnostics (docs/
     * REGION_CHUNK_SCHEDULER_SPEC.md). All numbers here are purely observational: nothing this
     * command reports has any effect on real chunk loading. See
     * net.nestworld.chunk.ChunkSchedulerShadow.
     */
    private static int chunkScheduler(CommandSourceStack src) {
        boolean enabled = NestworldTuning.CHUNK_SCHEDULER_SHADOW_MODE;
        src.sendSuccess(() -> Component.literal(enabled
                ? "NW Chunk Scheduler (Phase 2 SHADOW MODE -- observation only, no real effect on chunk loading)"
                : "NW Chunk Scheduler: shadow mode is OFF (-Dnestworld.chunkSchedulerShadowMode=true to enable)"), false);
        if (!enabled) return 0;
        long observed = net.nestworld.chunk.ChunkSchedulerShadow.observedCount();
        long duplicates = net.nestworld.chunk.ChunkSchedulerShadow.duplicatesSkippedCount();
        long misrouted = net.nestworld.chunk.ChunkSchedulerShadow.misroutedCount();
        long drainedForStats = net.nestworld.chunk.ChunkSchedulerShadow.drainedForStatsCount();
        double avgLatencyMs = net.nestworld.chunk.ChunkSchedulerShadow.avgLatencyMs();
        double maxLatencyMs = net.nestworld.chunk.ChunkSchedulerShadow.maxLatencyMs();
        src.sendSuccess(() -> Component.literal(String.format(
                "  observed: %,d   duplicates_skipped: %,d   misrouted: %,d   drained: %,d",
                observed, duplicates, misrouted, drainedForStats)), false);
        StringBuilder tiers = new StringBuilder("  tiers ever-enqueued: ");
        int currentlyQueued = 0;
        for (net.nestworld.chunk.ChunkRequestPriority tier : net.nestworld.chunk.ChunkRequestPriority.values()) {
            tiers.append(tier.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                    .append(net.nestworld.chunk.ChunkSchedulerShadow.tierEverEnqueuedCount(tier)).append(' ');
        }
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        for (net.nestworld.region.WorldRegion region : dr.getGrid().getAllRegions()) {
            currentlyQueued += region.getChunkScheduler().totalQueueSize();
        }
        String tiersStr = tiers.toString();
        int finalCurrentlyQueued = currentlyQueued;
        src.sendSuccess(() -> Component.literal(tiersStr), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  currently queued (all regions): %,d   drain latency: avg=%.1fms max=%.1fms",
                finalCurrentlyQueued, avgLatencyMs, maxLatencyMs)), false);
        return (int) observed;
    }

    /**
     * NestWorld DIAG 2026-08-12: investigating whether DistanceManager.nestworldHasPlayerTicket's
     * linear scan over a position's ticket set explains the validator batch_max mystery -- see
     * project memory validator-batch-hasplayerticket-cost. Read-only.
     */
    private static int ticketDistribution(CommandSourceStack src) {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) src.getServer().overworld().getChunkSource();
        net.minecraft.server.level.DistanceManager dm = cache.chunkMap.getDistanceManager();
        String result = dm.nestworldTicketDistribution(15);
        src.sendSuccess(() -> Component.literal("NW Ticket Distribution\n  " + result), false);
        return 0;
    }

    /**
     * NestWorld DIAG 2026-08-12: investigating whether BoundaryManager.syncGhostZones() (an
     * unconditional, unbudgeted per-tick scan over all loaded FULL chunks + border-band block
     * entity NBT serialization) is the same "unconditional O(n) scan" bug class found 3x already
     * this session -- see project memory syncghostzones-unbounded-scan-investigation. Read-only.
     */
    private static int ghostZoneStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = dr.getBoundaryManager().nestworldGhostZoneStats();
        src.sendSuccess(() -> Component.literal("NW Ghost Zone Sync: " + result), false);
        return 0;
    }

    /**
     * P0 RegionThreadPool redesign, Phase 1 baseline telemetry (docs/
     * P0_REGIONTHREADPOOL_REDESIGN_SPEC.md). Read-only, no behavior change.
     */
    private static int regionPoolStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = dr.getPool().nestworldPhaseTelemetry();
        src.sendSuccess(() -> Component.literal("NW RegionThreadPool per-phase telemetry:\n" + result), false);
        return 0;
    }

    /**
     * P2 audit (docs/P2_REGIONTHREADPOOL_BARRIER_AUDIT.md) Q3/Q4: per-region-id "how often is
     * THIS region the slowest in its round" ranking. Read-only, no behavior change.
     */
    private static int regionSlowStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = dr.getPool().nestworldRegionSlowTelemetry();
        src.sendSuccess(() -> Component.literal("NW per-region slowest-in-round ranking:\n" + result), false);
        return 0;
    }

    /**
     * P0.1 pollTask() attribution audit (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md follow-up):
     * breaks down where main-thread pollTask() time actually goes, plus a per-tick correlation
     * view. Read-only, no behavior change.
     */
    private static int pollTaskStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = dr.pollTaskReport();
        src.sendSuccess(() -> Component.literal("NW pollTask() attribution:\n" + result), false);
        return 0;
    }

    /**
     * P1.0 audit (docs/P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md): worldgen vs main-thread-required
     * boundary breakdown, read-only, no behavior change.
     */
    private static int worldgenBoundaryStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = sys.nestworldWorldgenBoundaryReport();
        src.sendSuccess(() -> Component.literal("NW " + result), false);
        return 0;
    }

    /** P1.2/P1.4: exact-hook A/B/C worldgen main-thread glue attribution, read-only. */
    private static int worldgenGlueStats(CommandSourceStack src) {
        net.nestworld.region.NestworldRegionSystem sys = net.nestworld.region.NestworldRegionSystem.get();
        net.nestworld.region.NestworldDimensionRegion dr = overworldRegion(sys);
        String result = sys.nestworldWorldgenGlueReport();
        src.sendSuccess(() -> Component.literal("NW " + result), false);
        return 0;
    }

    /**
     * Visibility for {@link NestworldTuning#RESILIENT_FEATURE_PLACEMENT}: which worldgen
     * features/structures have been skipped (instead of crashing the server) since boot, and how
     * many times each.
     */
    private static int featureFails(CommandSourceStack src) {
        String result = FeaturePlacementResilience.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 0;
    }

    /** Sub-attributes the tick-correlation telemetry's "outside_pollTask" residual into
     *  the 6 sequential main-thread tick phases (see NestworldRegionSystem.nestworldTickPhaseReport). */
    /** Acceptance-criterion check for the LevelTicks ownership fix: violation count must
     *  reach exactly 0 under sustained heavy multi-region load before this race is
     *  considered closed. Also reports MailboxAudit's sent/applied/pending/duplicates/
     *  misrouted, the other half of the same soak-test acceptance criteria. */
    private static int levelTicksOwnership(CommandSourceStack src) {
        long violations = net.nestworld.region.NestworldTickOwnership.getViolationCount();
        boolean strict = net.nestworld.region.NestworldTuning.LEVELTICKS_OWNERSHIP_STRICT;
        String result = String.format(
                "NW LevelTicks ownership: violations=%,d mode=%s | mailbox: %s",
                violations, strict ? "STRICT" : "DIAGNOSTIC",
                net.nestworld.region.MailboxAudit.summary());
        src.sendSuccess(() -> Component.literal(result), false);
        return 0;
    }

    private static int ownershipAssertions(CommandSourceStack src) {
        String result = "NW ownership assertions: " + net.nestworld.region.NestworldOwnershipAssertions.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 0;
    }

    /** Phase 11 #31.2 shadow-mode diagnostics. */
    private static int playerInputStats(CommandSourceStack src) {
        String mode = net.nestworld.region.NestworldTuning.PLAYER_INPUT_REGION_EXECUTION ? "SHADOW-ON" : "OFF";
        String result = "NW player-input (mode=" + mode + "): " + net.nestworld.region.PlayerInputDiagnostics.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 0;
    }

    /** Phase 11 #31.3 — staged rollout control: add/remove a named player from the region-tick
     *  opt-in set. No effect unless -Dnestworld.playerTickRegionExecution=true is also set. */
    private static int playerTickExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerTickExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_TICK_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerTickRegionExecution is OFF -- this player will still tick on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.3 player-tick experiment" + masterFlag), true);
        return 1;
    }

    private static int playerTickExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            // Allow removing an offline/UUID-unresolvable name defensively -- no-op if not found.
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerTickExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.3 player-tick experiment"), true);
        return 1;
    }

    private static int playerTickExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerTickExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_TICK_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.3 player-tick experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 — staged rollout control: add/remove a named player from the region-attack
     *  opt-in set. No effect unless -Dnestworld.playerAttackRegionExecution=true is also set. */
    private static int playerAttackExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerAttackExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_ATTACK_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerAttackRegionExecution is OFF -- this player's attacks will still run on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.4 player-attack experiment" + masterFlag), true);
        return 1;
    }

    private static int playerAttackExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerAttackExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.4 player-attack experiment"), true);
        return 1;
    }

    private static int playerAttackExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerAttackExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_ATTACK_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.4 player-attack experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 measurement-only diagnostics, see PlayerAttackTiming. */
    private static int playerAttackTiming(CommandSourceStack src) {
        String result = "NW #31.4 attack timing: " + net.nestworld.region.PlayerAttackTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int playerAttackTimingReset(CommandSourceStack src) {
        net.nestworld.region.PlayerAttackTiming.reset();
        src.sendSuccess(() -> Component.literal("NW #31.4 attack timing reset"), true);
        return 1;
    }

    /** Phase 11 #31.4 step 2 measurement-only diagnostics, see PlayerInteractionTiming. */
    private static int playerInteractionTiming(CommandSourceStack src) {
        String result = "NW #31.4 step2 interaction timing: " + net.nestworld.region.PlayerInteractionTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int playerInteractionTimingReset(CommandSourceStack src) {
        net.nestworld.region.PlayerInteractionTiming.reset();
        src.sendSuccess(() -> Component.literal("NW #31.4 step2 interaction timing reset"), true);
        return 1;
    }

    /** Phase 11 #31.4 step 2 — staged rollout control for the block-interaction experiment. No
     *  effect unless -Dnestworld.playerInteractionRegionExecution=true is also set. */
    private static int playerInteractionExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerInteractionExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_INTERACTION_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerInteractionRegionExecution is OFF -- this player's block interactions will still run on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.4 step2 player-interaction experiment" + masterFlag), true);
        return 1;
    }

    private static int playerInteractionExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerInteractionExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.4 step2 player-interaction experiment"), true);
        return 1;
    }

    private static int playerInteractionExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerInteractionExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_INTERACTION_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.4 step2 player-interaction experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 step 3 — staged rollout control for the useItem() experiment. No effect
     *  unless -Dnestworld.playerUseItemRegionExecution=true is also set. */
    private static int playerUseItemExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerUseItemExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_USE_ITEM_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerUseItemRegionExecution is OFF -- this player's useItem() will still run on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.4 step3 player-useitem experiment" + masterFlag), true);
        return 1;
    }

    private static int playerUseItemExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerUseItemExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.4 step3 player-useitem experiment"), true);
        return 1;
    }

    private static int playerUseItemExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerUseItemExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_USE_ITEM_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.4 step3 player-useitem experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 step 3 measurement-only diagnostics, see PlayerUseItemTiming. */
    private static int playerUseItemTiming(CommandSourceStack src) {
        String result = "NW #31.4 step3 useitem timing: " + net.nestworld.region.PlayerUseItemTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int playerUseItemTimingReset(CommandSourceStack src) {
        net.nestworld.region.PlayerUseItemTiming.reset();
        src.sendSuccess(() -> Component.literal("NW #31.4 step3 useitem timing reset"), true);
        return 1;
    }

    /** Phase 11 #31.4 step 4 — staged rollout control for the item-on-block experiment. No effect
     *  unless -Dnestworld.playerUseItemOnBlockRegionExecution=true is also set. */
    private static int playerUseItemOnBlockExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerUseItemOnBlockExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_USE_ITEM_ON_BLOCK_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerUseItemOnBlockRegionExecution is OFF -- this player's useOn() will still run on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.4 step4 player-useitemonblock experiment" + masterFlag), true);
        return 1;
    }

    private static int playerUseItemOnBlockExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerUseItemOnBlockExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.4 step4 player-useitemonblock experiment"), true);
        return 1;
    }

    private static int playerUseItemOnBlockExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerUseItemOnBlockExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_USE_ITEM_ON_BLOCK_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.4 step4 player-useitemonblock experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 step 4 measurement-only diagnostics, see PlayerUseItemOnBlockTiming. */
    private static int playerUseItemOnBlockTiming(CommandSourceStack src) {
        String result = "NW #31.4 step4 useitemonblock timing: " + net.nestworld.region.PlayerUseItemOnBlockTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int playerUseItemOnBlockTimingReset(CommandSourceStack src) {
        net.nestworld.region.PlayerUseItemOnBlockTiming.reset();
        src.sendSuccess(() -> Component.literal("NW #31.4 step4 useitemonblock timing reset"), true);
        return 1;
    }

    /** Phase 11 #31.3/#31.4 player-action-ownership hardening measurement-only diagnostics,
     *  see PlayerUseControlTiming. */
    private static int playerUseControlTiming(CommandSourceStack src) {
        String result = "NW player-use-control timing: " + net.nestworld.region.PlayerUseControlTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int playerUseControlTimingReset(CommandSourceStack src) {
        net.nestworld.region.PlayerUseControlTiming.reset();
        src.sendSuccess(() -> Component.literal("NW player-use-control timing reset"), true);
        return 1;
    }

    private static int playerEntityInteractExperimentAdd(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            src.sendFailure(Component.literal("No online player named " + name));
            return 0;
        }
        boolean added = net.nestworld.region.PlayerEntityInteractExperiment.add(player.getUUID());
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_ENTITY_INTERACT_REGION_EXECUTION ? "" :
                " (WARNING: -Dnestworld.playerEntityInteractRegionExecution is OFF -- this player's interact() will still run on main)";
        src.sendSuccess(() -> Component.literal((added ? "Added " : "Already in set: ") + name
                + " to #31.4 item-on-entity experiment" + masterFlag), true);
        return 1;
    }

    private static int playerEntityInteractExperimentRemove(CommandSourceStack src, String name) {
        ServerPlayer player = src.getServer().getPlayerList().getPlayerByName(name);
        java.util.UUID id = player != null ? player.getUUID() : null;
        if (id == null) {
            src.sendFailure(Component.literal("No online player named " + name + " (nothing removed)"));
            return 0;
        }
        boolean removed = net.nestworld.region.PlayerEntityInteractExperiment.remove(id);
        src.sendSuccess(() -> Component.literal((removed ? "Removed " : "Was not in set: ") + name
                + " from #31.4 item-on-entity experiment"), true);
        return 1;
    }

    private static int playerEntityInteractExperimentList(CommandSourceStack src) {
        java.util.Set<java.util.UUID> ids = net.nestworld.region.PlayerEntityInteractExperiment.snapshot();
        String masterFlag = net.nestworld.region.NestworldTuning.PLAYER_ENTITY_INTERACT_REGION_EXECUTION ? "ON" : "OFF";
        StringBuilder sb = new StringBuilder("NW #31.4 item-on-entity experiment: masterFlag=").append(masterFlag)
                .append(" size=").append(ids.size());
        for (java.util.UUID id : ids) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(id);
            sb.append("\n  ").append(p != null ? p.getGameProfile().getName() : id.toString());
        }
        String result = sb.toString();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** Phase 11 #31.4 item-on-entity measurement-only diagnostics, see EntityInteractionTiming. */
    private static int entityInteractTiming(CommandSourceStack src) {
        String result = "NW item-on-entity timing: " + net.nestworld.region.EntityInteractionTiming.summary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int entityInteractTimingReset(CommandSourceStack src) {
        net.nestworld.region.EntityInteractionTiming.reset();
        src.sendSuccess(() -> Component.literal("NW item-on-entity timing reset"), true);
        return 1;
    }

    /** Shared per-tick interaction-dispatch wait budget diagnostics, see
     *  PlayerInteractionWaitBudget -- added after a real Server Watchdog crash this session
     *  (reproduced at 60-80 concurrent players: N synchronous dispatch calls in the same tick,
     *  each individually timeout-bounded, compounding with no shared cap). Also reports the max
     *  region mailbox depth across all active regions as a coarse backlog signal. */
    private static int interactionWaitBudget(CommandSourceStack src) {
        String result = "NW interaction-wait-budget: " + net.nestworld.region.PlayerInteractionWaitBudget.summary();
        int maxDepth = 0;
        if (NestworldRegionSystem.isInitialised()) {
            for (WorldRegion r : overworldRegion(NestworldRegionSystem.get()).getTree().getActiveRegions()) {
                maxDepth = Math.max(maxDepth, r.nestworldMailboxDepthFast());
            }
        }
        final String finalResult = result + " regionQueueDepthMax=" + maxDepth;
        src.sendSuccess(() -> Component.literal(finalResult), false);
        return 1;
    }

    private static int interactionWaitBudgetReset(CommandSourceStack src) {
        net.nestworld.region.PlayerInteractionWaitBudget.reset();
        src.sendSuccess(() -> Component.literal("NW interaction-wait-budget reset"), true);
        return 1;
    }

    /** Real percentile distributions (p50/p90/p95/p99/max) for round-trip apply latency (split
     *  same/cross-region) and main-thread wait latency, plus the calls-per-tick distribution --
     *  2026-08-28 measurement request, run BEFORE choosing K/maxPerCall/region-health thresholds
     *  for the interaction-dispatch redesign. See PlayerInteractionWaitBudget#distributionSummary. */
    private static int interactionLatencyDist(CommandSourceStack src) {
        String result = "NW interaction-latency-dist: " + net.nestworld.region.PlayerInteractionWaitBudget.distributionSummary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** 2026-08-30 -- scheduler-starvation attribution (see PlayerInteractionWaitBudget's
     *  recordWait javadoc): when a bounded latch.await() call's REAL wall-clock time exceeds
     *  what was requested/granted, that's evidence the thread wasn't scheduled back promptly by
     *  the OS (CPU oversubscription), not a bug in the dispatcher's own timeout logic. */
    private static int interactionWaitSpikes(CommandSourceStack src) {
        String result = net.nestworld.region.PlayerInteractionWaitBudget.spikeSummary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    /** K-first-slots sweep knobs (2026-08-28) -- see NestworldTuning#INTERACTION_MAX_WAIT_SLOTS_PER_TICK's
     *  javadoc. Runtime-settable (not just startup system property) so a K/X sweep across many
     *  combinations doesn't need a JVM restart between each one. */
    private static int interactionSlots(CommandSourceStack src, int k) {
        net.nestworld.region.NestworldTuning.INTERACTION_MAX_WAIT_SLOTS_PER_TICK = k;
        src.sendSuccess(() -> Component.literal("NW interaction K (max wait slots/tick) set to " + k), true);
        return 1;
    }

    private static int interactionMaxWaitMs(CommandSourceStack src, int x) {
        net.nestworld.region.NestworldTuning.INTERACTION_MAX_WAIT_PER_CALL_NANOS = x * 1_000_000L;
        src.sendSuccess(() -> Component.literal("NW interaction X (max wait per call) set to " + x + "ms"), true);
        return 1;
    }

    /** 2026-08-28 split/merge thrash investigation -- see SplitMergeEventLog's javadoc. */
    private static int splitMergeSummary(CommandSourceStack src) {
        String result = "NW split/merge: " + net.nestworld.region.SplitMergeEventLog.summary()
                + " | " + net.nestworld.region.SplitMergeEventLog.regionCountSummary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int splitMergeDump(CommandSourceStack src, int lines) {
        String dump = net.nestworld.region.SplitMergeEventLog.dump(lines);
        for (String line : dump.split("\n")) {
            if (!line.isBlank()) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static int splitMergeReset(CommandSourceStack src) {
        net.nestworld.region.SplitMergeEventLog.reset();
        src.sendSuccess(() -> Component.literal("NW split/merge event log reset"), true);
        return 1;
    }

    /** 2026-08-28 Connection/Netty fan-in diagnostics -- see ConnectionDiagnostics's javadoc. */
    private static int connectionDiag(CommandSourceStack src) {
        String result = "NW connection-diag: " + net.nestworld.region.ConnectionDiagnostics.summary()
                + " | " + net.nestworld.region.ConnectionDiagnostics.inFlightSummary()
                + " | perSource: " + net.nestworld.region.ConnectionDiagnostics.perSourceSummary()
                + " | broadcastComposition: " + net.nestworld.region.ConnectionDiagnostics.broadcastCompositionSummary();
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int connectionDiagReset(CommandSourceStack src) {
        net.nestworld.region.ConnectionDiagnostics.reset();
        src.sendSuccess(() -> Component.literal("NW connection-diag reset"), true);
        return 1;
    }

    /** 2026-08-28 Entity Tracking Fan-out Attribution ТЗ -- see TrackingMetrics's javadoc. */
    private static int trackingReport(CommandSourceStack src) {
        int players = src.getServer().getPlayerList().getPlayerCount();
        double serverMspt = src.getServer().getAverageTickTime();
        double tpsVal = Math.min(20.0, 1000.0 / Math.max(serverMspt, 0.001));
        String mspt = String.format("%.1f", serverMspt);
        String tps = String.format("%.1f", tpsVal);
        String report = net.nestworld.region.TrackingMetrics.report(players, tps, mspt);
        for (String line : report.split("\n")) {
            if (!line.isBlank()) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static int trackingReset(CommandSourceStack src) {
        net.nestworld.region.TrackingMetrics.reset();
        src.sendSuccess(() -> Component.literal("NW tracking metrics reset"), true);
        return 1;
    }

    /** 2026-08-28 Outbound Tracking Optimization ТЗ -- sizes the ClientboundBundlePacket win.
     *  {@code ticks} is the caller-measured tick count for the window (tps * durationSeconds). */
    private static int recipientFanout(CommandSourceStack src, double ticks) {
        String report = net.nestworld.region.TrackingMetrics.recipientFanoutReport(ticks);
        for (String line : report.split("\n")) {
            if (!line.isBlank()) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    /** 2026-08-28 Outbound Tracking Optimization ТЗ -- feature-flag toggle for
     *  OutboundBatchQueue (default OFF). */
    private static int outboundBatchToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.OutboundBatchQueue.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW outbound-batch " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int outboundBatchReport(CommandSourceStack src) {
        String report = net.nestworld.region.OutboundBatchQueue.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int outboundBatchReset(CommandSourceStack src) {
        net.nestworld.region.OutboundBatchQueue.reset();
        src.sendSuccess(() -> Component.literal("NW outbound-batch metrics reset"), true);
        return 1;
    }

    private static int outboundBatchMaxSize(CommandSourceStack src, int n) {
        net.nestworld.region.OutboundBatchQueue.MAX_BUNDLE_SIZE = n;
        src.sendSuccess(() -> Component.literal("NW outbound-batch maxBundleSize=" + n), true);
        return 1;
    }

    /** 2026-08-29 Setup-Burst / Player Join Pipeline ТЗ -- see JoinBurstMetrics's javadoc. */
    private static int joinBurstReport(CommandSourceStack src) {
        String report = net.nestworld.region.JoinBurstMetrics.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int joinBurstReset(CommandSourceStack src) {
        net.nestworld.region.JoinBurstMetrics.reset();
        src.sendSuccess(() -> Component.literal("NW join-burst metrics reset"), true);
        return 1;
    }

    /** 2026-08-29 Setup-Burst / Player Join Pipeline -- see ChunkOutboundQueue's javadoc. */
    private static int chunkOutboundToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.ChunkOutboundQueue.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW chunk-outbound " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int chunkOutboundReport(CommandSourceStack src) {
        String report = net.nestworld.region.ChunkOutboundQueue.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int chunkOutboundReset(CommandSourceStack src) {
        net.nestworld.region.ChunkOutboundQueue.reset();
        src.sendSuccess(() -> Component.literal("NW chunk-outbound metrics reset"), true);
        return 1;
    }

    private static int chunkOutboundBudget(CommandSourceStack src, int n) {
        net.nestworld.region.ChunkOutboundQueue.BUDGET_PER_TICK = n;
        src.sendSuccess(() -> Component.literal("NW chunk-outbound budgetPerTick=" + n), true);
        return 1;
    }

    /** 2026-08-29 Pairing Outbound Queue ТЗ §21 -- see PairingOutboundQueue's javadoc. */
    private static int pairingBatchToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.PairingOutboundQueue.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW pairing-batch " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int pairingBatchStatus(CommandSourceStack src) {
        String status = net.nestworld.region.PairingOutboundQueue.status();
        src.sendSuccess(() -> Component.literal(status), false);
        return 1;
    }

    private static int pairingBatchReset(CommandSourceStack src) {
        net.nestworld.region.PairingOutboundQueue.reset();
        src.sendSuccess(() -> Component.literal("NW pairing-batch metrics reset"), true);
        return 1;
    }

    private static int pairingBatchBudget(CommandSourceStack src, int n) {
        net.nestworld.region.PairingOutboundQueue.BUDGET_PER_TICK = n;
        src.sendSuccess(() -> Component.literal("NW pairing-batch budgetPerTick=" + n), true);
        return 1;
    }

    /** 2026-08-29 Player Tick / Decision Fan-out Attribution ТЗ -- see MoveDecisionMetrics's javadoc. */
    private static int moveDecisionReport(CommandSourceStack src) {
        String report = net.nestworld.region.MoveDecisionMetrics.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int moveDecisionReset(CommandSourceStack src) {
        net.nestworld.region.MoveDecisionMetrics.reset();
        src.sendSuccess(() -> Component.literal("NW move-decision metrics reset"), true);
        return 1;
    }

    /** 2026-08-29 NaturalSpawner Attribution ТЗ -- see NaturalSpawnerMetrics's javadoc. */
    private static int naturalSpawnerReport(CommandSourceStack src) {
        String report = net.nestworld.region.NaturalSpawnerMetrics.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int naturalSpawnerReset(CommandSourceStack src) {
        net.nestworld.region.NaturalSpawnerMetrics.reset();
        src.sendSuccess(() -> Component.literal("NW natural-spawner metrics reset"), true);
        return 1;
    }

    private static int burstReport(CommandSourceStack src) {
        String report = net.nestworld.region.BurstAdmissionMetrics.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int modCallbackReport(CommandSourceStack src) {
        String report = net.nestworld.region.ModCallbackAttribution.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int modCallbackReset(CommandSourceStack src) {
        net.nestworld.region.ModCallbackAttribution.reset();
        src.sendSuccess(() -> Component.literal("NW mod-callback-attribution reset"), true);
        return 1;
    }

    private static int modCallbackRouterReport(CommandSourceStack src) {
        String report = net.nestworld.region.ModCallbackRouter.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int modCallbackRouterReset(CommandSourceStack src) {
        net.nestworld.region.ModCallbackRouter.reset();
        src.sendSuccess(() -> Component.literal("NW mod-callback-router reset"), true);
        return 1;
    }

    private static int burstReset(CommandSourceStack src) {
        net.nestworld.region.BurstAdmissionMetrics.reset();
        src.sendSuccess(() -> Component.literal("NW burst-admission metrics reset"), true);
        return 1;
    }

    private static int burstThreads(CommandSourceStack src) {
        String census = net.nestworld.region.BurstAdmissionMetrics.threadCensus();
        src.sendSuccess(() -> Component.literal(census), false);
        return 1;
    }

    private static int admissionToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.AdmissionController.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW admission controller " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int admissionReport(CommandSourceStack src) {
        String report = net.nestworld.region.AdmissionController.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int admissionReset(CommandSourceStack src) {
        net.nestworld.region.AdmissionController.reset();
        src.sendSuccess(() -> Component.literal("NW admission metrics reset"), true);
        return 1;
    }

    private static int admissionBudget(CommandSourceStack src, int n) {
        net.nestworld.region.AdmissionController.BUDGET_PER_TICK = n;
        src.sendSuccess(() -> Component.literal("NW admission budgetPerTick=" + n), true);
        return 1;
    }

    private static int pairingAdmissionToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.PairingAdmissionGate.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW pairing-admission gate " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int pairingAdmissionReport(CommandSourceStack src) {
        String report = net.nestworld.region.PairingAdmissionGate.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int pairingAdmissionReset(CommandSourceStack src) {
        net.nestworld.region.PairingAdmissionGate.reset();
        src.sendSuccess(() -> Component.literal("NW pairing-admission metrics reset"), true);
        return 1;
    }

    private static int pairingAdmissionBudget(CommandSourceStack src, int n) {
        net.nestworld.region.PairingAdmissionGate.GLOBAL_BUDGET_PER_TICK = n;
        src.sendSuccess(() -> Component.literal("NW pairing-admission globalBudgetPerTick=" + n), true);
        return 1;
    }

    private static int pairingAdmissionPerPlayer(CommandSourceStack src, int n) {
        net.nestworld.region.PairingAdmissionGate.PER_PLAYER_BUDGET_PER_TICK = n;
        src.sendSuccess(() -> Component.literal("NW pairing-admission perPlayerBudgetPerTick=" + n), true);
        return 1;
    }

    private static int connectionAdmissionToggle(CommandSourceStack src, boolean on) {
        net.nestworld.region.ConnectionAdmissionGate.ENABLED = on;
        src.sendSuccess(() -> Component.literal("NW connection-admission gate " + (on ? "ENABLED" : "disabled")), true);
        return 1;
    }

    private static int connectionAdmissionReport(CommandSourceStack src) {
        String report = net.nestworld.region.ConnectionAdmissionGate.report();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int connectionAdmissionReset(CommandSourceStack src) {
        net.nestworld.region.ConnectionAdmissionGate.reset();
        src.sendSuccess(() -> Component.literal("NW connection-admission metrics reset"), true);
        return 1;
    }

    private static int connectionAdmissionBudget(CommandSourceStack src, int n) {
        net.nestworld.region.ConnectionAdmissionGate.MAX_NEW_CONNECTIONS_PER_WINDOW = n;
        src.sendSuccess(() -> Component.literal("NW connection-admission maxPerWindow=" + n), true);
        return 1;
    }

    private static int cpuPlannerReport(CommandSourceStack src) {
        String report = net.nestworld.region.CpuCapacityPlanner.buildFullReport().format();
        for (String line : report.split("\n")) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int nearestPlayerVerifyReport(CommandSourceStack src) {
        String report = net.nestworld.region.NearestPlayerIndex.verifyReport();
        src.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int nearestPlayerVerifyReset(CommandSourceStack src) {
        net.nestworld.region.NearestPlayerIndex.verifyReset();
        src.sendSuccess(() -> Component.literal("NW nearest-player-index verify metrics reset"), true);
        return 1;
    }

    private static int nearestPlayerMismatches(CommandSourceStack src) {
        var log = net.nestworld.region.NearestPlayerIndex.mismatchLog();
        src.sendSuccess(() -> Component.literal("NW nearest-player-index mismatch log (" + log.size() + " entries):"), false);
        for (String line : log) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    private static int nearestPlayerMismatchesDump(CommandSourceStack src) {
        var log = net.nestworld.region.NearestPlayerIndex.mismatchLog();
        java.nio.file.Path out = java.nio.file.Path.of("nearestplayer_mismatches.txt");
        try {
            java.nio.file.Files.write(out, log);
            src.sendSuccess(() -> Component.literal("Dumped " + log.size() + " mismatch entries to " + out.toAbsolutePath()), false);
            return 1;
        } catch (java.io.IOException e) {
            src.sendFailure(Component.literal("Dump failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int connectionDiagSlow(CommandSourceStack src, int lines) {
        String dump = net.nestworld.region.ConnectionDiagnostics.slowLog(lines);
        for (String line : dump.split("\n")) {
            if (!line.isBlank()) {
                src.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    /** Diagnostic-only, temporary: blocks the calling (main) thread for {@code ms}, then reports
     *  how many times region {@code regionId}'s normal tick body ran before, during, and after
     *  the block -- isolates whether a blocked main thread stalls a free-running region's own
     *  loop, independent of any interaction-specific code. See RegionLoopProbe. */
    private static int blockMainProbe(CommandSourceStack src, int ms, int regionId) {
        long before = net.nestworld.region.RegionLoopProbe.get(regionId);
        long beforeNanos = System.nanoTime();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long after = net.nestworld.region.RegionLoopProbe.get(regionId);
        long elapsedMs = (System.nanoTime() - beforeNanos) / 1_000_000;
        long delta = after - before;
        double expectedTicks = elapsedMs / 50.0;
        String result = String.format(
                "NW blockMainProbe: blocked main for %dms (region %d): tick count %d -> %d (delta=%d, expected~%.0f @ 20Hz)",
                elapsedMs, regionId, before, after, delta, expectedTicks);
        src.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int tickPhases(CommandSourceStack src) {
        if (!net.nestworld.region.NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        net.nestworld.region.NestworldRegionSystem sys0 = net.nestworld.region.NestworldRegionSystem.get();
        String result = overworldRegion(sys0).tickPhaseReport();
        src.sendSuccess(() -> Component.literal(result), false);
        return 0;
    }

    /**
     * P0.3 ТЗ (docs/P0_3_AUTONOMOUS_BASELINE_SPEC.md): no-player autonomous chunk-gen benchmark.
     * Dispatches `count` fresh-chunk tickets via a non-PLAYER, self-expiring ticket type -- see
     * ChunkGenBenchmark's javadoc. Fire-and-forget; poll progress with /nestworld gentest status.
     */
    private static int genTestStart(CommandSourceStack src, int count, int baseX, int baseZ) {
        ServerLevel level = src.getServer().overworld();
        int baseChunkX = baseX >> 4, baseChunkZ = baseZ >> 4;
        ChunkGenBenchmark.dispatch(level, count, baseChunkX, baseChunkZ);
        src.sendSuccess(() -> Component.literal(String.format(
                "NW gentest: dispatched %,d chunk tickets around chunk (%d,%d) -- poll with /nestworld gentest status",
                count, baseChunkX, baseChunkZ)), true);
        return count;
    }

    private static int genTestStatus(CommandSourceStack src) {
        String result = ChunkGenBenchmark.status(src.getServer().overworld());
        src.sendSuccess(() -> Component.literal("NW " + result), false);
        return 0;
    }

    /**
     * NestWorld (Layer 13.1): live concurrency-cap change for the shared NOISE/SURFACE/
     * CARVERS generation pool, no restart required -- see ChunkMap.nestworldSetGenConcurrency's
     * javadoc. Built to run a clean scaling benchmark (2/4/6/8/10/12) on one warmed-up JVM,
     * since a restart before every data point was found to introduce a real JIT-compilation-
     * storm confound (see project memory: layer13-concurrency-sweep-partial-and-jit-storm).
     * Only takes effect if the server was booted with a positive
     * -Dnestworld.chunkGenMaxConcurrent (0 disables gating entirely, independent of this).
     */
    private static int setGenConcurrency(CommandSourceStack src, int n) {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) src.getServer().overworld().getChunkSource();
        cache.chunkMap.nestworldSetGenConcurrency(n);
        src.sendSuccess(() -> Component.literal("NW gen-pool concurrency set to " + n), true);
        return n;
    }

    /**
     * P0.5 (docs/P0_3_AUTONOMOUS_BASELINE_SPEC.md follow-up ТЗ): live chunkGenBudget override,
     * same JIT-storm-safe one-warm-JVM sweep rationale as setGenConcurrency above -- see
     * NestworldLiveTuning's javadoc. -1 restores the boot-time NestworldTuning.CHUNK_GEN_BUDGET.
     */
    private static int setGenBudget(CommandSourceStack src, int n) {
        net.nestworld.region.NestworldLiveTuning.chunkGenBudgetOverride = n;
        int effective = net.nestworld.region.NestworldLiveTuning.effectiveChunkGenBudget();
        src.sendSuccess(() -> Component.literal("NW chunkGenBudget set to " + n + " (effective=" + effective + ")"), true);
        return n;
    }

    private static int genBudget(CommandSourceStack src) {
        int effective = net.nestworld.region.NestworldLiveTuning.effectiveChunkGenBudget();
        int override = net.nestworld.region.NestworldLiveTuning.chunkGenBudgetOverride;
        src.sendSuccess(() -> Component.literal("NW chunkGenBudget effective=" + effective
                + " (override=" + override + ", boot-default=" + net.nestworld.region.NestworldTuning.CHUNK_GEN_BUDGET + ")"), false);
        return effective;
    }

    /**
     * Live override for the "moved too quickly" movement-check multiplier (see
     * NestworldTuning.MOVE_TOO_QUICKLY_MULTIPLIER's javadoc). -1 restores the
     * boot-time server.properties value.
     */
    private static int setMoveThreshold(CommandSourceStack src, double multiplier) {
        net.nestworld.region.NestworldLiveTuning.moveTooQuicklyMultiplierOverride = multiplier;
        double effective = net.nestworld.region.NestworldLiveTuning.effectiveMoveTooQuicklyMultiplier();
        src.sendSuccess(() -> Component.literal("NW moveTooQuicklyMultiplier set to " + multiplier
                + " (effective=" + effective + ")"), true);
        return (int) Math.round(effective);
    }

    private static int moveThreshold(CommandSourceStack src) {
        double effective = net.nestworld.region.NestworldLiveTuning.effectiveMoveTooQuicklyMultiplier();
        double override = net.nestworld.region.NestworldLiveTuning.moveTooQuicklyMultiplierOverride;
        src.sendSuccess(() -> Component.literal("NW moveTooQuicklyMultiplier effective=" + effective
                + " (override=" + override + ", boot-default(server.properties)="
                + net.nestworld.region.NestworldTuning.MOVE_TOO_QUICKLY_MULTIPLIER + ")"), false);
        return (int) Math.round(effective);
    }

    /**
     * Live override for vanilla's "floating too long" anti-flyhack kick (see
     * NestworldTuning.FLOATING_KICK_ENABLED's javadoc). Takes effect immediately for
     * every connected player's next tick, no restart or reconnect needed.
     */
    private static int setFloatingKick(CommandSourceStack src, boolean enabled) {
        net.nestworld.region.NestworldLiveTuning.floatingKickEnabledOverride = enabled ? 1 : -1;
        src.sendSuccess(() -> Component.literal("NW floating-too-long kick " + (enabled ? "ENABLED" : "DISABLED")
                + " (live, takes effect immediately)"), true);
        return enabled ? 1 : 0;
    }

    private static int floatingKick(CommandSourceStack src) {
        boolean effective = net.nestworld.region.NestworldLiveTuning.effectiveFloatingKickEnabled();
        int override = net.nestworld.region.NestworldLiveTuning.floatingKickEnabledOverride;
        src.sendSuccess(() -> Component.literal("NW floating-too-long kick effective=" + effective
                + " (override=" + override + ", boot-default(server.properties)="
                + net.nestworld.region.NestworldTuning.FLOATING_KICK_ENABLED + ")"), false);
        return effective ? 1 : 0;
    }

    /**
     * Test/ops entry point for {@link ChunkPrefetch} — prefetches a (2*radius+1)^2 chunk
     * square around the command sender's current position, then reports the resulting
     * counters a few hundred ms later (long enough for the parallel pool to mostly finish
     * on any reasonable radius). Manual verification tool, not meant for routine use —
     * an external mod calling {@code NestworldApi.prefetchChunks} is the real integration
     * point this exists to validate.
     */
    private static int chunkPrefetchTest(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        net.minecraft.world.level.ChunkPos center = new net.minecraft.world.level.ChunkPos(BlockPos.containing(src.getPosition()));
        java.util.List<net.minecraft.world.level.ChunkPos> positions = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                positions.add(new net.minecraft.world.level.ChunkPos(center.x + dx, center.z + dz));
            }
        }
        long beforePrefetched = ChunkPrefetch.prefetchedTotal();
        long beforeFailed = ChunkPrefetch.failedTotal();
        long beforeMisses = ChunkPrefetch.missesTotal();
        net.nestworld.api.NestworldApi.prefetchChunks(level, positions);
        src.sendSuccess(() -> Component.literal("NW chunkprefetch: dispatched " + positions.size()
                + " positions (radius " + radius + "). Before: prefetched=" + beforePrefetched
                + " failed=" + beforeFailed + " misses=" + beforeMisses
                + " — run /nestworld chunkprefetchstats shortly to see the delta."), true);
        return positions.size();
    }

    private static int chunkPrefetchStats(CommandSourceStack src) {
        long p = ChunkPrefetch.prefetchedTotal();
        long f = ChunkPrefetch.failedTotal();
        long m = ChunkPrefetch.missesTotal();
        src.sendSuccess(() -> Component.literal("NW ChunkPrefetch (cumulative since boot): prefetched="
                + p + " failed=" + f + " misses(no chunk on disk)=" + m), false);
        return (int) p;
    }

    /**
     * Live override for the movement-packet-burst cap (see
     * NestworldTuning.MAX_MOVEMENT_PACKETS_PER_TICK's javadoc). -1 restores the boot-time
     * server.properties value.
     */
    private static int setMovePacketCap(CommandSourceStack src, int n) {
        net.nestworld.region.NestworldLiveTuning.maxMovementPacketsPerTickOverride = n;
        int effective = net.nestworld.region.NestworldLiveTuning.effectiveMaxMovementPacketsPerTick();
        src.sendSuccess(() -> Component.literal("NW maxMovementPacketsPerTick set to " + n
                + " (effective=" + effective + ")"), true);
        return effective;
    }

    private static int movePacketCap(CommandSourceStack src) {
        int effective = net.nestworld.region.NestworldLiveTuning.effectiveMaxMovementPacketsPerTick();
        int override = net.nestworld.region.NestworldLiveTuning.maxMovementPacketsPerTickOverride;
        src.sendSuccess(() -> Component.literal("NW maxMovementPacketsPerTick effective=" + effective
                + " (override=" + override + ", boot-default(server.properties)="
                + net.nestworld.region.NestworldTuning.MAX_MOVEMENT_PACKETS_PER_TICK + ")"), false);
        return effective;
    }

    /**
     * Live override for the chunk-promotion pump-budget deadline clamp (see
     * NestworldTuning.CHUNK_PROMOTION_PUMP_BUDGET_MS's javadoc). -1 restores the
     * boot-time value; 0 disables the clamp entirely (NOT recommended, see the
     * 2026-08-20 incident it exists to prevent).
     */
    private static int setPumpBudget(CommandSourceStack src, int ms) {
        net.nestworld.region.NestworldLiveTuning.chunkPromotionPumpBudgetMsOverride = ms;
        long effective = net.nestworld.region.NestworldLiveTuning.effectiveChunkPromotionPumpBudgetMs();
        src.sendSuccess(() -> Component.literal("NW chunkPromotionPumpBudgetMs set to " + ms
                + " (effective=" + effective + ")"), true);
        return (int) effective;
    }

    private static int pumpBudget(CommandSourceStack src) {
        long effective = net.nestworld.region.NestworldLiveTuning.effectiveChunkPromotionPumpBudgetMs();
        long override = net.nestworld.region.NestworldLiveTuning.chunkPromotionPumpBudgetMsOverride;
        src.sendSuccess(() -> Component.literal("NW chunkPromotionPumpBudgetMs effective=" + effective
                + "ms (override=" + override + ", boot-default="
                + net.nestworld.region.NestworldTuning.CHUNK_PROMOTION_PUMP_BUDGET_MS + "ms)"), false);
        return (int) effective;
    }

    /**
     * NestWorld (Layer 13.1): main-thread MSPT percentiles (p50/p95/p99/max), for the
     * scaling benchmark's "does the main thread's own tick time degrade" question --
     * complements chunkpromotion (WHY it might degrade) with the actual tick-time
     * distribution. Reuses vanilla's own {@code MinecraftServer.tickTimes} ring buffer
     * (last 100 ticks, nanoseconds) -- no new tracking needed, it was already there.
     */
    private static int msptPercentiles(CommandSourceStack src) {
        long[] ticks = src.getServer().tickTimes.clone();
        double[] ms = new double[ticks.length];
        for (int i = 0; i < ticks.length; i++) ms[i] = ticks[i] / 1_000_000.0;
        java.util.Arrays.sort(ms);
        double avg = java.util.Arrays.stream(ms).average().orElse(0.0);
        double p50 = ms[(int) (ms.length * 0.50)];
        double p95 = ms[(int) Math.min(ms.length - 1, ms.length * 0.95)];
        double p99 = ms[(int) Math.min(ms.length - 1, ms.length * 0.99)];
        double max = ms[ms.length - 1];
        src.sendSuccess(() -> Component.literal(String.format(
                "NW MSPT (last %d ticks): avg=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f",
                ticks.length, avg, p50, p95, p99, max)), false);
        return (int) p99;
    }

    private static int genConcurrency(CommandSourceStack src) {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) src.getServer().overworld().getChunkSource();
        int cap = cache.chunkMap.nestworldGenConcurrency();
        int poolPending = cache.chunkMap.nestworldGenPoolPending();
        src.sendSuccess(() -> Component.literal(
                "NW gen-pool concurrency: " + cap + "   queued-in-pool: " + poolPending), false);
        return cap;
    }

    /**
     * NestWorld debug (see docs/LOCAL_TICK_STAGE4.md, "DistanceManager root cause
     * investigation"): simulates a Draconic-Evolution-reactor-style raycast burst —
     * {@code count} synchronous {@code Level.getBlockState()} calls to SCATTERED,
     * genuinely fresh (never-before-touched) chunk positions, all within this ONE
     * command execution (one tick), run directly on the main thread. Unlike
     * {@code /forceload} (a batched ticket registration, already stress-tested this
     * project's history), this matches the actual access shape suspected of driving
     * {@code DistanceManager.chunksToUpdateFutures} backlog growth: many individual
     * top-level chunk requests issued rapidly from ordinary game code, not a bulk
     * command. Positions spiral outward from (baseX, baseZ) with wide spacing so
     * every call is guaranteed to touch a chunk this test (or anything else) has
     * never generated before — repeat calls with a NEW base to get a clean sample.
     */
    private static int stressChunks(CommandSourceStack src, int count, int baseX, int baseZ) {
        net.minecraft.server.level.ServerLevel level = src.getServer().overworld();
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) level.getChunkSource();
        long lookupsBefore = cache.nestworldChunkLookups.get();
        long fastBefore = cache.nestworldFastPathHits.get();
        long blockingBefore = cache.nestworldBlockingCalls.get();
        long waitBefore = cache.nestworldWaitNanos.get();

        // Spiral outward in chunk-sized (16-block) steps, wide enough (37 blocks ~=
        // 2.3 chunks) that consecutive positions essentially never land in the same
        // chunk even after thousands of steps — approximates a raycast fanning out
        // from a central point rather than a compact, already-generated-adjacent area.
        long start = System.nanoTime();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        double angle = 0.0;
        double radius = 0.0;
        for (int i = 0; i < count; i++) {
            angle += 0.7;
            radius += 37.0 / (2.0 * Math.PI * Math.max(1.0, radius / 37.0) + 1.0);
            int x = baseX + (int) Math.round(radius * Math.cos(angle));
            int z = baseZ + (int) Math.round(radius * Math.sin(angle));
            pos.set(x, 100, z);
            level.getBlockState(pos);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        long lookupsDelta = cache.nestworldChunkLookups.get() - lookupsBefore;
        long fastDelta = cache.nestworldFastPathHits.get() - fastBefore;
        long blockingDelta = cache.nestworldBlockingCalls.get() - blockingBefore;
        long waitDeltaMs = (cache.nestworldWaitNanos.get() - waitBefore) / 1_000_000L;
        src.sendSuccess(() -> Component.literal(String.format(
                "stresschunks: %,d getBlockState() calls around (%d,%d) took %,d ms wall-clock "
                        + "(chunkstats delta: lookups=%,d fast=%,d blocking=%,d wait=%,dms)",
                count, baseX, baseZ, elapsedMs, lookupsDelta, fastDelta, blockingDelta, waitDeltaMs)), false);
        return (int) elapsedMs;
    }

    /** Debug: exercises the Stage 3 generic block-write mailbox mechanism
     *  (RegionMessage.BLOCK_WRITE -> post -> drain -> apply, step 4c of
     *  tickAllRegions()) end-to-end WITHOUT needing a live mob-AI/mod trigger.
     *  Runs on the main thread (this command's caller), so it does not exercise
     *  Level.setBlock()'s entry guard itself (simple boolean logic, low risk,
     *  verified by inspection) -- it validates the newer/riskier plumbing:
     *  message posting, draining, and the deferred apply landing correctly on
     *  a DIFFERENT region than the one that "sourced" the write. */
    private static int testStage3Write(CommandSourceStack src, int x, int y, int z) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        WorldRegion destination = dr.getGrid().getRegionForChunk(x >> 4, z >> 4);
        if (destination == null) {
            src.sendFailure(Component.literal("No region owns chunk (" + (x >> 4) + "," + (z >> 4) + ")"));
            return 0;
        }
        WorldRegion source = null;
        for (WorldRegion r : dr.getGrid().getAllRegions()) {
            if (r != destination) { source = r; break; }
        }
        if (source == null) {
            src.sendFailure(Component.literal("Need at least 2 active regions for this test"));
            return 0;
        }
        net.minecraft.world.level.block.state.BlockState before = sys.getOverworld().getBlockState(pos);
        boolean result = sys.nestworldDeferForeignBlockWrite(source, destination, pos,
                net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3, 0);
        final WorldRegion srcF = source, destF = destination;
        src.sendSuccess(() -> Component.literal(String.format(
                "teststage3write: posted BLOCK_WRITE at (%d,%d,%d), source=region#%d dest=region#%d, "
                        + "before=%s, computed-return=%b (will apply on main next tick's step 4c)",
                x, y, z, srcF.getId(), destF.getId(), before.getBlock(), result)), false);
        return 1;
    }

    private static int split(CommandSourceStack src, int id) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        WorldRegion region = findRegion(sys, id);
        if (region == null) {
            src.sendFailure(Component.literal("No active region with id " + id));
            return 0;
        }
        WorldRegion[] children = dr.getSplitManager().doSplit(region);
        if (children == null) {
            src.sendFailure(Component.literal("Region " + id + " cannot be split (already 1x1?)"));
            return 0;
        }
        // Manual splits are pinned so the idle-TPS hysteresis doesn't
        // immediately merge them back while someone is inspecting them.
        children[0].pinned = true;
        children[1].pinned = true;
        src.sendSuccess(() -> Component.literal(
                "Split #" + id + " -> #" + children[0].getId() + " + #" + children[1].getId()
                + " (pinned — won't auto-merge; use /nestworld merge)"), true);
        return 1;
    }

    private static int merge(CommandSourceStack src, int idA, int idB) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        WorldRegion a = findRegion(sys, idA);
        WorldRegion b = findRegion(sys, idB);
        if (a == null || b == null) {
            src.sendFailure(Component.literal("Region not found: " + (a == null ? idA : idB)));
            return 0;
        }
        WorldRegion merged = dr.getSplitManager().doMerge(a, b);
        if (merged == null) {
            src.sendFailure(Component.literal(
                    "Regions " + idA + " and " + idB + " are not siblings in the BSP tree"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Merged #" + idA + " + #" + idB + " -> #" + merged.getId()), true);
        return 1;
    }

    /** NestWorld: writes a full registry id/key/value snapshot (see
     *  {@link net.minecraftforge.registries.RegistryManager#nestworldDumpAll}) to
     *  {@code <serverDir>/<relPath>}, callable any time from RCON/console -- no
     *  reboot, no log4j marker configuration needed. Meant as a "ground truth"
     *  reference: diff a snapshot taken before a patch that touches
     *  block/registration code against one taken after, on the same world, to
     *  confirm registry ids stayed identical (world-save id persistence risk --
     *  see project memory registry-bake-parallel-init-cache.md). */
    private static int dumpRegistries(CommandSourceStack src, String relPath) {
        java.io.File serverDir = src.getServer().getServerDirectory();
        java.io.File file = new java.io.File(serverDir, relPath);
        // NestWorld: full-core-audit finding (2026-08-27) -- relPath is a raw greedyString with
        // no containment check; a "../../etc/..."-style path escaped the server directory
        // entirely (arbitrary file write). OP-gated already, but a materially bigger blast
        // radius than this diagnostic command's own intent warrants defense in depth.
        try {
            String serverCanon = serverDir.getCanonicalPath() + java.io.File.separator;
            String fileCanon = file.getCanonicalPath();
            if (!fileCanon.startsWith(serverCanon)) {
                src.sendFailure(Component.literal("Path escapes the server directory: " + relPath));
                return 0;
            }
        } catch (java.io.IOException e) {
            src.sendFailure(Component.literal("Failed to resolve path: " + e));
            return 0;
        }
        java.io.File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(file))) {
            net.minecraftforge.registries.RegistryManager.nestworldDumpAll(out);
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to dump registries: " + e));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Registry snapshot written to " + file.getAbsolutePath()), true);
        return 1;
    }

    private static int listPins(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldPins p = NestworldRegionSystem.get().getPins();
        var entityPins = p.list();
        var bePins = p.beList();
        var modPinsEarly = p.modList();
        if (entityPins.isEmpty() && bePins.isEmpty() && modPinsEarly.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No pins (all entities and block entities tick on region threads)."), false);
            return 0;
        }
        if (!entityPins.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    entityPins.size() + " pinned entity type(s) — tick on main thread:"), false);
            for (String id : entityPins) src.sendSuccess(() -> Component.literal("  " + id), false);
        }
        if (!bePins.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    bePins.size() + " pinned block-entity type(s) — tick on main thread:"), false);
            for (String id : bePins) src.sendSuccess(() -> Component.literal("  " + id), false);
        }
        if (!modPinsEarly.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    modPinsEarly.size() + " pinned mod(s) — all their entities/BEs tick on main:"), false);
            for (String ns : modPinsEarly) src.sendSuccess(() -> Component.literal("  " + ns), false);
        }
        return entityPins.size() + bePins.size() + modPinsEarly.size();
    }

    private static int pinMod(CommandSourceStack src, String ns) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem.get().getPins().pinMod(ns.trim());
        src.sendSuccess(() -> Component.literal(
                "Pinned mod '" + ns.trim() + "' — all its entities and block entities tick "
                + "on main (takes effect next tick)."), true);
        return 1;
    }

    private static int unpinMod(CommandSourceStack src, String ns) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        boolean removed = NestworldRegionSystem.get().getPins().unpinMod(ns.trim());
        if (!removed) {
            src.sendFailure(Component.literal("Mod '" + ns.trim() + "' was not pinned"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Unpinned mod '" + ns.trim() + "' (resumes region-thread ticking)."), true);
        return 1;
    }

    private static int pin(CommandSourceStack src, String type) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        String err = NestworldRegionSystem.get().getPins().pin(type.trim());
        if (err != null) {
            src.sendFailure(Component.literal(err));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Pinned " + type.trim() + " to main-thread ticking (takes effect next tick)."), true);
        return 1;
    }

    private static int unpin(CommandSourceStack src, String type) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        boolean removed = NestworldRegionSystem.get().getPins().unpin(type.trim());
        if (!removed) {
            src.sendFailure(Component.literal(type.trim() + " was not pinned"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Unpinned " + type.trim() + " (resumes region-thread ticking next boundary scan)."), true);
        return 1;
    }

    /**
     * NestWorld Inspector §4 (docs/NESTWORLD_INSPECTOR_SPEC.md): combined block/fluid-tick +
     * entity-density hotspot ranking, computed fresh on every call (no continuous tracking cost
     * between calls). Caches the result for {@code /nestworld goto hotspot <id>} to reference.
     */
    private static int hotspots(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        java.util.List<HotspotDetector.Hotspot> found = HotspotDetector.compute(NestworldRegionSystem.get(), 10);
        if (found.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No hotspots detected right now — load is flat."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("TOP HOTSPOTS\n");
        for (HotspotDetector.Hotspot h : found) {
            sb.append(String.format("#%d region=#%s heat=%.1f (block/fluid=%.1f, entities=%.1f)\n",
                    h.rank(), h.region() != null ? String.valueOf(h.region().getId()) : "?",
                    h.totalHeat(), h.blockTickHeat(), h.entityHeat()));
            if (!h.topEntityTypes().isEmpty()) {
                StringBuilder types = new StringBuilder("   dominant: ");
                boolean first = true;
                for (var e : h.topEntityTypes()) {
                    if (!first) types.append(", ");
                    types.append(e.getKey()).append(" x").append(e.getValue());
                    first = false;
                }
                sb.append(types).append('\n');
            } else if (h.blockTickHeat() > 0) {
                sb.append("   dominant: block/fluid ticks\n");
            }
            sb.append(String.format("   at (%d, %d, %d) — chunk (%d, %d)\n",
                    h.position().getX(), h.position().getY(), h.position().getZ(),
                    h.chunk().x, h.chunk().z));
            if (h.region() != null) {
                sb.append(String.format("   region avg cost: %.1fms\n", h.region().getAvgTickMs()));
            }
        }
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return found.size();
    }

    /** "Хто винен" -- топ модів за сумарним виміряним часом entity/block-entity тіку
     *  (точна інструментація, не семпл), накопичено з моменту старту сервера.
     *  Джерело: {@link TickAttribution}, хуки в RegionThread/NestworldDimensionRegion/Level. */
    private static int modLoad(CommandSourceStack src) {
        var entityTop = TickAttribution.topMods("entity", 15);
        var beTop = TickAttribution.topMods("blockentity", 15);
        if (entityTop.isEmpty() && beTop.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No tick attribution data yet — server just started or no entities/block-entities have ticked."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("MOD LOAD (cumulative measured tick time since boot)\n");
        sb.append("-- entities --\n");
        for (var m : entityTop) {
            sb.append(String.format("  %-24s total=%9.2fms count=%,-9d avg=%.4fms%n",
                    m.modId(), m.ms(), m.count(), m.count() == 0 ? 0.0 : m.ms() / m.count()));
        }
        sb.append("-- block entities --\n");
        for (var m : beTop) {
            sb.append(String.format("  %-24s total=%9.2fms count=%,-9d avg=%.4fms%n",
                    m.modId(), m.ms(), m.count(), m.count() == 0 ? 0.0 : m.ms() / m.count()));
        }
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static java.util.List<TickAttribution.ChunkCost> lastTickChunks = java.util.List.of();

    /** "В яких чанках" -- топ чанків за виміряним tick-часом (entity+block-entity разом),
     *  за поточне decayed вікно (~останні секунди, дивись TickAttribution.decayChunks()).
     *  На відміну від {@code /nestworld hotspots} (миттєвий density-скан), це РЕАЛЬНО
     *  виміряний час, накопичений з точних хуків, а не оцінка по кількості ентіті. */
    private static int tickChunks(CommandSourceStack src) {
        var top = TickAttribution.topChunks(15);
        lastTickChunks = top;
        if (top.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No chunk tick-cost data in the current window."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("TOP TICK-COST CHUNKS (measured, current window)\n");
        int rank = 1;
        for (var c : top) {
            sb.append(String.format("#%d [%s] chunk (%d, %d) — %.2fms\n",
                    rank++, c.dimensionKey(), c.chunkX(), c.chunkZ(), c.ms()));
        }
        sb.append("Use /nestworld goto tickchunk <id> (overworld only) to teleport.\n");
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return top.size();
    }

    /** Teleport to a ranked entry from the most recent {@code /nestworld tickchunks} call.
     *  Overworld-only, same constraint as the rest of this file's goto commands
     *  ({@link #teleportTo} hardcodes {@code NestworldRegionSystem.getOverworld()}). */
    private static int gotoTickChunk(CommandSourceStack src, int id) throws CommandSyntaxException {
        if (id < 1 || id > lastTickChunks.size()) {
            src.sendFailure(Component.literal("No tick-chunk #" + id + " — run /nestworld tickchunks first"));
            return 0;
        }
        TickAttribution.ChunkCost c = lastTickChunks.get(id - 1);
        if (!"minecraft:overworld".equals(c.dimensionKey())) {
            src.sendFailure(Component.literal("Tick-chunk #" + id + " is in " + c.dimensionKey() + " — goto only supports the overworld"));
            return 0;
        }
        return gotoChunk(src, c.chunkX(), c.chunkZ());
    }

    /** NestWorld Inspector §5: teleport the admin to a specific ranked hotspot from the most
     *  recent {@code /nestworld hotspots} call. */
    private static int gotoHotspot(CommandSourceStack src, int id) throws CommandSyntaxException {
        HotspotDetector.Hotspot h = HotspotDetector.get(id);
        if (h == null) {
            src.sendFailure(Component.literal("No hotspot #" + id + " — run /nestworld hotspots first"));
            return 0;
        }
        return teleportTo(src, h.position(), "hotspot #" + id);
    }

    /** NestWorld Inspector §5: teleport to the most interesting spot inside a region (its hottest
     *  chunk right now, or a live entity, or a heightmap-safe geometric fallback). */
    private static int gotoRegion(CommandSourceStack src, int id) throws CommandSyntaxException {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        NestworldDimensionRegion dr = overworldRegion(sys);
        WorldRegion region = findRegion(sys, id);
        if (region == null) {
            src.sendFailure(Component.literal("No active region with id " + id));
            return 0;
        }
        BlockPos pos = HotspotDetector.bestPositionInRegion(sys, region);
        return teleportTo(src, pos, "region #" + id);
    }

    /** NestWorld Inspector §5: teleport to a chunk's center, landing on top of terrain. */
    private static int gotoChunk(CommandSourceStack src, int x, int z) throws CommandSyntaxException {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        ServerLevel level = NestworldRegionSystem.get().getOverworld();
        int bx = (x << 4) + 8, bz = (z << 4) + 8;
        int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        return teleportTo(src, new BlockPos(bx, by, bz), "chunk (" + x + ", " + z + ")");
    }

    /** NestWorld Inspector §5: teleport to a live entity's current position. Reads the entity's
     *  position directly (not via a region's EntitySnapshot) since this targets an arbitrary,
     *  admin-chosen UUID that may not be in any recently-computed hotspot's cached data — the same
     *  category of live cross-thread read every vanilla entity-targeting command already performs
     *  in this environment (command dispatch doesn't check region ownership), not a new hazard. */
    /** Phase 9.2 (#27) test-only entry point: exercises {@code EntityMutationDispatcher.dispatch}
     *  directly with {@link EntityMutationOp.Kill}/{@link EntityMutationOp.Discard} for foreign-
     *  entity live testing, since neither op has a real in-game call site yet. Always dispatched
     *  from the MAIN thread (RCON/command execution), so this exercises the same
     *  redirectFromMainThread-shaped path Player.attack() uses. */
    private static int testMutate(CommandSourceStack src, java.util.UUID uuid, EntityMutationOp op) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        net.minecraft.world.entity.Entity target = NestworldRegionSystem.get().getOverworld().getEntity(uuid);
        if (target == null) {
            src.sendFailure(Component.literal("No live entity with UUID " + uuid));
            return 0;
        }
        EntityMutationDispatcher.dispatch(target, op);
        src.sendSuccess(() -> Component.literal("Dispatched " + op.getClass().getSimpleName() + " for " + uuid), true);
        return 1;
    }

    private static int gotoEntity(CommandSourceStack src, java.util.UUID uuid) throws CommandSyntaxException {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        net.minecraft.world.entity.Entity target = NestworldRegionSystem.get().getOverworld().getEntity(uuid);
        if (target == null) {
            src.sendFailure(Component.literal("No live entity with UUID " + uuid));
            return 0;
        }
        return teleportTo(src, target.blockPosition(), "entity " + uuid);
    }

    private static int teleportTo(CommandSourceStack src, BlockPos pos, String label) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel overworld = NestworldRegionSystem.get().getOverworld();
        player.teleportTo(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.literal("Teleported to " + label + " at " + pos.toShortString()), true);
        return 1;
    }

    private static WorldRegion findRegion(NestworldRegionSystem sys, int id) {
        for (WorldRegion r : overworldRegion(sys).getTree().getActiveRegions()) {
            if (r.getId() == id) return r;
        }
        return null;
    }
}
