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

    private NestworldTuning() {
    }
}
