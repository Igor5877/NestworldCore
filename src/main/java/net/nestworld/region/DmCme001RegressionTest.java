package net.nestworld.region;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/**
 * DISTANCEMANAGER-CME-001 TEST-ONLY regression test ({@code /nestworld test dmcme001 ...}).
 *
 * <p>Drives DistanceManager.runAllUpdates through the server's own chunk-cache pump
 * ({@link ServerChunkCache#pollTask()} -> MainThreadExecutor.pollTask -> runDistanceManagerUpdates) right
 * after a ticket removal makes a block of chunks demote, with a re-entrant getChunk(load=true) from inside
 * ChunkHolder.updateFutures (the live root-cause shape), and checks:
 * no CME; every pending insertion of a tracked holder gets exactly one updateFutures (no double processing,
 * no drop); holders re-added during a pass are processed by a later pass; the tracked area drains within
 * 2 passes after its last insertion (unbudgeted branches).
 *
 * <p>Scenarios: {@code diode} (repeater at the edge chunk E, input in neighbour N; fixed code must NOT
 * re-enter because the seed is skipped on demotion), {@code diodeseed} (seed-on-demotion forced back on:
 * natural diode re-entry path, fixed loop must absorb it), {@code inject} (deterministic: a probe calls
 * getChunkFuture(FULL, true) on a demoting chunk from inside the first tracked updateFutures),
 * {@code leave} (temporary EntityLeaveLevelEvent listener calls level.getChunk on a demoted neighbour).
 * Branches: {@code unlimited} / {@code tiered} (budget override 0 = unbounded) / {@code budget2}
 * (tiered, override 2) / {@code jvm} (production branch selection from -Dnestworld.chunkGenBudget) /
 * {@code both} (unlimited+tiered). Mode {@code legacy} = negative control (pre-fix loops).
 *
 * <p>Runs on MAIN only (ServerTickEvent END state machine: setup waits across ticks, the measured part is
 * one synchronous block inside one tick). Uses its own ticket types (no persistent forced-chunk data),
 * removes the placed blocks / armor stand / tickets at the end. Results are logged with {@code [DMCME001]}
 * and retrievable via {@code /nestworld test dmcme001 status} (RCON output of a multi-tick run is lost).
 * Never run the {@code legacy} mode on a server whose world matters: a CME mid-demotion can leave
 * entity-section state inconsistent.
 */
public final class DmCme001RegressionTest {
    private static final Logger LOG = LogManager.getLogger("NestWorld/DIAG");
    private static final String TAG = "[DMCME001] ";
    private static final TicketType<ChunkPos> FORCE =
            TicketType.create("nestworld_dmcme001_force", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> HOLD =
            TicketType.create("nestworld_dmcme001_hold", Comparator.comparingLong(ChunkPos::toLong));
    static final int DEF_CX = 20000, DEF_CZ = 20000; // chunk coords (block 320000)
    private static final int SQUARE = 2;              // forced square side (chunks)
    private static final int HALF = 16 + SQUARE;      // tracked box half-size (level-44 reach of a level-31 ticket + margin)
    private static final int SPACING = 64;            // chunks between sub-runs (fresh, cold area each)
    private static final int Y = 300;                 // repeater y (air in a normal overworld)
    private static final int READY_TIMEOUT_TICKS = 2400, COLD_TIMEOUT_TICKS = 600;
    private static final int MAX_PASSES_UNBUDGETED = 400, MAX_PASSES_BUDGETED = 20000;

    private static volatile List<String> LAST_RESULTS = List.of("no run yet");
    private static Run RUNNING; // main thread only

    private DmCme001RegressionTest() {}

    // ------------------------------------------------------------------ command

    static LiteralArgumentBuilder<CommandSourceStack> buildNode() {
        return Commands.literal("dmcme001")
                .executes(c -> start(c.getSource(), "all", "both", "fixed", DEF_CX, DEF_CZ))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.argument("scenario", StringArgumentType.word())
                        .executes(c -> start(c.getSource(), s(c, "scenario"), "both", "fixed", DEF_CX, DEF_CZ))
                        .then(Commands.argument("branch", StringArgumentType.word())
                                .executes(c -> start(c.getSource(), s(c, "scenario"), s(c, "branch"), "fixed", DEF_CX, DEF_CZ))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(c -> start(c.getSource(), s(c, "scenario"), s(c, "branch"), s(c, "mode"), DEF_CX, DEF_CZ))
                                        .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                        .executes(c -> start(c.getSource(), s(c, "scenario"), s(c, "branch"), s(c, "mode"),
                                                                IntegerArgumentType.getInteger(c, "cx"), IntegerArgumentType.getInteger(c, "cz"))))))));
    }

    /** Overlay route only (DmCmeOverlay hooks NestworldCommand.register RETURN to call this): brigadier merges
     *  this "nestworld" literal into the existing node, adding {@code /nestworld test dmcme001}. A source build
     *  uses the one-line NestworldCommand change instead and never calls this. */
    public static void registerStandalone(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nestworld").requires(src -> src.hasPermission(2))
                .then(Commands.literal("test").then(buildNode())));
    }

    private static String s(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, String n) {
        return StringArgumentType.getString(c, n);
    }

    private static int status(CommandSourceStack src) {
        for (String l : LAST_RESULTS) src.sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    private static int start(CommandSourceStack src, String scenario, String branch, String mode, int cx, int cz) {
        MinecraftServer server = src.getServer();
        if (!server.isSameThread()) { src.sendFailure(Component.literal("must run on MAIN")); return 0; }
        if (RUNNING != null) { src.sendFailure(Component.literal("dmcme001 already running")); return 0; }
        List<String> scen = switch (scenario) {
            case "all" -> List.of("diode", "diodeseed", "inject", "leave");
            case "diode", "diodeseed", "inject", "leave" -> List.of(scenario);
            default -> null;
        };
        List<String> br = switch (branch) {
            case "both" -> List.of("unlimited", "tiered");
            case "unlimited", "tiered", "budget2", "jvm" -> List.of(branch);
            default -> null;
        };
        if (scen == null || br == null || !(mode.equals("fixed") || mode.equals("legacy"))) {
            src.sendFailure(Component.literal("usage: /nestworld test dmcme001 [status | <all|diode|diodeseed|inject|leave> "
                    + "[both|unlimited|tiered|budget2|jvm] [fixed|legacy] [cx cz]]"));
            return 0;
        }
        Run run = new Run(server, src);
        int k = 0;
        for (String sc : scen) for (String b : br) run.queue.add(new Sub(sc, b, mode.equals("legacy"), cx + SPACING * k++, cz));
        if (scenario.equals("all") && branch.equals("both")) run.queue.add(new Sub("inject", "budget2", mode.equals("legacy"), cx + SPACING * k, cz));
        RUNNING = run;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, run.tickListener);
        run.line("START scenario=" + scenario + " branch=" + branch + " mode=" + mode + " subruns=" + run.queue.size()
                + " base=(" + cx + "," + cz + ") CHUNK_GEN_BUDGET(jvm)=" + NestworldTuning.CHUNK_GEN_BUDGET);
        src.sendSuccess(() -> Component.literal("dmcme001 started (" + run.queue.size()
                + " sub-runs, multi-tick). Tail log for [DMCME001] or run /nestworld test dmcme001 status"), false);
        return 1;
    }

    // ------------------------------------------------------------------ run / sub-run state machine

    private static final class Run {
        final MinecraftServer server;
        final CommandSourceStack src;
        final Deque<Sub> queue = new ArrayDeque<>();
        final List<String> out = new ArrayList<>();
        final Consumer<TickEvent.ServerTickEvent> tickListener = this::onTick;
        Sub cur;
        int pass, fail, inconclusive, negDetected, negMissed;

        Run(MinecraftServer server, CommandSourceStack src) { this.server = server; this.src = src; }

        void line(String l) {
            out.add(l);
            LOG.info(TAG + l);
        }

        void onTick(TickEvent.ServerTickEvent ev) {
            if (ev.phase != TickEvent.Phase.END) return;
            try {
                if (cur == null) {
                    cur = queue.poll();
                    if (cur == null) { finish(); return; }
                    cur.begin(this);
                }
                if (cur.step(this)) cur = null;
            } catch (Throwable t) {
                line("FAIL harness exception in " + (cur == null ? "?" : cur.name()) + ": " + t);
                LOG.error(TAG + "harness exception", t);
                fail++;
                if (cur != null) cur.emergencyCleanup(this);
                cur = null;
            }
        }

        void finish() {
            MinecraftForge.EVENT_BUS.unregister(tickListener);
            line("SUMMARY pass=" + pass + " fail=" + fail + " inconclusive=" + inconclusive
                    + " negctrlDetected=" + negDetected + " negctrlNotDetected=" + negMissed
                    + " => " + (fail == 0 && negMissed == 0 ? (inconclusive == 0 ? "PASS" : "PASS-WITH-INCONCLUSIVE") : "FAIL"));
            LAST_RESULTS = List.copyOf(out);
            RUNNING = null;
            for (String l : out) src.sendSuccess(() -> Component.literal(l), false);
        }
    }

    private enum Phase { COLD, READY, SETTLE, DONE }

    private static final class Sub {
        final String scenario, branch;
        final boolean legacy;
        final int cx, cz;
        final ChunkPos e, n, l, q;   // edge (held FULL), diode input neighbour, stand chunk, leave-listener target
        final List<ChunkPos> forced = new ArrayList<>();
        final LongPredicate box;
        ServerLevel level;
        ServerChunkCache cache;
        DistanceManager dm;
        Phase phase = Phase.COLD;
        int ticks;
        UUID stand;
        ArmorStand standEntity; // kept: level.getEntity(uuid) no longer sees it once its chunk is HIDDEN
        BlockPos repeaterPos;
        boolean ticketsAdded;

        Sub(String scenario, String branch, boolean legacy, int cx, int cz) {
            this.scenario = scenario; this.branch = branch; this.legacy = legacy; this.cx = cx; this.cz = cz;
            for (int a = 0; a < SQUARE; a++) for (int b = 0; b < SQUARE; b++) forced.add(new ChunkPos(cx - a, cz + b));
            e = new ChunkPos(cx + 1, cz);          // level 32 (BLOCK_TICKING) with FORCE, 33 (FULL, HOLD) without
            n = new ChunkPos(cx + 2, cz);          // level 33 with FORCE, 34 after
            l = new ChunkPos(cx, cz);              // level 31 (ENTITY_TICKING) -> 34 (INACCESSIBLE)
            q = new ChunkPos(cx - SQUARE, cz);     // level 32 -> 35+ ; never re-promoted by anything else
            box = p -> Math.abs(ChunkPos.getX(p) - cx) <= HALF && Math.abs(ChunkPos.getZ(p) - cz) <= HALF;
        }

        String name() { return scenario + "/" + branch + "/" + (legacy ? "legacy" : "fixed") + "@(" + cx + "," + cz + ")"; }

        void begin(Run run) {
            level = run.server.overworld();
            cache = level.getChunkSource();
            dm = cache.chunkMap.getDistanceManager();
            int view = run.server.getPlayerList().getViewDistance();
            for (ServerPlayer p : level.players()) {
                ChunkPos pc = p.chunkPosition();
                if (Math.max(Math.abs(pc.x - cx), Math.abs(pc.z - cz)) <= HALF + view + 2) {
                    throw new IllegalStateException("player " + p.getGameProfile().getName() + " too close to " + name());
                }
            }
            run.line("BEGIN " + name());
        }

        boolean coldBox() {
            for (int x = cx - HALF; x <= cx + HALF; x++)
                for (int z = cz - HALF; z <= cz + HALF; z++)
                    if (cache.getChunkNow(x, z) != null) return false;
            return true;
        }

        /** @return true when the sub-run is finished. */
        boolean step(Run run) {
            ticks++;
            switch (phase) {
                case COLD -> {
                    if (!coldBox()) {
                        if (ticks > COLD_TIMEOUT_TICKS) { run.line("FAIL " + name() + " area not cold (loaded chunks in box); pick other cx/cz"); run.fail++; return true; }
                        return false;
                    }
                    // N, Q, every box chunk verified NOT loaded before any ticket of ours exists.
                    cache.addRegionTicket(HOLD, e, 0, e);                   // E level 33
                    for (ChunkPos f : forced) cache.addRegionTicket(FORCE, f, 2, f); // level 31 ("forceload" w/o saved data)
                    ticketsAdded = true;
                    phase = Phase.READY; ticks = 0;
                    return false;
                }
                case READY -> {
                    if (!ready()) {
                        if (ticks > READY_TIMEOUT_TICKS) { run.line("FAIL " + name() + " chunks never reached BLOCK/ENTITY_TICKING"); run.fail++; cleanup(run); return true; }
                        return false;
                    }
                    prepare(run);
                    phase = Phase.SETTLE; ticks = 0;
                    return false;
                }
                case SETTLE -> {
                    if (ticks < 3) return false;
                    if (stand != null && level.getEntity(stand) == null) {
                        run.line("FAIL " + name() + " armor stand vanished before demotion"); run.fail++; cleanup(run); return true;
                    }
                    execute(run);
                    cleanup(run);
                    phase = Phase.DONE;
                    return true;
                }
                default -> { return true; }
            }
        }

        boolean ready() {
            LevelChunk ce = cache.getChunkNow(e.x, e.z), cl = cache.getChunkNow(l.x, l.z), cn = cache.getChunkNow(n.x, n.z);
            return ce != null && cl != null && cn != null
                    && ce.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
                    && cl.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING);
        }

        void prepare(Run run) {
            if (scenario.startsWith("diode")) {
                // Repeater on E's east edge, FACING=EAST: DiodeBlock.getInputSignal reads pos.relative(FACING) = N.
                repeaterPos = new BlockPos(e.getMinBlockX() + 15, Y, e.getMinBlockZ() + 8);
                level.setBlock(repeaterPos.below(), Blocks.STONE.defaultBlockState(), 2);
                BlockState rep = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST);
                level.setBlock(repeaterPos, rep, 2);
                if (!level.getBlockState(repeaterPos).is(Blocks.REPEATER)) throw new IllegalStateException("repeater not placed");
            } else if (scenario.equals("leave")) {
                ArmorStand as = new ArmorStand(level, l.getMiddleBlockX() + 0.5, Y, l.getMiddleBlockZ() + 0.5);
                as.setNoGravity(true);
                as.setInvisible(true);
                as.setInvulnerable(true);
                as.addTag("nw_dmcme001");
                if (!level.addFreshEntity(as)) throw new IllegalStateException("armor stand not added");
                stand = as.getUUID();
                standEntity = as;
            }
        }

        void execute(Run run) {
            Probe probe = new Probe(box);
            // Pre-drain: no tracked holder may be pending when accounting starts.
            for (int i = 0; i < 200 && dm.nestworldTestPendingIn(box) > 0; i++) cache.pollTask();
            int pre = dm.nestworldTestPendingIn(box);
            if (pre > 0) { run.line("FAIL " + name() + " pre-drain left " + pre + " pending"); run.fail++; return; }
            if (cache.getChunkNow(q.x, q.z) == null) { run.line("FAIL " + name() + " Q not loaded at start"); run.fail++; return; }

            int savedOverride = NestworldLiveTuning.chunkGenBudgetOverride;
            long savedPump = NestworldLiveTuning.chunkPromotionPumpBudgetMsOverride;
            long deadlineHits0 = dm.nestworldPumpDeadlineHits();
            boolean budgeted;
            switch (branch) {
                case "unlimited" -> { DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 1; budgeted = false; }
                case "tiered" -> { // unbounded admission AND no pump deadline: every pending holder is admitted each pass
                    DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 2;
                    NestworldLiveTuning.chunkGenBudgetOverride = 0;
                    NestworldLiveTuning.chunkPromotionPumpBudgetMsOverride = 0;
                    budgeted = false;
                }
                case "budget2" -> { DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 2; NestworldLiveTuning.chunkGenBudgetOverride = 2; budgeted = true; }
                default -> { DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 0; budgeted = NestworldLiveTuning.effectiveChunkGenBudget() > 0; }
            }
            DistanceManager.NESTWORLD_TEST_LEGACY_LOOP = legacy;
            ChunkMap.NESTWORLD_TEST_SEED_ON_DEMOTION = scenario.equals("diodeseed");
            if (scenario.equals("inject")) {
                // From inside the first outer updateFutures: the same call the live stacks show
                // (getChunkFutureMainThread + managedBlock) on a chunk that just demoted (L: 31 -> 34).
                probe.inject = () -> cache.getChunkFuture(l.x, l.z, ChunkStatus.FULL, true);
            }
            final boolean[] leaveFired = {false};
            Consumer<EntityLeaveLevelEvent> leave = ev -> {
                if (leaveFired[0] || stand == null || !stand.equals(ev.getEntity().getUUID())) return;
                leaveFired[0] = true;
                probe.leaveDepth = probe.depthNow;
                ev.getLevel().getChunk(q.x, q.z); // getChunk(FULL, load=true) on the demoted neighbour Q
            };
            if (scenario.equals("leave")) MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, leave);
            // Take the box out of the per-tick fastpath snapshot (as if those chunks were still loading),
            // so getChunk on N/Q/L reaches getChunkFutureMainThread like in the incident. Region threads
            // keep their snapshot for every chunk outside the (player-free) box.
            Long2ObjectOpenHashMap<LevelChunk> snap = new Long2ObjectOpenHashMap<>(cache.nestworldLoadedFull);
            it.unimi.dsi.fastutil.longs.LongArrayList inBox = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (it.unimi.dsi.fastutil.longs.LongIterator it = snap.keySet().iterator(); it.hasNext(); ) {
                long k = it.nextLong();
                if (box.test(k)) inBox.add(k);
            }
            for (int i = 0; i < inBox.size(); i++) snap.remove(inBox.getLong(i));
            cache.nestworldLoadedFull = snap;

            int cme = 0, other = 0, passes = 0, drainPass = -1, quiet = 0;
            String firstThrow = null;
            int maxPasses = budgeted ? MAX_PASSES_BUDGETED : MAX_PASSES_UNBUDGETED;
            DistanceManager.NESTWORLD_TEST_PROBE = probe;
            try {
                for (ChunkPos f : forced) cache.removeRegionTicket(FORCE, f, 2, f);
                for (int p = 1; p <= maxPasses; p++) {
                    passes = p;
                    probe.pass = p;
                    dm.nestworldResetGenBudget(); // one pass == one tick's admission budget
                    try {
                        cache.pollTask();
                    } catch (Throwable t) {
                        if (isCme(t)) cme++; else other++;
                        if (firstThrow == null) { firstThrow = t.toString(); LOG.warn(TAG + name() + " pass " + p + " threw", t); }
                    }
                    if (dm.nestworldTestPendingIn(box) == 0) {
                        if (drainPass < 0) drainPass = p;
                        if (++quiet >= 3) break;
                    } else { drainPass = -1; quiet = 0; }
                }
            } finally {
                DistanceManager.NESTWORLD_TEST_PROBE = null;
                DistanceManager.NESTWORLD_TEST_LEGACY_LOOP = false;
                DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 0;
                ChunkMap.NESTWORLD_TEST_SEED_ON_DEMOTION = false;
                NestworldLiveTuning.chunkGenBudgetOverride = savedOverride;
                NestworldLiveTuning.chunkPromotionPumpBudgetMsOverride = savedPump;
                if (scenario.equals("leave")) MinecraftForge.EVENT_BUS.unregister(leave);
                cache.nestworldRefreshLoadedChunks();
            }

            int dropped = probe.droppedReinserted, stuck = 0;
            for (long p : probe.open) {
                ChunkHolder h = probe.holders.get(p);
                if (h != null && dm.nestworldTestIsPending(h)) stuck++; else dropped++;
            }
            int extra = drainPass < 0 ? -1 : drainPass - Math.max(1, probe.lastInsertPass);
            int globalPending = dm.nestworldPromotionPending();
            String stats = "ins=" + probe.insertions + " merges=" + probe.merges + " upd=" + probe.updates
                    + " spurious=" + probe.spurious + " dropped=" + dropped + " stuck=" + stuck
                    + " reentries=" + probe.reentries + " insInsideUpdate=" + probe.insertsInsideUpdate
                    + " maxDepth=" + probe.maxDepth + " injected=" + probe.injected
                    + (scenario.equals("leave") ? " leaveFired=" + leaveFired[0] + "@lastUpdDepth" + probe.leaveDepth : "")
                    + " cme=" + cme + " otherThrow=" + other + (firstThrow == null ? "" : " first=" + firstThrow)
                    + " passes=" + passes + " drainPass=" + drainPass + " lastInsPass=" + probe.lastInsertPass
                    + " extraPasses=" + extra + " globalPending=" + globalPending + " budgeted=" + budgeted
                    + " pumpDeadlineHits=" + (dm.nestworldPumpDeadlineHits() - deadlineHits0);

            if (legacy) {
                boolean detected = cme > 0 || probe.spurious > 0 || dropped > 0;
                if (detected) run.negDetected++; else run.negMissed++;
                run.line((detected ? "NEGCTRL DETECTED " : "NEGCTRL NOT-DETECTED ") + name() + " " + stats);
                return;
            }
            List<String> fails = new ArrayList<>();
            if (cme > 0) fails.add("CME");
            if (other > 0) fails.add("throwable");
            if (probe.insertions < 3) fails.add("setup:<3 pending insertions");
            if (probe.updates != probe.insertions || probe.spurious > 0) fails.add("accounting(upd!=ins or double-processing)");
            if (dropped > 0) fails.add("dropped");
            if (stuck > 0 || drainPass < 0) fails.add("not-drained");
            if (!budgeted && (extra < 0 || extra > 2)) fails.add("drain>2 extra passes");
            if (run.server.getPlayerCount() == 0 && globalPending != 0) fails.add("global sets not empty");
            // diode (fixed): the seed is skipped on demotion, so the repeater must not cause re-entry. Any
            // re-entry here comes from another source (e.g. a mod's EntityLeaveLevelEvent handler for a mob
            // in the demoting chunks) -> INCONCLUSIVE for the seed-skip claim; compare with diodeseed.
            boolean inconclusive = fails.isEmpty()
                    && (scenario.equals("diode") ? probe.reentries != 0 : probe.reentries == 0);
            if (!fails.isEmpty()) { run.fail++; run.line("FAIL " + name() + " " + fails + " " + stats); }
            else if (inconclusive) {
                run.inconclusive++;
                run.line((scenario.equals("diode") ? "INCONCLUSIVE(re-entry from a non-seed source) " : "INCONCLUSIVE(no re-entry happened) ")
                        + name() + " " + stats);
            }
            else { run.pass++; run.line("PASS " + name() + " " + stats); }
        }

        void cleanup(Run run) {
            try {
                if (standEntity != null && !standEntity.isRemoved()) standEntity.discard(); // not saved with the chunk
                if (repeaterPos != null) {
                    if (cache.getChunkNow(e.x, e.z) != null) {
                        level.setBlock(repeaterPos, Blocks.AIR.defaultBlockState(), 2);
                        level.setBlock(repeaterPos.below(), Blocks.AIR.defaultBlockState(), 2);
                    } else {
                        run.line("WARN " + name() + " E not loaded at cleanup; repeater+stone left at " + repeaterPos.toShortString());
                    }
                }
                if (ticketsAdded) {
                    for (ChunkPos f : forced) cache.removeRegionTicket(FORCE, f, 2, f); // no-op if already removed
                    cache.removeRegionTicket(HOLD, e, 0, e);
                    for (int i = 0; i < 20; i++) cache.pollTask();
                }
            } catch (Throwable t) {
                run.line("WARN " + name() + " cleanup threw " + t);
            }
            run.line("CLEANUP " + name() + " done (tag nw_dmcme001 marks any leftover stand)");
        }

        void emergencyCleanup(Run run) {
            DistanceManager.NESTWORLD_TEST_PROBE = null;
            DistanceManager.NESTWORLD_TEST_LEGACY_LOOP = false;
            DistanceManager.NESTWORLD_TEST_FORCE_BRANCH = 0;
            ChunkMap.NESTWORLD_TEST_SEED_ON_DEMOTION = false;
            cleanup(run);
        }
    }

    private static boolean isCme(Throwable t) {
        for (int i = 0; t != null && i < 16; i++, t = t.getCause()) {
            if (t instanceof ConcurrentModificationException) return true;
            for (Throwable s : t.getSuppressed()) if (s instanceof ConcurrentModificationException) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ accounting probe

    /** Pending-episode accounting per chunk position inside the tracked box. An episode opens when
     *  setLevel inserts a holder that was in neither set, and must be closed by exactly one updateFutures. */
    static final class Probe implements DistanceManager.NestworldTestProbe {
        final LongPredicate tracked;
        final LongOpenHashSet open = new LongOpenHashSet();
        final Long2ObjectOpenHashMap<ChunkHolder> holders = new Long2ObjectOpenHashMap<>();
        int pass, lastInsertPass, insertions, merges, updates, spurious, reentries, insertsInsideUpdate,
                droppedReinserted, maxDepth, depthNow, leaveDepth = -1;
        Runnable inject;
        boolean injected;

        Probe(LongPredicate tracked) { this.tracked = tracked; }

        @Override public void onPending(ChunkHolder h, boolean wasPending, int depth) {
            long p = h.getPos().toLong();
            if (!tracked.test(p)) return;
            holders.put(p, h);
            lastInsertPass = pass;
            if (depth > 0) insertsInsideUpdate++;
            if (wasPending) { merges++; return; }
            if (!open.add(p)) droppedReinserted++; // previous episode left the sets without an update
            insertions++;
        }

        @Override public void beforeUpdate(ChunkHolder h, int depth) {
            depthNow = depth;
            maxDepth = Math.max(maxDepth, depth);
            long p = h.getPos().toLong();
            if (!tracked.test(p)) return;
            if (open.remove(p)) updates++; else spurious++;
            if (inject != null && !injected && depth == 1) {
                injected = true;
                inject.run();
            }
        }

        @Override public void onRunAllUpdates(int depth) {
            if (depth > 0) reentries++;
        }
    }
}
