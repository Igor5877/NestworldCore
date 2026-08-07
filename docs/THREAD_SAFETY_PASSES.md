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

### ❌ Open — crash-class
None known. All seven confirmed crash-class races are fixed; #4 and #10 were investigated and
found safe. Cross-chunk worldgen writes (#8, #9) were a separate discovery this pass — found via
a differential correctness test (same seed, forced-serial vs default-concurrent generation), not
a live crash — see [[concurrent-features-nondeterminism]].
Residual (deeper, non-crash, deferred): cross-region `recomputePath` in sendBlockUpdated can repath
a mob owned by another region — caught per-entity, transient path glitch at worst; revisit only if
a real nav crash ever appears.

### 🔍 Suspected — audit (not yet triggered)
Vanilla single-thread structures on the entity/block tick path. Audit each: does a region thread
mutate it while another thread reads/mutates?
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
- **Scoreboard** (`Scoreboard` score/team updates). **Traced, NOT closed** (2026-08-07) — the
  `playerScores`/`objectivesByName`/`teamsByPlayer` maps are ONE shared instance for the whole
  level, plain HashMaps. Kill-count/criteria scoring turned out NOT to be the region-thread risk
  it first looked like (`Entity.awardKillScore`'s base implementation only fires an advancement
  criteria trigger, doesn't touch `Scoreboard` directly — traced the actual call chain to rule
  this out rather than assume). The REAL confirmed region-thread-reachable touch point is
  `Entity.getTeam()`/`getPlayersTeam()` (a READ, called constantly from region-threaded combat/
  friendly-fire checks) racing against `/team join`/`/team leave` command execution
  (`ScoreboardCommand`/`TeamCommand`, main-thread but NOT gated by the region-tick barrier the
  way entity/block ticking is — commands run whenever queued, independent of tick phase). Whether
  this main-thread command execution can genuinely OVERLAP a region thread's concurrent read
  depends on exactly when/how `MinecraftServer`'s task queue drains relative to
  `RegionThreadPool.awaitLatch()`'s barrier wait — NOT fully traced, this needs the same kind of
  call-site tracing that closed the tick-phase items, just deeper (command execution timing isn't
  as cleanly phase-separated as entity/block ticking is). Lower confidence and likely lower
  severity than the MapItemSavedData bug (a torn HashMap READ during concurrent modification is
  usually silent staleness or a rare exception, not a guaranteed crash) — flagged as open, not
  silently assumed safe or unsafe.
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
- **Forge capabilities** attach/invalidate on entities/chunks during region tick. **Traced, NOT
  closed** (2026-08-07) — too diffuse for the single-call-site audit approach that closed the
  tick-phase items above (capabilities have thousands of call sites across vanilla+every mod, not
  one clear collection point). What's confirmed: `RegionChunkView.getBlockEntity()`'s deep-lock
  path only protects the FETCH of the `BlockEntity` reference (`owner.getChunkLock().tryReadLock`
  released in a `finally` before the method returns) — any `.getCapability()` call a caller makes
  on the returned reference happens completely outside that lock, same fetch-under-lock/use-
  outside-lock shape as the POI bug (#9) fixed this pass. Plausible mitigating argument (NOT
  verified empirically): the barrier's "wait for slowest region" semantics mean the OWNING
  region's thread cannot start a new tick (re-acquire its write lock, per the comment at
  `RegionChunkView.java:166`) until every region — including whichever thread is doing this
  foreign read — reaches the barrier together, which may bound the actual concurrent-mutation
  window. The ghost-zone path (`BoundaryManager.getGhostChunk`, bounded-staleness by design) likely
  inherits the same "stale but not corrupt" character already accepted for block-state reads,
  *if* `CapabilityProvider`'s plain (non-volatile) `capabilities`/`valid` fields don't produce a
  torn read under the JLS (individual reference/boolean field reads are atomic, visibility timing
  is the open question, not corruption) — this reasoning has NOT been empirically stress-tested.
  **Recommended next step if picked up**: a live test with a capability-heavy interaction (hopper→
  chest item-handler chain, or similar) deliberately straddling a known region border under
  sustained load, watching for capability-related exceptions or item duplication/loss — this is a
  genuinely open question, not a closed one, unlike the items above.
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
  call site (`ServerChunkCache.java` around line 464). No overlap window. **Weather** (rain/snow
  tick, lightning) not yet separately audited — usually a small fixed set of per-level state
  reads/writes on main during the same tick, lower suspicion, but not confirmed.

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
3. ~~**Audit pass** — walk the 🔍 list; fix any confirmed.~~ — done for the tick-phase-dispatched
   structures (#10 scheduled ticks, block-entity tickers, random ticks — all safe by the same
   collect-then-barrier pattern, see heuristic below). Remaining unaudited: Raids/village,
   MapItemSavedData, Scoreboard, Boss events, Forge capability attach/invalidate, chunk save/unload
   racing region tick, weather. These are event-driven (death/interaction-triggered) rather than
   per-tick-phase-collected, so the shortcut heuristic below doesn't apply — each needs individual
   tracing if picked up.
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
