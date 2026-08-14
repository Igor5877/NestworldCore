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

    /** Layer 3: pre-generate the frontier ahead of moving players (default off). */
    private final PredictiveChunkGen predictiveGen = new PredictiveChunkGen();

    /** Entities spawned by region threads (off-main addFreshEntity), drained on
     *  main each tick so ChunkMap entity tracking is never mutated concurrently.
     *  Bounded (anti-grief spawn-flood cap) — offer() refuses atomically when full,
     *  so there is no separate counter to fall out of sync with the queue. */
    private final java.util.concurrent.LinkedBlockingQueue<net.minecraft.world.entity.Entity> deferredSpawns =
            new java.util.concurrent.LinkedBlockingQueue<>(NestworldTuning.DEFERRED_SPAWN_QUEUE_CAP);

    /** Entity-tracking removals queued by region threads (off-main discard, e.g.
     *  TNT consumed by an explosion), drained on main each tick so ChunkMap's
     *  non-thread-safe entityMap is never mutated concurrently with its own tick
     *  iteration. Symmetric with {@link #deferredSpawns}; bounded by the live
     *  entity count, so no cap is needed (you cannot remove more than exist). */
    private final java.util.Queue<net.minecraft.world.entity.Entity> deferredRemovals =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Entity-tracking ADDS queued by region threads: a section-move visibility transition
     *  (entity walks/teleports into an entity-ticking section during a region tick) calls
     *  ServerChunkCache.addEntity off-main, which must not touch ChunkMap's entityMap.
     *  Drained on main AFTER {@link #deferredRemovals}, so a leave+re-enter within one tick
     *  resolves to the correct final tracked state. Bounded by the live entity count. */
    private final java.util.Queue<net.minecraft.world.entity.Entity> deferredTrackingAdds =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private ServerLevel overworld;
    private MinecraftServer server;

    /** Cached plains biome holder for the per-call empty chunks (lazy). */
    private volatile net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> emptyChunkBiome;

    private NestworldRegionSystem() {}

    /**
     * Returns a FRESH empty (void-air) chunk at (x, z) for a region thread
     * reading an unloaded chunk, so it never synchronously loads + blocks on
     * main. Gated by {@link NestworldTuning#NONBLOCKING_CHUNK_READS}; called from
     * the patched ServerChunkCache. A new instance per call — a single shared one
     * races on LevelChunk's inherited mutable arrays (heightmaps/sections) when
     * several region threads read it at once (observed AIOOBE). Only the biome
     * holder is cached.
     */
    public net.minecraft.world.level.chunk.LevelChunk nestworldEmptyChunk(int x, int z) {
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = this.emptyChunkBiome;
        if (biome == null) {
            biome = overworld.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            this.emptyChunkBiome = biome;
        }
        return new net.minecraft.world.level.chunk.EmptyLevelChunk(
                overworld, new net.minecraft.world.level.ChunkPos(x, z), biome);
    }

    public static NestworldRegionSystem get() {
        if (INSTANCE == null) throw new IllegalStateException("NestworldRegionSystem not initialised");
        return INSTANCE;
    }

    public static boolean isInitialised() { return INSTANCE != null; }

    /**
     * Routes an entity section-move (or a just-added entity, which starts with no owner) to the
     * overworld ownership tracker for event-driven reassignment
     * ({@link NestworldTuning#EVENT_DRIVEN_OWNERSHIP}). No-op unless the system is initialised
     * and the entity belongs to the sharded overworld. Called from the patched section-move and
     * entity-add callbacks on any thread; fully null/level-guarded and cheap.
     */
    public static void markOwnershipDirty(net.minecraft.world.entity.Entity entity) {
        NestworldRegionSystem sys = INSTANCE;
        if (sys != null && sys.entityTransfer != null && entity != null
                && entity.level() == sys.overworld) {
            sys.entityTransfer.markDirty(entity);
        }
    }

    // -----------------------------------------------------------------------
    // Forge event hooks (registered in NestworldMod)
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        INSTANCE = new NestworldRegionSystem();
        INSTANCE.init(event.getServer());
        // Start the always-on profiler (no-op unless AUTO_SPARK + spark present).
        SparkBridge.autoStart(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Capture the session's profile link to spark-history.txt before everything tears down.
        SparkBridge.writeHistoryOnShutdown(event.getServer());
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        NestworldCommand.register(event.getDispatcher());
    }

    // NestWorld: see NestworldTuning.MAX_FALLING_BLOCKS's javadoc. Global live-count cap
    // against runaway falling-block accumulation (e.g. a dispenser/observer "sand duper"
    // loop, or -- confirmed live on ATM9 -- a mod constructing FallingBlockEntity and
    // calling addFreshEntity() directly, bypassing FallingBlockEntity.fall()'s own factory
    // entirely: 20981 falling_block entities existed with a fall()-based cap of 3000 in
    // effect). EntityJoinLevelEvent fires for EVERY path an entity can join a level --
    // Level.addFreshEntity() (used by fall() and any mod calling it directly) AND
    // PersistentEntitySectionManager's own internal add path (chunk load, other add
    // routes) -- so this is the one truly bypass-proof choke point. Cancelling here means
    // the entity object still exists (any caller already holding a reference, e.g.
    // AnvilBlock.falling()'s setHurtsEntities() call, still has a valid non-null object to
    // call methods on) but never actually joins the level -- matches fall()'s own existing
    // "block vanishes instead of animating a fall" trade-off for the capped case.
    // loadedFromDisk() entities are NEVER blocked (would destroy legitimately-saved world
    // state) but ARE still counted, so the running total stays accurate against reality.
    private static final java.util.concurrent.atomic.AtomicInteger nestworldFallingBlockCount =
            new java.util.concurrent.atomic.AtomicInteger();

    @SubscribeEvent
    public static void onEntityJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity)) return;
        int cap = NestworldTuning.MAX_FALLING_BLOCKS;
        if (cap > 0 && !event.loadedFromDisk() && nestworldFallingBlockCount.get() >= cap) {
            event.setCanceled(true);
            return;
        }
        nestworldFallingBlockCount.incrementAndGet();
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity) {
            nestworldFallingBlockCount.decrementAndGet();
        }
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    private void init(MinecraftServer server) {
        LOGGER.info("Initialising NestWorld region sharding system…");

        // Guard against mods whose optimizations are thread-unsafe under parallel
        // region ticking (Canary/radium/lithium overwrite ClassInstanceMultiMap
        // with single-thread-only structures that race and freeze the server).
        NestworldCompat.check();

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
        pins.loadBe(bePinsFile());
        pins.loadMods(pinnedModsFile());
        pins.loadCascadeSafeBe(cascadeSafeBeFile());

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
        if (System.getenv("NESTWORLD_AUTOPIN_TEST") != null
                || Boolean.getBoolean("nestworld.autoPinTest")) {
            NestworldPins.selfTest();
        }
        if (System.getenv("NESTWORLD_LAYOUT_TEST") != null
                || Boolean.getBoolean("nestworld.layoutTest")) {
            RegionTree.selfTest();
        }
        if (System.getenv("NESTWORLD_MAILBOX_TEST") != null
                || Boolean.getBoolean("nestworld.mailboxTest")) {
            BoundaryManager.selfTest();
        }
        if (System.getenv("NESTWORLD_MARGIN_TEST") != null
                || Boolean.getBoolean("nestworld.marginTest")) {
            WorldGrid.selfTest();
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

    /** Text file listing block-entity-type ids pinned to main-thread ticking. */
    private Path bePinsFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-be-pins.txt");
    }

    /** Text file listing mod namespaces whose entities/BEs are pinned to main. */
    private Path pinnedModsFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-pinned-mods.txt");
    }

    /** Text file listing block-entity type ids the operator asserts are cascade-safe
     *  (see {@link NestworldPins#markCascadeSafe} javadoc for the trust boundary). */
    private Path cascadeSafeBeFile() {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-be-cascade-safe.txt");
    }

    public NestworldPins getPins() { return pins; }

    /** Called from a region thread's addFreshEntity (overworld): queue the spawn
     *  for main-thread registration instead of mutating ChunkMap off-main. */
    public boolean queueEntitySpawn(net.minecraft.world.entity.Entity e) {
        // Anti-grief: the bounded queue drops spawns past the cap so a flood
        // (mass breeding, skeleton volleys) cannot exhaust memory.
        return deferredSpawns.offer(e);
    }

    /** Main-thread: register entities region threads spawned, up to a per-tick
     *  budget so a deliberate flood throttles over several ticks instead of
     *  freezing the main thread draining the whole queue at once. */
    private void drainDeferredSpawns() {
        // NestWorld: stop at whichever limit — entity count or wall-clock time —
        // is hit first. The count alone doesn't bound tick-time cost predictably
        // (see NestworldTuning.DEFERRED_SPAWN_BUDGET_NANOS's javadoc); the time
        // check only runs every 16 entities (via the bitmask), not after every
        // single one, since System.nanoTime() itself has real per-call cost.
        long deadlineNanos = System.nanoTime() + NestworldTuning.DEFERRED_SPAWN_BUDGET_NANOS;
        net.minecraft.world.entity.Entity e;
        int nestworldProcessed = 0;
        while ((e = deferredSpawns.poll()) != null) {
            try {
                overworld.addFreshEntity(e);
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity spawn failed: {}", t.toString());
            }
            nestworldProcessed++;
            if (nestworldProcessed >= NestworldTuning.MAX_DEFERRED_SPAWNS_PER_TICK) break;
            if ((nestworldProcessed & 15) == 0 && System.nanoTime() >= deadlineNanos) break;
        }
    }

    /** Called from a region thread's ServerChunkCache.removeEntity: queue the
     *  tracking removal for main instead of mutating ChunkMap's entityMap off-main. */
    public void queueEntityRemoval(net.minecraft.world.entity.Entity e) {
        deferredRemovals.add(e);
    }

    /** Main-thread: process the entity-tracking removals region threads queued
     *  this tick. Drained fully (not budgeted) — removals are bounded by the live
     *  entity count and must not lag, or a removed entity keeps being tracked. */
    private void drainDeferredRemovals() {
        net.minecraft.world.entity.Entity e;
        while ((e = deferredRemovals.poll()) != null) {
            try {
                overworld.getChunkSource().removeEntity(e);
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity removal failed: {}", t.toString());
            }
        }
    }

    /** Called from a region thread's ServerChunkCache.addEntity (a section-move visibility
     *  transition during the region tick): queue the tracking add for main instead of mutating
     *  ChunkMap's entityMap off-main. */
    public void queueEntityTrackingAdd(net.minecraft.world.entity.Entity e) {
        deferredTrackingAdds.add(e);
    }

    /** Main-thread: process the entity-tracking adds region threads queued this tick. Runs
     *  AFTER {@link #drainDeferredRemovals} so a leave+re-enter sequence lands tracked. An
     *  entity that is already tracked (e.g. its queued removal was superseded) or died since
     *  queueing is skipped — ChunkMap.addEntity would throw on the former. */
    private void drainDeferredTrackingAdds() {
        net.minecraft.world.entity.Entity e;
        while ((e = deferredTrackingAdds.poll()) != null) {
            if (e.isRemoved()) continue;
            try {
                overworld.getChunkSource().addEntity(e);
            } catch (IllegalStateException dup) {
                // "Entity is already tracked!" — the add was superseded (never untracked);
                // the tracker is already in the desired state, so this is benign.
                LOGGER.debug("Deferred tracking add skipped (already tracked): {}", e.getUUID());
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity tracking add failed: {}", t.toString());
            }
        }
    }

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
    // Phase timing accumulators (ns), logged every TIMING_LOG_INTERVAL ticks.
    // On by default (used for live dev profiling); set -Dnestworld.timingLog=false
    // to silence the every-10-s line in production.
    private static final boolean TIMING_LOG =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.timingLog", "true"));
    private static final int TIMING_LOG_INTERVAL = 200;
    private final long[] phaseNanos = new long[6];
    private int timedTicks = 0;
    // Cumulative-since-boot mirror of the above (never reset), for live on-demand reading
    // via /nestworld tickphases -- the windowed phaseNanos[]/timedTicks above only ever
    // reach players through the every-200-tick LOG line, gated behind TIMING_LOG. Added
    // 2026-08-12 to finally sub-attribute the tick-correlation telemetry's "outside_pollTask"
    // residual (ghostzones/splitmerge/entityXfer/vanilla-tick, previously only a single
    // undifferentiated number -- see project memory).
    private final long[] phaseNanosCumulative = new long[6];
    private long timedTicksCumulative = 0;

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
        pool.nestworldResetTickAccumulators();
        long nestworldTickWallStart = System.nanoTime();
        long t0 = System.nanoTime();
        // Publish the loaded-FULL chunk snapshot for region threads to read
        // lock-free this tick (avoids missing already-loaded chunks off-main and
        // the getChunk park/unpark herd that dominated the main thread under load).
        overworld.getChunkSource().nestworldRefreshLoadedChunks();
        // 1. Vanilla global tick (time, weather, chunk I/O) — entity tick skipped by
        // patch; due scheduled block/fluid ticks are parallelized from within it
        // via runScheduledTicksPhase (ServerLevel patch calls back into us).
        overworld.tick(hasTime);
        long t1 = System.nanoTime();

        // 1b. Region-Owned Chunk Scheduler, Phase 2 -- SHADOW MODE ONLY (docs/
        // REGION_CHUNK_SCHEDULER_SPEC.md). Both calls are no-ops unless
        // NestworldTuning.CHUNK_SCHEDULER_SHADOW_MODE is on; purely diagnostic, never
        // influences real chunk loading. See net.nestworld.chunk.ChunkSchedulerShadow.
        net.nestworld.chunk.ChunkSchedulerShadow.drainObservationsAndEnqueue(this);
        net.nestworld.chunk.ChunkSchedulerShadow.drainForStats(this);

        // 2. Apply boundary redstone signals + wire updates whose network left
        // their region last tick (deferred by NestworldRedstone)
        signalQueue.flush();
        RegionMessage<net.minecraft.core.BlockPos> wireMsg;
        while ((wireMsg = deferredWireUpdates.poll()) != null) {
            try {
                overworld.nestworldWireHandler.onWireUpdated(wireMsg.payload());
            } catch (Throwable t) {
                LOGGER.warn("Deferred wire update at {} failed: {}", wireMsg.payload(), t.toString());
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

        // 3d. Predictive frontier: request generation of chunks ahead of moving
        // players so terrain is ready before they arrive (no-op unless enabled).
        // Additive — only adds expiring region tickets; the tiered budget paces them.
        predictiveGen.tick(overworld);
        long t3 = System.nanoTime();

        // 4. Parallel tick — blocks until all region threads finish
        pool.tickAllRegions();
        long t4 = System.nanoTime();

        // 4b. Stage 3 (docs/LOCAL_TICK_STAGE4.md, "entity-triggered block-write race"):
        // apply explosion batches posted THIS tick by regions whose explosions reached
        // into a neighbouring region's territory. Must run here — after the region-tick
        // round (explosions happen during step 4, so nothing existed to apply before it)
        // but before ghost-zone sync (5) below, so the freshly-destroyed blocks/removed
        // block entities are reflected in this tick's ghost-zone snapshot instead of
        // lagging an extra tick. Every region thread is parked here, same as every other
        // main-thread-only phase in this method.
        // Stage 5.3 design v3 (docs/LOCAL_TICK_STAGE4.md, "budgeted drain" fix): one
        // deadline shared across every region and both message types in this pass —
        // a flood targeting any single region (e.g. a chain-reaction explosion whose
        // blast crosses a boundary) cannot stall the main thread past this budget.
        // Anything left queued once the deadline hits is picked up on a LATER tick's
        // pass instead — extends, not violates, these message types' existing Tier 2
        // "eventually applied" contract.
        long nestworldMailboxDeadline = System.nanoTime() + NestworldTuning.MAILBOX_DRAIN_BUDGET_NANOS;
        for (WorldRegion region : grid.getAllRegions()) {
            region.nestworldDrainMailboxBudgeted(RegionMessage.Type.EXPLOSION_APPLY, nestworldMailboxDeadline, msg -> {
                try {
                    net.minecraft.world.level.Explosion.NestworldExplosionBatch batch =
                            (net.minecraft.world.level.Explosion.NestworldExplosionBatch) msg.payload();
                    nestworldApplyWithCascadeGuard(region, msg, batch.positions(),
                            () -> net.minecraft.world.level.Explosion.nestworldApplyBatch(batch));
                } catch (Throwable t) {
                    LOGGER.warn("Deferred explosion batch apply failed: {}", t.toString());
                }
            });
            // 4c. Stage 3 EXTENSION (docs/LOCAL_TICK_STAGE4.md): generic Level.setBlock()
            // calls (mob-AI, mod code — e.g. Draconic Evolution's reactor, EnderDragon/
            // WitherBoss) deferred by Level.setBlock()'s guard when made from a region
            // thread for a position outside its own bounds. Same barrier-safe point as
            // EXPLOSION_APPLY above, applied on main so it is always safe to write anywhere.
            region.nestworldDrainMailboxBudgeted(RegionMessage.Type.BLOCK_WRITE, nestworldMailboxDeadline, msg -> {
                try {
                    RegionMessage.BlockWrite write = (RegionMessage.BlockWrite) msg.payload();
                    nestworldApplyWithCascadeGuard(region, msg, java.util.List.of(write.pos()),
                            () -> overworld.setBlock(write.pos(), write.newState(), write.flags(), write.recursionLeft()));
                } catch (Throwable t) {
                    LOGGER.warn("Deferred block write at {} failed: {}", msg, t.toString());
                }
            });
        }

        // 5. Refresh ghost zones (runs while region threads are paused at barrier)
        boundaryManager.syncGhostZones();
        // 5b. Register/unregister entities that region threads spawned or removed
        // this tick (queued to avoid corrupting ChunkMap's non-thread-safe entity
        // tracking while the main thread ran it during overworld.tick). Region
        // threads are idle at the barrier here, so this is the safe point on main.
        drainDeferredRemovals();
        drainDeferredTrackingAdds();
        drainDeferredSpawns();
        // 5c. Vanilla visibility/broadcast tracker (ChunkMap.tick()), deferred from its normal
        // position (ServerChunkCache.tickChunks(), phase 1 — see the guard there) to HERE,
        // strictly after region threads finish their tick: REGIONALIZED_TRACKER's skip-check
        // (nestworldTrackedTick == this tick's number) can only ever succeed if it runs after
        // regions have set that stamp for owned entities, which happens above in step 4. Also
        // means players/unowned entities are tracked/broadcast with THIS tick's fresh position
        // instead of last tick's (one tick less latency) — an intentional, understood side
        // effect, not a bug. Region threads are fully parked outside the step-4 window, so no
        // race is possible on the seenBy/lastSectionPos state this call writes.
        overworld.getChunkSource().chunkMap.tick();
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
        phaseNanosCumulative[0] += t1 - t0;
        phaseNanosCumulative[1] += t2 - t1;
        phaseNanosCumulative[2] += t3 - t2;
        phaseNanosCumulative[3] += t4 - t3;
        phaseNanosCumulative[4] += t5 - t4;
        phaseNanosCumulative[5] += t6 - t5;
        timedTicksCumulative++;
        if (++timedTicks >= TIMING_LOG_INTERVAL) {
            if (TIMING_LOG) {
                LOGGER.info("Tick phases avg ms over {} ticks: vanilla={} signals={} entityXfer={} regionPool={} ghostZones={} splitMerge={}",
                        timedTicks,
                        String.format("%.2f", phaseNanos[0] / 1e6 / timedTicks),
                        String.format("%.2f", phaseNanos[1] / 1e6 / timedTicks),
                        String.format("%.2f", phaseNanos[2] / 1e6 / timedTicks),
                        String.format("%.2f", phaseNanos[3] / 1e6 / timedTicks),
                        String.format("%.2f", phaseNanos[4] / 1e6 / timedTicks),
                        String.format("%.2f", phaseNanos[5] / 1e6 / timedTicks));
            }
            java.util.Arrays.fill(phaseNanos, 0L);
            timedTicks = 0;
        }

        if (++layoutSaveTickCounter >= LAYOUT_SAVE_INTERVAL_TICKS) {
            layoutSaveTickCounter = 0;
            int layout = grid.getLayoutVersion();
            if (layout != lastSavedLayoutVersion) saveLayout();
        }

        // P0.1 per-tick correlation (docs/P0_REGIONTHREADPOOL_REDESIGN_SPEC.md follow-up):
        // pollTask total / region tick total / actual latch wait / main-thread work outside
        // pollTask, all for THIS tick's 5 awaitLatch() rounds combined. O(1) — a few subtractions
        // on already-accumulated counters, no scan.
        long nestworldTickWallNanos = System.nanoTime() - nestworldTickWallStart;
        long[] nestworldTickAcc = pool.nestworldDrainTickAccumulators();
        long nestworldDispatchTotal = nestworldTickAcc[0];
        long nestworldWaitTotal = nestworldTickAcc[1];
        long nestworldPollTotal = nestworldTickAcc[2];
        long nestworldRegionMaxTotal = nestworldTickAcc[3];
        long nestworldLatchWaitOnly = nestworldWaitTotal - nestworldPollTotal; // idle park portion
        long nestworldOutsidePollTask = nestworldTickWallNanos - nestworldDispatchTotal - nestworldWaitTotal;
        nestworldCorrelation.record(nestworldTickWallNanos, nestworldPollTotal, nestworldRegionMaxTotal,
                nestworldLatchWaitOnly, nestworldOutsidePollTask);

        // Spike log (NestworldTuning.SPIKE_LOG_THRESHOLD_NANOS): index WHEN + which phase
        // dominated, for correlating against the continuous spark profile SparkBridge is
        // already recording -- see that constant's javadoc. Cheap comparison on the
        // already-computed tick_wall; the logging/allocation below only runs on an actual
        // spike, never on the hot path otherwise.
        if (NestworldTuning.SPIKE_LOG_THRESHOLD_NANOS > 0
                && nestworldTickWallNanos >= NestworldTuning.SPIKE_LOG_THRESHOLD_NANOS) {
            nestworldLogSpike(nestworldTickWallNanos, t0, t1, t2, t3, t4, t5, t6);
        }
    }

    private static final String[] NESTWORLD_PHASE_NAMES =
            {"vanilla(+3-pool-phases)", "signals", "entityXfer", "regionPool(entity)", "ghostZones+tracker", "splitMerge"};

    /** Appends one line to {@code spark-spikes.txt} (same append-only convention as
     *  {@code spark-history.txt}) and logs a WARN: timestamp, tick_wall ms, and the
     *  dominant phase by cost -- so a spike found live in chat/log can be matched to the
     *  right moment in the continuous spark profile without guessing. */
    private void nestworldLogSpike(long tickWallNanos, long t0, long t1, long t2, long t3, long t4, long t5, long t6) {
        long[] phaseNs = {t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, t6 - t5};
        int dominant = 0;
        for (int i = 1; i < phaseNs.length; i++) if (phaseNs[i] > phaseNs[dominant]) dominant = i;
        double tickWallMs = tickWallNanos / 1e6;
        double dominantMs = phaseNs[dominant] / 1e6;
        double dominantPct = tickWallMs > 0 ? dominantMs / tickWallMs * 100.0 : 0.0;
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String line = String.format("%s  tick_wall=%.1fms  dominant=%s(%.1fms,%.0f%%)  regions=%d",
                stamp, tickWallMs, NESTWORLD_PHASE_NAMES[dominant], dominantMs, dominantPct,
                grid.getAllRegions().size());
        // Drill into ServerLevel's own THIS-TICK vanilla sub-phase snapshot when "vanilla"
        // is the dominant phase -- otherwise "dominant=vanilla" alone doesn't say whether
        // it was e.g. chunkSource (autosave/chunk I/O/gen) vs entityManagement vs blockEvents,
        // and those have completely different fixes. See ServerLevel.nestworldLastSubphaseNanos.
        if (dominant == 0) {
            long[] sub = net.minecraft.server.level.ServerLevel.nestworldLastSubphaseNanos;
            line += String.format("  [preChunkSource=%.1fms chunkSource=%.1fms blockEvents=%.1fms entitiesAndBE=%.1fms entityMgmt=%.1fms]",
                    sub[0] / 1e6, sub[1] / 1e6, sub[2] / 1e6, sub[3] / 1e6, sub[4] / 1e6);
        }
        LOGGER.warn("NestWorld tick spike: {}", line);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("spark-spikes.txt"),
                    line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            LOGGER.warn("spike log: failed to write spark-spikes.txt", t);
        }
    }

    /** Cumulative-since-boot breakdown of the 6 sequential tick phases (vanilla/signals/
     *  entityXfer/regionPool/ghostZones/splitMerge) -- sub-attributes the tick-correlation
     *  telemetry's undifferentiated "outside_pollTask" residual. Zero behaviour change,
     *  same discipline as every other /nestworld read-only stat command. */
    public String nestworldTickPhaseReport() {
        if (timedTicksCumulative == 0) return "NW tick phases: no ticks recorded yet";
        double n = timedTicksCumulative;
        double totalMs = 0;
        for (long v : phaseNanosCumulative) totalMs += v / 1e6;
        String[] names = {"vanilla(+3-pool-phases)", "signals", "entityXfer", "regionPool(entity)", "ghostZones+tracker", "splitMerge"};
        StringBuilder sb = new StringBuilder(String.format(
                "NW tick phases (cumulative since boot, %,d ticks, avg tick_wall=%.3fms):%n",
                timedTicksCumulative, totalMs / n));
        for (int i = 0; i < 6; i++) {
            double avgMs = phaseNanosCumulative[i] / 1e6 / n;
            double pct = totalMs > 0 ? (phaseNanosCumulative[i] / 1e6) / totalMs * 100.0 : 0.0;
            sb.append(String.format("  %-24s avg=%.4fms (%.1f%%)%n", names[i], avgMs, pct));
        }
        return sb.toString();
    }

    /** P0.1 per-tick correlation accumulator (cumulative averages + max, main-thread-only). */
    private final TickCorrelation nestworldCorrelation = new TickCorrelation();

    static final class TickCorrelation {
        long ticks = 0;
        long tickWallSum = 0, tickWallMax = 0;
        long pollSum = 0;
        long regionMaxSum = 0;
        long latchWaitSum = 0;
        long outsideSum = 0, outsideMax = 0;

        void record(long tickWall, long poll, long regionMax, long latchWait, long outside) {
            ticks++;
            tickWallSum += tickWall;
            if (tickWall > tickWallMax) tickWallMax = tickWall;
            pollSum += poll;
            regionMaxSum += regionMax;
            latchWaitSum += latchWait;
            outsideSum += outside;
            if (outside > outsideMax) outsideMax = outside;
        }

        String snapshot() {
            if (ticks == 0) return "no ticks recorded yet";
            return String.format(
                "tick correlation over %,d ticks (avg ms, tick_wall max=%.3fms):%n" +
                "  tick_wall=%.3f%n" +
                "   +-- pollTask_total     =%.3f%n" +
                "   +-- region_tick_total  =%.3f (max single region, summed across this tick's rounds)%n" +
                "   +-- actual_latch_wait  =%.3f (idle park, NOT doing pollTask work)%n" +
                "   +-- outside_pollTask   =%.3f (max=%.3fms) (ghostzones/splitmerge/entityXfer/vanilla-tick/etc.)%n",
                ticks, tickWallMax / 1e6,
                tickWallSum / 1e6 / ticks,
                pollSum / 1e6 / ticks,
                regionMaxSum / 1e6 / ticks,
                latchWaitSum / 1e6 / ticks,
                outsideSum / 1e6 / ticks, outsideMax / 1e6);
        }
    }

    public String nestworldPollTaskReport() {
        return net.nestworld.region.PollTaskAttribution.snapshot() + "\n" + nestworldCorrelation.snapshot()
                + "\n" + net.nestworld.region.DistanceManagerAttribution.snapshot();
    }

    /** P1.0 audit (docs/P1_WORLDGEN_MAINTHREAD_DECOUPLING_SPEC.md): worldgen/main-thread boundary. */
    public String nestworldWorldgenBoundaryReport() {
        return net.nestworld.region.PollTaskAttribution.boundarySnapshot();
    }

    /**
     * P1.2/P1.4: combined A (vanilla main-thread glue) + B (our Tier bookkeeping, already
     * instrumented by DistanceManagerAttribution + DistanceManager's own promotion counters) + C
     * (chunk-finalize work, confirmed already main-thread) report.
     */
    public String nestworldWorldgenGlueReport() {
        net.minecraft.server.level.ServerChunkCache cache =
                (net.minecraft.server.level.ServerChunkCache) overworld.getChunkSource();
        net.minecraft.server.level.DistanceManager dm = cache.chunkMap.getDistanceManager();
        StringBuilder sb = new StringBuilder();
        sb.append(net.nestworld.region.WorldgenGlueAttribution.snapshot());
        sb.append(String.format(
                "  B tier_update (from DistanceManagerAttribution TIER1*/TIER2*/VALIDATOR_BATCH -- see /nestworld polltask)%n"));
        sb.append(String.format(
                "  B promotion (updateFutures) applied=%,d avg=%.4fms p50=%.4fms p95=%.4fms p99=%.4fms max=%.4fms%n",
                dm.nestworldPromotionTotalApplied(), dm.nestworldPromotionAvgMs(),
                dm.nestworldPromotionPercentileMs(0.50), dm.nestworldPromotionPercentileMs(0.95),
                dm.nestworldPromotionPercentileMs(0.99), dm.nestworldPromotionMaxMs()));
        return sb.toString();
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
     * See {@link NestworldTuning#BORDER_BAND_CHUNKS} (single source of truth,
     * shared with {@link RegionSplitManager}'s split-veto scoring).
     */
    private static final int BORDER_BAND_CHUNKS = NestworldTuning.BORDER_BAND_CHUNKS;

    /** Wire updates whose network left its region; re-run on main next tick. Stage 2
     *  (docs/LOCAL_TICK_STAGE4.md): wrapped in the tagged RegionMessage shape — this is
     *  the ACTUALLY-WIRED "Deferred" tier precedent (BoundarySignalQueue, despite its
     *  javadoc, is dead code — enqueue() is never called anywhere in the repo). */
    private final java.util.Queue<RegionMessage<net.minecraft.core.BlockPos>> deferredWireUpdates =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public void deferWireUpdate(WorldRegion sourceRegion, net.minecraft.core.BlockPos pos) {
        deferredWireUpdates.add(RegionMessage.wireUpdate(sourceRegion, server.getTickCount(), pos.immutable()));
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
    // NestWorld DIAG (2026-08-11, chasing the LevelTicks NPE found under Layer 13.1's
    // heavy sustained chunk-gen load -- crash-2026-08-11_02.35.16-server.txt): captures
    // the thread identity this phase is expected to always run on, and a flag other code
    // can check to detect a concurrent/reentrant mutation of the shared per-level
    // LevelTicks structure while this phase is iterating it. Diagnostic only -- no
    // behaviour change, just loud logging if the invariant is ever violated. See project
    // memory: leveticks-race-under-heavy-chunkgen.
    private static volatile Thread nestworldMainThreadIdentity = null;
    public static volatile boolean nestworldLevelTicksIterating = false;

    /** NestWorld DIAG: true if called from any thread other than the captured main
     * tick thread (or if that identity hasn't been captured yet -- treated as unknown,
     * not a violation, since it just means runScheduledTicksPhase hasn't run yet). */
    public static boolean nestworldIsOffMainThread() {
        Thread main = nestworldMainThreadIdentity;
        return main != null && main != Thread.currentThread();
    }

    // NestWorld (2026-08-11, fix for the CONFIRMED race above -- see project memory
    // leveticks-race-under-heavy-chunkgen "Update 3"): a free-running region's own
    // thread calling vanilla's level.scheduleTick(...) (e.g. water rescheduling its own
    // follow-up flow tick) used to land in LevelTicks.schedule() directly on that
    // thread -- racing this exact phase's unsynchronized LevelTicks.tick() iteration.
    // Confirmed live: 267 ownership-assertion violations in ~15-20min of real water
    // flow inside a free-running region. Fix (Gemini-reviewed 2026-08-11, "proceed with
    // this design"): LevelAccessor's 4 scheduleTick() default methods now redirect a
    // free-running-region-thread caller here instead of calling .schedule() directly;
    // drained on the OWNER thread (this phase, before LevelTicks.tick() runs) so a
    // freshly-enqueued request gets registered same-tick when timing allows, next-tick
    // at worst -- never lost (LevelTicks.tick() doesn't discard already-due entries).
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> nestworldPendingMainThreadScheduleTicks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Called from LevelAccessor's scheduleTick() default methods. True only for a
     * region thread whose region is currently free-running -- a normal barrier-synced
     * region thread never calls vanilla block/fluid tick logic outside the main
     * thread's own latch-waited work-round, so this deliberately does NOT redirect for
     * those (would just add pointless queue overhead with no race to prevent there). */
    public static boolean nestworldIsFreeRunningRegionThread() {
        return NestworldTuning.FREE_RUNNING_REGIONS_ENABLED
                && Thread.currentThread() instanceof RegionThread rt
                && rt.getRegion().isFreeRunning();
    }

    // NestWorld (2026-08-12): broadened the above fix after a SECOND, distinct LevelTicks
    // race was confirmed live under real multi-player load with free-running regions OFF
    // (crash-2026-08-12_22.20.39-server.txt -- NPE in Long2LongOpenHashMap$MapIterator
    // during runScheduledTicksPhase's LevelTicks.tick() iteration, Thread: Server thread).
    // The assumption above -- "a normal barrier-synced region thread never calls vanilla
    // block/fluid tick logic outside the main thread's own latch-waited work-round" -- does
    // NOT hold: the P2 barrier audit (docs/P2_AUDIT_RESULTS.md, Q6) separately confirmed
    // awaitLatch() has no cancellation on its 30s timeout and the pool has no busy-check
    // before re-dispatching, so a region thread can still be executing (and therefore still
    // able to call scheduleTick() -> LevelTicks.schedule() directly) after the main thread
    // has already moved on into a LATER tick's runScheduledTicksPhase() call, racing that
    // call's LevelTicks.tick() iteration on the SAME underlying map. Under severe real-play
    // lag (observed live: region tick costs up to 967ms, MSPT up to 231ms) this window is
    // real, not theoretical. Fix: redirect EVERY region thread's scheduleTick() call through
    // the same already-proven-safe deferred queue, not just free-running ones -- strictly a
    // superset of the shipped fix, same mechanism, same "never lost, next-tick at worst"
    // guarantee documented on nestworldEnqueueScheduleTick below.
    public static boolean nestworldIsRegionThread() {
        return Thread.currentThread() instanceof RegionThread;
    }

    /** Enqueues a LevelTicks.schedule(...) call to run on the main thread. `apply` must
     * close over nothing but immutable values (the ScheduledTick itself, and the
     * dimension-scoped LevelTickAccess reference) -- never a live WorldRegion/
     * RegionThread reference, so this stays correct even if the originating region's
     * free-running status or the queue's drain timing changes between enqueue and
     * drain (see design notes in project memory). */
    public static void nestworldEnqueueScheduleTick(Runnable apply) {
        nestworldPendingMainThreadScheduleTicks.add(apply);
    }

    private void nestworldDrainPendingScheduleTicks() {
        Runnable r;
        while ((r = nestworldPendingMainThreadScheduleTicks.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("Deferred free-running scheduleTick apply failed: {}", t.toString());
            }
        }
    }

    public void runScheduledTicksPhase(ServerLevel level, long gameTime) {
        NestworldTickOwnership.noteServerTick(gameTime);
        Thread nestworldHere = Thread.currentThread();
        if (nestworldMainThreadIdentity == null) {
            nestworldMainThreadIdentity = nestworldHere;
        } else if (nestworldMainThreadIdentity != nestworldHere) {
            LOGGER.error("NestWorld DIAG: runScheduledTicksPhase called from unexpected thread '{}' (expected '{}')",
                    nestworldHere.getName(), nestworldMainThreadIdentity.getName());
        }

        nestworldDrainPendingScheduleTicks();

        java.util.Map<WorldRegion, java.util.List<Runnable>> buckets = new java.util.IdentityHashMap<>();
        java.util.List<Runnable> mainBucket = new java.util.ArrayList<>();

        nestworldLevelTicksIterating = true;
        try {
            level.getBlockTicks().tick(gameTime, 65536, (pos, block) ->
                    routeScheduledTick(pos, () -> level.nestworldRunBlockTick(pos, block), buckets, mainBucket));
            level.getFluidTicks().tick(gameTime, 65536, (pos, fluid) ->
                    routeScheduledTick(pos, () -> level.nestworldRunFluidTick(pos, fluid), buckets, mainBucket));
        } finally {
            nestworldLevelTicksIterating = false;
        }

        for (Runnable r : mainBucket) {
            try {
                r.run();
            } catch (Throwable t) {
                LOGGER.warn("Main-band scheduled tick failed: {}", t.toString());
            }
        }
        pool.runWorkRound(buckets, RegionPhase.SCHEDULED_TICK);
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
        pool.runWorkRound(randomTickBuckets, RegionPhase.RANDOM_TICK);
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
        // E5: bucket the TickingBlockEntity objects themselves and submit ONE composite Runnable
        // per region (was: a Runnable per BE — 5k BEs → 200k transient lambdas/s — plus an
        // auto-pin wrapper lambda each). Per-BE isolation is preserved inside tickBlockEntityList
        // (per-BE try/catch + auto-pin error note), so one broken BE still cannot kill the round.
        java.util.Map<WorldRegion, java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity>> beBuckets =
                new java.util.IdentityHashMap<>();
        java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> mainBes = new java.util.ArrayList<>();
        boolean traced = false;
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : due) {
            net.minecraft.core.BlockPos pos = ticker.getPos();
            if (BE_TRACE_POS != null && BE_TRACE_POS.equals(pos)) traced = true;
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            String typeId = ticker.getType();
            // Pinned BE types always tick on main (mod compat), regardless of position —
            // takes priority over cascade-safe. Cascade-safe types (operator-asserted: never
            // write a neighbouring block, never trigger redstone/piston) skip the border-band
            // inset check entirely and go straight to their true owning region, even inside
            // another region's border band; everything else keeps the conservative check.
            boolean pinned = !pins.isBeEmpty() && pins.isBePinned(typeId);
            boolean cascadeSafe = !pinned && !pins.isCascadeSafeBeEmpty() && pins.isCascadeSafeBe(typeId);
            // Heat is recorded either way (real load, informs cut position); only load that
            // still needs border-band protection counts toward the split veto.
            if (pinned || cascadeSafe) {
                blockTickHeat.record(cx, cz);
            } else {
                blockTickHeat.recordVetoable(cx, cz);
            }
            WorldRegion region = pinned ? null
                    : cascadeSafe ? grid.getRegionForChunk(cx, cz)
                    : interiorRegionFor(cx, cz);
            if (region == null) {
                mainBes.add(ticker);
            } else {
                beBuckets.computeIfAbsent(region, r -> new java.util.ArrayList<>()).add(ticker);
            }
        }
        if (BE_TRACE_POS != null && !traced && ++beTraceCountdown >= 40) {
            beTraceCountdown = 0;
            LOGGER.info("BE trace {}: NOT in due list (not collected on main)", BE_TRACE_POS);
        }
        boolean logNow = ++bePhaseLogCountdown >= 200;
        if (logNow) {
            bePhaseLogCountdown = 0;
            StringBuilder sb = new StringBuilder();
            for (var e : beBuckets.entrySet()) {
                sb.append(" #").append(e.getKey().getId()).append('=').append(e.getValue().size());
            }
            LOGGER.info("BE phase: due={} main={} buckets:{}", due.size(), mainBes.size(), sb);
        }
        tickBlockEntityList(level, mainBes);
        // Batches of 32 keep the work-round budget granular (runWorkBudgeted checks its deadline
        // between runnables and defers the remainder) while still cutting the per-BE lambda churn
        // ~32×. One giant composite per region would run unbudgeted.
        final int nestworldBeBatch = 32;
        java.util.Map<WorldRegion, java.util.List<Runnable>> buckets = new java.util.IdentityHashMap<>();
        for (var e : beBuckets.entrySet()) {
            final java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> list = e.getValue();
            java.util.List<Runnable> runs = new java.util.ArrayList<>((list.size() + nestworldBeBatch - 1) / nestworldBeBatch);
            for (int i = 0; i < list.size(); i += nestworldBeBatch) {
                final java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> slice =
                        list.subList(i, Math.min(i + nestworldBeBatch, list.size()));
                runs.add(() -> tickBlockEntityList(level, slice));
            }
            buckets.put(e.getKey(), runs);
        }
        int before = buckets.size();
        pool.runWorkRoundDropIfBacklogged(buckets, RegionPhase.BLOCK_ENTITY);
        if (logNow && buckets.size() != before) {
            LOGGER.info("BE phase: {} region bucket(s) dropped (backlog)", before - buckets.size());
        }
    }

    /** Ticks a bucket of block entities with per-BE isolation: a throwing BE is logged (and fed
     *  to the auto-pin detector) and the rest of the bucket still runs — same semantics as the
     *  former one-Runnable-per-BE dispatch, without the per-BE lambda churn. */
    private void tickBlockEntityList(ServerLevel level,
                                     java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> list) {
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : list) {
            if (BE_TRACE_POS != null && BE_TRACE_POS.equals(ticker.getPos())) {
                net.minecraft.core.BlockPos pos = ticker.getPos();
                int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
                net.minecraft.world.level.chunk.LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                LOGGER.info("BE trace exec {} on [{}]: removed={} fullStatus={} entitiesLoaded={}",
                        pos, Thread.currentThread().getName(), ticker.isRemoved(),
                        c == null ? "NO_CHUNK" : c.getFullStatus(),
                        level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz)));
            }
            try {
                ticker.tick();
            } catch (Throwable err) {
                if (pins.isAutoPinEnabled()) pins.noteBlockEntityTickError(ticker.getType());
                LOGGER.warn("Block entity tick failed at {}: {}", ticker.getPos(), err.toString());
            }
        }
    }

    /** The region owning chunk (cx,cz) if the chunk sits strictly inside its interior (outside
     *  the border band); null routes the work to the main-thread bucket. Shared routing rule of
     *  the parallel work rounds (see routeScheduledTick). */
    private WorldRegion interiorRegionFor(int cx, int cz) {
        WorldRegion region = grid.getRegionForChunk(cx, cz);
        if (region != null
                && cx >= region.getMinChunkX() + BORDER_BAND_CHUNKS && cx <= region.getMaxChunkX() - BORDER_BAND_CHUNKS
                && cz >= region.getMinChunkZ() + BORDER_BAND_CHUNKS && cz <= region.getMaxChunkZ() - BORDER_BAND_CHUNKS) {
            return region;
        }
        return null;
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
        pool.runWorkRound(buckets, RegionPhase.BLOCK_EVENT);
    }

    private void routeScheduledTick(net.minecraft.core.BlockPos pos, Runnable run,
                                    java.util.Map<WorldRegion, java.util.List<Runnable>> buckets,
                                    java.util.List<Runnable> mainBucket) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        // Record block-tick load for the split scorer regardless of which side
        // of the band it lands on — a hot column currently stuck in the band is
        // exactly what we want the next split to see and route into an interior.
        // Scheduled ticks/fluid ticks/block events always need border-band
        // protection (no cascade-safe concept applies here), so this always
        // counts toward the split veto.
        blockTickHeat.recordVetoable(cx, cz);
        WorldRegion region = interiorRegionFor(cx, cz);
        if (region != null) {
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

    /** The sharded level this instance manages — used by patches to guard region-aware
     *  routing (e.g. cross-region block-entity reads) against other dimensions, where
     *  chunk coordinates can numerically collide with the overworld's but must never be
     *  resolved through its region grid. */
    public ServerLevel getOverworld()                 { return overworld; }
    public WorldGrid getGrid()                        { return grid; }
    public BoundaryManager getBoundaryManager()        { return boundaryManager; }

    /**
     * Step 3 (docs/LOCAL_TICK_STAGE4.md, "Step 3 — Single Free-Running Region", design
     * point 3): called from {@code ServerLevel.save()} before chunk/entity serialization.
     * Requests a save rendezvous from every currently free-running region belonging to
     * this level's overworld (in Step 3, at most one) and waits — bounded, same
     * timeout-not-indefinite-block philosophy as every other cross-region wait in this
     * codebase — for each to confirm it has paused at a safe point (between its own
     * local ticks, never mid-tick). A region that doesn't respond in time is logged and
     * the save proceeds anyway rather than risking hanging the whole save indefinitely —
     * a rare timeout means that region's data might reflect a slightly newer tick than
     * intended, not corruption (its OWN thread is what's writing that data, just not
     * demonstrably paused at the exact moment save started).
     *
     * @return the paused regions, to hand back to {@link #nestworldResumeFreeRunningRegionsAfterSave}
     */
    public static java.util.List<WorldRegion> nestworldPauseFreeRunningRegionsForSave(ServerLevel level) {
        if (!NestworldTuning.FREE_RUNNING_REGIONS_ENABLED || !isInitialised()
                || level != INSTANCE.overworld) {
            return java.util.List.of();
        }
        java.util.List<WorldRegion> freeRunning = new java.util.ArrayList<>();
        java.util.List<java.util.concurrent.CountDownLatch> paused = new java.util.ArrayList<>();
        for (WorldRegion region : INSTANCE.grid.getAllRegions()) {
            if (region.isFreeRunning()) {
                freeRunning.add(region);
                paused.add(region.nestworldRequestSaveRendezvous());
            }
        }
        for (int i = 0; i < freeRunning.size(); i++) {
            try {
                boolean confirmed = paused.get(i).await(
                        NestworldTuning.FREE_RUNNING_SAVE_RENDEZVOUS_TIMEOUT_NANOS, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (!confirmed) {
                    LOGGER.warn("Region #{} did not confirm save rendezvous within {}ms — saving anyway",
                            freeRunning.get(i).getId(),
                            NestworldTuning.FREE_RUNNING_SAVE_RENDEZVOUS_TIMEOUT_NANOS / 1_000_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return freeRunning;
    }

    /** Releases every region {@link #nestworldPauseFreeRunningRegionsForSave} paused. */
    public static void nestworldResumeFreeRunningRegionsAfterSave(java.util.List<WorldRegion> paused) {
        for (WorldRegion region : paused) {
            region.nestworldReleaseSaveRendezvous();
        }
    }

    /** NestWorld: Stage 3 generic block-write guard (docs/LOCAL_TICK_STAGE4.md) — called
     *  from {@code Level.setBlock()}'s NestWorld guard when a region thread targets a
     *  position outside its own bounds. Computes the vanilla-equivalent boolean result
     *  synchronously via the SAFE (Tier 0) {@code getBlockState()} read — cheap and
     *  correct from any thread, unlike the actual mutation — and defers the real write
     *  to a {@link RegionMessage} applied on the main thread during {@code
     *  tickAllRegions()}'s step 4c, the same barrier-safe point Explosion's Stage 3
     *  batches already use. Callers relying on the return value (e.g. mob-AI follow-up
     *  logic: drop items, spawn particles, only if something actually changed) see the
     *  correct answer immediately; the visible world state itself lags by up to one
     *  region-tick round, same latency class already accepted throughout Stage 1-3. */
    public boolean nestworldDeferForeignBlockWrite(WorldRegion source, WorldRegion destination,
            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState newState,
            int flags, int recursionLeft) {
        boolean nestworldWouldChange = !overworld.getBlockState(pos).equals(newState);
        source.nestworldSendOrQueue(destination, RegionMessage.blockWrite(source, destination, server.getTickCount(),
                new RegionMessage.BlockWrite(pos.immutable(), newState, flags, recursionLeft)));
        return nestworldWouldChange;
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 3 (docs/LOCAL_TICK_STAGE4.md, "Stage 5
     * tick-scheduler architecture — DECIDED", Blocker 3 read-side). Applies {@code
     * applyWork} only after acquiring the {@code chunkLock} of every region within
     * {@link NestworldTuning#CASCADE_SAFETY_MARGIN_BLOCKS} of every position in {@code
     * positions} — the set whose territory a piston push or redstone cascade triggered
     * by this write could plausibly reach. Locks are acquired in ascending region-ID
     * order (deadlock avoidance, same rule any future multi-region lock acquisition in
     * this codebase must follow) with a bounded timeout ({@link
     * NestworldTuning#CASCADE_LOCK_TIMEOUT_NANOS}). On timeout: does NOT apply — re-posts
     * {@code msg} to {@code destination}'s own mailbox so it is retried on a later pass,
     * the same "eventually applied" Tier 2 contract every other deferred write already
     * uses, not a new failure mode.
     *
     * <p>Under today's still-barrier-synchronized model this is always uncontended —
     * every region thread is already parked at the point this runs (step 4b/4c, after
     * {@code pool.tickAllRegions()}'s barrier) — so lock acquisition here always succeeds
     * immediately. It becomes load-bearing only once regions go free-running (Part 5),
     * at which point a border-band-deferred write's cascade could otherwise race a
     * neighbouring region's own concurrent tick. Deliberately built and tested now, while
     * harmless, rather than deferred until Part 5 needs it for the first time.
     */
    private void nestworldApplyWithCascadeGuard(WorldRegion destination, RegionMessage<?> msg,
            java.util.Collection<net.minecraft.core.BlockPos> positions, Runnable applyWork) {
        java.util.TreeSet<WorldRegion> toLock = new java.util.TreeSet<>(
                java.util.Comparator.comparingInt(WorldRegion::getId));
        for (net.minecraft.core.BlockPos pos : positions) {
            toLock.addAll(grid.getRegionsWithinMargin(pos, NestworldTuning.CASCADE_SAFETY_MARGIN_BLOCKS));
        }
        java.util.List<WorldRegion> locked = new java.util.ArrayList<>(toLock.size());
        java.util.List<Long> stamps = new java.util.ArrayList<>(toLock.size());
        long deadline = System.nanoTime() + NestworldTuning.CASCADE_LOCK_TIMEOUT_NANOS;
        try {
            for (WorldRegion r : toLock) {
                long remainingNanos = deadline - System.nanoTime();
                long stamp = 0L;
                if (remainingNanos > 0) {
                    try {
                        stamp = r.getChunkLock().tryWriteLock(remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (stamp == 0L) {
                    LOGGER.warn("Cascade guard: timed out locking region {} for a deferred write near {} "
                            + "— re-queued for a later pass", r.getId(), positions);
                    destination.nestworldRequeueMessage(msg); // NOT a new "sent" — same message, still pending
                    return;
                }
                locked.add(r);
                stamps.add(stamp);
            }
            applyWork.run();
            MailboxAudit.recordApplied(msg.auditId(), destination, msg.messageType());
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).getChunkLock().unlockWrite(stamps.get(i));
            }
        }
    }

    public RegionTree getTree()                       { return tree; }
    public RegionThreadPool getPool()                 { return pool; }
    public RegionSplitManager getSplitManager()       { return splitManager; }
    BlockTickHeat getBlockTickHeat()                  { return blockTickHeat; }
    public BoundarySignalQueue getSignalQueue()       { return signalQueue; }
    public BoundaryEntityTransfer getEntityTransfer() { return entityTransfer; }
    public CrossRegionCapabilityBus getCapabilityBus(){ return capabilityBus; }
    public RegionChunkView getChunkView()             { return chunkView; }
}
