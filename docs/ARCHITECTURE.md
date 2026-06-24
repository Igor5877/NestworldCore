# NestworldCore — How the Core Works

NestworldCore is a Forge 1.20.1 server fork that **shards one world across many threads inside a
single JVM**. Instead of vanilla's single `Server thread` ticking everything, the world is cut into
rectangular **regions**, each ticked by its own **region thread** in parallel. Goal: scale to high
player / mob / contraption counts (the design target is ~200 players on a 230-mod pack) by spreading
load horizontally across CPU cores, while keeping a vanilla-identical world.

## The big idea

```
 vanilla:                         NestworldCore:
 ┌───────────────┐                ┌──────┬──────┬──────┬──────┐
 │ Server thread │  everything    │ R0   │ R1   │ R2   │ R3   │  regions, one thread each
 │  ticks ALL    │  in one queue  │thread│thread│thread│thread│  tick in parallel
 └───────────────┘                └──────┴──────┴──────┴──────┘
                                   main thread = barrier + glue
```

200 players spread across the world land in different regions → each thread ticks ~10-15 of them
(~4.5 ms) instead of all 200 serially (~60 ms, over the 50 ms tick budget). One laggy region (e.g. a
griefer's TNT chunk) only stalls **its** thread; the other 190 players keep running at 20 TPS.

## The tick cycle

Every server tick, `NestworldRegionSystem.tickAllRegions()` runs six phases (timings shown by
`Tick phases avg ms`):

1. **vanilla** — the unavoidable main-thread part of `ServerLevel.tick` (chunk load/unload, weather,
   the loaded-FULL chunk snapshot is also (re)published here so region threads can read chunks
   lock-free this tick).
2. **signals** — redstone / cross-region signal propagation drained from `BoundarySignalQueue`.
3. **entityXfer** — entities that crossed a region boundary last tick are reassigned to their new
   owner region (`BoundaryEntityTransfer`); main-pinned entity types are ticked here too.
4. **regionPool** — the parallel tick: `RegionThreadPool` releases all region threads and **blocks on
   a `CountDownLatch` barrier** until they finish. This is where the real work happens.
5. **ghostZones** — boundary "ghost" copies refreshed; **deferred entity add/remove drained on main**
   (region threads queued them; main registers them now, while threads are idle at the barrier).
6. **splitMerge** — `RegionSplitManager` adaptively splits hot regions / merges cold ones.

While the main thread is blocked at the phase-4 barrier, it **services `pollTask`** — it runs any
main-only work a region thread asked for (e.g. a synchronous chunk load) so the threads don't stall.

## Core components

| Component | Role |
|---|---|
| `WorldRegion` | a rectangular chunk-range owned by one thread; tracks `ownedEntityIds`, a rolling tick-cost window (`getAvgTickMs`), a `StampedLock` for cross-region chunk reads |
| `RegionThread` | one thread per region; `tickEntities` (budgeted) + block ticks; per-thread chunk cache, neighbour updater, AC-wire handler, void-air chunk |
| `RegionThreadPool` | owns the threads; per-tick `CountDownLatch` barrier; `awaitLatch` services main-thread tasks |
| `RegionTree` / `RegionSplitManager` | the live region layout; adaptive split/merge; layout persistence |
| `BoundaryManager` / `BoundaryEntityTransfer` / `RegionChunkView` | cross-region reads (ghost zones) and entity ownership handoff at borders |
| `ConcurrentEntitySectionStorage` | thread-safe entity-section index (a `ReentrantReadWriteLock` over vanilla's `EntitySectionStorage`) so threads can `getEntities` concurrently for collision/AI |
| `BlockTickHeat` | per-chunk block-tick load signal feeding the load-aware split scorer |
| `NestworldRedstone` / `BoundarySignalQueue` / `CrossRegionCapabilityBus` | redstone & capability access across region boundaries |
| `NestworldCompat` / `NestworldPins` | mod compatibility (see below) |
| `NestworldTuning` | budgets, caps, feature flags |
| `NestworldCommand` | `/nestworld status \| lag \| split \| merge \| pins …` |

## Reading chunks safely (the lock-free snapshot)

Region threads do thousands of block reads per tick (collisions, AI, pathfinding). Going through
vanilla `getChunk` from off-main meant blocking on `CompletableFuture.supplyAsync(...).join()`, which
woke the main thread — a **park/unpark thundering herd** that ate ~30% of the main thread.

Fix: the main thread publishes a `volatile Long2ObjectMap<LevelChunk> nestworldLoadedFull` of all
FULL chunks each tick (phase 1). A region thread's `getChunk` resolves: **per-thread cache → that
published snapshot → null** (for `load=false`, the common vanilla "null if absent" contract). It
never blocks, so the herd is gone. `load=true` blocks for correct data (rare); `-Dnestworld.
nonBlockingChunkReads=true` opts into per-thread void-air instead (experimental).

## Spawning / removing entities (deferral)

`ChunkMap`'s entity tracker (`entityMap`, an `Int2ObjectOpenHashMap`) is iterated by the main thread
every tick and is **not** thread-safe. So region threads never touch it directly:
- **spawns** → `queueEntitySpawn` → drained on main at the phase-5 barrier.
- **removals** → `queueEntityRemoval` → drained on main at the phase-5 barrier.

This keeps the tracker main-only — no locks, no contention, no corruption (this is what made mass-TNT
entity churn stop crashing).

> **Refinement (Phase 2, `REGIONALIZED_TRACKER`, default off).** The "region threads never touch the
> tracker" rule is the *strong* form. Phase 2 relaxes it to the *exact* form that is actually
> required for safety: **region threads may touch the tracker during `regionPool`, but only to READ
> `entityMap` and to mutate the tracker state (`seenBy`, `lastSectionPos`, `sendChanges`) of the
> entities THEY OWN.** That is safe because of two facts that already hold:
> 1. `entityMap` is **structurally frozen during `regionPool`** — every add/remove is deferred to the
>    phase-5 barrier (above), so no thread is rehashing the map while region threads `get()` from it.
> 2. A `TrackedEntity`'s mutable state is **only ever touched by its one owning region** (single
>    ownership), and the region pass (`regionPool`) never overlaps the main passes that also touch it
>    (`ChunkMap.tick` in the vanilla phase, `ChunkMap.move` during packet handling) — different,
>    non-overlapping phases.
>
> So the precise invariant is: *the tracker may be **read** concurrently while it is structurally
> frozen, and each entity's tracker state may be **written** only by its owning region.* Phase 2 is
> the one place that leans on the subtle (rather than the blunt) version of the rule — which is why it
> is flag-gated and the highest-care change. If you ever make `entityMap` mutable during `regionPool`,
> or let an entity be tracked by more than one thread, this breaks.

## The thread-safety layer

Vanilla stores game state in single-thread structures (`Int2ObjectOpenHashMap`, `ShortOpenHashSet`,
`LongLinkedOpenHashSet`, `DynamicGraphMinFixedPoint`). Each one a region thread touches **directly**
had to be made safe (the recurring `AIOOBE Index -1` in a fastutil `rehash` is the signature):

| Structure | Fix |
|---|---|
| POI (`SectionStorage` + `PoiManager.DistanceTracker`) | one `ReentrantLock`; disk reads moved outside the lock |
| `ChunkMap.entityMap` | deferral to main (above) |
| `ChunkHolder.changedBlocksPerSection` | per-holder lock, snapshot-and-clear; packets built off-lock |
| `ServerLevel` nav guard (`isUpdatingNavigations`) | `ThreadLocal` per-thread reentrancy guard |
| explosion block reads | per-explosion `NestworldExplosionCache` (lock-free, one explosion = one thread) |

Structures that are **already behind a task queue stay safe untouched** — e.g. the light engine
(`ThreadedLevelLightEngine`) isolates its `DynamicGraphMinFixedPoint` behind a `ProcessorMailbox`, so
region threads only enqueue. See `THREAD_SAFETY_PASSES.md` for the full inventory + the rule of thumb:
*a structure is only crash-class if region threads touch it directly.*

## Adaptive splitting & merging

`RegionSplitManager` watches per-region cost (`getAvgTickMs`) + `BlockTickHeat`. A hot region is cut
along the axis that best balances both entity density and block-tick density (the "load-aware cut").
Cold neighbours merge back. There is a cap (`MAX_REGIONS_TOTAL ≈ cores×2`) so it doesn't over-split,
and the per-region entity budget (`REGION_ENTITY_BUDGET_NANOS`) defers excess entity ticking so one
region can't blow the whole tick. Layout is persisted across restarts.

## Mod compatibility

- **`NestworldCompat`** name-denylists the Lithium family (Canary/lithium/radium): they *replace* the
  core's thread-safe structures with single-thread ones, so they corrupt or contend under parallel
  ticking. Their optimisations are already provided thread-safely by the core, so they are disabled
  with a loud log.
- **Everything else is welcome.** The core does **not** police mods by which classes they touch
  (that would break legitimate feature mods). Robustness comes from the core's own thread-safety, so
  arbitrary mods that touch these classes from region threads are safe.
- **`NestworldPins`** is the escape hatch: a genuinely-incompatible mod's entities/block-entities can
  be pinned to main-thread ticking (vanilla behaviour, just not parallelised) via
  `/nestworld pin`/`pinmod` or `nestworld-pinned-mods.txt` — isolate, don't block.

## Performance optimizations (the tuning flags)

Layered on the architecture above; each is a `-Dnestworld.*` flag so it can be toggled/rolled back
instantly. Full rationale + measurements in `OPTIMIZATION_PACKAGE.md`.

| Flag (`nestworld.`) | Phase it touches | What it does | Default | Risk |
|---|---|---|---|---|
| `trackerMoveThrottle` (A1) | vanilla / `move` | re-evaluate a moving player's entity tracking ≤1×/tick instead of per move-packet (fixes the flying-into-a-pile stall) | **on** | low |
| `eventDrivenOwnership` (B1) | entityXfer | only re-check ownership for entities that changed section (vs scanning all) + periodic full-pass safety net | off | low |
| `trackerParallelDetection` (Phase 1) | vanilla | run the serial tracker detection scan as a read-only `parallelStream`, deferring the one mutation (section-change `updatePlayers`) to a short serial post-pass | off | low-med |
| `regionalizedTracker` (Phase 2) | vanilla → regionPool | each region tracks its OWNED entities during its own tick; main `tick()` only handles the rest, via a "tracked-this-tick" stamp so nothing is missed | off | **high** (bends the tracker invariant above — most care) |
| `skipEmptyEntityCollision` (C1) | regionPool (`move`) | skip the entity-collision broad-phase when `ServerLevel`'s count of hard-collidable entities is zero (a TNT/item pile collides with nothing) | off | med (collision correctness; counter never under-reports) |
| `cacheExplosionExposure` (C2) | regionPool (`explode`) | memoise the explosion exposure raycast per block position within one explosion (Paper-style, not bit-identical) | off | med (slight gameplay change) |

Rule of thumb for any new optimization: **decide which phase it runs in, and what data it touches in
that phase.** If it runs in `regionPool` it may touch only its own region's data (and the tracker
only under the refined invariant above). If it touches cross-region or shared state, it belongs in a
serial phase. Flag-gate it, and — if it changes the entity tracker — test it with a real client (the
`Soaker` mineflayer bot reports `VISIBLE_ENTITIES`; a regression shows up as that number dropping).

## Known limits

- **Spatial concentration** — region = chunk-granular, so load piled into *one* chunk (e.g. 7000 TNT
  in a chunk) lands on *one* thread; the other threads idle. This is local, not global: only that
  region lags. Work-stealing across regions is blocked by the per-region ownership/write-lock model.
- **Main-thread work** — commands (`/fill`, `/clone`, `/forceload`), the vanilla phase, and redstone
  signal drain run on the main thread. An OP can tank the server with a huge `/fill` (demonstrated);
  sharding can't help because vanilla executes commands single-threaded. Not a griefer vector.
- **Per-compute floors** — inherent vanilla per-mob/per-explosion cost (AI target search, collision
  broad-phase, explosion raycasts) is real work; the core parallelises it but can't remove it.

See also: `THREAD_SAFETY_PASSES.md` (the race inventory + roadmap), `OPTIMIZATION_PACKAGE.md` (this
session's A1/B1/Phase1/Phase2/C1/C2 — rationale, measurements, the Folia roadmap), `OPTIMIZATIONS.md`,
`CONCURRENCY_FINDINGS.md`.
