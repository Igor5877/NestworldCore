# Core Thread-Safety Passes — Roadmap

## Why this exists

NestworldCore ticks entities and blocks from **many region threads in parallel**. Vanilla
Minecraft was written for a **single Server thread**, so it stores game state in plain,
single-thread data structures (`Int2ObjectOpenHashMap`, `ShortOpenHashSet`,
`LongLinkedOpenHashSet`, `DynamicGraphMinFixedPoint` queues, lazy mutable caches). When two
region threads — or a region thread and the main thread — touch the same vanilla structure at
once, it corrupts (typically `ArrayIndexOutOfBoundsException: Index -1` in a fastutil `rehash`,
or an NPE mid-iteration) and crashes the tick.

**Strategic goal:** making these structures genuinely thread-safe in the core is what makes the
parallelism robust **mod-agnostically**. We do NOT police mods by which classes they touch
(that breaks legitimate mods). We harden the core; then any mod — or vanilla code — that runs on
a region thread is safe. The only mods we special-case (by name) are the Lithium family
(Canary/lithium/radium), because they *replace* our thread-safe structures with single-thread
ones — see `NestworldCompat`. Genuinely-incompatible mods are *pinned to main* (`NestworldPins`),
not blocked.

Every extreme stress test so far has surfaced a new race of this class (POI → nav → entity-tracker
→ ChunkHolder → ...). Assume there are more until an audit proves otherwise.

## Fix patterns

- **A. Lock the structure** — one `ReentrantLock` guarding all access. Use when region threads
  both read and write it and deferral is awkward. *(POI: SectionStorage.nestworldLock.)*
- **B. Defer mutation to main** — region threads queue the mutation; main drains it at the region
  barrier (threads idle). Use when main *also* iterates the structure each tick. *(entity-tracker:
  deferredRemovals/deferredSpawns.)* Cleanest — zero contention, consistent.
- **C. Concurrent replacement** — swap for a thread-safe structure. *(ConcurrentEntitySectionStorage
  with a ReentrantReadWriteLock.)*
- **D. ThreadLocal / per-instance scratch** — for shared scratch state or re-entrancy guards.
  *(explosion block cache is per-Explosion-instance; nav `isUpdatingNavigations` should be this.)*
- **E. Pin to main** — for state that genuinely cannot parallelize: run the owning entity/logic on
  the main thread via `NestworldPins`.

## Inventory

### ✅ Fixed
| # | Structure | Race | Fix | Build |
|---|---|---|---|---|
| 1 | `PoiManager.DistanceTracker` + `SectionStorage` storage/dirty | mob POI queries vs main POI tick | A (lock) + disk-outside-lock | 47.4.102/103 |
| 2 | `ChunkMap.entityMap` (Int2ObjectOpenHashMap) | mass entity removal (TNT) vs main ChunkMap.tick | B (defer removals to main) | 47.4.104 |
| 3 | `ChunkHolder.changedBlocksPerSection` (ShortOpenHashSet) | mass block changes (explosions) vs main `broadcastChanges` | A (per-holder lock, snapshot-and-clear; packets off-lock) | 47.4.107 |
| 5 | `ServerLevel.sendBlockUpdated` nav guard (`isUpdatingNavigations`) | shared boolean false-positives across region threads (log spam) | D (ThreadLocal guard; navigatingMobs was already concurrent) | 47.4.109 |
| 6 | `ChunkMap.entityMap` via PESM visibility transition | entity walks/teleports into an entity-ticking section during a region tick → `startTracking` → `ChunkMap.addEntity` off-main ("Entity is already tracked!", enderman, 200-bot test 2026-07-03) | B (defer tracking ADDS to main, symmetric with removals; drained after removals so leave+re-enter lands tracked) | 47.4.141 |
| 7 | `RandomSequences.sequences` (`Object2ObjectOpenHashMap`) | ANY loot roll (entity death or block break) on ANY region thread calls `get()` → `computeIfAbsent` off-main; two region threads racing the first request for a sequence corrupt the map during rehash (AIOOBE "Index -1 out of bounds for length 33" — found 2026-07-08 on a real 188-mod pack overnight soak: enderman death loot + villager block-break loot, 4 hits/8h) | C (concurrent replacement — `ConcurrentHashMap`, atomic `computeIfAbsent`) | 47.4.145 |
| 8 | `ProtoChunk.heightmaps` (EnumMap+`Heightmap`) / `blockEntities`+`pendingBlockEntities` (HashMap) | `WorldGenRegion.setBlock()` can resolve into a NEIGHBOUR chunk while that neighbour concurrently decorates itself (`chunkGenFeaturesMaxConcurrent`); the block-state write itself was already lock-protected (PalettedContainer semaphore) but the heightmap update + block-entity registration that follows it were not | A (lock — `synchronized` on the shared map/field itself, same pattern as postProcessing) | 47.4.176→178, commit `353c845c3` |
| 9 | `PoiSection.byType` (HashMap) via `PoiManager.getInChunk`'s lazy stream | `getRecords()` returned a lazy `Stream` straight over the section's HashMap with no lock held past the fetch; actual iteration (whenever the caller finally consumed it) raced unprotected against a concurrent `add()`/`remove()` (which hold the write lock for their full mutate, fix #1-adjacent from the RWLock upgrade) | A (eagerly materialise under the read lock instead of returning the raw lazy stream — **pitfall caught while fixing**: never hold `readLock()` across a call that might itself need `writeLock()` on a cache-miss/lazy-init path, e.g. `SectionStorage.getOrLoad`'s slow path — resolve those calls first, lock only around the pure-read extraction) | 47.4.176→178, commit `d7db5af05` |
| 11 | `MapItemSavedData` (`carriedBy`/`carriedByPlayers`/`bannerMarkers`/`decorations`/`frameMarkers`) | ONE shared instance per map ID, reachable from any region — two item frames in different regions displaying the same map, destroyed concurrently (combat/explosion) via `ItemFrame.removeFramedMap` → `removedFromFrame`, mutate the same unsynchronized HashMaps from two region threads at once | A (`synchronized` on every touching method, reentrant-safe for nested same-thread calls) + defensive copies for `getBanners`/`getDecorations` (previously returned the live `HashMap.values()` view — same escape shape as #9) | 47.4.181, commit `dc03ab347` |

Also shipped: **explosion-ray cache** (`NestworldExplosionCache`, 47.4.105) — per-explosion block/fluid
memoisation, bit-identical; all explosion block lookups route through it.

### ✅ Investigated — SAFE (not a race)
| # | Structure | Finding |
|---|---|---|
| 4 | **Light engine** (`DynamicGraphMinFixedPoint` queue) | NOT racy. `ThreadedLevelLightEngine` isolates the graph behind a `ProcessorMailbox`: region threads only `addTask`/`tell` (thread-safe enqueue); the graph mutation (`runUpdate`/`super.checkBlock`) runs serially on the single light thread (`tryScheduleUpdate` only `mailbox.tell` + `scheduled.compareAndSet`). Light *reads* (getRawBrightness) are atomic byte-array reads — at worst stale, never corrupt. **Roadmap prediction was wrong** — light differs from POI precisely because it's mailbox-deferred, not directly accessed. |
| 10 | **Scheduled ticks** (`LevelTicks` — block/fluid) | NOT racy, same isolation class as #4 but via a different mechanism: sequential-phase non-overlap, not a mailbox. `NestworldRegionSystem.runScheduledTicksPhase` calls `LevelTicks.tick()` (the unsynchronized main-thread drain — collects due ticks, `sortContainersToTick`/`drainContainers`) to completion FIRST, entirely on the main thread; only AFTER it returns are the collected callbacks bucketed and handed to `pool.runWorkRound(buckets)`, which the main thread then blocks on (barrier) until all region threads finish. Region threads never touch `LevelTicks`' unsynchronized internals concurrently with the main-thread drain — the two phases never overlap in time. The `addContainer`/`removeContainer`/`schedule`/`hasScheduledTick`/`willTickThisTick` methods ARE `synchronized` (pre-existing patch) for the case where a region-thread tick callback reschedules another tick (`schedule()`) — safe against other region threads doing the same, and main is parked in the barrier by then anyway. |

### Refined heuristic (from #4)
A structure is crash-class ONLY if region threads touch it **directly**. If it sits behind a
`ProcessorMailbox` / task queue / is main-only / is already deferred, it is safe. Re-scan the
suspected list through this lens before assuming a fix is needed — many may be mailbox-isolated.

### ✅ Fixed (continued)
| # | Structure | Race | Fix | Build |
|---|---|---|---|---|
| 12 | `ChunkAccess.structureStarts`/`structuresRefences`, `ProtoChunk.carvingMasks`/`entities` | same crash-class as #8 — `WorldGenRegion` resolves a write into a NEIGHBOUR chunk during concurrent feature/structure generation (`chunkGenFeaturesMaxConcurrent` > 1); these four siblings of #8's fields were left unprotected (`getOrCreateCarvingMask()`'s `computeIfAbsent` was the same unsafe "check-then-create" shape the class's own `postProcessing` comment already warned about) | A (`synchronized` on the shared field, identical pattern to #8) + defensive-copy on `getAllStarts()`/`getAllReferences()`/`ProtoChunk.getEntities()` (same lesson as #9/#11 — never return a live view across the lock boundary) | 2026-08-10, this session |
| 13 | `FlowingFluid.shapes` (`IdentityHashMap`, one instance server-wide per fluid type) | fluid ticking on ANY region thread calls `getShape()` → unguarded `computeIfAbsent()` | A (`synchronized` on the shared field) | 2026-08-10, this session |
| 14 | `DimensionDataStorage.cache` (`HashMap`, one per dimension) | `get()`'s check-miss-load-put is a genuine TOCTOU — two region threads racing the first request for the same `SavedData` key (map, raid, scoreboard) can both miss, both read from disk, one `put()` clobbers the other or corrupts the map mid-rehash; `set()`/`save()` touch the same unprotected map | A (`synchronized` on the shared field across `get()`/`set()`/`save()` — the whole check-then-create sequence, not just individual map ops) | 2026-08-10, this session |

Found during the exhaustive Local Tick / Stage 4 architecture review (see
`docs/LOCAL_TICK_STAGE4.md`), independent of that decision. Validated on gen-spike-repro:
256 fresh chunks force-generated with `chunkGenFeaturesMaxConcurrent=8` (structures +
carving active), zero exceptions, 20 TPS throughout; deployed to ATM9, clean boot, no new
crash-reports. Live reproduction of the EXACT race (two neighbour chunks' FEATURES
generation landing in the same tick window) was not directly forced — same
difficulty class as other rare cross-thread timing races this session — confidence rests
on the proven #8/#9/#11 fix pattern plus a clean real-generation stress run, not a live
crash-before/no-crash-after A/B.

All other confirmed crash-class races are fixed; #4 and #10 were investigated and
found safe. Cross-chunk worldgen writes (#8, #9) were a separate discovery this pass — found via
a differential correctness test (same seed, forced-serial vs default-concurrent generation), not
a live crash — see [[concurrent-features-nondeterminism]].
Residual (deeper, non-crash, deferred): cross-region `recomputePath` in sendBlockUpdated can repath
a mob owned by another region — caught per-entity, transient path glitch at worst; revisit only if
a real nav crash ever appears.

### 🔍 Suspected — audit pass complete (2026-08-07)
Vanilla single-thread structures on the entity/block tick path. Audit each: does a region thread
mutate it while another thread reads/mutates? **Full list walked this pass** — 1 real bug found
and fixed (MapItemSavedData, #11), 8 items confirmed safe by tracing actual call sites (not
assumed) — including Scoreboard/team, closed after finding the command-execution-timing
guarantee (`waitUntilNextTick`) that was missing earlier in this same pass. Only 1 item left
genuinely open: the Forge-capabilities ghost-zone path (empirically tested once, clean, but not
exhaustive proof — see its entry above for what would raise confidence further).
- ~~**Scheduled ticks** — `LevelTicks` (block/fluid).~~ — **AUDITED, SAFE, see #10 above.**
- ~~**Block-entity tick list** / `LevelChunk` tick lists.~~ — **AUDITED, SAFE.** Same collect-then-
  barrier-execute pattern as scheduled ticks: `NestworldRegionSystem.beginBlockEntityPhase` collects
  due tickers via the patched (still-serial, main-thread) `Level.tickBlockEntities`, and only AFTER
  that full collection completes does `runBlockEntityPhase` bucket-and-dispatch to region threads
  (`pool.runWorkRound`), which the main thread then blocks on. No window where a region thread and
  the main-thread collection touch the shared ticker list concurrently.
- ~~**Raids / village** (`Raids`, POI-adjacent).~~ — **AUDITED, MOSTLY SAFE**, one narrow
  unconfirmed edge left. `Raids.tick()` (which advances/removes entries from the plain HashMap
  `raidMap`) is called from `ServerLevel.tick()` — part of vanilla's global tick (step 1 of
  `tickAllRegions`, main-thread, before the region barrier), same safe timing as
  weather/scheduled-ticks/chunk-I/O. Raid triggering (Bad Omen in a village) is player-tick-
  driven, also main-thread-only. The one narrow, NOT fully confirmed edge: `Raider.java`'s
  `readAdditionalSaveData` (entity NBT deserialization, happens during chunk LOADING — a
  different subsystem/thread pool than region entity-ticking) does `getRaids().get(raidId)`, a
  plain `HashMap.get()` that could theoretically race the main thread's `raids.tick()` mutating
  the same map concurrently with a chunk-load worker thread. Low severity if real (a stale/null
  read at worst, not obviously crash-capable the way the fixed bugs were) and narrow (raid-entity
  deserialization specifically, not general gameplay) — not chased further given the marginal
  value; revisit only if a raid-loading-related exception is ever actually observed.
- ~~**MapItemSavedData** (entity tracking on maps).~~ — **FIXED, see #11 above.**
- ~~**Scoreboard** (`Scoreboard` score/team updates).~~ — **AUDITED, SAFE (resolved
  2026-08-07, corrects the earlier "not closed" note in this same pass).** Kill-count scoring
  ruled out early (`Entity.awardKillScore`'s base implementation only fires an advancement
  criteria trigger, doesn't touch `Scoreboard` directly). The remaining concern —
  `Entity.getTeam()`/`getPlayersTeam()` (read, called from region-threaded combat/friendly-fire
  checks) racing `/team join`/`/team leave` command execution — is closed by tracing the actual
  command-dispatch timing: `RconClient`/chat commands reach `MinecraftServer` via
  `executeBlocking`, which queues onto the server's own task list; that queue is drained by
  `runAllTasks()` inside `waitUntilNextTick()` (`MinecraftServer.java`), called from the main
  server loop at line 665 STRICTLY AFTER `tickServer()` (line 661, which contains the entire
  `tickChildren` → `tickAllRegions` → region-barrier sequence) has FULLY returned, and before the
  next `tickServer()` call begins. Every command — RCON, console, or player-issued — executes in
  the gap BETWEEN complete tick invocations, never overlapping ANY region thread's active work.
  Stronger guarantee than the tick-phase pattern (not just "after the barrier," but "between
  entire tick invocations") — closes this cleanly, no fix needed.
- ~~**Boss events** (`ServerBossEvent` player sets).~~ — **AUDITED, SAFE.** `ServerBossEvent`
  (`players` HashSet) is per-ENTITY (each `WitherBoss`/`EnderDragon` owns its own instance), not
  a world-shared singleton like `MapItemSavedData` was — so the risk shape is different: does the
  SAME entity's bossEvent ever get touched by two threads at once? `startSeenByPlayer`/
  `stopSeenByPlayer` (which call `bossEvent.addPlayer`/`removePlayer`) are invoked from
  `ServerEntity`, itself driven by `ChunkMap`'s tracker-broadcast phase — traced both the
  `regionalizedTracker` and default code paths in `ChunkMap.java` (~line 1660-1757):
  `TRACKER_PARALLEL_BROADCAST`'s parallel split is always by TrackedEntity (one thread per
  entity's `sendChanges()`), never splitting a single entity's own broadcast work across threads,
  and the whole phase is documented as running with "region threads parked at the barrier" — same
  safe timing as the other tick-phase items. No two threads can ever touch one entity's bossEvent
  concurrently.
- **Forge capabilities** attach/invalidate on entities/chunks during region tick. **Refined
  (2026-08-07) — split into two paths with DIFFERENT confidence levels**, after confirming a
  concrete real-world trigger: hoppers pulling across a region border go through the exact same
  `Level.getBlockEntity()` → `RegionChunkView` path (`HopperBlockEntity.getContainerAt` at line
  351 calls it directly) — not a diffuse theoretical concern, a common, everyday interaction.
  - **"Deep" cross-region path (`owner.getChunkLock().tryReadLock`) — CONFIRMED SAFE.** Verified
    `RegionThread.java:219-245`: the owning region's `writeLock()` is held for its ENTIRE tick
    (acquired before `tickEntities()`/`runWorkBudgeted`, released only in the `finally` after both
    finish) — not a short critical section. Combined with the barrier's lockstep semantics (the
    owner cannot start a NEW tick — re-acquire that write lock — until every region, including
    whichever thread is doing this foreign read, reaches the barrier together, which cannot happen
    while the reading region is still mid-tick), a foreign thread that successfully acquires the
    brief read lock is guaranteed the owner's current tick is fully finished AND cannot resume
    until the reader's own tick also finishes. Any `.getCapability()` call made after the fetch
    returns is safe from concurrent owner mutation for this path.
  - **Ghost-zone path (`BoundaryManager.getGhostChunk`, within `GHOST_DEPTH`=2 of a border) —
    STILL OPEN, likely the more common case in practice.** This path takes NO lock at all (by
    design, to avoid contention for the common near-border case) and returns the SAME live
    `BlockEntity` object the owning region may be actively mutating THIS SAME TICK (both regions
    genuinely run in parallel during step 4, not sequentially). Simple BlockState staleness here
    is an accepted, documented tradeoff, but capability/container MUTATION is a different risk
    category: e.g. two hoppers (one in-region, one ghost-zone-cross-region) both calling
    `insertItem`/`extractItem` — or a container's `setItem`/`removeItem` — on the SAME chest's
    internal `NonNullList` concurrently is a genuine unsynchronized structural mutation, not just
    a stale read. **This is now the concrete, higher-priority half of the open question** — most
    cross-region hopper/pipe interactions are near-border (ghost-zone depth), not "deep" reads.
    **Empirical test run (2026-08-07, gen-spike-repro)**: chest A (1728 diamonds) → hopper →
    chest B, chest A/hopper on one side of the confirmed region border, chest B one block across
    it (well within `GHOST_DEPTH`=2), RCON-scripted via `/setblock`+`/item replace block` (no
    client needed). ~40 zombies spawned on both sides throughout to force genuine concurrent
    region-thread activity (both regions actively ticking 45-75 entities each, not idle). Ran
    ~9 minutes continuous transfer, checked item totals twice (mid-run and final) by summing NBT
    `Items[].Count` across all three containers: **1728/1728 both times, zero loss, zero
    duplication, zero exceptions in the server log.** Reassuring evidence — NOT exhaustive proof
    (single test, ~9 min, one hopper pair, unmodded vanilla container code path only; a genuinely
    rare race could still exist and simply not have been hit). If this needs stronger confidence
    later: longer duration, multiple hopper pairs at once, and/or a modded `IItemHandler`
    capability (not just vanilla `Container`) would extend coverage.
- ~~**`PersistentEntitySectionManager`** visibility/section transitions~~ — **FIXED, see #6 above.**
  Residual (non-crash, exotic): the rest of `onTrackingStart` still runs on the region thread for
  a section-move transition — `navigatingMobs` (concurrent, #5) and the C1 counter (synchronized)
  are safe; `dragonParts` (multipart entities only) and `updateDynamicGameEventListener` (sculk)
  remain off-main there. Revisit only if a dragon/sculk crash ever appears.
- ~~**Chunk save/unload** racing region ticking the same chunk.~~ — **AUDITED, SAFE.**
  `NestworldRegionSystem.tickAllRegions()` calls `overworld.getChunkSource().chunkMap.tick()`
  (which contains `processUnloads`/`saveChunkIfNeeded`) at step 5c, explicitly AFTER step 4
  (`pool.tickAllRegions()`, the full parallel region tick + barrier) — the code comment there
  already states "Region threads are fully parked outside the step-4 window, so no race is
  possible". The periodic full `saveAllChunks` autosave (every 6000 ticks) is even further
  removed: called from `MinecraftServer.tickServer()` AFTER `tickChildren()` (which contains the
  whole region-tick-plus-barrier call) fully returns — no region thread is running at all by then.
- ~~**Random-tick** structures touched off-main.~~ — **AUDITED, SAFE.** Same pattern again:
  `ServerChunkCache`'s per-chunk `tickChunk` loop (main thread only) calls `queueRandomTicksFor`
  to collect into `randomTickBuckets`; `flushRandomTicksPhase()` — which hands the buckets to
  `pool.runWorkRound` — is called exactly once, AFTER that entire loop finishes, confirmed at the
  call site (`ServerChunkCache.java` around line 464). No overlap window.
- ~~**Weather**~~ — **AUDITED, SAFE.** `advanceWeatherCycle()` (rain/thunder state advancement,
  lightning-strike placement) is called from within `ServerLevel.tick()`, i.e. main-thread-only,
  part of vanilla's global tick (step 1 of `tickAllRegions`, before the region barrier) — same
  timing as raids/scheduled-ticks. The weather-state fields (`isRaining`/`isThundering`/etc.) are
  simple primitives read constantly from region threads (spawn conditions, fire behavior) — atomic
  at the primitive level in Java, so at worst a stale read, never a torn/corrupt one — same
  "mailbox-adjacent" safe category already accepted for the Light Engine (#4). Lightning strikes
  themselves spawn as normal entities (covered by the existing deferred-add fix, #6) and their
  gameplay effects run on their own region-threaded tick like any other entity — nothing new here.

### ⏸️ Deferred — perf, not crash
| Structure | Issue | Note |
|---|---|---|
| ~~Global POI lock~~ | ~~doesn't scale to N region threads under distributed load~~ | **DONE** — upgraded `ReentrantLock` → `ReentrantReadWriteLock` (commit `67f604566`); see #9 above and `poi-rwlock-and-fetch-mutate-race-fix` memory. |

## Methodology (how we find + fix)

1. **Spark every extreme test** (never guess) — `/spark profiler --thread *`, parse region tree.
2. **Stress the surface:** mob crowds, mass spawn/kill, TNT (entity + block), `/fill`, `/forceload`,
   `/clone`, redstone, mass block changes. Each new extreme tends to expose a new race.
3. **On a crash:** read the stack. Pattern = fastutil `rehash` AIOOBE "Index -1" or NPE mid-iteration
   in a vanilla structure, called from a `RegionThread.tickEntities` / off-main path concurrent with
   a main-thread iteration → it's this class.
4. **Pick the pattern** (A–E above) — prefer **B (defer to main)** when main iterates each tick
   (cheapest, consistent); **A (lock)** when both read+write on region threads.
5. **Gemini-review** the fix design (deadlock, staleness, bit-identical), then **measure before/after**
   on the same stress, confirm 0 crashes.

## Priority order

1. ~~**#3 ChunkHolder block-change**~~ — done.
2. ~~**#4 Light engine**~~ — audited, safe.
3. ~~**Audit pass** — walk the 🔍 list; fix any confirmed.~~ — **DONE, full list walked
   (2026-08-07).** Tick-phase-dispatched structures (#10 scheduled ticks, block-entity tickers,
   random ticks, chunk save/unload, weather) all safe by the collect-then-barrier pattern. Boss
   events safe (per-entity, never split across threads). Scoreboard/team safe (commands execute
   strictly between complete tick invocations, never overlapping region-thread work — traced
   `MinecraftServer.waitUntilNextTick`). Raids mostly safe (one narrow unconfirmed chunk-load-time
   edge, low severity). MapItemSavedData was a REAL bug — fixed (#11). Only Forge capabilities'
   ghost-zone path remains open (empirically tested once, clean, not exhaustive — see its entry
   above for what would raise confidence further).
4. ~~**#5 nav**~~ — done.
5. ~~**Deferred POI scaling**~~ — done (#9, RWLock + lazy-stream fix).

All of 1–5 above are now done or safe-confirmed. Also fixed this pass, discovered via differential
correctness testing rather than a live crash: #8 (cross-chunk worldgen heightmaps/blockEntities
race under `chunkGenFeaturesMaxConcurrent`).

**Second heuristic (from #10, block-entity tickers, random ticks — 2026-08-07):** beyond the
mailbox pattern (#4), NestworldCore's tick-phase dispatch has a SECOND systemic safety pattern
worth checking before assuming a fix is needed: **collect-then-barrier-execute**. If a structure is
only ever mutated by (a) the main thread during a single serial collection pass, and (b) region
threads acting on the ALREADY-COLLECTED result AFTER that pass fully returns, with the main thread
blocked on the region-thread barrier (`pool.runWorkRound`) before doing anything else with that
structure — there is no concurrent-access window, regardless of whether anything is `synchronized`.
This is the shape of `runScheduledTicksPhase`, `runBlockEntityPhase`/`beginBlockEntityPhase`, and
`flushRandomTicksPhase`/`queueRandomTicksFor`. Before auditing a NEW suspected structure, check
whether it's already routed through one of these three phase methods (or a similar collect→barrier
call site) — if so, it's very likely already safe by construction.

When all of 1–5 are done, the core is robust enough that arbitrary feature-mods touching these
classes from region threads are safe — which is the real compatibility story, far better than
blocking mods.
