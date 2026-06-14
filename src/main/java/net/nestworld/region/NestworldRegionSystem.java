package net.nestworld.region;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central singleton that owns all NestWorld region-sharding subsystems.
 *
 * Lifecycle:
 *   {@link #onServerAboutToStart} → creates all subsystems for the overworld.
 *   Per-tick: {@link #tickAllRegions()} is called from the patched MinecraftServer
 *             tick loop instead of the vanilla {@code serverlevel.tick()} call.
 *   {@link #onServerStopping} → shuts down all RegionThreads cleanly.
 *
 * Access pattern: static singleton via {@link #get()}.  The instance is
 * valid from ServerAboutToStartEvent until ServerStoppedEvent.
 */
public class NestworldRegionSystem {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionSystem");

    /**
     * Half-span of the initial single region in chunks. Covers the entire
     * playable world (world border is ±30,000,000 blocks = ±1,875,000 chunks)
     * so every entity always belongs to some region; splits subdivide from here.
     */
    private static final int INITIAL_REGION_HALF_SPAN = 1_875_000;

    private static NestworldRegionSystem INSTANCE;

    // --- Subsystems ---
    private WorldGrid grid;
    private RegionTree tree;
    private RegionThreadPool pool;
    private RegionSplitManager splitManager;
    private BoundaryManager boundaryManager;
    private BoundarySignalQueue signalQueue;
    private BoundaryEntityTransfer entityTransfer;
    private CrossRegionCapabilityBus capabilityBus;
    private RegionChunkView chunkView;

    /** Per-chunk block-tick load signal, feeding the load-aware split scorer. */
    private final BlockTickHeat blockTickHeat = new BlockTickHeat();

    /** Entity types pinned to main-thread ticking (mod-compat escape hatch). */
    private final NestworldPins pins = new NestworldPins();

    private ServerLevel overworld;
    private MinecraftServer server;

    private NestworldRegionSystem() {}

    public static NestworldRegionSystem get() {
        if (INSTANCE == null) throw new IllegalStateException("NestworldRegionSystem not initialised");
        return INSTANCE;
    }

    public static boolean isInitialised() { return INSTANCE != null; }

    // -----------------------------------------------------------------------
    // Forge event hooks (registered in NestworldMod)
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        INSTANCE = new NestworldRegionSystem();
        INSTANCE.init(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        NestworldCommand.register(event.getDispatcher());
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    private void init(MinecraftServer server) {
        LOGGER.info("Initialising NestWorld region sharding system…");

        this.server = server;
        overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.error("Overworld not available — region system disabled");
            return;
        }

        grid           = new WorldGrid();
        pool           = new RegionThreadPool(overworld);

        // Boot straight into the saved layout if there is one; otherwise start
        // with a single whole-world region and let adaptive splits grow it.
        // The tree only registers regions in the grid here — threads are
        // spawned below, after every subsystem a RegionThread tick may touch
        // (boundary/ghost zones, entity transfer) has been constructed.
        CompoundTag saved = loadSavedLayout();
        boolean restored = saved != null;
        if (restored) {
            tree = new RegionTree(grid, saved);
        } else {
            int r = INITIAL_REGION_HALF_SPAN;
            tree = new RegionTree(grid, new WorldRegion(grid.nextId(), -r, -r, r, r));
        }

        pins.load(pinsFile());

        boundaryManager = new BoundaryManager(overworld, grid);
        signalQueue    = new BoundarySignalQueue(overworld);
        entityTransfer = new BoundaryEntityTransfer(overworld, grid, pins);
        capabilityBus  = new CrossRegionCapabilityBus(overworld, grid, boundaryManager);
        chunkView      = new RegionChunkView(grid, boundaryManager);
        splitManager   = new RegionSplitManager(tree, pool, blockTickHeat);

        java.util.List<WorldRegion> regions = tree.getActiveRegions();
        for (WorldRegion region : regions) pool.spawn(region);

        if (System.getenv("NESTWORLD_CUT_TEST") != null
                || Boolean.getBoolean("nestworld.cutTest")) {
            RegionSplitManager.selfTest();
        }

        if (restored) {
            LOGGER.info("NestWorld restored saved layout — {} region(s): {}",
                    regions.size(), regions);
        } else {
            LOGGER.info("NestWorld started — initial region: {}", regions.get(0));
        }
    }

    /**
     * Ticks every loaded entity whose type is pinned, on the main thread.
     * Mirrors the region-thread gate (skip removed/passengers, despawn check,
     * ticking-chunk gate) so a pinned entity behaves identically to vanilla —
     * just without the parallelism. Called only when pins are non-empty.
     */
    private void tickPinnedEntitiesOnMain() {
        // Snapshot: ticking can spawn/remove entities, mutating the live view.
        java.util.List<net.minecraft.world.entity.Entity> snapshot = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : overworld.getAllEntities()) snapshot.add(e);
        for (net.minecraft.world.entity.Entity entity : snapshot) {
            if (entity.isRemoved() || entity.isPassenger()) continue;
            if (!pins.isPinned(entity.getType())) continue;
            try {
                entity.checkDespawn();
                if (entity.isRemoved()) continue;
                if (!overworld.isPositionEntityTicking(entity.blockPosition())) continue;
                overworld.tickNonPassenger(entity);
            } catch (Throwable t) {
                LOGGER.warn("Pinned entity {} tick error: {}",
                        entity.getType().getDescriptionId(), t.toString());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Persistent region layout
    // -----------------------------------------------------------------------

    /** NBT file holding the saved BSP layout, under the world's data folder. */
    private Path layoutFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-regions.dat");
    }

    /** Text file listing entity-type ids pinned to main-thread ticking. */
    private Path pinsFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-pins.txt");
    }

    public NestworldPins getPins() { return pins; }

    /**
     * Reads the saved layout, or returns null if there is none / it is
     * unreadable (first boot, or a world that predates persistence). A corrupt
     * file must never block startup — we just fall back to a single region.
     */
    private CompoundTag loadSavedLayout() {
        File file = layoutFile().toFile();
        if (!file.isFile()) return null;
        try {
            CompoundTag tag = NbtIo.read(file);
            return (tag != null && tag.contains("root")) ? tag : null;
        } catch (Throwable t) {
            LOGGER.warn("Could not read saved region layout ({}) — starting fresh",
                    t.toString());
            return null;
        }
    }

    /** Writes the current BSP layout so the next boot starts already sharded. */
    private void saveLayout() {
        if (tree == null) return;
        try {
            File file = layoutFile().toFile();
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            NbtIo.write(tree.writeNbt(), file);
            lastSavedLayoutVersion = grid.getLayoutVersion();
            LOGGER.info("Saved region layout ({} region(s))",
                    tree.getActiveRegions().size());
        } catch (Throwable t) {
            LOGGER.warn("Could not save region layout: {}", t.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Per-tick entry point (called from patched MinecraftServer)
    // -----------------------------------------------------------------------

    /**
     * Replaces the vanilla {@code serverlevel.tick(hasTime)} call inside
     * {@code MinecraftServer.tickChildren()}.
     *
     * Execution order each game tick:
     *  1. Run overworld.tick() for global state (time, weather, chunk loading).
     *     Entity/block-entity ticking is skipped by the ServerLevel patch.
     *  2. Flush cross-boundary redstone signals from last tick.
     *  3. Reassign entities that crossed region boundaries.
     *  4. Parallel-tick all regions (region threads + barrier sync).
     *  5. Sync ghost zones for next tick's cross-region reads.
     *  6. Evaluate TPS and apply any pending split / merge operations.
     */
    // Phase timing accumulators (ns), logged every TIMING_LOG_INTERVAL ticks
    private static final int TIMING_LOG_INTERVAL = 200;
    private final long[] phaseNanos = new long[6];
    private int timedTicks = 0;

    // Self-test driven by env var NESTWORLD_AUTOSPLIT=<tick>: forces a split at
    // that tick and a merge back 600 ticks later, exercising the full lifecycle.
    private static final int AUTOSPLIT_AT_TICK =
            Integer.parseInt(System.getenv().getOrDefault("NESTWORLD_AUTOSPLIT", "-1"));
    private long totalTicks = 0;
    private WorldRegion[] autosplitChildren = null;

    // Periodic layout save: shutdown is the primary save point, but a crash
    // between shutdowns would lose the topology and bring back the cold-start
    // freeze. Re-save every interval, but only when the layout actually changed
    // since the last write (a tiny NBT file, so the I/O is negligible).
    private static final long LAYOUT_SAVE_INTERVAL_TICKS =
            Long.getLong("nestworld.layoutSaveIntervalTicks", 6000L); // ~5 min
    private long layoutSaveTickCounter = 0;
    private int lastSavedLayoutVersion = -1;

    // Self-test driven by env var NESTWORLD_RANDOMTICK_TEST=<speed>: every tick
    // queues chunk (0,0) for random ticking through the exact production path
    // (queueRandomTicksFor -> bucket -> region thread). Vanilla only random-
    // ticks chunks near a player, so headless verification needs this hook.
    private static final int RANDOMTICK_TEST_SPEED =
            Integer.parseInt(System.getenv().getOrDefault("NESTWORLD_RANDOMTICK_TEST", "-1"));

    private void runRandomTickTest() {
        if (RANDOMTICK_TEST_SPEED <= 0) return;
        net.minecraft.world.level.chunk.LevelChunk chunk =
                overworld.getChunkSource().getChunkNow(0, 0);
        if (chunk != null && queueRandomTicksFor(overworld, chunk, RANDOMTICK_TEST_SPEED)) {
            flushRandomTicksPhase();
        }
    }

    public void tickAllRegions(BooleanSupplier hasTime) {
        runAutosplitTest();
        runRandomTickTest();
        long t0 = System.nanoTime();
        // 1. Vanilla global tick (time, weather, chunk I/O) — entity tick skipped by
        // patch; due scheduled block/fluid ticks are parallelized from within it
        // via runScheduledTicksPhase (ServerLevel patch calls back into us).
        overworld.tick(hasTime);
        long t1 = System.nanoTime();

        // 2. Apply boundary redstone signals + wire updates whose network left
        // their region last tick (deferred by NestworldRedstone)
        signalQueue.flush();
        net.minecraft.core.BlockPos wirePos;
        while ((wirePos = deferredWireUpdates.poll()) != null) {
            try {
                overworld.nestworldWireHandler.onWireUpdated(wirePos);
            } catch (Throwable t) {
                LOGGER.warn("Deferred wire update at {} failed: {}", wirePos, t.toString());
            }
        }
        long t2 = System.nanoTime();

        // 3. Reassign entities that moved between regions
        entityTransfer.checkAndReassign();

        // 3b. Tick players on the main thread — their state is shared with the
        // network thread, so region-thread ticking races on position and causes
        // rubber-banding. Passengers are ticked by their vehicle's region.
        // (copy — ticking can mutate the list via dimension change/disconnect)
        for (net.minecraft.server.level.ServerPlayer player : java.util.List.copyOf(overworld.players())) {
            if (player.isRemoved() || player.isPassenger()) continue;
            try {
                overworld.tickNonPassenger(player);
            } catch (Throwable t) {
                LOGGER.warn("Player {} tick error: {}", player.getGameProfile().getName(), t.getMessage());
            }
        }
        // 3c. Tick pinned entity types on the main thread (mod-compat escape
        // hatch). They are never assigned to a region, so no region thread
        // touches them — the main thread ticks them here exactly as vanilla
        // would. Zero cost when nothing is pinned.
        if (!pins.isEmpty()) tickPinnedEntitiesOnMain();
        long t3 = System.nanoTime();

        // 4. Parallel tick — blocks until all region threads finish
        pool.tickAllRegions();
        long t4 = System.nanoTime();

        // 5. Refresh ghost zones (runs while region threads are paused at barrier)
        boundaryManager.syncGhostZones();
        long t5 = System.nanoTime();

        // 6. Adaptive split / merge
        splitManager.onTick();
        long t6 = System.nanoTime();

        // 7. Optional border visualisation for players
        renderBorderParticles();

        phaseNanos[0] += t1 - t0;
        phaseNanos[1] += t2 - t1;
        phaseNanos[2] += t3 - t2;
        phaseNanos[3] += t4 - t3;
        phaseNanos[4] += t5 - t4;
        phaseNanos[5] += t6 - t5;
        if (++timedTicks >= TIMING_LOG_INTERVAL) {
            LOGGER.info("Tick phases avg ms over {} ticks: vanilla={} signals={} entityXfer={} regionPool={} ghostZones={} splitMerge={}",
                    timedTicks,
                    String.format("%.2f", phaseNanos[0] / 1e6 / timedTicks),
                    String.format("%.2f", phaseNanos[1] / 1e6 / timedTicks),
                    String.format("%.2f", phaseNanos[2] / 1e6 / timedTicks),
                    String.format("%.2f", phaseNanos[3] / 1e6 / timedTicks),
                    String.format("%.2f", phaseNanos[4] / 1e6 / timedTicks),
                    String.format("%.2f", phaseNanos[5] / 1e6 / timedTicks));
            java.util.Arrays.fill(phaseNanos, 0L);
            timedTicks = 0;
        }

        if (++layoutSaveTickCounter >= LAYOUT_SAVE_INTERVAL_TICKS) {
            layoutSaveTickCounter = 0;
            int layout = grid.getLayoutVersion();
            if (layout != lastSavedLayoutVersion) saveLayout();
        }
    }

    // -----------------------------------------------------------------------
    // Parallel scheduled block/fluid ticks (called back from ServerLevel patch)
    // -----------------------------------------------------------------------

    /**
     * Scheduled ticks in chunks this close to a region edge run on the main
     * thread: their update cascades (pistons, observers, comparators) can reach
     * a few blocks past the origin, and from the band that could cross into a
     * neighbouring region mid-round and race its thread. Interior cascades
     * can't travel 2 chunks (32 blocks) in a single update, and wires are
     * separately bounds-checked by the per-thread Alternate Current handler.
     */
    private static final int BORDER_BAND_CHUNKS = 2;

    /** Wire updates whose network left its region; re-run on main next tick. */
    private final java.util.Queue<net.minecraft.core.BlockPos> deferredWireUpdates =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public void deferWireUpdate(net.minecraft.core.BlockPos pos) {
        deferredWireUpdates.add(pos.immutable());
    }

    /**
     * Replaces the vanilla blockTicks/fluidTicks drain inside ServerLevel.tick
     * for the managed overworld. Vanilla's collection logic still runs on the
     * main thread (so priority/time ordering is preserved), but instead of
     * executing each tick it buckets them: ticks in a region's interior execute
     * on that region's thread in parallel; ticks in the border band, or outside
     * any region, run on the main thread first (regions are parked then, so
     * their cascades may safely cross borders).
     */
    public void runScheduledTicksPhase(ServerLevel level, long gameTime) {
        java.util.Map<WorldRegion, java.util.List<Runnable>> buckets = new java.util.IdentityHashMap<>();
        java.util.List<Runnable> mainBucket = new java.util.ArrayList<>();

        level.getBlockTicks().tick(gameTime, 65536, (pos, block) ->
                routeScheduledTick(pos, () -> level.nestworldRunBlockTick(pos, block), buckets, mainBucket));
        level.getFluidTicks().tick(gameTime, 65536, (pos, fluid) ->
                routeScheduledTick(pos, () -> level.nestworldRunFluidTick(pos, fluid), buckets, mainBucket));

        for (Runnable r : mainBucket) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("Main-band scheduled tick failed: {}", t.toString());
            }
        }
        pool.runWorkRound(buckets);
    }

    // -----------------------------------------------------------------------
    // Random ticks (crops, fire, ice…) — phase 2a. ServerLevel.tickChunk asks
    // queueRandomTicksFor() per ticking chunk: interior chunks are bucketed
    // per region and executed in parallel by flushRandomTicksPhase() after the
    // chunk loop (ServerChunkCache patch); border-band or unowned chunks
    // return false and tick inline on main, exactly like scheduled ticks.
    // -----------------------------------------------------------------------

    private final java.util.Map<WorldRegion, java.util.List<Runnable>> randomTickBuckets =
            new java.util.IdentityHashMap<>();

    /** Called from the patched ServerLevel.tickChunk. True = queued for a region thread. */
    public static boolean queueRandomTicksFor(ServerLevel level,
                                              net.minecraft.world.level.chunk.LevelChunk chunk,
                                              int randomTickSpeed) {
        if (!isInitialised()) return false;
        NestworldRegionSystem sys = get();
        if (level != sys.overworld) return false;
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        WorldRegion region = sys.grid.getRegionForChunk(pos.x, pos.z);
        if (region == null
                || pos.x < region.getMinChunkX() + BORDER_BAND_CHUNKS || pos.x > region.getMaxChunkX() - BORDER_BAND_CHUNKS
                || pos.z < region.getMinChunkZ() + BORDER_BAND_CHUNKS || pos.z > region.getMaxChunkZ() - BORDER_BAND_CHUNKS) {
            return false;
        }
        sys.randomTickBuckets.computeIfAbsent(region, r -> new java.util.ArrayList<>())
                .add(() -> level.nestworldRandomTickChunk(chunk, randomTickSpeed));
        return true;
    }

    /** Runs the queued per-chunk random ticks on their region threads (parallel). */
    public void flushRandomTicksPhase() {
        if (randomTickBuckets.isEmpty()) return;
        pool.runWorkRound(randomTickBuckets);
        randomTickBuckets.clear();
    }

    // -----------------------------------------------------------------------
    // Block entities — phase 2c. The patched Level.tickBlockEntities collects
    // due tickers instead of running them when beginBlockEntityPhase returns
    // non-null: interior tickers run on their region's thread in parallel;
    // border-band or unowned tickers run on main BEFORE the round (hoppers
    // pull from neighbour-chunk inventories, BE ticks write blocks — the same
    // cascade-reach argument as scheduled ticks). Vanilla list order is
    // preserved within each bucket.
    // -----------------------------------------------------------------------

    /** Non-null collector when NestWorld routes block entities for this level. */
    public static java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> beginBlockEntityPhase(net.minecraft.world.level.Level level) {
        if (!isInitialised()) return null;
        NestworldRegionSystem sys = get();
        if (level != sys.overworld) return null;
        return new java.util.ArrayList<>();
    }

    private int bePhaseLogCountdown = 0;

    /** Debug: -Dnestworld.beTracePos=x,y,z logs that ticker's routing every BE phase. */
    private static final net.minecraft.core.BlockPos BE_TRACE_POS;
    static {
        String s = System.getProperty("nestworld.beTracePos");
        net.minecraft.core.BlockPos p = null;
        if (s != null) {
            String[] parts = s.split(",");
            p = new net.minecraft.core.BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
        BE_TRACE_POS = p;
    }
    private int beTraceCountdown = 0;

    /** Buckets and runs the tickers collected by the patched tickBlockEntities. */
    public void runBlockEntityPhase(ServerLevel level,
                                    java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> due) {
        java.util.Map<WorldRegion, java.util.List<Runnable>> buckets = new java.util.IdentityHashMap<>();
        java.util.List<Runnable> mainBucket = new java.util.ArrayList<>();
        boolean traced = false;
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : due) {
            net.minecraft.core.BlockPos pos = ticker.getPos();
            Runnable run = ticker::tick;
            if (BE_TRACE_POS != null && BE_TRACE_POS.equals(pos)) {
                traced = true;
                boolean logThis = ++beTraceCountdown >= 40;
                if (logThis) beTraceCountdown = 0;
                final boolean log = logThis;
                run = () -> {
                    if (log) {
                        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
                        net.minecraft.world.level.chunk.LevelChunk c =
                                level.getChunkSource().getChunkNow(cx, cz);
                        LOGGER.info("BE trace exec {} on [{}]: removed={} fullStatus={} entitiesLoaded={}",
                                pos, Thread.currentThread().getName(), ticker.isRemoved(),
                                c == null ? "NO_CHUNK" : c.getFullStatus(),
                                level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz)));
                    }
                    ticker.tick();
                };
            }
            routeScheduledTick(pos, run, buckets, mainBucket);
        }
        if (BE_TRACE_POS != null && !traced && ++beTraceCountdown >= 40) {
            beTraceCountdown = 0;
            LOGGER.info("BE trace {}: NOT in due list (not collected on main)", BE_TRACE_POS);
        }
        boolean logNow = ++bePhaseLogCountdown >= 200;
        if (logNow) {
            bePhaseLogCountdown = 0;
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<WorldRegion, java.util.List<Runnable>> e : buckets.entrySet()) {
                sb.append(" #").append(e.getKey().getId()).append('=').append(e.getValue().size());
            }
            LOGGER.info("BE phase: due={} main={} buckets:{}", due.size(), mainBucket.size(), sb);
        }
        for (Runnable r : mainBucket) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("Main-band block entity tick failed: {}", t.toString());
            }
        }
        int before = buckets.size();
        pool.runWorkRoundDropIfBacklogged(buckets);
        if (logNow && buckets.size() != before) {
            LOGGER.info("BE phase: {} region bucket(s) dropped (backlog)", before - buckets.size());
        }
    }

    // -----------------------------------------------------------------------
    // Block events — phase 2c. The vanilla blockEvents() (m_8807_) drains the
    // f_8556_ queue and executes each piston/note-block/chest event on main.
    // We route the same way as scheduled ticks: interior events run on their
    // region's thread in parallel; border-band or unowned events run on main
    // first (events write blocks and can cascade across borders, same reach
    // argument as scheduled ticks). Events whose chunk is no longer tickable
    // are rescheduled; successful events broadcast their packet.
    // -----------------------------------------------------------------------

    /** Drains and runs the level's block-event queue, bucketed per region. */
    public void runBlockEventsPhase(ServerLevel level) {
        java.util.List<net.minecraft.world.level.BlockEventData> due =
                level.nestworldDrainBlockEvents();
        if (due.isEmpty()) return;

        java.util.Map<WorldRegion, java.util.List<Runnable>> buckets = new java.util.IdentityHashMap<>();
        java.util.List<Runnable> mainBucket = new java.util.ArrayList<>();
        for (net.minecraft.world.level.BlockEventData e : due) {
            Runnable run = () -> {
                if (level.nestworldShouldTickBlocksAt(e.pos())) {
                    if (level.nestworldDoBlockEvent(e)) {
                        level.nestworldBroadcastBlockEvent(e);
                    }
                } else {
                    level.nestworldRescheduleBlockEvent(e);
                }
            };
            routeScheduledTick(e.pos(), run, buckets, mainBucket);
        }
        for (Runnable r : mainBucket) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("Main-band block event failed: {}", t.toString());
            }
        }
        pool.runWorkRound(buckets);
    }

    private void routeScheduledTick(net.minecraft.core.BlockPos pos, Runnable run,
                                    java.util.Map<WorldRegion, java.util.List<Runnable>> buckets,
                                    java.util.List<Runnable> mainBucket) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        // Record block-tick load for the split scorer regardless of which side
        // of the band it lands on — a hot column currently stuck in the band is
        // exactly what we want the next split to see and route into an interior.
        blockTickHeat.record(cx, cz);
        WorldRegion region = grid.getRegionForChunk(cx, cz);
        if (region != null
                && cx >= region.getMinChunkX() + BORDER_BAND_CHUNKS && cx <= region.getMaxChunkX() - BORDER_BAND_CHUNKS
                && cz >= region.getMinChunkZ() + BORDER_BAND_CHUNKS && cz <= region.getMaxChunkZ() - BORDER_BAND_CHUNKS) {
            buckets.computeIfAbsent(region, r -> new java.util.ArrayList<>()).add(run);
        } else {
            mainBucket.add(run);
        }
    }

    // -----------------------------------------------------------------------
    // Region border visualisation (/nestworld borders)
    // -----------------------------------------------------------------------

    /** When true, region borders near players are outlined with particles. */
    public static volatile boolean showBorders = false;
    private static final int BORDER_VIEW_RANGE = 96;   // blocks
    private int borderParticleTimer = 0;

    private void renderBorderParticles() {
        if (!showBorders || (++borderParticleTimer % 10) != 0) return;

        for (net.minecraft.server.level.ServerPlayer player : overworld.players()) {
            int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();

            for (WorldRegion r : grid.getAllRegions()) {
                // Block-space edges of the region (east/south edges are exclusive)
                int west = r.getMinChunkX() << 4;
                int east = (r.getMaxChunkX() + 1) << 4;
                int north = r.getMinChunkZ() << 4;
                int south = (r.getMaxChunkZ() + 1) << 4;

                drawBorderPlaneX(player, west,  north, south, px, py, pz);
                drawBorderPlaneX(player, east,  north, south, px, py, pz);
                drawBorderPlaneZ(player, north, west,  east,  px, py, pz);
                drawBorderPlaneZ(player, south, west,  east,  px, py, pz);
            }
        }
    }

    private void drawBorderPlaneX(net.minecraft.server.level.ServerPlayer player,
                                  int x, int zMin, int zMax, int px, int py, int pz) {
        if (Math.abs(px - x) > BORDER_VIEW_RANGE) return;
        int from = Math.max(zMin, pz - BORDER_VIEW_RANGE);
        int to   = Math.min(zMax, pz + BORDER_VIEW_RANGE);
        for (int z = from; z <= to; z += 2) {
            for (int y = py - 8; y <= py + 12; y += 4) {
                overworld.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD,
                        false, x + 0.0, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
            }
        }
    }

    private void drawBorderPlaneZ(net.minecraft.server.level.ServerPlayer player,
                                  int z, int xMin, int xMax, int px, int py, int pz) {
        if (Math.abs(pz - z) > BORDER_VIEW_RANGE) return;
        int from = Math.max(xMin, px - BORDER_VIEW_RANGE);
        int to   = Math.min(xMax, px + BORDER_VIEW_RANGE);
        for (int x = from; x <= to; x += 2) {
            for (int y = py - 8; y <= py + 12; y += 4) {
                overworld.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD,
                        false, x + 0.5, y + 0.5, z + 0.0, 1, 0, 0, 0, 0);
            }
        }
    }

    private void runAutosplitTest() {
        if (AUTOSPLIT_AT_TICK < 0) return;
        totalTicks++;
        if (totalTicks == AUTOSPLIT_AT_TICK) {
            WorldRegion target = tree.getActiveRegions().get(0);
            LOGGER.info("[AUTOSPLIT TEST] forcing split of {}", target);
            autosplitChildren = splitManager.doSplit(target);
            LOGGER.info("[AUTOSPLIT TEST] split result: {}", (Object) autosplitChildren);
        } else if (totalTicks == AUTOSPLIT_AT_TICK + 600 && autosplitChildren != null) {
            LOGGER.info("[AUTOSPLIT TEST] forcing merge of {} + {}", autosplitChildren[0], autosplitChildren[1]);
            WorldRegion merged = splitManager.doMerge(autosplitChildren[0], autosplitChildren[1]);
            LOGGER.info("[AUTOSPLIT TEST] merge result: {}", merged);
        }
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    private void shutdown() {
        LOGGER.info("Shutting down NestWorld region threads…");
        // Persist the layout before tearing threads down so the next boot skips
        // the cold-start freeze (whole world on one thread until splits catch up).
        saveLayout();
        for (RegionThread t : pool.getThreads()) {
            t.shutdown();
        }
        LOGGER.info("NestWorld region system stopped");
    }

    // -----------------------------------------------------------------------
    // Subsystem accessors (for admin commands, Spark integration, etc.)
    // -----------------------------------------------------------------------

    public WorldGrid getGrid()                        { return grid; }
    public RegionTree getTree()                       { return tree; }
    public RegionThreadPool getPool()                 { return pool; }
    public RegionSplitManager getSplitManager()       { return splitManager; }
    BlockTickHeat getBlockTickHeat()                  { return blockTickHeat; }
    public BoundarySignalQueue getSignalQueue()       { return signalQueue; }
    public BoundaryEntityTransfer getEntityTransfer() { return entityTransfer; }
    public CrossRegionCapabilityBus getCapabilityBus(){ return capabilityBus; }
    public RegionChunkView getChunkView()             { return chunkView; }
}
