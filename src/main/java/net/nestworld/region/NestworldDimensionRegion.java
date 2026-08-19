package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Region-sharding for Nether/End, Stage 1: one dimension NestWorld manages, and everything
 * needed to run that dimension's own region-parallel tick — the "physical field migration"
 * explicitly deferred from Stage 0a (see that stage's own scope-correction, in project memory
 * as "nether-end-sharding-stage0a-done"). {@code NestworldRegionSystem} keeps exactly the state
 * that's genuinely dimension-agnostic (the {@code dimensions} map itself, shared {@code pins},
 * the single shared main-thread identity, the destination-keyed portal/dimension-change defer
 * queues, which were already built dimension-aware in Stage 0.5) and delegates everything else
 * — the actual tick machinery, one instance per managed dimension.
 *
 * <p>Field/method ownership here mirrors {@code NestworldRegionSystem}'s pre-Stage-1 shape
 * closely on purpose: most of {@link #tick(BooleanSupplier)}'s body is the former {@code
 * tickAllRegions()} moved verbatim, with {@code overworld} references replaced by {@code
 * this.level} — minimizing the risk of subtly changing tick-orchestration logic while moving it.
 */
public final class NestworldDimensionRegion {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionSystem");

    private final ServerLevel level;
    private final ResourceKey<Level> key;

    // --- Subsystems ---
    private final WorldGrid grid;
    private final RegionTree tree;
    private final RegionThreadPool pool;
    private final RegionSplitManager splitManager;
    private final BoundaryManager boundaryManager;
    private final BoundarySignalQueue signalQueue;
    private final BoundaryEntityTransfer entityTransfer;
    private final CrossRegionCapabilityBus capabilityBus;
    private final RegionChunkView chunkView;

    /** Per-chunk block-tick load signal, feeding the load-aware split scorer. Per-dimension:
     *  raw chunk coordinates numerically collide across dimensions (same hazard {@link WorldGrid}
     *  itself is stamped against), so a shared instance would let one dimension's block-tick
     *  load corrupt another's split scoring. */
    private final BlockTickHeat blockTickHeat = new BlockTickHeat();

    /** Layer 3: pre-generate the frontier ahead of moving players (default off). Per-dimension
     *  so a player's last-known position isn't mixed across a dimension crossing. */
    private final PredictiveChunkGen predictiveGen = new PredictiveChunkGen();

    /** Entities spawned by region threads (off-main addFreshEntity), drained on
     *  main each tick so ChunkMap entity tracking is never mutated concurrently.
     *  Bounded (anti-grief spawn-flood cap) — offer() refuses atomically when full,
     *  so there is no separate counter to fall out of sync with the queue. */
    private final LinkedBlockingQueue<Entity> deferredSpawns =
            new LinkedBlockingQueue<>(NestworldTuning.DEFERRED_SPAWN_QUEUE_CAP);

    /** Entity-tracking removals queued by region threads (off-main discard, e.g.
     *  TNT consumed by an explosion), drained on main each tick so ChunkMap's
     *  non-thread-safe entityMap is never mutated concurrently with its own tick
     *  iteration. Symmetric with {@link #deferredSpawns}; bounded by the live
     *  entity count, so no cap is needed (you cannot remove more than exist). */
    private final Queue<Entity> deferredRemovals = new ConcurrentLinkedQueue<>();

    /** Entity-tracking ADDS queued by region threads: a section-move visibility transition
     *  (entity walks/teleports into an entity-ticking section during a region tick) calls
     *  ServerChunkCache.addEntity off-main, which must not touch ChunkMap's entityMap.
     *  Drained on main AFTER {@link #deferredRemovals}, so a leave+re-enter within one tick
     *  resolves to the correct final tracked state. Bounded by the live entity count. */
    private final Queue<Entity> deferredTrackingAdds = new ConcurrentLinkedQueue<>();

    /** Wire updates whose network left its region; re-run on main next tick. Stage 2
     *  (docs/LOCAL_TICK_STAGE4.md): wrapped in the tagged RegionMessage shape. */
    private final Queue<RegionMessage<BlockPos>> deferredWireUpdates = new ConcurrentLinkedQueue<>();

    private final Map<WorldRegion, List<Runnable>> randomTickBuckets = new IdentityHashMap<>();

    // Phase timing accumulators (ns), logged every TIMING_LOG_INTERVAL ticks.
    private static final boolean TIMING_LOG =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.timingLog", "true"));
    private static final int TIMING_LOG_INTERVAL = 200;
    private final long[] phaseNanos = new long[6];
    private int timedTicks = 0;
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

    // Self-test driven by env var NESTWORLD_RANDOMTICK_TEST=<speed>.
    private static final int RANDOMTICK_TEST_SPEED =
            Integer.parseInt(System.getenv().getOrDefault("NESTWORLD_RANDOMTICK_TEST", "-1"));

    private static final int BORDER_BAND_CHUNKS = NestworldTuning.BORDER_BAND_CHUNKS;

    /** When true, region borders near players are outlined with particles (shared toggle,
     *  see {@link NestworldRegionSystem#showBorders}). */
    private static final int BORDER_VIEW_RANGE = 96;
    private int borderParticleTimer = 0;

    private int bePhaseLogCountdown = 0;
    private int beTraceCountdown = 0;
    private static final BlockPos BE_TRACE_POS;
    static {
        String s = System.getProperty("nestworld.beTracePos");
        BlockPos p = null;
        if (s != null) {
            String[] parts = s.split(",");
            p = new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
        BE_TRACE_POS = p;
    }

    /** P0.1 per-tick correlation accumulator (cumulative averages + max, main-thread-only). */
    private final TickCorrelation correlation = new TickCorrelation();

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

    /**
     * Constructs every subsystem for {@code level}. {@code sharedPins} is the ONE
     * cross-dimension pin table (operator-facing pin/unpin is dimension-independent by
     * nature — see {@link NestworldRegionSystem#getPins()}), passed in rather than owned here.
     * {@code savedLayoutOrNull} is this dimension's own persisted BSP layout, if any.
     */
    NestworldDimensionRegion(ServerLevel level, NestworldPins sharedPins, CompoundTag savedLayoutOrNull) {
        this.level = level;
        this.key = level.dimension();

        this.grid = new WorldGrid(key);
        boolean restored = savedLayoutOrNull != null;
        if (restored) {
            this.tree = new RegionTree(grid, savedLayoutOrNull);
        } else {
            int r = NestworldRegionSystem.INITIAL_REGION_HALF_SPAN;
            this.tree = new RegionTree(grid, new WorldRegion(grid.nextId(), key, -r, -r, r, r));
        }
        this.pool = new RegionThreadPool(level);
        this.boundaryManager = new BoundaryManager(level, grid);
        this.signalQueue = new BoundarySignalQueue(level);
        this.entityTransfer = new BoundaryEntityTransfer(level, grid, sharedPins);
        this.capabilityBus = new CrossRegionCapabilityBus(level, grid, boundaryManager);
        this.chunkView = new RegionChunkView(grid, boundaryManager);
        this.splitManager = new RegionSplitManager(tree, pool, blockTickHeat);

        for (WorldRegion region : tree.getActiveRegions()) pool.spawn(region);

        if (restored) {
            LOGGER.info("NestWorld restored saved layout for {} — {} region(s): {}",
                    key.location(), tree.getActiveRegions().size(), tree.getActiveRegions());
        } else {
            LOGGER.info("NestWorld started for {} — initial region: {}",
                    key.location(), tree.getActiveRegions().get(0));
        }
    }

    // --- Identity/accessors ---

    public ServerLevel getLevel() { return level; }
    public ResourceKey<Level> getKey() { return key; }
    public WorldGrid getGrid() { return grid; }
    public RegionTree getTree() { return tree; }
    public RegionThreadPool getPool() { return pool; }
    public RegionSplitManager getSplitManager() { return splitManager; }
    public BoundaryManager getBoundaryManager() { return boundaryManager; }
    public BoundarySignalQueue getSignalQueue() { return signalQueue; }
    public BoundaryEntityTransfer getEntityTransfer() { return entityTransfer; }
    public CrossRegionCapabilityBus getCapabilityBus() { return capabilityBus; }
    public RegionChunkView getChunkView() { return chunkView; }
    BlockTickHeat getBlockTickHeat() { return blockTickHeat; }

    /** The region owning chunk (cx,cz) if the chunk sits strictly inside its interior (outside
     *  the border band); null routes the work to the main-thread bucket. */
    WorldRegion interiorRegionFor(int cx, int cz) {
        WorldRegion region = grid.getRegionForChunk(cx, cz);
        if (region != null
                && cx >= region.getMinChunkX() + BORDER_BAND_CHUNKS && cx <= region.getMaxChunkX() - BORDER_BAND_CHUNKS
                && cz >= region.getMinChunkZ() + BORDER_BAND_CHUNKS && cz <= region.getMaxChunkZ() - BORDER_BAND_CHUNKS) {
            return region;
        }
        return null;
    }

    void routeScheduledTick(BlockPos pos, Runnable run,
                             Map<WorldRegion, List<Runnable>> buckets, List<Runnable> mainBucket) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        blockTickHeat.recordVetoable(cx, cz);
        WorldRegion region = interiorRegionFor(cx, cz);
        if (region != null) {
            buckets.computeIfAbsent(region, r -> new ArrayList<>()).add(run);
        } else {
            mainBucket.add(run);
        }
    }

    void deferWireUpdate(WorldRegion sourceRegion, BlockPos pos) {
        deferredWireUpdates.add(RegionMessage.wireUpdate(sourceRegion, level.getServer().getTickCount(), pos.immutable()));
    }

    boolean queueEntitySpawn(Entity e) {
        return deferredSpawns.offer(e);
    }

    private void drainDeferredSpawns() {
        long deadlineNanos = System.nanoTime() + NestworldTuning.DEFERRED_SPAWN_BUDGET_NANOS;
        Entity e;
        int nestworldProcessed = 0;
        while ((e = deferredSpawns.poll()) != null) {
            try {
                level.addFreshEntity(e);
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity spawn failed: {}", t.toString());
            }
            nestworldProcessed++;
            if (nestworldProcessed >= NestworldTuning.MAX_DEFERRED_SPAWNS_PER_TICK) break;
            if ((nestworldProcessed & 15) == 0 && System.nanoTime() >= deadlineNanos) break;
        }
    }

    void queueEntityRemoval(Entity e) {
        deferredRemovals.add(e);
    }

    private void drainDeferredRemovals() {
        Entity e;
        while ((e = deferredRemovals.poll()) != null) {
            try {
                level.getChunkSource().removeEntity(e);
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity removal failed: {}", t.toString());
            }
        }
    }

    void queueEntityTrackingAdd(Entity e) {
        deferredTrackingAdds.add(e);
    }

    private void drainDeferredTrackingAdds() {
        Entity e;
        while ((e = deferredTrackingAdds.poll()) != null) {
            if (e.isRemoved()) continue;
            try {
                level.getChunkSource().addEntity(e);
            } catch (IllegalStateException dup) {
                LOGGER.debug("Deferred tracking add skipped (already tracked): {}", e.getUUID());
            } catch (Throwable t) {
                LOGGER.warn("Deferred entity tracking add failed: {}", t.toString());
            }
        }
    }

    /** Called from the patched ServerLevel.tickChunk (via {@link NestworldRegionSystem#queueRandomTicksFor}). */
    void queueRandomTick(WorldRegion region, Runnable work) {
        randomTickBuckets.computeIfAbsent(region, r -> new ArrayList<>()).add(work);
    }

    /** Runs the queued per-chunk random ticks on their region threads (parallel). */
    void flushRandomTicksPhase() {
        if (randomTickBuckets.isEmpty()) return;
        pool.runWorkRound(randomTickBuckets, RegionPhase.RANDOM_TICK);
        randomTickBuckets.clear();
    }

    void runScheduledTicksPhase(long gameTime) {
        // NestworldTickOwnership / nestworldMainThreadIdentity / nestworldLevelTicksIterating /
        // nestworldDrainPendingScheduleTicks stay on NestworldRegionSystem — genuinely shared
        // (one main thread across every dimension); see NestworldRegionSystem.runScheduledTicksPhase.
        Map<WorldRegion, List<Runnable>> buckets = new IdentityHashMap<>();
        List<Runnable> mainBucket = new ArrayList<>();

        NestworldRegionSystem.nestworldLevelTicksIterating = true;
        try {
            level.getBlockTicks().tick(gameTime, 65536, (pos, block) ->
                    routeScheduledTick(pos, () -> level.nestworldRunBlockTick(pos, block), buckets, mainBucket));
            level.getFluidTicks().tick(gameTime, 65536, (pos, fluid) ->
                    routeScheduledTick(pos, () -> level.nestworldRunFluidTick(pos, fluid), buckets, mainBucket));
        } finally {
            NestworldRegionSystem.nestworldLevelTicksIterating = false;
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

    /** Drains and runs the level's block-event queue, bucketed per region. */
    void runBlockEventsPhase() {
        List<net.minecraft.world.level.BlockEventData> due = level.nestworldDrainBlockEvents();
        if (due.isEmpty()) return;

        Map<WorldRegion, List<Runnable>> buckets = new IdentityHashMap<>();
        List<Runnable> mainBucket = new ArrayList<>();
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

    /** Ticks a bucket of block entities with per-BE isolation. {@code pins} is the shared
     *  cross-dimension pin table (passed in — not owned here, see the constructor javadoc). */
    private void tickBlockEntityList(NestworldPins pins,
                                      List<net.minecraft.world.level.block.entity.TickingBlockEntity> list) {
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : list) {
            if (BE_TRACE_POS != null && BE_TRACE_POS.equals(ticker.getPos())) {
                BlockPos pos = ticker.getPos();
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

    /** Buckets and runs the tickers collected by the patched tickBlockEntities. */
    void runBlockEntityPhase(NestworldPins pins,
                              List<net.minecraft.world.level.block.entity.TickingBlockEntity> due) {
        Map<WorldRegion, List<net.minecraft.world.level.block.entity.TickingBlockEntity>> beBuckets =
                new IdentityHashMap<>();
        List<net.minecraft.world.level.block.entity.TickingBlockEntity> mainBes = new ArrayList<>();
        boolean traced = false;
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker : due) {
            BlockPos pos = ticker.getPos();
            if (BE_TRACE_POS != null && BE_TRACE_POS.equals(pos)) traced = true;
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            String typeId = ticker.getType();
            boolean pinned = !pins.isBeEmpty() && pins.isBePinned(typeId);
            boolean cascadeSafe = !pinned && !pins.isCascadeSafeBeEmpty() && pins.isCascadeSafeBe(typeId);
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
                beBuckets.computeIfAbsent(region, r -> new ArrayList<>()).add(ticker);
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
            LOGGER.info("BE phase [{}]: due={} main={} buckets:{}", key.location(), due.size(), mainBes.size(), sb);
        }
        tickBlockEntityList(pins, mainBes);
        final int nestworldBeBatch = 32;
        Map<WorldRegion, List<Runnable>> buckets = new IdentityHashMap<>();
        for (var e : beBuckets.entrySet()) {
            final List<net.minecraft.world.level.block.entity.TickingBlockEntity> list = e.getValue();
            List<Runnable> runs = new ArrayList<>((list.size() + nestworldBeBatch - 1) / nestworldBeBatch);
            for (int i = 0; i < list.size(); i += nestworldBeBatch) {
                final List<net.minecraft.world.level.block.entity.TickingBlockEntity> slice =
                        list.subList(i, Math.min(i + nestworldBeBatch, list.size()));
                runs.add(() -> tickBlockEntityList(pins, slice));
            }
            buckets.put(e.getKey(), runs);
        }
        int before = buckets.size();
        pool.runWorkRoundDropIfBacklogged(buckets, RegionPhase.BLOCK_ENTITY);
        if (logNow && buckets.size() != before) {
            LOGGER.info("BE phase [{}]: {} region bucket(s) dropped (backlog)", key.location(), before - buckets.size());
        }
    }

    /**
     * Ticks every loaded entity whose type is pinned, on the main thread. {@code pins} is the
     * shared cross-dimension pin table (passed in — not owned here).
     */
    private void tickPinnedEntitiesOnMain(NestworldPins pins) {
        List<Entity> snapshot = new ArrayList<>();
        for (Entity e : level.getAllEntities()) snapshot.add(e);
        for (Entity entity : snapshot) {
            if (entity.isRemoved() || entity.isPassenger()) continue;
            if (!pins.isPinned(entity.getType())) continue;
            try {
                entity.checkDespawn();
                if (entity.isRemoved()) continue;
                if (!level.isPositionEntityTicking(entity.blockPosition())) continue;
                level.tickNonPassenger(entity);
            } catch (Throwable t) {
                LOGGER.warn("Pinned entity {} tick error: {}", entity.getType().getDescriptionId(), t.toString());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Persistent region layout
    // -----------------------------------------------------------------------

    /** NBT file holding this dimension's saved BSP layout, under the world's data folder.
     *  The Overworld keeps the pre-Stage-1 unsuffixed filename (existing saves load with zero
     *  migration, and matches the "indistinguishable from today" bar for a single-dimension
     *  setup); any other managed dimension gets a disambiguated filename. */
    private Path layoutFile() {
        String suffix = key == Level.OVERWORLD ? "" : "-" + key.location().getNamespace() + "_" + key.location().getPath();
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-regions" + suffix + ".dat");
    }

    /** Reads this dimension's saved layout, or returns null if there is none / it is
     *  unreadable (first boot, or a world that predates persistence). Static so it can run
     *  BEFORE a {@code NestworldDimensionRegion} exists (the constructor needs the result). */
    static CompoundTag loadSavedLayout(ServerLevel level, ResourceKey<Level> key) {
        String suffix = key == Level.OVERWORLD ? "" : "-" + key.location().getNamespace() + "_" + key.location().getPath();
        File file = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("nestworld-regions" + suffix + ".dat").toFile();
        if (!file.isFile()) return null;
        try {
            CompoundTag tag = NbtIo.read(file);
            return (tag != null && tag.contains("root")) ? tag : null;
        } catch (Throwable t) {
            LOGGER.warn("Could not read saved region layout for {} ({}) — starting fresh",
                    key.location(), t.toString());
            return null;
        }
    }

    /** Writes the current BSP layout so the next boot starts already sharded. */
    void saveLayout() {
        try {
            File file = layoutFile().toFile();
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            NbtIo.write(tree.writeNbt(), file);
            lastSavedLayoutVersion = grid.getLayoutVersion();
            LOGGER.info("Saved region layout for {} ({} region(s))",
                    key.location(), tree.getActiveRegions().size());
        } catch (Throwable t) {
            LOGGER.warn("Could not save region layout for {}: {}", key.location(), t.toString());
        }
    }

    private void runRandomTickTest() {
        if (RANDOMTICK_TEST_SPEED <= 0) return;
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(0, 0);
        if (chunk != null) {
            net.minecraft.world.level.ChunkPos pos = chunk.getPos();
            WorldRegion region = grid.getRegionForChunk(pos.x, pos.z);
            if (region != null
                    && pos.x >= region.getMinChunkX() + BORDER_BAND_CHUNKS && pos.x <= region.getMaxChunkX() - BORDER_BAND_CHUNKS
                    && pos.z >= region.getMinChunkZ() + BORDER_BAND_CHUNKS && pos.z <= region.getMaxChunkZ() - BORDER_BAND_CHUNKS) {
                queueRandomTick(region, () -> level.nestworldRandomTickChunk(chunk, RANDOMTICK_TEST_SPEED));
                flushRandomTicksPhase();
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
    // Region border visualisation (/nestworld borders)
    // -----------------------------------------------------------------------

    private void renderBorderParticles() {
        if (!NestworldRegionSystem.showBorders || (++borderParticleTimer % 10) != 0) return;

        for (ServerPlayer player : level.players()) {
            int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();

            for (WorldRegion r : grid.getAllRegions()) {
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

    private void drawBorderPlaneX(ServerPlayer player, int x, int zMin, int zMax, int px, int py, int pz) {
        if (Math.abs(px - x) > BORDER_VIEW_RANGE) return;
        int from = Math.max(zMin, pz - BORDER_VIEW_RANGE);
        int to   = Math.min(zMax, pz + BORDER_VIEW_RANGE);
        for (int z = from; z <= to; z += 2) {
            for (int y = py - 8; y <= py + 12; y += 4) {
                level.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD,
                        false, x + 0.0, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
            }
        }
    }

    private void drawBorderPlaneZ(ServerPlayer player, int z, int xMin, int xMax, int px, int py, int pz) {
        if (Math.abs(pz - z) > BORDER_VIEW_RANGE) return;
        int from = Math.max(xMin, px - BORDER_VIEW_RANGE);
        int to   = Math.min(xMax, px + BORDER_VIEW_RANGE);
        for (int x = from; x <= to; x += 2) {
            for (int y = py - 8; y <= py + 12; y += 4) {
                level.sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD,
                        false, x + 0.5, y + 0.5, z + 0.0, 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Stage 5 tick-scheduler architecture, Part 3 (docs/LOCAL_TICK_STAGE4.md): applies {@code
     * applyWork} only after acquiring the {@code chunkLock} of every region within {@link
     * NestworldTuning#CASCADE_SAFETY_MARGIN_BLOCKS} of every position in {@code positions}.
     */
    /**
     * Batch-apply Phase 1 (docs/BATCH_APPLY_COALESCING_DESIGN.md): acquires ONE combined
     * lock set for a whole batch of same-type, same-destination messages, applies every
     * message in the batch in its original order, releases once — instead of one lock
     * acquire/release cycle per message (the confirmed remaining Fix 3 bottleneck under
     * Step 5). Whole-batch fail on timeout: nothing is ever half-applied, so no rollback
     * logic is needed (see design doc's "Lock-timeout behavior" section). Fault
     * isolation is per-MESSAGE even though the lock is shared — one message's apply
     * throwing does not skip its batch-mates or leak the lock, matching the old
     * per-message method's behavior exactly (each drain-site call used to be wrapped in
     * its own try/catch; that granularity is preserved here, just inside the batch loop).
     *
     * <p>The lock-acquisition wait is clamped to BOTH this call's own {@code
     * CASCADE_LOCK_TIMEOUT_NANOS} window AND {@code sharedDeadlineNanos} (the same
     * deadline the caller's outer drain loop uses across every region/type in one
     * barrier pass) — Gemini review finding, 2026-08-19: a count-only batch cap does not
     * bound an in-progress batch's lock-wait duration, which would reintroduce the exact
     * starvation shape Fix 3 already fixed once at message granularity (one region's
     * unbounded floor of work ignoring the shared deadline). Whichever deadline is
     * sooner wins.
     */
    void applyBatchWithCascadeGuard(WorldRegion destination, List<RegionMessage<?>> batch,
            java.util.Collection<BlockPos> combinedPositions, long sharedDeadlineNanos,
            java.util.function.Consumer<RegionMessage<?>> perMessageApply) {
        TreeSet<WorldRegion> toLock = new TreeSet<>(Comparator.comparingInt(WorldRegion::getId));
        for (BlockPos pos : combinedPositions) {
            toLock.addAll(grid.getRegionsWithinMargin(pos, NestworldTuning.CASCADE_SAFETY_MARGIN_BLOCKS));
        }
        BatchApplyStats.recordBatchFormed(batch.size(), toLock.size());
        List<WorldRegion> locked = new ArrayList<>(toLock.size());
        List<Long> stamps = new ArrayList<>(toLock.size());
        long batchOwnDeadline = System.nanoTime() + NestworldTuning.CASCADE_LOCK_TIMEOUT_NANOS;
        long deadline = Math.min(batchOwnDeadline, sharedDeadlineNanos);
        try {
            for (WorldRegion r : toLock) {
                long remainingNanos = deadline - System.nanoTime();
                long stamp = 0L;
                if (remainingNanos > 0) {
                    try {
                        stamp = r.getChunkLock().tryWriteLock(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (stamp == 0L) {
                    LOGGER.warn("Batch cascade guard: timed out locking region {} for a deferred batch of {} "
                            + "message(s) near {} — whole batch re-queued for a later pass",
                            r.getId(), batch.size(), combinedPositions);
                    for (RegionMessage<?> msg : batch) destination.nestworldRequeueMessage(msg);
                    BatchApplyStats.recordBatchTimeout(batch.size());
                    return;
                }
                locked.add(r);
                stamps.add(stamp);
            }
            for (RegionMessage<?> msg : batch) {
                try {
                    perMessageApply.accept(msg);
                    MailboxAudit.recordApplied(msg.auditId(), destination, msg.messageType());
                } catch (Throwable t) {
                    LOGGER.warn("Deferred batch message apply failed: {}", t.toString());
                }
            }
            BatchApplyStats.recordBatchApplied();
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).getChunkLock().unlockWrite(stamps.get(i));
            }
        }
    }

    /**
     * Batch-apply Phase 1: forms and applies successive batches (see {@link
     * WorldRegion#nestworldDrainMailboxBatch}/{@link #applyBatchWithCascadeGuard}) of one
     * message type for one destination region until either the mailbox has no more
     * messages of that type or {@code deadlineNanos} passes — the batch-granularity
     * equivalent of the old {@code nestworldDrainMailboxBudgeted} loop.
     */
    private void nestworldDrainAndApplyBatched(WorldRegion region, RegionMessage.Type type, long deadlineNanos) {
        while (System.nanoTime() < deadlineNanos) {
            List<RegionMessage<?>> batch = region.nestworldDrainMailboxBatch(type, NestworldTuning.BATCH_APPLY_MAX_SIZE);
            if (batch.isEmpty()) return;

            List<BlockPos> combinedPositions = new ArrayList<>(batch.size());
            java.util.function.Consumer<RegionMessage<?>> applier;
            if (type == RegionMessage.Type.EXPLOSION_APPLY) {
                for (RegionMessage<?> msg : batch) {
                    combinedPositions.addAll(((net.minecraft.world.level.Explosion.NestworldExplosionBatch) msg.payload()).positions());
                }
                applier = msg -> net.minecraft.world.level.Explosion.nestworldApplyBatch(
                        (net.minecraft.world.level.Explosion.NestworldExplosionBatch) msg.payload());
            } else {
                for (RegionMessage<?> msg : batch) {
                    combinedPositions.add(((RegionMessage.BlockWrite) msg.payload()).pos());
                }
                applier = msg -> {
                    RegionMessage.BlockWrite write = (RegionMessage.BlockWrite) msg.payload();
                    level.setBlock(write.pos(), write.newState(), write.flags(), write.recursionLeft());
                };
            }

            applyBatchWithCascadeGuard(region, batch, combinedPositions, deadlineNanos, applier);

            if (batch.size() < NestworldTuning.BATCH_APPLY_MAX_SIZE) return; // mailbox had no more of this type
        }
    }

    /** NestWorld: Stage 3 generic block-write guard (docs/LOCAL_TICK_STAGE4.md) — called from
     *  {@code Level.setBlock()}'s NestWorld guard when a region thread targets a position
     *  outside its own bounds, within THIS dimension (source and destination are always the
     *  same dimension for this call — a region thread never targets a foreign dimension's
     *  blocks directly, only via the portal machinery Stage 0.5 already guards separately). */
    boolean deferForeignBlockWrite(WorldRegion source, WorldRegion destination,
            BlockPos pos, net.minecraft.world.level.block.state.BlockState newState,
            int flags, int recursionLeft) {
        boolean nestworldWouldChange = !level.getBlockState(pos).equals(newState);
        source.nestworldSendOrQueue(destination, RegionMessage.blockWrite(source, destination,
                level.getServer().getTickCount(),
                new RegionMessage.BlockWrite(pos.immutable(), newState, flags, recursionLeft)));
        return nestworldWouldChange;
    }

    // -----------------------------------------------------------------------
    // Per-tick entry point
    // -----------------------------------------------------------------------

    private static final String[] NESTWORLD_PHASE_NAMES =
            {"vanilla(+3-pool-phases)", "signals", "entityXfer", "regionPool(entity)", "ghostZones+tracker", "splitMerge"};

    private void nestworldLogSpike(long tickWallNanos, long t0, long t1, long t2, long t3, long t4, long t5, long t6) {
        long[] phaseNs = {t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, t6 - t5};
        int dominant = 0;
        for (int i = 1; i < phaseNs.length; i++) if (phaseNs[i] > phaseNs[dominant]) dominant = i;
        double tickWallMs = tickWallNanos / 1e6;
        double dominantMs = phaseNs[dominant] / 1e6;
        double dominantPct = tickWallMs > 0 ? dominantMs / tickWallMs * 100.0 : 0.0;
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String line = String.format("[%s] %s  tick_wall=%.1fms  dominant=%s(%.1fms,%.0f%%)  regions=%d",
                key.location(), stamp, tickWallMs, NESTWORLD_PHASE_NAMES[dominant], dominantMs, dominantPct,
                grid.getAllRegions().size());
        if (dominant == 0) {
            long[] sub = ServerLevel.nestworldLastSubphaseNanos;
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

    /** Cumulative-since-boot breakdown of the 6 sequential tick phases for THIS dimension —
     *  the metric Stage 1's own acceptance bar needs ("Overworld's timing stays flat while a
     *  second dimension is under load" is only checkable with a per-dimension breakdown). */
    public String tickPhaseReport() {
        if (timedTicksCumulative == 0) return "NW tick phases [" + key.location() + "]: no ticks recorded yet";
        double n = timedTicksCumulative;
        double totalMs = 0;
        for (long v : phaseNanosCumulative) totalMs += v / 1e6;
        StringBuilder sb = new StringBuilder(String.format(
                "NW tick phases [%s] (cumulative since boot, %,d ticks, avg tick_wall=%.3fms):%n",
                key.location(), timedTicksCumulative, totalMs / n));
        for (int i = 0; i < 6; i++) {
            double avgMs = phaseNanosCumulative[i] / 1e6 / n;
            double pct = totalMs > 0 ? (phaseNanosCumulative[i] / 1e6) / totalMs * 100.0 : 0.0;
            sb.append(String.format("  %-24s avg=%.4fms (%.1f%%)%n", NESTWORLD_PHASE_NAMES[i], avgMs, pct));
        }
        return sb.toString();
    }

    public String pollTaskReport() {
        return "[" + key.location() + "] " + net.nestworld.region.PollTaskAttribution.snapshot()
                + "\n" + correlation.snapshot()
                + "\n" + net.nestworld.region.DistanceManagerAttribution.snapshot();
    }

    /**
     * Replaces the vanilla {@code serverlevel.tick(hasTime)} call inside
     * {@code MinecraftServer.tickChildren()} for THIS dimension. {@code pins} is the shared
     * cross-dimension pin table (passed in each tick — not owned here). Same 7-phase structure
     * as the pre-Stage-1 {@code NestworldRegionSystem.tickAllRegions()} this replaces.
     */
    void tick(NestworldPins pins, BooleanSupplier hasTime) {
        runAutosplitTest();
        runRandomTickTest();
        pool.nestworldResetTickAccumulators();
        long nestworldTickWallStart = System.nanoTime();
        long t0 = System.nanoTime();
        // Publish the loaded-FULL chunk snapshot for region threads to read
        // lock-free this tick (avoids missing already-loaded chunks off-main and
        // the getChunk park/unpark herd that dominated the main thread under load).
        level.getChunkSource().nestworldRefreshLoadedChunks();
        // 1. Vanilla global tick (time, weather, chunk I/O) — entity tick skipped by
        // patch; due scheduled block/fluid ticks are parallelized from within it
        // via runScheduledTicksPhase (ServerLevel patch calls back into us).
        level.tick(hasTime);
        long t1 = System.nanoTime();

        // 1b. Region-Owned Chunk Scheduler, Phase 2 -- SHADOW MODE ONLY (docs/
        // REGION_CHUNK_SCHEDULER_SPEC.md). No-op unless CHUNK_SCHEDULER_SHADOW_MODE is on.
        net.nestworld.chunk.ChunkSchedulerShadow.drainObservationsAndEnqueue(this);
        net.nestworld.chunk.ChunkSchedulerShadow.drainForStats(this);

        // 2. Apply boundary redstone signals + wire updates whose network left
        // their region last tick (deferred by NestworldRedstone)
        signalQueue.flush();
        RegionMessage<BlockPos> wireMsg;
        while ((wireMsg = deferredWireUpdates.poll()) != null) {
            try {
                level.nestworldWireHandler.onWireUpdated(wireMsg.payload());
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
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (player.isRemoved() || player.isPassenger()) continue;
            try {
                level.tickNonPassenger(player);
            } catch (Throwable t) {
                LOGGER.warn("Player {} tick error: {}", player.getGameProfile().getName(), t.getMessage());
            }
        }
        // 3c. Tick pinned entity types on the main thread (mod-compat escape hatch).
        if (!pins.isEmpty()) tickPinnedEntitiesOnMain(pins);

        // 3d. Predictive frontier: request generation of chunks ahead of moving players.
        predictiveGen.tick(level);
        long t3 = System.nanoTime();

        // 4. Parallel tick — blocks until all region threads finish
        pool.tickAllRegions();
        long t4 = System.nanoTime();

        // 4b/4c. Apply cross-region explosion/block-write batches deferred THIS tick.
        // Batch-apply Phase 1 (docs/BATCH_APPLY_COALESCING_DESIGN.md): groups each
        // region/type's queued messages under one combined cascade-guard lock instead of
        // one lock cycle per message — see nestworldDrainAndApplyBatched.
        long nestworldMailboxDeadline = System.nanoTime() + NestworldTuning.MAILBOX_DRAIN_BUDGET_NANOS;
        for (WorldRegion region : grid.getAllRegions()) {
            nestworldDrainAndApplyBatched(region, RegionMessage.Type.EXPLOSION_APPLY, nestworldMailboxDeadline);
            nestworldDrainAndApplyBatched(region, RegionMessage.Type.BLOCK_WRITE, nestworldMailboxDeadline);
        }

        // 5. Refresh ghost zones (runs while region threads are paused at barrier)
        boundaryManager.syncGhostZones();
        // 5b. Register/unregister entities that region threads spawned or removed this tick.
        drainDeferredRemovals();
        drainDeferredTrackingAdds();
        drainDeferredSpawns();
        NestworldRegionSystem.get().drainDeferredDimensionChanges();
        NestworldRegionSystem.get().drainDeferredGatewayTeleports();
        // 5c. Vanilla visibility/broadcast tracker (ChunkMap.tick()), deferred strictly after
        // region threads finish their tick — see the pre-Stage-1 method's javadoc for why.
        level.getChunkSource().chunkMap.tick();
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
                LOGGER.info("[{}] Tick phases avg ms over {} ticks: vanilla={} signals={} entityXfer={} regionPool={} ghostZones={} splitMerge={}",
                        key.location(), timedTicks,
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

        long nestworldTickWallNanos = System.nanoTime() - nestworldTickWallStart;
        long[] nestworldTickAcc = pool.nestworldDrainTickAccumulators();
        long nestworldDispatchTotal = nestworldTickAcc[0];
        long nestworldWaitTotal = nestworldTickAcc[1];
        long nestworldPollTotal = nestworldTickAcc[2];
        long nestworldRegionMaxTotal = nestworldTickAcc[3];
        long nestworldLatchWaitOnly = nestworldWaitTotal - nestworldPollTotal;
        long nestworldOutsidePollTask = nestworldTickWallNanos - nestworldDispatchTotal - nestworldWaitTotal;
        correlation.record(nestworldTickWallNanos, nestworldPollTotal, nestworldRegionMaxTotal,
                nestworldLatchWaitOnly, nestworldOutsidePollTask);

        if (NestworldTuning.SPIKE_LOG_THRESHOLD_NANOS > 0
                && nestworldTickWallNanos >= NestworldTuning.SPIKE_LOG_THRESHOLD_NANOS) {
            nestworldLogSpike(nestworldTickWallNanos, t0, t1, t2, t3, t4, t5, t6);
        }
    }

    /** Called once, at server shutdown: persist this dimension's layout and stop its threads. */
    void shutdown() {
        saveLayout();
        for (RegionThread t : pool.getThreads()) {
            t.shutdown();
        }
    }
}
