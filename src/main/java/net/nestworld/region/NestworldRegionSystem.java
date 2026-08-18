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
     * Package-private: also used by {@link NestworldDimensionRegion}'s constructor.
     */
    static final int INITIAL_REGION_HALF_SPAN = 1_875_000;

    private static NestworldRegionSystem INSTANCE;

    /** Entity types pinned to main-thread ticking (mod-compat escape hatch). Shared across
     *  every managed dimension — an operator pinning a type means it everywhere, not per-world
     *  (see {@link NestworldDimensionRegion}'s constructor javadoc for the full reasoning). */
    private final NestworldPins pins = new NestworldPins();

    private ServerLevel overworld;
    private MinecraftServer server;

    // Region-sharding for Nether/End: identity+state map of every dimension NestWorld manages
    // (see plan doc referenced from project memory, "Region-Sharding for Nether & End"). Stage
    // 0a held exactly one entry (overworld) with NestworldDimensionRegion as identity-only;
    // Stage 1 physically moved grid/pool/tree/etc. onto NestworldDimensionRegion itself (see that
    // class's own javadoc) so this map can hold more than one real managed dimension. Entries are
    // added only in init() (never removed at runtime), and each is assigned before any of its
    // RegionThreads are spawned, so a plain (non-concurrent) Map is safe for lock-free reads from
    // any thread afterward. Prefer isManagedLevel()/getDimensionRegion() over comparing against
    // getOverworld() directly in new code -- the former keeps working unchanged as dimensions are
    // added; the latter would not.
    private final java.util.Map<net.minecraft.resources.ResourceKey<Level>, NestworldDimensionRegion> dimensions =
            new java.util.HashMap<>();

    /** Stage 0.5 (Nether/End sharding plan): entities that arrived via a dimension portal
     *  while the SOURCE level was being ticked by a region thread ({@code
     *  ServerLevel.addDuringTeleport}, reached from {@code Entity.changeDimension}). Keyed by
     *  DESTINATION level rather than a single field like {@link #deferredSpawns} — the portal
     *  target can be any registered dimension (today always Nether/End, since only the
     *  overworld is sharded so far), not just {@link #overworld}. Drained from {@code
     *  MinecraftServer.tickChildren()}'s own per-level loop, dimension-agnostically, so this
     *  works whether the destination is itself region-managed or still plain vanilla-ticked.
     *  Bounded per destination (anti-grief portal-spam cap), same reasoning as {@link
     *  #deferredSpawns}. A plain {@link java.util.concurrent.ConcurrentHashMap} of the outer map
     *  is safe: entries are only ever added via {@code computeIfAbsent} and never removed, and
     *  each value is itself a thread-safe bounded queue. */
    private final java.util.Map<ServerLevel, java.util.concurrent.LinkedBlockingQueue<net.minecraft.world.entity.Entity>> pendingPortalArrivals =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Stage 0.5 follow-up (see {@link NestworldTuning#MAX_DEFERRED_DIMENSION_CHANGES_PER_TICK}'s
     *  javadoc for the live-reproduced crash this fixes): entity dimension-change requests
     *  ({@code Entity.changeDimension}) made from a region thread, deferred in their ENTIRETY
     *  (not just the final add — the portal search/creation step itself touches the destination
     *  dimension's chunk source and must not run off-main either) to the main thread. Drained
     *  during the overworld's own post-region-round barrier window, alongside {@link
     *  #deferredSpawns}/{@link #deferredRemovals} — same "region threads are idle here" safety
     *  argument. Unbounded queue: unlike the entity-add queues, a flood here is naturally capped
     *  by how many entities region threads can tick per tick (already bounded elsewhere), and the
     *  PROCESSING side is what needs throttling (see the tuning constant), not the queue depth. */
    private final java.util.Queue<PendingDimensionChange> pendingDimensionChanges =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Tuple for {@link #pendingDimensionChanges}. */
    private static final class PendingDimensionChange {
        final net.minecraft.world.entity.Entity entity;
        final ServerLevel destination;
        final net.minecraftforge.common.util.ITeleporter teleporter;
        PendingDimensionChange(net.minecraft.world.entity.Entity entity, ServerLevel destination,
                                net.minecraftforge.common.util.ITeleporter teleporter) {
            this.entity = entity;
            this.destination = destination;
            this.teleporter = teleporter;
        }
    }

    /** Stage 1g (region-sharding for Nether/End plan): same hazard shape as {@link
     *  #pendingDimensionChanges} — {@code TheEndGatewayBlockEntity.teleportEntity()}'s
     *  exit-portal search/creation ({@code findOrCreateValidTeleportPos} -> {@code getChunk})
     *  can trigger synchronous chunk generation, and this ticker runs on a region thread once
     *  End is a managed dimension (unlike Stage 0.5, this is a SAME-dimension hazard — a gateway
     *  BE ticking in one region's interior touching a chunk possibly far outside that region's
     *  own bounds, not a cross-dimension one). Deferred in its ENTIRETY (not just the chunk
     *  touch) to the main thread, same "region threads are idle here" barrier-window drain as
     *  every other deferred queue. Unbounded for the same reason {@link #pendingDimensionChanges}
     *  is: naturally capped by how many gateway BEs a region can tick per tick already. */
    private final java.util.Queue<PendingGatewayTeleport> pendingGatewayTeleports =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Tuple for {@link #pendingGatewayTeleports}. */
    private static final class PendingGatewayTeleport {
        final ServerLevel level;
        final net.minecraft.core.BlockPos pos;
        final net.minecraft.world.level.block.state.BlockState state;
        final net.minecraft.world.entity.Entity entity;
        final net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity blockEntity;
        PendingGatewayTeleport(ServerLevel level, net.minecraft.core.BlockPos pos,
                                net.minecraft.world.level.block.state.BlockState state,
                                net.minecraft.world.entity.Entity entity,
                                net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity blockEntity) {
            this.level = level;
            this.pos = pos;
            this.state = state;
            this.entity = entity;
            this.blockEntity = blockEntity;
        }
    }

    private NestworldRegionSystem() {}

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
        if (sys == null || entity == null) return;
        NestworldDimensionRegion dr = sys.getDimensionRegion(entity.level());
        if (dr != null) dr.getEntityTransfer().markDirty(entity);
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

        pins.load(pinsFile());
        pins.loadBe(bePinsFile());
        pins.loadMods(pinnedModsFile());
        pins.loadCascadeSafeBe(cascadeSafeBeFile());

        // Stage 1: managed dimensions = Overworld (always) + whatever the dark-launch flag
        // adds (see NestworldTuning.SHARDED_DIMENSIONS_RAW's javadoc). Each gets its own
        // NestworldDimensionRegion — grid/pool/tree/etc. now live there, not here (see that
        // class's own javadoc for why the physical migration waited until this stage).
        for (net.minecraft.resources.ResourceKey<Level> key : resolveManagedDimensionKeys()) {
            ServerLevel level = server.getLevel(key);
            if (level == null) {
                LOGGER.warn("Managed dimension {} not available — skipping", key.location());
                continue;
            }
            CompoundTag saved = NestworldDimensionRegion.loadSavedLayout(level, key);
            dimensions.put(key, new NestworldDimensionRegion(level, pins, saved));
        }

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
    }

    /** Stage 1 dark-launch flag (see {@link NestworldTuning#SHARDED_DIMENSIONS_RAW}'s javadoc):
     *  the Overworld is always managed first (so the flag can only ever ADD dimensions, never
     *  accidentally disable it), followed by every valid, non-duplicate dimension id it lists. */
    private static java.util.List<net.minecraft.resources.ResourceKey<Level>> resolveManagedDimensionKeys() {
        java.util.List<net.minecraft.resources.ResourceKey<Level>> targets = new java.util.ArrayList<>();
        targets.add(Level.OVERWORLD);
        for (String token : NestworldTuning.SHARDED_DIMENSIONS_RAW.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(token);
            if (loc == null) {
                LOGGER.warn("Invalid nestworld.shardedDimensions entry: {}", token);
                continue;
            }
            net.minecraft.resources.ResourceKey<Level> key =
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
            if (!targets.contains(key)) targets.add(key);
        }
        return targets;
    }

    // -----------------------------------------------------------------------
    // Persistent region layout / pin files
    // -----------------------------------------------------------------------

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

    /** Called from a region thread's {@code Entity.changeDimension}: queue the WHOLE
     *  cross-dimension teleport (not just the final add) for the main thread to actually
     *  perform, since the portal search/creation step also touches the destination's chunk
     *  source and is not safe off-main. See {@link NestworldTuning#MAX_DEFERRED_DIMENSION_CHANGES_PER_TICK}'s
     *  javadoc for the live-reproduced deadlock this replaces. */
    public void queueDimensionChange(net.minecraft.world.entity.Entity entity, ServerLevel destination,
                                      net.minecraftforge.common.util.ITeleporter teleporter) {
        pendingDimensionChanges.add(new PendingDimensionChange(entity, destination, teleporter));
        LOGGER.info("Deferred dimension change queued: {} -> {} (from region thread)",
                entity, destination.dimension().location());
    }

    /** Main-thread: actually perform dimension changes region threads requested, up to a
     *  per-tick budget (this work is heavy — chunk gen, portal search — unlike the other
     *  deferred queues). Left in the queue simply means "handled next tick", not lost. */
    void drainDeferredDimensionChanges() {
        int nestworldProcessed = 0;
        PendingDimensionChange pending;
        while (nestworldProcessed < NestworldTuning.MAX_DEFERRED_DIMENSION_CHANGES_PER_TICK
                && (pending = pendingDimensionChanges.poll()) != null) {
            try {
                if (!pending.entity.isRemoved()) {
                    pending.entity.changeDimension(pending.destination, pending.teleporter);
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred dimension change failed: {}", t.toString());
            }
            nestworldProcessed++;
        }
    }

    /** Called from a region thread's {@code TheEndGatewayBlockEntity.teleportEntity}: queue the
     *  WHOLE exit-portal search/creation + actual teleport for the main thread, since the search
     *  step touches (and may synchronously generate) a possibly-unloaded chunk. See {@link
     *  #pendingGatewayTeleports}'s javadoc. The caller sets {@code teleportCooldown} itself
     *  BEFORE deferring (a plain same-region-owned field write, safe on the calling thread) so a
     *  re-entrant touch of the same gateway next tick doesn't queue a duplicate. */
    public void queueGatewayTeleport(ServerLevel level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.entity.Entity entity,
            net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity blockEntity) {
        pendingGatewayTeleports.add(new PendingGatewayTeleport(level, pos, state, entity, blockEntity));
        LOGGER.info("Deferred gateway teleport queued: {} at {} (from region thread)", entity, pos);
    }

    /** Main-thread: actually perform gateway teleports region threads requested. Unbudgeted
     *  (like {@link #drainDeferredDimensionChanges} it's heavy per-call, but gateway activations
     *  are rare enough in practice that a per-tick cap isn't worth the complexity yet — revisit
     *  if a soak shows otherwise). */
    void drainDeferredGatewayTeleports() {
        PendingGatewayTeleport pending;
        while ((pending = pendingGatewayTeleports.poll()) != null) {
            try {
                if (!pending.entity.isRemoved() && !pending.blockEntity.isRemoved()) {
                    net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity.nestworldDoTeleportEntity(
                            pending.level, pending.pos, pending.state, pending.entity, pending.blockEntity);
                }
            } catch (Throwable t) {
                LOGGER.warn("Deferred gateway teleport failed: {}", t.toString());
            }
        }
    }

    /** Called from a region thread's addFreshEntity: resolve the entity's own dimension and
     *  queue the spawn there for main-thread registration instead of mutating ChunkMap off-main. */
    public boolean queueEntitySpawn(net.minecraft.world.entity.Entity e) {
        NestworldDimensionRegion dr = getDimensionRegion(e.level());
        return dr != null && dr.queueEntitySpawn(e);
    }

    /** Called from a region thread's {@code ServerLevel.addDuringTeleport} (portal arrival):
     *  queue the entity for registration on the DESTINATION level's own next tick, instead of
     *  mutating its (possibly still plain-vanilla, unmanaged) entity tracking off-main. Safe
     *  regardless of whether {@code destination} is itself region-managed — the hazard is the
     *  calling thread being a region thread at all, not the destination's own status; see the
     *  guard in {@code ServerLevel.addDuringTeleport}'s patch. */
    public boolean queuePortalArrival(ServerLevel destination, net.minecraft.world.entity.Entity e) {
        return pendingPortalArrivals
                .computeIfAbsent(destination, d -> new java.util.concurrent.LinkedBlockingQueue<>(
                        NestworldTuning.PORTAL_ARRIVAL_QUEUE_CAP))
                .offer(e);
    }

    /** Called once per server tick, from {@code MinecraftServer.tickChildren()}'s per-level
     *  loop, for EVERY level (managed or not) right before that level's own tick dispatch —
     *  so a portal arrival queued while the source was on a region thread gets registered on
     *  the destination's own tick, whichever dimension that is. No-op (cheap map lookup) for
     *  the overwhelming majority of ticks where nothing queued anything for this level. */
    public void drainPortalArrivals(ServerLevel level) {
        java.util.concurrent.LinkedBlockingQueue<net.minecraft.world.entity.Entity> queue =
                pendingPortalArrivals.get(level);
        if (queue == null || queue.isEmpty()) return;
        long deadlineNanos = System.nanoTime() + NestworldTuning.DEFERRED_SPAWN_BUDGET_NANOS;
        net.minecraft.world.entity.Entity e;
        int nestworldProcessed = 0;
        while ((e = queue.poll()) != null) {
            try {
                level.addDuringTeleport(e);
            } catch (Throwable t) {
                LOGGER.warn("Deferred portal arrival failed: {}", t.toString());
            }
            nestworldProcessed++;
            if (nestworldProcessed >= NestworldTuning.MAX_PORTAL_ARRIVALS_PER_TICK) break;
            if ((nestworldProcessed & 15) == 0 && System.nanoTime() >= deadlineNanos) break;
        }
    }

    /** Called from a region thread's ServerChunkCache.removeEntity: resolve the entity's own
     *  dimension (it's still a valid reference — removal is deferred, not yet applied) and
     *  queue the tracking removal there for main instead of mutating ChunkMap off-main. */
    public void queueEntityRemoval(net.minecraft.world.entity.Entity e) {
        NestworldDimensionRegion dr = getDimensionRegion(e.level());
        if (dr != null) dr.queueEntityRemoval(e);
    }

    /** Called from a region thread's ServerChunkCache.addEntity (a section-move visibility
     *  transition during the region tick): queue the tracking add for main instead of mutating
     *  ChunkMap's entityMap off-main. */
    public void queueEntityTrackingAdd(net.minecraft.world.entity.Entity e) {
        NestworldDimensionRegion dr = getDimensionRegion(e.level());
        if (dr != null) dr.queueEntityTrackingAdd(e);
    }

    // -----------------------------------------------------------------------
    // Per-tick entry point (called from patched MinecraftServer)
    // -----------------------------------------------------------------------

    /**
     * Replaces the vanilla {@code serverlevel.tick(hasTime)} call inside
     * {@code MinecraftServer.tickChildren()} for {@code level} — dispatches to that
     * dimension's own {@link NestworldDimensionRegion#tick}. See that method's javadoc for
     * the 7-phase execution order (unchanged from the pre-Stage-1 single-dimension version).
     */
    public void tickAllRegions(ServerLevel level, BooleanSupplier hasTime) {
        NestworldDimensionRegion dr = getDimensionRegion(level);
        if (dr == null) {
            throw new IllegalStateException("tickAllRegions called for unmanaged level " + level.dimension().location());
        }
        dr.tick(pins, hasTime);
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
    /** Wire updates whose network left its region; re-run on main next tick. Stage 2
     *  (docs/LOCAL_TICK_STAGE4.md): wrapped in the tagged RegionMessage shape — this is
     *  the ACTUALLY-WIRED "Deferred" tier precedent (BoundarySignalQueue, despite its
     *  javadoc, is dead code — enqueue() is never called anywhere in the repo). {@code
     *  sourceRegion} now carries its own dimension (Stage 1a) so this resolves the right
     *  {@link NestworldDimensionRegion} without needing a level parameter threaded through. */
    public void deferWireUpdate(WorldRegion sourceRegion, net.minecraft.core.BlockPos pos) {
        NestworldDimensionRegion dr = dimensions.get(sourceRegion.getDimension());
        if (dr != null) dr.deferWireUpdate(sourceRegion, pos);
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
        NestworldDimensionRegion dr = getDimensionRegion(level);
        if (dr == null) return;
        NestworldTickOwnership.noteServerTick(gameTime);
        Thread nestworldHere = Thread.currentThread();
        if (nestworldMainThreadIdentity == null) {
            nestworldMainThreadIdentity = nestworldHere;
        } else if (nestworldMainThreadIdentity != nestworldHere) {
            LOGGER.error("NestWorld DIAG: runScheduledTicksPhase called from unexpected thread '{}' (expected '{}')",
                    nestworldHere.getName(), nestworldMainThreadIdentity.getName());
        }

        nestworldDrainPendingScheduleTicks();
        dr.runScheduledTicksPhase(gameTime);
    }

    // -----------------------------------------------------------------------
    // Random ticks (crops, fire, ice…) — phase 2a. ServerLevel.tickChunk asks
    // queueRandomTicksFor() per ticking chunk: interior chunks are bucketed
    // per region and executed in parallel by flushRandomTicksPhase() after the
    // chunk loop (ServerChunkCache patch); border-band or unowned chunks
    // return false and tick inline on main, exactly like scheduled ticks.
    // -----------------------------------------------------------------------

    /** Called from the patched ServerLevel.tickChunk. True = queued for a region thread. */
    public static boolean queueRandomTicksFor(ServerLevel level,
                                              net.minecraft.world.level.chunk.LevelChunk chunk,
                                              int randomTickSpeed) {
        if (!isInitialised()) return false;
        NestworldDimensionRegion dr = get().getDimensionRegion(level);
        if (dr == null) return false;
        net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        WorldRegion region = dr.interiorRegionFor(pos.x, pos.z);
        if (region == null) return false;
        dr.queueRandomTick(region, () -> level.nestworldRandomTickChunk(chunk, randomTickSpeed));
        return true;
    }

    /** Runs the queued per-chunk random ticks on their region threads (parallel). */
    public void flushRandomTicksPhase(ServerLevel level) {
        NestworldDimensionRegion dr = getDimensionRegion(level);
        if (dr != null) dr.flushRandomTicksPhase();
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
        if (!sys.isManagedLevel(level)) return null;
        return new java.util.ArrayList<>();
    }

    /** Buckets and runs the tickers collected by the patched tickBlockEntities. */
    public void runBlockEntityPhase(ServerLevel level,
                                    java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> due) {
        NestworldDimensionRegion dr = getDimensionRegion(level);
        if (dr != null) dr.runBlockEntityPhase(pins, due);
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
        NestworldDimensionRegion dr = getDimensionRegion(level);
        if (dr != null) dr.runBlockEventsPhase();
    }

    // -----------------------------------------------------------------------
    // Region border visualisation (/nestworld borders)
    // -----------------------------------------------------------------------

    /** When true, region borders near players are outlined with particles. Shared across every
     *  managed dimension (an operator toggling this means "show borders everywhere", matching
     *  the toggle's own single admin command). */
    public static volatile boolean showBorders = false;

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    private void shutdown() {
        LOGGER.info("Shutting down NestWorld region threads…");
        // Persist each managed dimension's layout before tearing its threads down so the
        // next boot skips the cold-start freeze (whole world on one thread until splits
        // catch up).
        for (NestworldDimensionRegion dr : dimensions.values()) {
            dr.shutdown();
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

    /** Region-sharding for Nether/End, Stage 0a: true if {@code level} is one of the
     *  dimensions NestWorld manages (today, always just the overworld — see {@link
     *  #dimensions}'s javadoc). Prefer this over comparing against {@link #getOverworld()}
     *  directly in new code. Accepts {@link Level} (not {@link ServerLevel}) to match how
     *  existing call sites are typed (e.g. {@code Entity.level()}). */
    public boolean isManagedLevel(Level level) {
        return level instanceof ServerLevel sl && dimensions.containsKey(sl.dimension());
    }

    /** Same as {@link #isManagedLevel(Level)} but from a dimension key directly, for call
     *  sites that already have one (e.g. {@code ServerLevel.dimension()}) without needing
     *  to resolve the actual {@link ServerLevel} instance first. */
    public boolean isManagedDimension(net.minecraft.resources.ResourceKey<Level> key) {
        return dimensions.containsKey(key);
    }

    /** Region-sharding for Nether/End, Stage 0a: the {@link NestworldDimensionRegion} for
     *  {@code level}, or null if it isn't managed. Null-safe on non-{@link ServerLevel}
     *  input (matches {@link #isManagedLevel(Level)}'s guard). */
    public NestworldDimensionRegion getDimensionRegion(Level level) {
        return level instanceof ServerLevel sl ? dimensions.get(sl.dimension()) : null;
    }

    /** Same as {@link #getDimensionRegion(Level)} but from a dimension key directly, for call
     *  sites that only have a {@link WorldRegion} (which carries its own key, Stage 1a) and no
     *  separate {@link Level}/{@link ServerLevel} reference in scope. */
    public NestworldDimensionRegion getDimensionRegionByKey(net.minecraft.resources.ResourceKey<Level> key) {
        return dimensions.get(key);
    }

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
        NestworldDimensionRegion dr = !NestworldTuning.FREE_RUNNING_REGIONS_ENABLED || !isInitialised()
                ? null : INSTANCE.getDimensionRegion(level);
        if (dr == null) {
            return java.util.List.of();
        }
        java.util.List<WorldRegion> freeRunning = new java.util.ArrayList<>();
        java.util.List<java.util.concurrent.CountDownLatch> paused = new java.util.ArrayList<>();
        for (WorldRegion region : dr.getGrid().getAllRegions()) {
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
        NestworldDimensionRegion dr = dimensions.get(source.getDimension());
        return dr != null && dr.deferForeignBlockWrite(source, destination, pos, newState, flags, recursionLeft);
    }
}
