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

    /** Chunk span covered by the initial single region (32×32 = 512×512 blocks). */
    private static final int INITIAL_REGION_HALF_SPAN = 16; // chunks from 0

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

        // Start with one region that covers a 32×32 chunk square centred on spawn.
        // As players spread out, the system will split and spawn more regions.
        int r = INITIAL_REGION_HALF_SPAN;
        WorldRegion initial = new WorldRegion(0, -r, -r, r, r);

        grid           = new WorldGrid();
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
    public void tickAllRegions(BooleanSupplier hasTime) {
        // 1. Vanilla global tick (time, weather, chunk I/O) — entity tick skipped by patch
        overworld.tick(hasTime);

        // 2. Apply boundary redstone signals
        signalQueue.flush();

        // 3. Reassign entities that moved between regions
        entityTransfer.checkAndReassign();

        // 4. Parallel tick — blocks until all region threads finish
        pool.tickAllRegions();

        // 5. Refresh ghost zones (runs while region threads are paused at barrier)
        boundaryManager.syncGhostZones();

        // 6. Adaptive split / merge
        splitManager.onTick();
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
    public BoundarySignalQueue getSignalQueue()       { return signalQueue; }
    public BoundaryEntityTransfer getEntityTransfer() { return entityTransfer; }
    public CrossRegionCapabilityBus getCapabilityBus(){ return capabilityBus; }
    public RegionChunkView getChunkView()             { return chunkView; }
}
