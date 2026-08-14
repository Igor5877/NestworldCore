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
     * Hard cap on how many {@code FallingBlockEntity} instances (sand, gravel,
     * concrete powder, scaffolding, dripstone, suspicious sand/gravel) may exist
     * across the whole server at once. {@code 0} = unlimited (vanilla behaviour,
     * default).
     *
     * <p>Real-world trigger (2026-08-07, live ATM9, real player-built setup): a
     * dispenser/observer "sand duper"-style loop accumulated 12500+ falling_block
     * entities in a single tiny area. Unlike items, falling blocks never merge/stack,
     * and each one runs its own gravity+collision physics AND constantly moves (so it's
     * never visibility-static for the tracker either) every tick it's airborne — this
     * single hotspot alone cost ~43ms in the owning region's entity round (correctly
     * capped/deferred by {@link #REGION_ENTITY_BUDGET_NANOS}) PLUS ~46ms in the
     * separate main-thread tracker/broadcast phase (packing and sending that many
     * constantly-dirty entities' movement to the watching player) -- these two phases
     * run sequentially, not in parallel, so together they blew a 50ms tick budget to
     * ~97ms (TPS 20 -&gt; ~10) even though neither phase was individually pathological
     * and the box was nowhere near CPU-saturated overall (region-entity-budget and the
     * tracker parallelization flags were already doing their job; the sheer entity
     * COUNT was the problem, not a missing optimization).
     *
     * <p>When the live count is at or above this cap, {@code FallingBlockEntity.fall()}
     * still performs the normal block-removal side effect (so a capped block doesn't
     * become permanently un-fallable / stuck mid-air visually) but skips adding the
     * entity to the world -- the block simply vanishes instead of animating a fall and
     * landing. Every caller of {@code fall()} unconditionally dereferences its return
     * value (none currently null-check it), so the factory always returns a real,
     * usable (if world-detached) object rather than {@code null} -- this is a
     * deliberate constraint on how the cap can be implemented, not an oversight.
     *
     * <p>Set e.g. {@code -Dnestworld.maxFallingBlocks=2000} to cap globally. Pick a
     * value well above anything normal building/mining produces (a large sand pyramid
     * collapsing, TNT-cratering into a sand/gravel vein) -- this is an abuse/lag-machine
     * safety valve, not a normal-gameplay throttle.
     */
    public static final int MAX_FALLING_BLOCKS =
            Integer.getInteger("nestworld.maxFallingBlocks", 0);

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
     * Stage 5 tick-scheduler architecture, Part 3 (docs/LOCAL_TICK_STAGE4.md, "Stage 5
     * tick-scheduler architecture — DECIDED", Blocker 3 read-side). Quantified "cascade
     * safety margin": {@link #BORDER_BAND_CHUNKS}'s 32-block width plus the maximum
     * single-cascade reach across the vanilla mechanisms that can trigger from a
     * border-band-deferred write — redstone dust decaying from a 15-strength source is
     * the binding case (wider than a piston's 12-block {@code MAX_PUSH_DEPTH}), both
     * confirmed by reading the actual constants, not assumed. 32+15=47, rounded up to a
     * whole-chunk margin. Before applying a deferred EXPLOSION_APPLY/BLOCK_WRITE message,
     * every region within this many blocks of the write's position must be lock-excluded
     * — see {@link WorldGrid#getRegionsWithinMargin} and the apply-site guard in
     * {@code NestworldRegionSystem}. Under today's still-barrier-synchronized model this
     * lock is always uncontended (every region is already parked at the point it runs) —
     * it becomes load-bearing only once Part 5 (free-running regions) ships.
     */
    public static final int CASCADE_SAFETY_MARGIN_BLOCKS =
            Integer.getInteger("nestworld.cascadeSafetyMarginBlocks", 48);

    /**
     * Cap on how long applying a deferred border-band write will wait to acquire ANOTHER
     * region's {@code chunkLock} (see {@link #CASCADE_SAFETY_MARGIN_BLOCKS}). Same
     * philosophy as {@link #CROSS_REGION_READ_LOCK_TIMEOUT_NANOS} — short relative to a
     * tick, never blocks indefinitely. On timeout the write is re-posted to its own
     * destination mailbox and retried on a later pass instead of applied — the same
     * "eventually applied" Tier 2 contract every other deferred write already uses, not a
     * new failure mode.
     */
    public static final long CASCADE_LOCK_TIMEOUT_NANOS =
            Integer.getInteger("nestworld.cascadeLockTimeoutMs", 2) * 1_000_000L;

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
     * Time budget (ms) for draining deferred spawns per tick, on top of the
     * entity-count cap above. Region threads are fully idle at the barrier for
     * the whole duration of this drain (it mutates ChunkMap's non-thread-safe
     * entity tracking, so it must run alone) — so the count-based cap alone maps
     * unpredictably to actual tick-time cost: real per-entity registration cost
     * (chunk lookup + entity-list insert + tracking setup + events) varies with
     * entity complexity, and a "budget" expressed purely as a count can silently
     * consume far more of the tick than intended once real per-entity cost is
     * known (confirmed live on ATM9 under a mass-TNT flood: ~1000 entities/tick
     * cost ~150-200ms — CPU sat at ~64% the whole time because region threads
     * were correctly idle, not because more cores were available to use).
     * Draining stops at whichever limit — count or time — is hit first, so this
     * only ever TIGHTENS the existing cap, never loosens it; it exists purely to
     * make the per-tick cost of this phase predictable and directly tunable in
     * milliseconds instead of an indirect entity count.
     */
    public static final long DEFERRED_SPAWN_BUDGET_NANOS =
            Integer.getInteger("nestworld.deferredSpawnBudgetMs", 5) * 1_000_000L;

    /**
     * Stage 5.3 design v3 (docs/LOCAL_TICK_STAGE4.md): time budget (ms), shared
     * across all regions, for applying deferred cross-region mailbox messages
     * (EXPLOSION_APPLY, BLOCK_WRITE) at the post-region-tick barrier point. Same
     * "count alone doesn't bound tick-time cost predictably" reasoning as {@link
     * #DEFERRED_SPAWN_BUDGET_NANOS} — a single flood-triggering event (e.g. a
     * chain-reaction explosion whose blast crosses a region boundary) can post an
     * arbitrarily large batch; without a budget, applying it all in one barrier
     * pass would stall the main thread for however long that batch takes. Any
     * messages left unapplied when the budget is hit simply stay queued — they
     * are picked up on a LATER tick's barrier pass, extending (not violating) the
     * existing Tier 2 "eventually applied" contract these message types already
     * use. Generous default so normal play never hits it.
     */
    public static final long MAILBOX_DRAIN_BUDGET_NANOS =
            Integer.getInteger("nestworld.mailboxDrainBudgetMs", 5) * 1_000_000L;

    /**
     * Step 3 (docs/LOCAL_TICK_STAGE4.md, "Step 3 — Single Free-Running Region"): hard
     * kill-switch for the entire free-running-region code path. Off by default —
     * {@code RegionThread.run()}'s branch on {@code region.isFreeRunning()} is dead
     * code, byte-for-byte the same execution path as today, unless this is set AND a
     * region is explicitly toggled via {@code /nestworld freerun <id>}. Gemini-reviewed
     * design (2026-08-10): "sound and implementable... proceed with implementation."
     */
    public static final boolean FREE_RUNNING_REGIONS_ENABLED =
            System.getenv("NESTWORLD_FREE_RUNNING_REGIONS") != null
                    || Boolean.getBoolean("nestworld.freeRunningRegions");

    /** Target cadence for a free-running region's own self-paced loop — same ~50ms/tick
     *  vanilla itself targets, so a free-running region's baseline pace matches today's
     *  unless something (load, deliberate throttling) pushes it off that target. */
    public static final long FREE_RUNNING_TARGET_TICK_NANOS = 50_000_000L;

    /** Bounded wait for a free-running region to confirm it has paused for a world-save
     *  rendezvous (design point 3) — same timeout-not-indefinite-block philosophy as
     *  {@link #CROSS_REGION_READ_LOCK_TIMEOUT_NANOS}/{@link #CASCADE_LOCK_TIMEOUT_NANOS}. */
    public static final long FREE_RUNNING_SAVE_RENDEZVOUS_TIMEOUT_NANOS =
            Integer.getInteger("nestworld.freeRunningSaveRendezvousTimeoutMs", 2000) * 1_000_000L;

    /**
     * Advisory cap on simultaneously free-running regions: {@code availableProcessors() -
     * this many} reserved for the main thread + RegionThreadPool bookkeeping. Free-running
     * regions each self-schedule on their own thread with zero coordination between them
     * (RegionThread.runFreeRunningTick()) -- with spare cores this is a clean win (2026-08-13
     * scaling test Run B beat Run A at every region count), but on a small core count the
     * uncoordinated threads oversubscribe and contend, making it a net LOSS (same session's
     * Run D was consistently worse than Run C's barrier model at matched region counts,
     * ghostZones+tracker alone ballooning to 29% of tick cost). Per the resulting hardware-
     * priority decision, weak CPUs are a "don't break" floor, not a scaling target -- so this
     * is advisory (warns in /nestworld freerun's response), not a hard refusal: an operator
     * deliberately testing free-running behavior (as this exact scaling test did) should not
     * be blocked by their own diagnostic tooling.
     */
    public static final int FREE_RUNNING_RESERVED_CORES =
            Integer.getInteger("nestworld.freeRunningReservedCores", 2);

    /**
     * Step 5 (docs/LOCAL_TICK_STAGE4.md, "Step 5 — N free-running regions"): automatic
     * free-running promotion/demotion policy, built on top of Step 3's manual {@code
     * /nestworld freerun} toggle (see {@link RegionSplitManager#evaluateAutoFreeRunning}).
     * Off by default — ships the capability without silently changing live server
     * behavior on deploy; an operator opts in explicitly once ready to trust the policy
     * on their hardware. Hard-capped by {@link #FREE_RUNNING_RESERVED_CORES} (unlike the
     * manual command's advisory warning, this is a real refusal to promote past the cap)
     * — on a weak-CPU box the cap evaluates to 0 or near it, so enabling this flag there
     * is a safe near-no-op, matching the target-hardware-priority decision (2-4 cores is
     * a "must not break" compatibility floor, not a scaling target this policy needs to
     * help with). Requires {@link #FREE_RUNNING_REGIONS_ENABLED} to have any effect.
     */
    public static final boolean AUTO_FREE_RUNNING_ENABLED =
            Boolean.parseBoolean(System.getProperty("nestworld.autoFreeRunning", "false"));

    /**
     * Stage 5.3 v3, Fix 3 (docs/LOCAL_TICK_STAGE4.md, "backpressure without message
     * loss"): the destination-mailbox depth ({@link WorldRegion#nestworldMailboxDepthFast})
     * above which a SENDER holds new messages to that destination at its own side instead
     * of posting (see {@link WorldRegion#nestworldSendOrQueue}) — reopened 2026-08-14 after
     * Step 5 (many simultaneous uncoordinated free-running regions) produced the first-ever
     * observed unbounded mailbox growth (43,821 -> 51,250 -> 139,541 in ~80s, crashing via
     * ServerHangWatchdog while the main thread's drain/apply loop tried to work through it).
     * Started at 2000 (headroom below the tens-of-thousands the original crash reached);
     * empirical re-test under the SAME continuous-flood reproduction showed 2000 was still
     * high enough to let a slower-building version of the same MSPT death-spiral occur
     * (mailbox growing ~2,000/round, TPS 17.6 -> 3.4 over ~5 minutes before the crash) --
     * tightened to 300 after that finding, alongside shrinking {@link
     * WorldRegion#nestworldDrainMailboxBudgeted}'s per-call deadline-check granularity
     * (16 -> 4 applies) so a single region's unconditional "floor" of work before its
     * first clock check costs less even when every one of those applies is contending on
     * {@link WorldRegion#getChunkLock()}. High enough that ordinary bursts (a single big
     * explosion's foreign-region batch, tonight's 13,175-block cube test) never engage it,
     * low enough to keep the apply-side cost that caused the crash from compounding.
     */
    public static final int MAILBOX_BACKPRESSURE_THRESHOLD =
            Integer.getInteger("nestworld.mailboxBackpressureThreshold", 300);

    /** Max backpressure-deferred sends one region retries per own local tick (same
     *  budgeted-work-slice idiom as every other per-tick cap in this codebase) — bounds
     *  the flush's own cost so it can never itself become a source of tick-budget blowout. */
    public static final int MAILBOX_BACKPRESSURE_FLUSH_BUDGET =
            Integer.getInteger("nestworld.mailboxBackpressureFlushBudget", 64);

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
     * Chunk-radius (in chunks, Chebyshev/square, around a player's CURRENT chunk position)
     * within which a pending chunk-holder promotion is "Tier 1a — urgent": processed
     * unconditionally every tick, no budget, same guarantee the old undifferentiated Tier 1
     * gave every player-ticketed chunk. Found necessary 2026-08-11 (live ATM9 incident,
     * see project memory: distancemanager-tier1-volume-freeze.md): a player flying fast
     * through ungenerated territory can grow the "currently pending, has a player ticket"
     * population into the tens of thousands (everything in view distance, not just what's
     * near them right now), and since that whole tier was unconditional, a single tick could
     * end up calling {@code ChunkHolder.updateFutures()} tens of thousands of times back to
     * back — individual calls are cheap (sub-ms typical) but the volume alone produced
     * observed 18+ second main-thread freezes. Anything with a player ticket OUTSIDE this
     * radius is "Tier 1b" instead (see {@link #CHUNK_GEN_TIER1B_BUDGET}) -- budgeted like
     * bulk gen, trading a few ticks of pop-in at the edge of view distance (an unavoidable,
     * normal cost of moving faster than generation can keep up, same as vanilla's own
     * behavior under extreme movement speed) for a hard bound on worst-case per-tick cost.
     * Gemini-reviewed: sized generously (not tied to one player-speed constant) specifically
     * to survive the lag-spike-causes-effectively-larger-movement-per-observed-tick case this
     * mechanism itself is trying to recover from.
     */
    public static final int PLAYER_URGENT_CHUNK_RADIUS =
            Integer.getInteger("nestworld.playerUrgentChunkRadius", 3);

    /**
     * Region-Owned Chunk Scheduler, Phase 1 (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section 5):
     * distance-to-player thresholds (in chunks) mapping a chunk request to one of the 5 priority
     * tiers ({@code net.nestworld.chunk.ChunkRequestPriority}). Distances 0..CRITICAL are
     * CRITICAL, (CRITICAL..HIGH] are HIGH, (HIGH..NORMAL] are NORMAL, everything beyond NORMAL is
     * LOW; BACKGROUND is assigned by request source (forceload/bulk), not distance, so it has no
     * radius constant here. Not yet consumed anywhere -- Phase 1 is dark code (see
     * {@code RegionChunkScheduler}'s class doc); reserved now so Phase 2's distance-classification
     * call site doesn't need new tuning plumbing.
     */
    public static final int CHUNK_PRIORITY_CRITICAL_RADIUS =
            Integer.getInteger("nestworld.chunkPriorityCriticalRadius", 2);
    public static final int CHUNK_PRIORITY_HIGH_RADIUS =
            Integer.getInteger("nestworld.chunkPriorityHighRadius", 6);
    public static final int CHUNK_PRIORITY_NORMAL_RADIUS =
            Integer.getInteger("nestworld.chunkPriorityNormalRadius", 12);

    /**
     * Region-Owned Chunk Scheduler, Phase 2: master on/off switch for the shadow-mode observation
     * hook (docs/REGION_CHUNK_SCHEDULER_SPEC.md, section on shadow-mode validation before
     * cutover). {@code false} by default (opt-in) -- even though the hook is deferred/queued and
     * defensively try/catch-wrapped (Gemini-reviewed 2026-08-12), this flag lets it be disabled
     * without a rebuild if anything unexpected shows up while gathering comparison data on a test
     * server. Purely observational when enabled: zero effect on real chunk loading either way.
     */
    public static final boolean CHUNK_SCHEDULER_SHADOW_MODE =
            Boolean.getBoolean("nestworld.chunkSchedulerShadowMode");

    /**
     * Per-tick cap on Tier 1b (player-ticketed, but outside {@link #PLAYER_URGENT_CHUNK_RADIUS})
     * promotions applied — separate from {@link #CHUNK_GEN_BUDGET} (Tier 2, non-player bulk/
     * forceload gen) per Gemini's explicit recommendation: player-visible pop-in resolution
     * should not compete with (or be starved by) background bulk generation for the same
     * budget slice. {@code 0} = unlimited (same as the old undifferentiated-Tier-1 behavior
     * for this tier specifically — NOT recommended once a player can realistically produce a
     * large Tier 1b population, which is exactly the incident this exists to prevent).
     */
    public static final int CHUNK_GEN_TIER1B_BUDGET =
            Integer.getInteger("nestworld.chunkGenTier1bBudget", 64);

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
     * <p><b>2026-08-07 redesign:</b> this used to ALSO double as the standing in-flight
     * concurrency cap (how many admitted-but-unresolved scheduling units may exist at
     * once) — the same small number gated both "how fast new work enters" AND "how much
     * may be outstanding". That conflation is what made this mechanism actively harmful
     * at ordinary scale: a ROUTINE {@code /forceload} of 9-16 chunks needs up to ~4
     * gateable statuses each (NOISE/SURFACE/CARVERS/FEATURES), so a modest burst alone
     * produces 40-60+ competing scheduling units — with the in-flight cap pinned to the
     * same value as the per-window rate (e.g. 4), those units serialize into ~15+
     * sequential rounds, each bounded by its slowest member. Measured live
     * (2026-08-07, real 419-mod pack): a single isolated 9-16 chunk forceload call took
     * 20s+ (hit the {@link #FORCELOAD_WAIT_TIMEOUT_MS} bailout) with this gate at its
     * shipped default of 4/4/2 — with the whole gate disabled it took 0.78s, FASTER than
     * vanilla stock Forge on the same pack/world (10.33s). See {@link
     * #CHUNK_GEN_ADMIT_INFLIGHT_CAP} for the now-separate standing cap this rate limiter
     * no longer doubles as.
     *
     * <p>{@code 0} = unlimited (vanilla behaviour, default). Set e.g.
     * {@code -Dnestworld.chunkGenAdmitBudget=32} to admit at most 32 new bulk
     * (holder,status) scheduling units per ~tick-length window.
     */
    public static final int CHUNK_GEN_ADMIT_BUDGET =
            Integer.getInteger("nestworld.chunkGenAdmitBudget", 0);

    /**
     * Standing cap on how many bulk (holder,status) scheduling units may be
     * <em>admitted but not yet resolved</em> at once — independent of {@link
     * #CHUNK_GEN_ADMIT_BUDGET}'s per-window admission RATE. This is what actually
     * defends against unbounded backlog growth across MANY separate bursts (each burst
     * passes its own rate check independently, so a rate cap alone doesn't stop repeated
     * bursts from piling up faster than they resolve — see the field-group comment on
     * {@code ChunkMap.nestworldInFlight} for the original finding this protects
     * against: 3 stacked 256-chunk bursts survived, a 4th crossed the 60s watchdog).
     *
     * <p>Deliberately NOT the same knob as {@link #CHUNK_GEN_MAX_CONCURRENT} — an
     * "admitted" unit is pure scheduling bookkeeping (a stored {@code CompletableFuture}
     * plus whatever of its dependency graph is in memory), not necessarily active CPU
     * work (that's what {@code CHUNK_GEN_MAX_CONCURRENT}/{@code
     * CHUNK_GEN_FEATURES_MAX_CONCURRENT} bound separately) — but a large number of
     * simultaneously in-flight scheduling graphs is still real heap pressure, which is
     * why this exists at all rather than leaving admission concurrency fully unbounded.
     *
     * <p>{@code 0} = unlimited — no standing cap, only {@link #CHUNK_GEN_ADMIT_BUDGET}'s
     * rate paces admission (default; matches this file's usual "off unless configured"
     * convention). Set e.g. {@code -Dnestworld.chunkGenAdmitInflightCap=512} to
     * reintroduce a hard ceiling if a stacked-burst stress test on your own hardware
     * shows the rate limiter alone isn't enough — pick the value from that test, not
     * this default.
     *
     * <p><b>Has no effect unless {@link #CHUNK_GEN_ADMIT_BUDGET} is also &gt; 0</b> —
     * {@code ChunkMap.nestworldTryScheduleNow} bypasses the ENTIRE admission gate
     * (rate and this cap both) up front when the rate is disabled, so setting only
     * this property with the rate left at its default is silently a no-op.
     */
    public static final int CHUNK_GEN_ADMIT_INFLIGHT_CAP =
            Integer.getInteger("nestworld.chunkGenAdmitInflightCap", 0);

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
     * Same idea as {@link #CHUNK_GEN_MAX_CONCURRENT}, but a SEPARATE cap applying only to
     * {@code ChunkStatus.FEATURES} (ore veins, trees, structures' non-start pieces, etc.).
     *
     * <p>Kept independent rather than folded into the shared pool above for two reasons:
     * (1) real modpacks can make FEATURES far more expensive per-chunk than NOISE/SURFACE/
     * CARVERS (GregTech alone registers many ore vein types), so operators may want to cap
     * it lower than the others independently; (2) FEATURES was, until this fix, the ONLY
     * gated status whose concurrent execution could deadlock — see {@code
     * BulkSectionAccess}'s class-level comment for the full history: {@code OreFeature}
     * (vanilla) and, confirmed by reading real decompiled bytecode from a live 188-mod
     * pack, GregTech's {@code OrePlacer}, Mekanism's {@code ResizableOreFeature}, and Lost
     * Cities' {@code ChunkDriver} all construct {@code BulkSectionAccess} directly, which
     * used to acquire touched {@code LevelChunkSection}s lazily in visit order and hold
     * them until done — two concurrent placements reaching into each other's territory
     * could deadlock AB-BA. {@code BulkSectionAccess} now uses a deadlock-free release-and-
     * reacquire-in-total-order algorithm instead (see its source), so real FEATURES
     * concurrency &gt; 1 is safe again — this cap is now a pure throughput/resource-tuning
     * knob, the same as the shared pool, not a safety mechanism. (An earlier version of
     * this cap was hard-coded to exactly 1 regardless of configuration, before that fix
     * landed — CONFIRMED insufficient under real load by testing: rapid repeated player
     * teleports to unloaded terrain can arrive faster than a single global FEATURES slot
     * drains, growing an unbounded queue until some chunk's wait time alone crosses the
     * 60s {@code ServerHangWatchdog} threshold, with no deadlock or leak involved at all —
     * pure overload. A hard cap of 1 is no longer needed OR sufficient; real throughput is.)
     *
     * <p>{@code 0} = unlimited (vanilla behaviour, default). Tune independently of {@link
     * #CHUNK_GEN_MAX_CONCURRENT}, e.g. lower if FEATURES turns out to dominate wall-clock
     * time under your modpack.
     */
    public static final int CHUNK_GEN_FEATURES_MAX_CONCURRENT =
            Integer.getInteger("nestworld.chunkGenFeaturesMaxConcurrent", 0);

    /**
     * Number of parallel {@code worldgenMailbox} shards per dimension (EXPERIMENTAL,
     * default 1 = vanilla behaviour). Vanilla funnels ALL chunk-status generation work
     * (NOISE/SURFACE/CARVERS/FEATURES — the actual CPU-bound density-function/surface-
     * rule/feature-placement work) for an entire dimension through a SINGLE {@code
     * ProcessorMailbox}. That mailbox's own {@code registerForExecution}/{@code
     * setAsScheduled} gate (an atomic CAS bit) guarantees at most one {@code run()} of
     * it is ever active at a time — so no matter how many CPU cores the backing executor
     * has, real generation work for one dimension processes ONE (holder,status) task at
     * a time, taking turns across different worker threads over time but never running
     * concurrently. Confirmed by reading {@code ProcessorMailbox.pollUntil}/{@code
     * registerForExecution} directly, not inferred — this is genuine, long-standing
     * vanilla Mojang behaviour, not something NestworldCore introduced (PaperMC's
     * "multithreaded chunk generation" feature exists to work around the same limit).
     *
     * <p>With this &gt; 1, {@code ChunkMap} registers N independent raw {@code
     * ProcessorMailbox} instances with the same {@code ChunkTaskPriorityQueueSorter}
     * (each gets its own {@code ChunkTaskPriorityQueue} — verified by reading the
     * sorter's {@code getQueue} map, keyed by {@code ProcessorHandle} identity, so this
     * is a supported usage shape, not an abuse of internals) and routes each NEW
     * top-level {@code scheduleChunkGeneration} call to shard {@code
     * chunkPos.hashCode() % N} — a chunk's own sequence of statuses always lands on the
     * SAME shard (no same-chunk status-B-before-status-A race), only DIFFERENT chunks
     * can now generate truly concurrently, up to N at once.
     *
     * <p><b>Trade-off:</b> the strict GLOBAL priority ordering (today: the single
     * nearest-to-player chunk across the WHOLE dimension always loads first) becomes
     * only approximate — priority is preserved within a shard, not across shards. This
     * does not affect correctness (the {@code getChunkRangeFuture} neighbour-dependency
     * walk still fully resolves before a task is ever submitted, regardless of which
     * shard it lands on — sharding cannot start a task before its dependencies are
     * ready), only worst-case latency variance for an individual chunk whose neighbours
     * happen to land on a busy shard.
     *
     * <p>Orthogonal to {@link #CHUNK_GEN_MAX_CONCURRENT}: that cap still bounds how many
     * gateable-status generations run at once ACROSS ALL SHARDS COMBINED (its counter is
     * a single {@code synchronized} field shared by every shard's callback) — sharding
     * only provides the actual parallel execution capacity within that cap. Raise both
     * together; a shard count with no matching concurrency-cap increase just adds queues
     * without adding real throughput.
     *
     * <p><b>{@code ChunkStatus.FEATURES} used to be forced onto shard 0 (fully
     * serialized) — no longer.</b> That was a workaround for a CONFIRMED AB-BA deadlock in
     * {@code BulkSectionAccess} (used not just by vanilla {@code OreFeature} but, verified
     * by reading real decompiled bytecode from a live 188-mod pack, by GregTech's {@code
     * OrePlacer}, Mekanism's {@code ResizableOreFeature}, and Lost Cities' {@code
     * ChunkDriver} too): it used to acquire touched {@code LevelChunkSection}s lazily in
     * visit order and hold them all until done, so two concurrent placements reaching into
     * each other's territory could deadlock. Spatial routing alone (this shift-based
     * grouping) was tried first and did NOT prevent it (only reduced how often two such
     * chunks landed on different shards). {@code BulkSectionAccess} now uses a deadlock-
     * free release-and-reacquire-in-total-order algorithm internally (see its source) —
     * the hazard is fixed at its root, so FEATURES is spatially routed like every other
     * gateable status again, and {@link #CHUNK_GEN_FEATURES_MAX_CONCURRENT} is a normal
     * throughput knob rather than a hard safety cap.
     *
     * <p>{@code 1} = disabled (vanilla-identical, default). Set e.g. {@code
     * -Dnestworld.worldgenShards=4} to start with a conservative shard count before
     * trying your full core count — start low, watch for anything in {@code
     * net.minecraft.world.level.levelgen.*} that assumes single-threaded worldgen access
     * (structure-template caches, any non-concurrent global lookup) before going higher.
     */
    public static final int WORLDGEN_SHARDS =
            Math.max(1, Integer.getInteger("nestworld.worldgenShards", 1));

    /**
     * Chunk-coordinate right-shift used to group chunks into square blocks for {@link
     * #WORLDGEN_SHARDS} routing — block size is {@code 2^shift} chunks per side (default
     * 5 = 32x32 chunks, one vanilla region-file's worth). ALL chunks in the same block
     * route to the same worldgen shard, so cross-shard contention on vanilla's own
     * {@code PalettedContainer} section semaphore (see {@code
     * ChunkMap.scheduleChunkGeneration}'s routing comment — CARVERS-status
     * {@code BulkSectionAccess} can touch a neighbour chunk's section) is confined to
     * chunks near a block edge, not scattered across the whole dimension. This is a risk
     * REDUCTION, not a proof: chunks straddling a block boundary can still land on
     * different shards. Only lower this if you have measured the actual max cross-chunk
     * reach of every gateable status's generation task in your modpack and confirmed it's
     * smaller than the resulting block size; raising it (larger blocks) is always safer,
     * at the cost of coarser-grained parallelism.
     */
    public static final int WORLDGEN_SHARD_BLOCK_SHIFT =
            Integer.getInteger("nestworld.worldgenShardBlockShift", 5);

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
     * Resilient feature placement (default ON). Vanilla's {@code ChunkGenerator.applyBiomeDecoration}
     * deliberately re-throws any exception from an individual feature/structure placement as a fatal
     * {@code ReportedException}, crashing the ENTIRE server over ONE broken feature — confirmed live
     * 2026-08-12 on ATM9: a third-party mod ({@code ars_elemental}) shipped a tree feature that tries
     * to set a leaf-decay {@code distance} blockstate property on its own log block, which doesn't
     * define that property. That is a mod/datapack content bug, not a NestWorld or vanilla core issue
     * — but vanilla's fail-fast design means any such bug, anywhere in the loaded modpack, is a
     * standing whole-server crash risk that only surfaces when worldgen happens to walk into it.
     *
     * <p>With this on, the offending feature/structure placement is caught, logged (once per
     * feature key in full, then counted — see {@code /nestworld featurefails}), and skipped: that one
     * feature doesn't get placed at that chunk, and generation continues normally for everything else
     * (bit-identical except for the omission of the broken feature's output). Set {@code
     * -Dnestworld.resilientFeaturePlacement=false} to restore vanilla's crash-on-error behaviour,
     * e.g. to let a broken feature surface loudly during modpack development rather than silently
     * degrading terrain.
     */
    public static final boolean RESILIENT_FEATURE_PLACEMENT =
            Boolean.parseBoolean(System.getProperty("nestworld.resilientFeaturePlacement", "true"));

    /**
     * STRICT vs DIAGNOSTIC mode for {@link NestworldTickOwnership} (default DIAGNOSTIC =
     * false). Found live 2026-08-12: the ownership assertion had a blind spot -- it
     * auto-exempted any non-free-running region thread from the check entirely, based on
     * an assumption ({@code RegionThreadPool.runWorkRound} always fully joins before the
     * next phase) that P2 barrier audit (docs/P2_AUDIT_RESULTS.md, Q6) independently
     * disproved (no cancellation on awaitLatch()'s 30s timeout, no busy-check on
     * re-dispatch). That blind spot is why a real LevelTicks race surfaced as a raw
     * {@code NullPointerException} in fastutil internals instead of a clear, immediately-
     * located ownership violation. The exemption is removed; every call now goes through
     * the same check.
     *
     * <p>DIAGNOSTIC (default): a violation is logged with full NestWorld context (see
     * {@code NestworldTickOwnership.Violation}) and counted, but does NOT throw -- safe
     * for production, since throwing on every violation risks turning a rare race into a
     * guaranteed crash instead of the graceful degradation vanilla's own map would
     * otherwise usually survive. STRICT (set {@code -Dnestworld.levelTicksOwnershipStrict=true}):
     * throws immediately on the first violation -- for validation soak tests specifically
     * targeting this race (acceptance criterion: violations must reach exactly 0 under
     * sustained heavy multi-region load before this is considered closed).
     */
    public static final boolean LEVELTICKS_OWNERSHIP_STRICT =
            Boolean.parseBoolean(System.getProperty("nestworld.levelTicksOwnershipStrict", "false"));

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

    /**
     * Spike log threshold, in ms: {@link NestworldRegionSystem} already measures a full
     * per-phase breakdown (vanilla/signals/entityXfer/regionPool/ghostZones/splitMerge) of
     * every single tick's wall time -- this just adds a cheap comparison against that
     * already-computed number, and only when it's crossed does any logging/allocation
     * happen. The continuous all-threads spark profile ({@link #AUTO_SPARK}) already
     * captures every spike's actual sampled stack data from boot onward; this doesn't
     * replace that -- it gives an INDEX of exactly when + which phase dominated, appended to
     * {@code spark-spikes.txt} next to the server (same append-only pattern as
     * {@code spark-history.txt}), so a multi-hour flamegraph doesn't need to be eyeballed
     * blind to find the moment that matters. Default 50ms — half the 20 TPS tick budget,
     * i.e. "this tick alone burned at least half the budget." Disable with
     * {@code -Dnestworld.spikeLogThresholdMs=0} (a <=0 threshold disables the check
     * entirely, never fires).
     */
    public static final long SPIKE_LOG_THRESHOLD_NANOS =
            Integer.getInteger("nestworld.spikeLogThresholdMs", 50) * 1_000_000L;

    /**
     * Cap on how long {@code /forceload add} blocks the main thread waiting for the
     * requested chunks to finish generating, in milliseconds. Vanilla's own fix (already
     * in this core, not gated by a flag) batches the N requested chunks into one combined
     * wait instead of N sequential ones — bounded by the SLOWEST single chunk among them,
     * not their sum — but that wait itself has NO timeout: on real heavy modpacks a single
     * chunk of genuinely virgin territory (deep structure search, expensive per-chunk
     * feature cost) can itself take well past a minute, and vanilla's {@code
     * ServerHangWatchdog} force-kills the whole JVM at 60s with no graceful shutdown.
     * Reproduced live (2026-08-07): a 49-chunk {@code /forceload add} at fresh coordinates
     * crashed a real 188-mod server this way, despite {@link #CHUNK_GEN_ADMIT_BUDGET} /
     * {@link #CHUNK_GEN_MAX_CONCURRENT} already active — admission/concurrency throttling
     * bounds ongoing gameplay load, it does not bound how long ONE command is allowed to
     * block waiting on work it already kicked off.
     *
     * <p>The registration side effect ({@code addForcedChunkAsync}'s ticket-adding, i.e.
     * which chunks are marked "forced") already happens synchronously, unconditionally,
     * BEFORE this wait even starts — timing out here does not undo or delay that. Chunks
     * keep generating on the normal worker pool after the command returns; timing out just
     * means the command's own response comes back before every last one is 100% done,
     * instead of blocking indefinitely (and risking the whole server) to guarantee it.
     *
     * <p>{@code 0} = no timeout, vanilla-identical blocking behaviour (NOT recommended on
     * a heavy real pack). Default 20s — a large margin under the 60s watchdog even
     * accounting for scheduling jitter, per Gemini design review 2026-08-07.
     */
    public static final long FORCELOAD_WAIT_TIMEOUT_MS =
            Long.getLong("nestworld.forceloadWaitTimeoutMs", 20_000L);

    /**
     * Layer 12 (2026-08-11): {@link #FORCELOAD_WAIT_TIMEOUT_MS} bounds a SINGLE {@code
     * /forceload add} call's own wait, but does nothing to bound several such calls issued in
     * quick succession — each independently gets its own full 20s allowance, so N calls back
     * to back can still add up to N*20s on the SAME main thread and blow through the 60s
     * {@code ServerHangWatchdog} threshold. This is the exact same bug CLASS as the
     * {@code getChunk()} first-call burst-budget gap (see {@link #GETCHUNK_BURST_BUDGET_MS}),
     * just in this separate command path — found live (2026-08-11) when a 4-call, 1024-chunk
     * {@code /forceload add} batch (32x32, tiled into 256-chunk blocks by vanilla's per-command
     * cap) crashed a real ATM9 server via a genuine Watchdog kill, stack rooted at {@code
     * ForceLoadCommand}'s own {@code managedBlock} wait.
     *
     * <p>Unlike {@code getChunk()}'s per-TICK budget (a hot path, naturally scoped to one game
     * tick), {@code /forceload} is an infrequent, administrative command whose OWN wait can
     * itself span many ticks — a per-tick reset does not naturally bound consecutive calls the
     * way it does for getChunk(). Instead this is a rolling WALL-CLOCK window: a shared budget
     * of {@link #FORCELOAD_BURST_BUDGET_MS} that resets every {@link
     * #FORCELOAD_BURST_WINDOW_MS}, consumed by every {@code /forceload add} call's actual wait
     * time (not just calls after the first) — mirroring the getChunk() fix's
     * {@code Math.min(perCallTimeout, remainingBudget)} pattern exactly.
     *
     * <p>{@code 0} = disabled, falls back to {@link #FORCELOAD_WAIT_TIMEOUT_MS}'s old per-call-
     * only behaviour (NOT recommended — this is precisely the gap that just crashed a real
     * server). Default 3s, matching {@link #GETCHUNK_BURST_BUDGET_MS}'s philosophy: generous
     * enough for one legitimate large forceload, small enough that a rapid-fire batch of them
     * degrades to fast proxy/early-return responses well before stacking up to 60s.
     */
    public static final long FORCELOAD_BURST_BUDGET_MS =
            Long.getLong("nestworld.forceloadBurstBudgetMs", 3_000L);

    /**
     * The rolling wall-clock window over which {@link #FORCELOAD_BURST_BUDGET_MS} is spent and
     * replenished. Default 5s — long enough to catch a tight burst of several {@code
     * /forceload add} calls issued back-to-back (the crashing scenario: 4 calls whose own
     * combined wait time spanned ~63s), short enough that a lone admin issuing occasional
     * forceload commands minutes apart never sees any throttling at all.
     */
    public static final long FORCELOAD_BURST_WINDOW_MS =
            Long.getLong("nestworld.forceloadBurstWindowMs", 5_000L);

    /**
     * Sibling of {@link #FORCELOAD_WAIT_TIMEOUT_MS} for the harder, original crash: a mod
     * calling {@code Level.getChunk(x, z)} directly (e.g. FTBChunks' map-click teleport) for
     * genuinely virgin territory on a heavy real modpack, blocking the main thread on
     * {@code ServerChunkCache}'s {@code managedBlock} wait past the 60s {@code
     * ServerHangWatchdog} threshold — the ORIGINAL crash that motivated this whole project's
     * Phase-1 work (see {@code phase1-teleport-crash-reproduced} project notes).
     *
     * <p>Unlike {@code /forceload add} (whose command response can legitimately say
     * "registered, still generating"), {@code Level.getChunk(x, z)} is {@code @NotNull} and
     * synchronous — thousands of call sites throughout vanilla and every mod assume it always
     * returns a fully-ready chunk immediately. Design reviewed with Gemini (2026-08-07): a
     * timeout here returns a {@link net.minecraft.world.level.chunk.ProxyLevelChunk} instead
     * of blocking further — a real, type-correct {@code LevelChunk} that transparently starts
     * delegating to the actual chunk once generation finishes (the SAME object reference stays
     * valid for any caller still holding it), falling back to vanilla's own {@code
     * EmptyLevelChunk}-style safe-empty behaviour (VOID_AIR reads, no-op writes) until then.
     *
     * <p>This is a real, accepted behavioural tradeoff, not a free fix: a caller that
     * teleports a player into the returned proxy's position before it resolves will see void
     * air (and could take fall/void damage) for up to this many milliseconds, rather than the
     * whole server hanging for up to 60s. Chosen deliberately over the status quo (a full JVM
     * watchdog kill affecting every player) — see the design discussion in memory for the
     * full tradeoff analysis.
     *
     * <p>{@code 0} = no timeout, vanilla-identical blocking behaviour (NOT recommended on a
     * heavy real pack — this is the exact call path from the original live crash). Default
     * 20s, matching {@link #FORCELOAD_WAIT_TIMEOUT_MS}'s margin under the 60s watchdog.
     */
    public static final long GETCHUNK_WAIT_TIMEOUT_MS =
            Long.getLong("nestworld.getChunkWaitTimeoutMs", 20_000L);

    /**
     * NestWorld: caps the TOTAL time the main thread will spend blocking inside
     * {@code ServerChunkCache.getChunk()}'s FULL+load=true proxy path across ONE server
     * tick, on top of {@link #GETCHUNK_WAIT_TIMEOUT_MS}'s existing per-CALL bound.
     *
     * <p>Found necessary live, 2026-08-09: Draconic Evolution's reactor-explosion raycast
     * (BrandonsCore's {@code ProcessHandler.onServerTick()} -> {@code ExplosionHelper
     * .removeBlock()} -> {@code Level.getChunk()}) calls {@code getChunk()} synchronously,
     * on the MAIN thread, once per traced/removed block position, all within a single
     * {@code updateProcess()} invocation — i.e. a tight loop of many such calls in one
     * tick. GETCHUNK_WAIT_TIMEOUT_MS correctly bounds any ONE of those calls to 20s, but
     * does nothing to bound their SUM: a real crash-report (twice, same day) caught the
     * main thread stuck at exactly this call chain when the 60s ServerHangWatchdog fired —
     * a handful of genuinely-cold chunks in the same burst, each legitimately taking a
     * large fraction of the per-call budget, adds up past 60s even though no single call
     * ever violated its own bound. This is the main-thread analogue of the RegionThread
     * cumulative-wait gap (see {@code regionthread-getchunk-blocking-crash} project notes)
     * — bounding one call was necessary but not sufficient.
     *
     * <p>Once this tick's budget is exhausted, FURTHER FULL+load=true calls THIS TICK skip
     * the wait entirely and return a proxy immediately (same {@code NestworldProxyLevelChunk}
     * object used for a single-call timeout) rather than attempting even a short wait —
     * cheap and correct, since by definition the tick is already over-budget. The budget
     * refills at the start of the next tick. Every OTHER call shape (non-FULL, load=false,
     * internal worldgen dependency resolution) is completely untouched, matching
     * GETCHUNK_WAIT_TIMEOUT_MS's existing scoping.
     *
     * <p>{@code 0} = disabled (only the per-call bound applies, vanilla-identical to before
     * this flag existed). Default 3000ms: generous for ordinary gameplay (a handful of
     * fresh-chunk loads from normal exploration resolve in low tens of ms total, nowhere
     * near this budget) while keeping any single tick's worst case far under the 60s
     * watchdog even stacked with other main-thread work measured the same day (up to
     * ~26ms/tick from the vanilla sub-phase and chunkSource breakdowns).
     */
    public static final long GETCHUNK_BURST_BUDGET_MS =
            Long.getLong("nestworld.getChunkBurstBudgetMs", 3_000L);

    /**
     * Layer 10 (docs/GEN_SPIKE.md): the hard invariant — project owner's explicit
     * direction, 2026-08-11, after the burst-budget-first-call fix above still left a
     * BOUNDED (not zero) main-thread wait: "MAIN THREAD НІКОЛИ НЕ МОЖЕ БЛОКУВАТИСЯ НА
     * WORLDGEN... 0 секунд очікування worldgen." Default {@code true}: the gameplay-
     * thread FULL+load=true {@code getChunk()} path never attempts the bounded wait at
     * all — it degrades straight to the existing {@code NestworldProxyLevelChunk}
     * (VOID_AIR-until-resolved) the instant a chunk isn't already done, reusing 100% of
     * the machinery {@link #GETCHUNK_BURST_BUDGET_MS}/{@link #GETCHUNK_WAIT_TIMEOUT_MS}
     * already built — this only changes WHEN it's taken, not what it does.
     *
     * <p>Deliberately NOT a replacement for the two flags above — set this to
     * {@code false} to fall back to their bounded-tolerance behavior (e.g. for an
     * operator who has measured that a few seconds of real generation completing
     * synchronously is worth it for their specific mod set) — same "don't remove the
     * old path, gate the new one" discipline as every other toggle in this class.
     *
     * <p>What this does NOT solve (explicitly out of scope, matching this project's
     * existing caution about the critical loading path): a caller still gets a
     * SEMANTICALLY DIFFERENT result (void air, not the eventual real terrain) the
     * instant this path is taken — for arbitrary third-party mod code (not something
     * this project can rewrite), there is no way to make blocking impossible AND
     * preserve full semantic correctness at the same time without a bytecode-level
     * continuation/suspend-resume transform, which is a fundamentally different (and
     * far larger) undertaking than this flag. For operations THIS project owns
     * (Explosion, BLOCK_WRITE cascades), the real fix already exists as the
     * check-batch-defer-replay pattern via {@code NestworldGenPool}/the region
     * admission budget — this flag is specifically about the OUTER opaque-caller case.
     */
    public static final boolean GETCHUNK_ZERO_WAIT_MAIN_THREAD =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.getChunkZeroWaitMainThread", "true"));

    private NestworldTuning() {
    }
}
