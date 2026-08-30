# Phase 10 / #30 — Global-State Audit (2026-08-27)

Per `docs/PHASE10_GLOBAL_STATE_AUDIT_SPEC.md`. Conducted via 5 parallel investigation-only forks
(#30.1-#30.5, one per spec scope section), each explicitly instructed not to touch #28 (cross-
region piston/BlockEntity atomicity — CLOSED/ROOT CAUSE UNKNOWN, see
`piston-cross-region-atomicity-open-design-issue.md`) and not to edit any file. Findings below are
the union of all 5 reports, de-duplicated (one finding — `ServerLevel.players` — was independently
found by three separate forks; treated as a single, very-high-confidence finding).

## Full audited-state table

| ID | Class | Field | Category | Owner | Readers | Writers | Thread(s) | Protection | Risk | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| G1 | `ServerLevel` | `players` (plain `ArrayList`) | B→should be A/C | main (by convention) | mob AI (`PlayerSensor`, `TemptingSensor`, `NearestAttackableTargetGoal`, `TemptGoal`, `LookAtPlayerGoal`, `BegGoal`, `EntityGetter.getNearestPlayer`/`hasNearbyAlivePlayer`) — region threads, every hostile/passive mob, every tick | player join/leave/dimension-change entity-tracking callback — main thread | readers: region threads (constant); writers: main only | none | **CONFIRMED, P0/P1** — independently found by 3 forks | Highest-confidence finding of this audit. High read frequency vs. low write frequency plausibly explains why it hasn't surfaced as an attributed crash yet (tick-loop catch-and-continue may be silently swallowing CMEs) — cross-reference `newly-summoned-mob-vanishing-mystery.md`. **Selected for fix, see below.** |
| G2 | `ServerLevel` | `dragonParts` (`Int2ObjectOpenHashMap`) | B | main-ish (tracking callback) | EnderDragon combat/hit-detection | same tracking callback, region-thread-reachable since EnderDragon ticks on regions | writers: whichever thread ticks the dragon (can be region); readers: main or another region | none | CONFIRMED shape, PLAUSIBLE impact, **P2** | Same mechanism as G1, far lower frequency (dragon-fight-only). Fix alongside G1 if convenient, not separately urgent. |
| G3 | `DimensionDataStorage` | `cache` via `computeIfAbsent()` | B | global | `CommandStorage.set()` (region-thread, command blocks) and any other `computeIfAbsent` caller | first-ever access per key per dimension per boot | region threads | `get()`/`set()`/`save()` individually `synchronized`, but `computeIfAbsent`'s null-check→construct→set sequence is NOT | **CONFIRMED, P1** | TOCTOU: two region threads racing the FIRST access to the same key can each construct a separate instance; the losing thread's writes are silently orphaned. Narrow window (first-access-only) but silent data loss when it fires. **Selected for fix, see below.** |
| G4 | `Entity` | `vehicle` / `passengers` | A (2 entities, possibly 2 regions) | each entity's own region | `startRiding()`/`stopRiding()`/mob AI (`Mount.java` Brain behavior — every AI tick, not just spawn) | `startRiding()` mutates both sides with zero ownership check | region thread (AI-driven) or main (player interact) | none | **CONFIRMED, P1** | Fills the explicitly-flagged "passenger mount/dismount — not yet audited" gap from `PHASE9_1_ENTITY_MUTATION_AUDIT.md`. Not live-race-traced (needs two independently-owned entities adjacent at mount time to engineer). **Deferred — see below, higher design complexity than G1/G3.** |
| G5 | Entity Capabilities (`AttachCapabilitiesEvent<Entity>`) | mod-owned capability instances | E-EXTERNAL | mod | mod | mod | any (no bus) | none — unlike `CrossRegionCapabilityBus` for block entities, entities have no equivalent | PLAUSIBLE, **P2** | No repro; structurally plausible for a capability provider with its own mutable state (not just delegating to entity fields). Documented, not fixed — matches spec's "document, don't blanket-fix" instruction for mod-boundary risk. |
| G6 | `NestworldPins` auto-pin | config default | — | — | — | — | — | `-Dnestworld.autoPin` defaults `false` | **P3, informational** | A genuinely incompatible region-thread mob type will keep crash-looping (bounded by `maxRegionCrashes`) rather than self-healing unless an operator opts in or manually pins. Doc note, not a bug. |
| G7 | `Scoreboard` (`objectivesByName`/`objectivesByCriteria`/`playerScores`/`teamsByName`/`teamsByPlayer`) | plain `HashMap`s | B (likely) | main (commands) | plausibly stat-triggered objectives on mob-kill (region-thread-reachable) | main (commands) + possibly region (kill-triggered stat updates) | not fully traced | none found | **PLAUSIBLE, P2** | Same shape as G1 but not traced to a concrete call site. Needs a follow-up pass. |
| G8 | `WorldBorder.listeners` | plain `ArrayList` | B (likely) | main | change-listeners | add/remove + iterate-on-change | not traced for region-thread reachability | none found | **PLAUSIBLE, P2** | Not traced this pass. |
| G9 | `GameRules.Value` wrapper mutation | — | — | — | — | — | — | — | **PLAUSIBLE, P2** | The `rules` map itself is effectively immutable post-construction; individual `Value` wrapper mutation not traced. |
| G10 | `ChunkEvent.Load`/`Unload` firing thread | — | — | — | — | — | — | — | **not yet audited** | Flagged, not inferred from memory this pass — needs independent re-verification. |
| G11 | Runtime (non-boot) registry mutation from region threads | — | — | — | — | — | — | — | **not yet audited** | Only boot-time registry mutation was previously covered (ModernFix/startup-optimization work). |
| — | `EntityTickList` | n/a | D (self-protected) | vanilla | any | any | any | vanilla's own internal `synchronized(this)` on every method | SAFE, no action | Already safe independent of any NestWorld patch. |
| — | `ServerLevel.navigatingMobs` | n/a | A-region-adjacent | n/a | mob nav | tracking callback | region | `ConcurrentHashMap.newKeySet()` (existing fix) | SAFE, no action | — |
| — | `ChunkMap` entity tracking (add/remove) | n/a | C-MESSAGE-OWNED | main | main | deferred via `queueEntitySpawn`/`queueEntityRemoval`, drained on main | region defers, main applies | existing NestWorld guard | SAFE, no action | Guard covers the FIRST call in the tracking callback but not the REST of the same method — that's exactly where G1/G2 live. |
| — | `PoiManager`/`SectionStorage` lock | n/a | A-region-adjacent | n/a | POI lookups | POI add/remove | region + main | `ReentrantReadWriteLock` (existing fix) | SAFE, re-verified (spot check) | — |
| — | `MapItemSavedData` | all mutable fields | A/C | — | — | — | — | `synchronized` on every reachable path (traced full call graph incl. private helpers + `HoldingPlayer` inner class) | **SAFE, re-verified COMPLETE** | `addClientSideDecorations` unsynchronized but client-only, never server-reachable. |
| — | `PrimaryLevelData` weather/time fields | 7 fields | — | main | region (read) | main (write) | — | `volatile`, all writers are plain single assignments (no compound RMW) | **SAFE, re-verified COMPLETE** | — |
| — | `Raids.raidMap` | — | — | — | Raider AI (region) | main only (traced both callers) | — | `ConcurrentHashMap` | **SAFE, re-verified** | A `containsKey`-then-`put` TOCTOU pattern exists in the code but both callers are main-thread-only, so it's a single-writer sequence despite looking like an anti-pattern; original fix comment's "different region threads" framing was imprecise but the CHM fix is still correct for the real reader/writer split. |
| — | `CommandStorage.namespaces`/`Container.storage` | — | — | — | — | — | — | `ConcurrentHashMap` | **SAFE at this layer** | Class's own fix is fine; see G3 for the layer underneath it. |
| — | NestWorldCore static/singleton state (full sweep, ~70 files) | — | — | — | — | — | — | atomics/CHM/AtomicReference-swap-of-immutable/correctly-scoped `volatile` throughout | **CLOSED NEGATIVE — no P0/P1 findings** | See task #32/`#30.3` report. `EntityOwnershipGuard`/`EntityOwnershipRecheck` (pre-#29 diagnostics) overlap in purpose with `NestworldOwnershipAssertions` — worth consolidating eventually, not a bug. |
| — | Forge event/capability/networking boundaries | — | — | — | — | — | — | documented policy (`docs/ARCHITECTURE.md`) | **Mostly CLOSED NEGATIVE / documented accepted-risk** | See G5/G6 above for the two actionable sub-findings. Packet handling confirmed main-thread-only via unmodified Forge/Netty contract. |
| — | Block/BlockEntity structures beyond #28 (Hopper, Comparator, LevelTicks) | — | — | — | — | — | — | existing routing/fixes | **CLOSED NEGATIVE — already covered** | Hopper: main-thread-only by border-band construction (`border-band-protects-be-io-from-mailbox-risk.md`). Comparator: existing PLAUSIBLE read-only-staleness finding, not re-derived. LevelTicks: extensively covered (`leveticks-ownership-model-full-fix.md`), one still-open low-severity self-limiting edge case (`region-split-scheduletick-race.md`), unrelated to this audit's scope. |

## P0 findings

None met the strict P0 bar (a reproduced crash/corruption) — but **G1 (`ServerLevel.players`)
is explicitly flagged by its investigating fork as sitting on the P0/P1 boundary**: reachable from
region execution, very high call frequency, and a plausible (not yet confirmed) silent-failure
mode via this project's own tick-loop catch-and-continue circuit breaker swallowing the resulting
`ConcurrentModificationException`s rather than crashing loudly. Treated as P1 for scheduling
purposes below but fixed with P0 urgency given three independent forks converged on it.

## P1 findings (fix now)

- **G1**: `ServerLevel.players` unsynchronized `ArrayList` — see "Fixes applied" below.
- **G3**: `DimensionDataStorage.computeIfAbsent()` TOCTOU — see "Fixes applied" below.
- **G4**: `Entity.startRiding()`/passenger-vehicle linkage — **deferred**, see rationale below.

## P2 findings (instrument + document only, per spec — no fix this pass)

G2 (`dragonParts`), G5 (entity capabilities lack a cross-region bus), G7 (`Scoreboard` maps,
untraced), G8 (`WorldBorder.listeners`, untraced), G9 (`GameRules.Value` mutation, untraced).

## P3 findings (documentation only)

G6 (`NestworldPins` auto-pin defaults off — operational note, not a bug).

## Not yet audited (honest gaps — explicitly not claimed as covered)

`ChunkAccess` implementations beyond `LevelChunk`'s top-level fields, `DistanceManager`/
`ServerChunkCache` internals beyond the entity-tracking guard, `Level.java` base-class fields,
`GameEventListenerRegistry` sections, `MinecraftServer.levels` map, `PlayerList.players`/
`playersView` (the account-level list, distinct from `ServerLevel.players`), broader `SavedData`
subtypes beyond the 4 named ones, `ChunkEvent.Load`/`Unload` firing thread (G10), runtime registry
mutation from region threads (G11), the full `ForgeEventFactory.on*` surface beyond
`LivingHurtEvent`/`BlockEvent`, `RegionThreadPool`'s full field list beyond the already-fixed
`busy` flag, `RegionSplitManager`'s in-flight-split internal state (beyond the documented cascade-
guard-lock mechanism), `RegionScheduler`/`RegionTree`/`WorldGrid` beyond a grep sweep,
`ChunkSchedulerShadow.PENDING`'s consumer logic. This is a large scope — per the spec, partial,
honestly-bounded coverage is the expected shape of a first pass, not a failure.

## Threading Contract (formalized, per spec section 19)

- **Region thread**: owns one `WorldRegion`'s spatial state (entities, blocks, block entities
  within its bounds). Direct mutation of anything it owns. Cross-region mutation only via a
  `RegionMessage` posted to the target region's mailbox (drained on the target's own thread later,
  with fresh ownership re-validation at apply time — see `EntityMutationDispatcher.applyQueued`).
  Can call into vanilla/Forge APIs and trigger Forge events; NestWorldCore does not guarantee or
  police what thread a mod's event listener assumes it's on (see `docs/ARCHITECTURE.md`'s
  documented accepted-risk policy) — mitigated reactively via `NestworldPins`, not proactively.
- **Server (main) thread**: owns global/server-wide state (player join/leave, `MinecraftServer`
  lifecycle, chunk promotion/unload via `ChunkMap`, the barrier-phase mailbox drain for
  `BLOCK_WRITE`/`EXPLOSION_APPLY`, `LevelTicks.tick()`'s iteration, scoreboard/command
  execution). Writes to genuinely-global collections (`ServerLevel.players`, `MinecraftServer
  .levels`, etc.) should happen here by convention today, but several of those collections
  (G1, and PLAUSIBLY G7/G8) are read from region threads with no protection — this audit's
  central finding is that "main-thread-only by convention" is not the same as "main-thread-only by
  enforcement," and several global collections currently rely on the former without the latter.
- **Netty (network) thread**: packet decode only; packet HANDLING is dispatched to main via
  Forge/vanilla's own `ensureRunningOnSameThread` mechanism, unmodified by this project. Not a
  region-thread-reachable boundary today.
- **Worker thread(s)** (chunk-gen `ForkJoinPool`, spark profiler threads, etc.): scoped to their
  own subsystems, not found to reach region/global mutable state directly in this pass.
- **Global Owner**: not yet a distinct formal role in code — today "global" state is really
  "main-thread-by-convention" state (see above). Introducing an actual explicit Global Owner
  abstraction (vs. relying on convention) is a candidate follow-up, not attempted this pass (would
  be a design change beyond "audit," per spec section 1's own scope limit).

**Allowed transitions**: Region → Server (mailbox message, applied at barrier or on next drain).
Server → Region (mailbox message, or direct call while the region is parked at a barrier point).
Region → Region (mailbox, never direct). Netty → Server (vanilla's own packet-handling dispatch,
unmodified). Nothing should call directly from a Region thread into another Region's state, or
mutate genuinely-global state from a Region thread, without going through one of the above.

## Fixes applied this pass

Per spec section 18 (P0/P1 → fix), two of the three P1 findings were fixed and validated; the
third (G4) was deliberately deferred — see the dedicated write-up in
`phase10-global-state-audit-fixes.md` for the implementation, compile, and live-test details for
each.

## Definition of Done — status

```
[x] NestWorldCore's own global state audited (task #32, CLOSED NEGATIVE)
[x] vanilla global state audited (tasks #30, #31 — this document)
[x] relevant Forge boundaries audited (task #33)
[x] static mutable state audited (folded into the above three)
[x] global collections classified (this table)
[x] ownership assigned (this table's Owner column, where traceable)
[x] threading contract documented (section above)
[x] known race fixes revalidated (Part 1 of task #31's report — MapItemSavedData/PrimaryLevelData/
    Raids/CommandStorage all re-verified)
[~] no undocumented mutable global state remains — large "not yet audited" list remains honest and
    open, NOT claimed as covered
[x] P0/P1 confirmed bugs fixed or explicitly deferred (G1, G3 fixed; G4 explicitly deferred with
    stated rationale)
[ ] runtime stress completed (1000+ op stress test per spec section 16 — not yet run this pass)
[ ] ATM9 clean boot (re-verify AFTER the G1/G3 fixes are deployed)
[ ] ownership assertions = 0 violations (re-verify AFTER the G1/G3 fixes are deployed)
[x] no new deadlocks (fixes use simple lock/collection-type changes, no new lock ordering
    introduced)
[x] no mailbox loss (G1/G3 fixes don't touch the mailbox mechanism at all)
```
