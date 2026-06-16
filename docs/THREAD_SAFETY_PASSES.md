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

Also shipped: **explosion-ray cache** (`NestworldExplosionCache`, 47.4.105) — per-explosion block/fluid
memoisation, bit-identical; all explosion block lookups route through it.

### ✅ Investigated — SAFE (not a race)
| # | Structure | Finding |
|---|---|---|
| 4 | **Light engine** (`DynamicGraphMinFixedPoint` queue) | NOT racy. `ThreadedLevelLightEngine` isolates the graph behind a `ProcessorMailbox`: region threads only `addTask`/`tell` (thread-safe enqueue); the graph mutation (`runUpdate`/`super.checkBlock`) runs serially on the single light thread (`tryScheduleUpdate` only `mailbox.tell` + `scheduled.compareAndSet`). Light *reads* (getRawBrightness) are atomic byte-array reads — at worst stale, never corrupt. **Roadmap prediction was wrong** — light differs from POI precisely because it's mailbox-deferred, not directly accessed. |

### Refined heuristic (from #4)
A structure is crash-class ONLY if region threads touch it **directly**. If it sits behind a
`ProcessorMailbox` / task queue / is main-only / is already deferred, it is safe. Re-scan the
suspected list through this lens before assuming a fix is needed — many may be mailbox-isolated.

### ❌ Open — crash-class, prioritised
| # | Structure | Trigger | Severity | Recommended fix |
|---|---|---|---|---|
| 5 | **`ServerLevel.sendBlockUpdated`** nav (`isUpdatingNavigations` bool + `navigatingMobs` Set) | concurrent collision-block changes w/ navigating mobs | non-fatal (vanilla only logs) but real CME risk | D (ThreadLocal guard) + C (thread-safe `navigatingMobs`) |

### 🔍 Suspected — audit (not yet triggered)
Vanilla single-thread structures on the entity/block tick path. Audit each: does a region thread
mutate it while another thread reads/mutates?
- **Scheduled ticks** — `LevelTicks` (block/fluid). Core already patches it; verify per-region vs main.
- **Block-entity tick list** / `LevelChunk` tick lists.
- **Raids / village** (`Raids`, POI-adjacent), **MapItemSavedData** (entity tracking on maps).
- **Scoreboard** (`Scoreboard` score updates from mob death/criteria).
- **Boss events** (`ServerBossEvent` player sets).
- **Forge capabilities** attach/invalidate on entities/chunks during region tick.
- **`PersistentEntitySectionManager`** visibility/section transitions (partly handled — re-verify).
- **Chunk save/unload** racing region ticking the same chunk.
- **Random-tick / weather** structures touched off-main.

### ⏸️ Deferred — perf, not crash
| Structure | Issue | Note |
|---|---|---|
| Global POI lock | doesn't scale to N region threads under distributed load (contention, not crash) | RWLock or concurrent-map + thread-safe PoiSection. See `[[poi-lock-scaling]]`. |

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

1. **#3 ChunkHolder block-change** — actively crashes on TNT. Do first.
2. **#4 Light engine** — same queue class as POI, almost certainly the next FATAL under mass block edits.
3. **Audit pass** — walk the 🔍 list; fix any confirmed.
4. **#5 nav** — non-fatal, lower urgency but same class; cheap (ThreadLocal).
5. **Deferred POI scaling** — only when distributed load actually proves the ceiling.

When 1–3 are done, the core is robust enough that arbitrary feature-mods touching these classes from
region threads are safe — which is the real compatibility story, far better than blocking mods.
