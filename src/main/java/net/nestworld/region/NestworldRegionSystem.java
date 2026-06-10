package net.nestworld.region;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

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

    private ServerLevel overworld;

    private NestworldRegionSystem() {}

    public static NestworldRegionSystem get() {
        if (INSTANCE == null) throw new IllegalStateException("NestworldRegionSystem not initialised");
        return INSTANCE;
    }

    public static boolean isInitialised() { return INSTANCE != null; }

    // -----------------------------------------------------------------------
    // Forge event hooks (registered in SkyBlockMod)
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

        overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.error("Overworld not available — region system disabled");
            return;
        }

        // Start with one region covering the whole world; adaptive splits
        // subdivide it as load appears.
        grid           = new WorldGrid();
        int r = INITIAL_REGION_HALF_SPAN;
        WorldRegion initial = new WorldRegion(grid.nextId(), -r, -r, r, r);

        tree           = new RegionTree(grid, initial);
        pool           = new RegionThreadPool(overworld);
        boundaryManager = new BoundaryManager(overworld, grid);
        signalQueue    = new BoundarySignalQueue(overworld);
        entityTransfer = new BoundaryEntityTransfer(overworld, grid);
        capabilityBus  = new CrossRegionCapabilityBus(overworld, grid, boundaryManager);
        chunkView      = new RegionChunkView(grid, boundaryManager);
        splitManager   = new RegionSplitManager(tree, pool);

        // Spawn the first region thread
        pool.spawn(initial);

        LOGGER.info("NestWorld started — initial region: {}", initial);
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

    public void tickAllRegions(BooleanSupplier hasTime) {
        runAutosplitTest();
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

    private void routeScheduledTick(net.minecraft.core.BlockPos pos, Runnable run,
                                    java.util.Map<WorldRegion, java.util.List<Runnable>> buckets,
                                    java.util.List<Runnable> mainBucket) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
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
    public BoundarySignalQueue getSignalQueue()       { return signalQueue; }
    public BoundaryEntityTransfer getEntityTransfer() { return entityTransfer; }
    public CrossRegionCapabilityBus getCapabilityBus(){ return capabilityBus; }
    public RegionChunkView getChunkView()             { return chunkView; }
}
