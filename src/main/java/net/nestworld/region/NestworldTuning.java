package net.nestworld.region;

/**
 * Runtime tuning knobs, overridable via system properties.
 */
public final class NestworldTuning {

    /**
     * Max entities a single minecart collides with per tick. Bounds the
     * O(n^2) cramming cost of entity piles (500 carts in one block =
     * ~250k pair checks per tick in vanilla). Vanilla behavior: unlimited.
     */
    public static final int MAX_MINECART_PUSH =
            Integer.getInteger("nestworld.maxMinecartPush", 8);

    /**
     * Max entities a boat or living entity pushes against per tick — the same
     * O(n^2) cramming cost as minecarts. For living entities the effective cap
     * is raised to the maxEntityCramming game rule so cramming damage still
     * triggers at the vanilla threshold.
     */
    public static final int MAX_ENTITY_PUSH =
            Integer.getInteger("nestworld.maxEntityPush", 8);

    /**
     * Per-tick budget in nanoseconds for one region's entity round. When the
     * round exceeds it, the remaining entities are deferred to the next tick
     * (round-robin), so an unsplittable point hotspot slows down locally
     * instead of dragging the whole server's lockstep tick. Must stay above
     * the split manager's 25 ms threshold or overloaded regions would never
     * register split pressure.
     */
    public static final long REGION_ENTITY_BUDGET_NANOS =
            Integer.getInteger("nestworld.regionEntityBudgetMs", 40) * 1_000_000L;

    /**
     * Width (in chunks) of the border band kept on the main thread near every region
     * edge: scheduled-tick/piston/comparator cascades and hopper neighbour-chunk reads
     * can reach a few blocks past the origin, and interior cascades can't travel 2
     * chunks (32 blocks) in a single update. Shared by {@link NestworldRegionSystem}'s
     * routing ({@code interiorRegionFor}/{@code routeScheduledTick}) and
     * {@link RegionSplitManager}'s split-veto band scoring — both MUST use the same
     * value, so this is the single source of truth (previously duplicated in both
     * classes with a "must mirror" comment, a latent drift risk).
     */
    public static final int BORDER_BAND_CHUNKS =
            Integer.getInteger("nestworld.borderBandChunks", 2);

    /**
     * Cap on how long a region thread will block trying to acquire ANOTHER region's
     * chunk read-lock for a cross-region block-entity read ({@link RegionChunkView}).
     * A region thread holds its OWN write-lock for its entire tick (up to
     * {@link #REGION_ENTITY_BUDGET_NANOS}), so two regions reading each other at the
     * same time could otherwise deadlock forever — which hangs the WHOLE server
     * (RegionThreadPool's barrier waits on every region). Short relative to the tick
     * budget: on timeout, {@link RegionChunkView} falls back to a best-effort stale
     * read instead of blocking indefinitely.
     */
    public static final long CROSS_REGION_READ_LOCK_TIMEOUT_NANOS =
            Integer.getInteger("nestworld.crossRegionReadLockTimeoutMs", 2) * 1_000_000L;

    /**
     * Anti-grief: cap how many region-thread-spawned entities are registered on
     * the main thread per tick (the rest carry to following ticks). Off-main
     * spawns (mob breeding, projectiles, abilities) are queued and drained on
     * main; without a cap a deliberate flood (mass breeding, skeleton volleys)
     * would freeze the main thread draining the whole queue in one tick. The
     * queue is also hard-capped so the flood cannot exhaust memory — spawns past
     * it are dropped. Generous defaults so normal play never hits them.
     */
    public static final int MAX_DEFERRED_SPAWNS_PER_TICK =
            Integer.getInteger("nestworld.maxDeferredSpawnsPerTick", 1000);
    public static final int DEFERRED_SPAWN_QUEUE_CAP =
            Integer.getInteger("nestworld.deferredSpawnQueueCap", 200_000);

    /**
     * Weight of one unit of block-tick heat relative to one owned entity when
     * the split scorer scores candidate cut lines (see {@link BlockTickHeat}).
     * Block-tick heat is a decayed per-second count, so a busy redstone column
     * accumulates far more units than it has entities; this scales it down so a
     * single growth tick does not veto a split while a genuine machine still
     * dominates a cut's band score. Tune from the per-region heat reported by
     * {@code /nestworld status}. 0 disables block-tick awareness (entity-only,
     * the pre-8e434bc70 behaviour).
     */
    public static final double CUT_HEAT_WEIGHT =
            Double.parseDouble(System.getProperty("nestworld.cutHeatWeight", "0.15"));

    /**
     * Folia-style non-blocking chunk reads (EXPERIMENTAL, default off). When a
     * region thread reads a chunk that is not loaded, the default path queues a
     * synchronous load on the main thread and blocks on it (profiling under a
     * dense mob crowd showed ~25 % of region-thread time parked here, e.g. from
     * {@code Entity.isInsideWall} reading across an unloaded boundary). With this
     * on, an unloaded read instead returns a shared empty (void-air) chunk — no
     * sync load, no block, mirroring how Paper/Folia refuse to load chunks during
     * ticking. Trade-off: entities treat unloaded neighbours as empty space, so
     * behaviour at the very edge of loaded terrain can differ slightly. Enable
     * with {@code -Dnestworld.nonBlockingChunkReads=true}.
     */
    public static final boolean NONBLOCKING_CHUNK_READS =
            Boolean.getBoolean("nestworld.nonBlockingChunkReads");

    /**
     * Max chunk-holder future updates (the main-thread FULL/ticking promotions) the
     * DistanceManager applies per server tick. A burst of requested chunks (mass
     * {@code /forceload}, fast travel, a player exploring into ungenerated terrain)
     * otherwise promotes them all in one tick — with heavy custom gen this freezes
     * the main thread for tens of seconds (measured: 256 fresh chunks ≈ 60 s). With a
     * budget, the burst streams over many ticks at steady TPS; deferred holders stay
     * in {@code chunksToUpdateFutures} and are applied next tick (natural redrive),
     * highest-priority (lowest ticket level — player/forceload) first.
     *
     * <p>Deadlock-free: only the FULL/ticking promotion is paced, not generation
     * (which keeps its neighbour dependencies on the worker pool). Bit-identical
     * world — only the timing of when chunks appear changes.
     *
     * <p>{@code 0} = unlimited (vanilla behaviour, default). Set e.g.
     * {@code -Dnestworld.chunkGenBudget=8} to cap at 8 promotions/tick.
     */
    public static final int CHUNK_GEN_BUDGET =
            Integer.getInteger("nestworld.chunkGenBudget", 0);

    /**
     * Cap on how many BRAND-NEW top-level chunk-generation units (a {@code (ChunkHolder,
     * ChunkStatus)} pair that has never been scheduled before) are admitted into the
     * generation pipeline per server tick, for non-player-driven (bulk) requests.
     *
     * <p>Complements {@link #CHUNK_GEN_BUDGET}: that caps how many already-scheduled
     * chunk-holder futures get their FULL/ticking promotion <em>applied</em> per tick — a
     * narrow tail of the pipeline reached only via {@code DistanceManager.runAllUpdates}.
     * It does NOT cover a single caller (mass {@code /forceload}, a mod's synchronous
     * {@code getChunk(load=true)} check, e.g. a teleport-safety check) synchronously
     * triggering the recursive status-dependency + neighbour-cascade SCHEDULING walk
     * ({@code ChunkHolder.getOrScheduleFuture -> ChunkMap.schedule -> getChunkRangeFuture})
     * for thousands of chunks in one call — pure CPU-bound bookkeeping on the calling
     * thread. Measured (2026-08-05, clean core, no mods): a single 256-chunk
     * {@code /forceload} burst on a resource-constrained core can alone trip the 60 s
     * {@code ServerHangWatchdog}; a stacked run of ten such bursts reproduced the exact
     * watchdog crash (identical {@code ServerChunkCache.getChunk} stack) seen on a real
     * pack from a player flying with {@code predictiveGen} + an FTBChunks map teleport.
     *
     * <p>Deadlock-free by construction: the cap applies ONLY when NOT already inside a
     * scheduling cascade on the calling thread (tracked via a thread-local re-entrancy
     * marker set for the duration of {@code ChunkMap.schedule}) — so once a top-level
     * request is admitted, its entire recursive dependency graph (parent statuses,
     * neighbour range) resolves normally and uncapped; a gated request never orphans a
     * neighbour another chunk is depending on. A gated request gets a fresh, not-yet-done
     * future stored in the holder's normal slot (so repeat callers just keep waiting on
     * it, same as any in-progress schedule) and is queued to actually start once budget
     * frees up on a later tick — bit-identical result, only the timing changes. Player-
     * driven (interactive) requests are never gated — same tiering rule as
     * {@link #CHUNK_GEN_BUDGET}.
     *
     * <p>{@code 0} = unlimited (vanilla behaviour, default). Set e.g.
     * {@code -Dnestworld.chunkGenAdmitBudget=32} to admit at most 32 new bulk
     * (holder,status) scheduling units per tick.
     */
    public static final int CHUNK_GEN_ADMIT_BUDGET =
            Integer.getInteger("nestworld.chunkGenAdmitBudget", 0);

    /**
     * Cap on how many chunk-status GENERATION calls (the actual CPU-bound
     * {@code NoiseBasedChunkGenerator}/{@code Aquifer}/feature-placement work — the
     * final {@code ChunkStatus.generate()} call, not scheduling) run CONCURRENTLY
     * across the worker pool for a dimension at any one time. {@link #CHUNK_GEN_ADMIT_BUDGET}
     * bounds how fast NEW work enters the pipeline; this bounds how much of it can be
     * actively burning CPU at once — closing the residual gap where admission is paced
     * correctly but the resulting worker-pool contention still starves the main thread
     * of CPU time on a constrained box (measured: main thread genuinely CPU-starved,
     * not blocked on anything, while a worker thread churned inside
     * {@code NoiseBasedAquifer} — confirmed via jstack during an extreme stacked-burst
     * test that survived admission gating but still crossed the 60 s watchdog).
     *
     * <p><b>Only {@code NOISE}/{@code SURFACE}/{@code CARVERS}/{@code FEATURES} are
     * gated</b> — this narrowing is safety-critical, not a tuning choice. An earlier
     * attempt gated every status and hung a completely idle fresh-server startup
     * indefinitely: {@code ChunkStatus.FULL}'s generation task is a bare pass-through
     * to {@code ChunkMap.protoChunkToFullChunk}, which reads THIS SAME chunk's own
     * prior-status future via {@code getFutureIfPresentUnchecked} — a queued FULL can
     * hold a slot while waiting on its own parent status, which can itself be stuck
     * behind that very slot. The four gated statuses were verified by reading every
     * {@code ChunkStatus} registration: none of them invoke the callback that creates
     * this self-reference — they're pure leaf computations over the already-fetched
     * neighbour list (see {@code ChunkMap.getChunkRangeFuture}'s cascade-exemption),
     * matching the "independent leaf tasks on a bounded pool" shape the deadlock-
     * freedom argument requires. Do not extend this set to other statuses (especially
     * {@code FULL} or {@code LIGHT}) without re-verifying their generation task bodies
     * the same way.
     *
     * <p>{@code 0} = unlimited (vanilla behaviour, default). Set e.g.
     * {@code -Dnestworld.chunkGenMaxConcurrent=2} to allow at most 2 gated-status
     * generations running at once. Pick a value that leaves real CPU headroom for the
     * main thread given your core count — this is a coarser, blunter lever than the
     * two above; prefer leaving it off unless the admit-budget pair alone still isn't
     * enough for your hardware under worst-case load.
     */
    public static final int CHUNK_GEN_MAX_CONCURRENT =
            Integer.getInteger("nestworld.chunkGenMaxConcurrent", 0);

    /**
     * Spatial-cull the entity tracker's per-player-move update (EXPERIMENTAL, default
     * off). Vanilla {@code ChunkMap.move(player)} rescans <em>every</em> tracked entity
     * on each player-move packet to recompute visibility — O(total entities) per move.
     * At extreme entity counts this stalls the main thread (measured: ~86k entities →
     * ~1.1 s per move packet, TPS 3). With this on, {@code move} instead queries only the
     * entities within tracking range of the player's new position (plus the move delta,
     * so entities leaving range are still un-tracked correctly), falling back to the full
     * scan when the delta is large (teleport). Bit-identical visibility — only the cost of
     * computing it changes. Enable with {@code -Dnestworld.trackerSpatialCull=true}.
     */
    public static final boolean TRACKER_SPATIAL_CULL =
            Boolean.getBoolean("nestworld.trackerSpatialCull");

    /**
     * Event-driven region-ownership reassignment (validated, default ON). Vanilla-side
     * {@link net.nestworld.region.BoundaryEntityTransfer#checkAndReassign} scans <em>every</em>
     * loaded entity each tick to detect region-border crossings — O(all entities), even though
     * only entities that actually changed section can have crossed. With this on, the entity
     * section-move callback ({@code PersistentEntitySectionManager.Callback.onMove}) marks the
     * moved entity dirty, and {@code checkAndReassign} processes only the drained dirty set,
     * falling back to a periodic full scan (every {@link #OWNERSHIP_FULL_PASS_TICKS} ticks) and
     * on any layout change as a safety net — so a missed mark self-heals within that window and
     * can never double-tick (the {@code inTransfer} guard still holds). Marks are written by
     * region threads during the tick and drained on the main thread after the barrier, so there
     * is no concurrent access. Biggest win when most entities are stationary (the common case);
     * neutral when everything is moving. Validated (entityXfer 7.6->0.45ms). Disable with {@code -Dnestworld.eventDrivenOwnership=false}.
     */
    public static final boolean EVENT_DRIVEN_OWNERSHIP =
            Boolean.parseBoolean(System.getProperty("nestworld.eventDrivenOwnership", "true"));

    /** Safety-net interval (ticks) for a full ownership scan when {@link #EVENT_DRIVEN_OWNERSHIP} is on. */
    public static final int OWNERSHIP_FULL_PASS_TICKS =
            Integer.getInteger("nestworld.ownershipFullPassTicks", 200);

    /**
     * Throttle {@code ChunkMap.move(player)}'s entity-tracking re-evaluation to at most once
     * per server tick per player (default on). A fast-moving player (flying) emits several
     * move packets per tick; vanilla re-evaluates visibility for the nearby entities on
     * <em>each</em> one, serially on the main thread. Near a dense entity pile that is
     * O(entities-near-player) <em>per packet</em> — measured as a single thread pinned at 100%
     * with TPS collapsing to ~0 while flying past ~150k PrimedTNT, even with
     * {@link #TRACKER_SPATIAL_CULL} on (the pile <em>is</em> within tracking range). Capping
     * the re-evaluation to once per tick removes the per-packet multiplier; the periodic
     * tracker {@code tick()} (now parallel) and the next tick's move pick up any remainder, so
     * tracking lags by at most one tick. Disable with {@code -Dnestworld.trackerMoveThrottle=false}.
     */
    public static final boolean TRACKER_MOVE_THROTTLE =
            Boolean.parseBoolean(System.getProperty("nestworld.trackerMoveThrottle", "true"));

    /**
     * Parallelise the periodic entity-tracker broadcast ({@code ChunkMap.tick} →
     * {@code ServerEntity.sendChanges} per tracked entity). Vanilla runs this serially on
     * the main thread: it packs each entity's dirty {@code SynchedEntityData} and sends the
     * metadata/move packets to every viewer — O(entities × viewers). On the region core this
     * is the dominant <em>serial</em> ('vanilla'-phase) cost at high entity counts (measured:
     * ~50k per-tick-dirty PrimedTNT → vanilla phase ~100 ms, regions parked idle at the
     * barrier). The tracker {@code tick()} runs on main <em>after</em> the region barrier, so
     * entity state is stable; {@code sendChanges} only reads it, packs dirty data via the
     * thread-safe (clear-first, read-locked, defensive-copy) {@code packDirty}, and enqueues
     * packets on netty-thread-safe connections — so it parallelises safely across the common
     * pool. Section-change detection stays serial (it mutates shared visibility state).
     * Default on; only kicks in above {@link #TRACKER_PARALLEL_BROADCAST_THRESHOLD} entities
     * (below that the fork/join overhead outweighs the gain). Disable with
     * {@code -Dnestworld.trackerParallelBroadcast=false}.
     */
    public static final boolean TRACKER_PARALLEL_BROADCAST =
            Boolean.parseBoolean(System.getProperty("nestworld.trackerParallelBroadcast", "true"));

    /** Min number of entities needing a broadcast before {@link #TRACKER_PARALLEL_BROADCAST} parallelises. */
    public static final int TRACKER_PARALLEL_BROADCAST_THRESHOLD =
            Integer.getInteger("nestworld.trackerParallelBroadcastThreshold", 512);

    /**
     * Parallelise the tracker's per-tick DETECTION loop (validated, default ON). {@code
     * ChunkMap.tick} iterates every tracked entity to recompute its section ({@code SectionPos.of}),
     * detect section changes, and decide who needs a broadcast — O(all tracked) SERIALLY on the main
     * thread, which is the dominant 'vanilla'-phase cost once {@link #TRACKER_PARALLEL_BROADCAST}
     * has already offloaded the actual {@code sendChanges} (measured: ~178k per-tick-dirty PrimedTNT
     * → detection loop ~111 ms while region threads sit parked at the barrier). Unlike the
     * event-driven shortcuts this also helps when <em>every</em> entity is dirty (TNT fuses), because
     * it parallelises the scan itself rather than skipping it. The pass is read-only on entity state
     * (stable — region threads are parked); the one mutation ({@code updatePlayers} when an entity
     * changed section, which touches the shared {@code seenBy} sets) is deferred to a short SERIAL
     * post-pass over only the entities that actually moved section — so no shared visibility state is
     * mutated concurrently. Bit-identical visibility; only the cost of computing it changes. Default
     * off; only engages above {@link #TRACKER_PARALLEL_DETECTION_THRESHOLD}. Enable with
     * {@code -Dnestworld.trackerParallelDetection=false} to disable.
     */
    public static final boolean TRACKER_PARALLEL_DETECTION =
            Boolean.parseBoolean(System.getProperty("nestworld.trackerParallelDetection", "true"));

    /** Min number of tracked entities before {@link #TRACKER_PARALLEL_DETECTION} parallelises the scan. */
    public static final int TRACKER_PARALLEL_DETECTION_THRESHOLD =
            Integer.getInteger("nestworld.trackerParallelDetectionThreshold", 4096);

    /**
     * C1 — skip the entity-collision broad-phase when no entity can hard-block movement
     * (validated, default ON). {@code Entity.move -> getEntityCollisions} iterates every entity
     * in the moving entity's swept AABB looking for ones with {@code canBeCollidedWith()} (boats,
     * minecarts, shulkers, armor stands). In a dense pile of TNT/items — which collide with nothing —
     * that iterates thousands of neighbours per moving entity only to return empty (measured ~108 ms
     * on the hot region thread at 100k+ TNT). {@code ServerLevel} keeps a count of loaded
     * hard-collidable entities (maintained on the add/remove callbacks); when it is zero the result
     * is provably empty, so the broad-phase is skipped — bit-identical. The count over-approximates
     * (armor stands always counted, since their collidability toggles with the Marker flag) so it
     * never under-reports. Note: the count is per-level/global, so this only helps when the <em>whole</em>
     * dimension has no collidable entity (true for a pure TNT pile; a single boat elsewhere disables
     * it). Validated (collision broad-phase eliminated at 100k TNT). Disable with {@code -Dnestworld.skipEmptyEntityCollision=false}. Note: a heavily-modded entity with dynamic collidability is the only edge case; toggle off if collisions misbehave.
     */
    public static final boolean SKIP_EMPTY_ENTITY_COLLISION =
            Boolean.parseBoolean(System.getProperty("nestworld.skipEmptyEntityCollision", "true"));

    /**
     * C2 — cache explosion exposure per block position (EXPERIMENTAL, default off). {@code
     * Explosion.explode} calls {@code getSeenPercent} for every entity in the blast, casting up to 27
     * occlusion rays each (measured ~118-154 ms when thousands of entities sit in overlapping blasts).
     * With this on, the exposure raycast result is memoised per {@code BlockPos} within a single
     * explosion, so entities sharing a block reuse it. NOT bit-identical (entities at different
     * sub-block offsets get the same value — the same approximation Paper's optimizeExplosions makes),
     * so it is opt-in. Enable with {@code -Dnestworld.cacheExplosionExposure=true}.
     */
    public static final boolean CACHE_EXPLOSION_EXPOSURE =
            Boolean.getBoolean("nestworld.cacheExplosionExposure");

    /**
     * Phase 2 / Folia step — regionalise the entity tracker (EXPERIMENTAL, default off). Instead of
     * the serial 'vanilla'-phase {@code ChunkMap.tick} doing detection + broadcast for every entity
     * on the main thread, each region thread tracks the entities it OWNS during its own tick
     * (regionPool phase): section-change detection, {@code updatePlayers}, and {@code sendChanges}.
     * The main-thread {@code ChunkMap.tick} then only handles what the regions did NOT (players,
     * unowned/just-spawned/pinned entities, and any an over-budget region deferred) — detected via a
     * per-entity "tracked this tick" stamp, so nothing is ever missed (no invisible entities) and
     * nothing is processed twice. Safe because an entity's tracker state ({@code seenBy},
     * {@code lastSectionPos}) is only ever touched by its single owning region thread, and the region
     * (regionPool) and main ({@code tick}/{@code move}) tracker passes run in different, non-overlapping
     * phases of the server tick. Removes the serial tracker phase entirely (the dominant cost at high
     * entity counts). Enable with {@code -Dnestworld.regionalizedTracker=true}.
     */
    public static final boolean REGIONALIZED_TRACKER =
            Boolean.getBoolean("nestworld.regionalizedTracker");

    /**
     * Auto-spark (default ON, no-op if the spark standalone agent isn't present). The core starts a
     * continuous all-threads ({@code --thread *}) profiler at server boot and never stops it — the
     * only interaction is {@code open}. On server stop/restart it opens the profile and appends the
     * dated viewer link to {@code spark-history.txt} next to the server, so you can look at what the
     * server was doing at any past session (main thread + all parallel region threads in one link)
     * without running any command. Disable with {@code -Dnestworld.autoSpark=false}.
     */
    public static final boolean AUTO_SPARK =
            Boolean.parseBoolean(System.getProperty("nestworld.autoSpark", "true"));

    private NestworldTuning() {
    }
}
