# Core/Adapter Classification — Inventory Pass 1

Status: draft, first pass. Produced per §27 of the "NestWorldCore — Технічне завдання: Архітектура
ядра, Adapter Layer та підготовка до майбутніх API" (2026-09-05). Classifies every existing file
under `net.nestworld` against the taxonomy defined there: **CORE / ADAPTER / DIAGNOSTICS /
COMPATIBILITY / TEMPORARY**. Mixed files get a dual tag (e.g. `CORE+ADAPTER`) rather than a forced
single bucket — per §41, mixed files are split into their component parts later, not rewritten now.

This is a classification pass, not a refactor. No file has moved or changed. See §40 (Migration
Strategy): classify → define boundary → small refactor → test → next component.

## Physical rule already applied (§4)

`patches/` (709 files, vanilla `net.minecraft.*`/Forge source patches) = **ADAPTER**, wholesale, by
definition. Not broken out file-by-file here — the ТЗ's own physical rule already resolves this
tier.

## Full dependency-mechanism inventory (updated after Phase B Milestone 1, 2026-09-05)

Phase B's Core-slice bring-up experiment surfaced the complete picture — five distinct kinds of
non-`net.nestworld` source this project depends on, not the two originally assumed:

| # | Mechanism | Location | Nature |
|---|---|---|---|
| 1 | Vanilla patches | `patches/` (709 files) | ADAPTER, physical rule above |
| 2 | New pure-Java Adapter | `net.nestworld.adapter` (6 files, #96/#97) | ADAPTER |
| 3 | Vendored Forge source | `src/main/java/net/minecraftforge/` (652 files, 4 modified) | ADAPTER (found in Phase A) |
| 4 | **Vendored third-party mod source** | `src/main/java/alternate/current/` (11 files) | **NEW category — third-party vendored implementation dependency, neither Core nor Adapter.** Ships with its own `LICENSE` file (license-compliant vendoring of the open-source "Alternate Current" redstone-wire mod). Used by `RegionThread.acWireHandler()` as an optional, lazily-instantiated redstone-propagation optimization. Found during Phase B's Core-slice compile test — `RegionThread.java` requires it to compile as-is. |
| 5 | NestWorld's own vanilla-patch-injected fields/methods | e.g. `ServerLevel.nestworldWireHandler`, `ServerChunkCache.nestworldLoadedFull` | Not a separate source location (these live inside #1's patched files) but a **distinct dependency direction worth naming**: several `net.nestworld.region` Core files call directly into symbols that only exist because NestworldCore itself patched them into vanilla classes. Confirmed via Phase B's live compile test — see `docs/NEOFORGE_1211_DIFF_AUDIT.md`'s category-5 table. This means Core Java files and the vanilla patches are not independently portable/testable; a real Adapter bring-up needs a coordinated subset of both. |

`alternate/current/`'s own Minecraft coupling (6 of 11 files reference `net.minecraft`/
`net.minecraftforge`) is a portability question in its own right, orthogonal to NestworldCore's own
Core/Adapter boundary — tracked separately, not rolled into the Core/Adapter tags below.

## `net.nestworld.api` (6 files) — #94 RESOLVED

This package is **not embryonic/unused** — it is a real, already-deployed, disciplined facade with
exactly one confirmed consumer.

| File | Note |
|---|---|
| `NestworldApi.java` | public entry point — `isActive()`, `prefetchChunks()`, `dimensionKeys()`, `regions()`, `chunkGen()`, `ticks()`, `modLoad()`, `tickHotspots()` |
| `RegionSnapshot.java` | public DTO, own javadoc: "Stable, public API surface for external consumers (metrics exporters, future mods)" |
| `TickSnapshot.java` | public DTO |
| `ChunkGenSnapshot.java` | public DTO |
| `ChunkTickCost.java` | public DTO |
| `ModTickCost.java` | public DTO |

**Consumers found** (full-body search, not just imports — per the audit methodology standard):
- `metrics-exporter/` (in-repo companion Gradle module, `NestworldMetricsRenderer.java` +
  `NestworldMetricsMod.java`) — a **real, deployed, independently-updatable mod** exposing
  NestworldCore state as a Prometheus `/metrics` endpoint. Built 2026-08-21 explicitly so
  "CPU saturated vs. one thread serialized vs. one call ran long" never needs crash-report
  archaeology again. Its own header comment: "Everything it reads comes through
  `net.nestworld.api.NestworldApi` (the public, stable facade)... no internal NestworldCore
  classes, no patches, no mixins." A 2026-08-22 correction hardened it further: no method
  signature in the mod's event-listener class may reference any `net.nestworld.api` type, because
  Forge's `EventBus.register()` reflectively resolves *every* declared method signature —
  including unannotated/synthetic ones — so a stray reference would throw
  `NoClassDefFoundError` at construction time on a server running the exporter without
  NestworldCore installed. This mod is already built and present in `mods/` on multiple
  local test/prod server trees.
- `NestworldCommand.java:2701` — one **internal** call, `NestworldApi.prefetchChunks(level,
  positions)`. This is backwards: Core's own command handler routing through its external-facing
  API instead of calling `ChunkPrefetch` directly. Minor, not urgent — noted as a low-priority
  cleanup candidate, not acted on.

**Decision (per user's #94 guidance — understand role first, no cleanup-for-cleanup's-sake):**
- **Not** "Embryonic/Experimental, no guarantees" — that would understate a contract a real,
  independently-updatable consumer already depends on and was deliberately engineered to survive
  NestworldCore's absence.
- **Scope it explicitly as a narrow Metrics/Diagnostics facade**, not the seed of the future
  general Mod API (§29). Its shape (read-only snapshots + one action method) matches exactly what
  metrics-exporter needs and nothing more — a real Mod API would eventually need to cover far more
  than metrics (item/entity/world manipulation semantics), so this package's current shape must
  **not** be allowed to pre-determine that broader design later, per the user's explicit "не
  дозволяти його структуру диктувати архітектуру Core."
- **New dependents**: no blanket ban (only one exists today), but any new consumer added to this
  package should be a conscious decision, not silent accumulation — flag at review time.
- Fixing the backwards `NestworldCommand.java` call is optional low-priority cleanup, not
  required by this ТЗ stage.

## `net.nestworld.chunk` (7 files)

| File | Tag |
|---|---|
| `RegionChunkScheduler.java` | CORE (Scheduling) |
| `ChunkRequest.java` | CORE (Messaging, value type) |
| `ChunkRequestPriority.java` | CORE (Scheduling policy) |
| `ChunkRequestDuplicateGuard.java` | CORE (Ownership/dedup invariant) |
| `ChunkRequestMisrouteGuard.java` | CORE (Ownership invariant) |
| `ChunkSchedulerOwnershipGuard.java` | CORE (Ownership) |
| `ChunkSchedulerShadow.java` | DIAGNOSTICS (shadow-mode comparison, per standing shadow-mode preference) |

## `net.nestworld.compat` / `net.nestworld.startup`

| File | Tag |
|---|---|
| `compat/NestworldModCompat.java` | COMPATIBILITY |
| `startup/NestworldStartupProfiler.java` | DIAGNOSTICS |

## `net.nestworld.region` (121 files)

### Core — Execution / Regions / Scheduling

`RegionThread`, `RegionThreadPool`, `RegionTree`, `WorldRegion`, `WorldGrid`, `RegionSplitManager`,
`RegionScheduler`, `RegionPhase`, `SplitAxis`, `AdmissionController`, `CpuCapacityPlanner`,
`NestworldTuning`, `NestworldLiveTuning`, `PredictiveChunkGen` (mixed-leaning), `ChunkPrefetch`
(mixed-leaning).

### Core — Ownership

`EntityOwnershipRecheck`, `NestworldTickOwnership`, `NestworldPins.java` (re-tagged from
CORE+COMPATIBILITY — see `docs/DEPENDENCY_DIRECTION_AUDIT.md`: the ownership-resolution contract
*is* the whole file, "compatibility" was a purpose label not a structural one).

### Core+Adapter — Ownership (re-tagged after §6 audit)

`EntityOwnershipGuard`, `NestworldOwnershipAssertions`, `BoundaryManager`, `BoundarySignalQueue` —
their ownership/boundary *algorithm* is pure Core, but their assertion/read/write *subject types*
(`Entity`, `Level`, `BlockEntity`, `BlockState`, `LevelChunk`, `ServerLevel`) are the exact §7
example (`Entity`→`EntityExecution`, `Level`→`World`). See audit doc for detail.

### Core — Messaging / Dispatch

`RegionMessage`, `EntityMutationDispatcher`, `EntityMutationOp`, `EntityMutationHelper` (payload
vocabulary is game-semantic by necessity, not version-instability — see audit doc).

### Messaging / Dispatch — §41.C SPLIT DONE (#96/#97, 2026-09-05)

`PlayerAttackDispatcher`, `EntityInteractionDispatcher`, `PlayerInteractionDispatcher`,
`PlayerUseControlDispatcher`, `PlayerUseItemDispatcher`, `PlayerUseItemOnBlockDispatcher` are now
**CORE** (not CORE+ADAPTER) — each had its one Minecraft-vanilla invocation physically extracted
into a matching class in the new `net.nestworld.adapter` package (see below). `PlayerInputDispatcher`
was already pure CORE (shadow-mode, no vanilla invocation exists in it to extract).
`CrossRegionCapabilityBus` remains CORE+ADAPTER, untouched — its Forge `Capability`/`LazyOptional`
coupling is tracked debt (priority flag below), not yet split.

### `net.nestworld.adapter` (6 files) — first real Java Adapter-zone package, distinct from `patches/`

| File | Wraps |
|---|---|
| `PlayerAttackAdapter.java` | `Player.attack(Entity)` |
| `PlayerInteractionAdapter.java` | `BlockState.use(...)` |
| `PlayerUseItemAdapter.java` | `ServerPlayerGameMode.useItem(...)` |
| `PlayerUseControlAdapter.java` | `Player.releaseUsingItem()` / `LivingEntity.stopUsingItem()` |
| `PlayerUseItemOnBlockAdapter.java` | `ItemStack.useOn(...)` + creative count-preservation quirk |
| `EntityInteractionAdapter.java` | `Player.interactOn`/`ForgeHooks.onInteractEntityAt`/`Entity.interactAt` |

Every class here is a single-purpose static-method wrapper — no interface, factory, registry, DI,
or reflection. Full detail and the per-dispatcher table: `docs/DEPENDENCY_DIRECTION_AUDIT.md`'s
"#96/#97 DONE" section.

### Core+Adapter (mixed — central orchestrators, hook into Forge events directly)

`NestworldRegionSystem.java` — the central driver: hosts Core scheduling/dispatch calls *and* the
Forge `@SubscribeEvent` entry points (e.g. `onServerTick`). Classic §41.C candidate: split into a
thin Adapter-side event-hook file and a Core-side orchestration class once boundaries stabilize.

`NestworldDimensionRegion.java` — per-dimension region orchestration; Core region-lifecycle logic
directly interleaved with `ServerLevel`/`LevelChunk` calls. Same §41.C treatment as above.

`RegionTimeCompatibility.java`, `RegionScheduledTicks.java`, `NestworldScheduledTickLookup.java`,
`ScheduledTickHandoffMetrics.java` (this last one is Core logic + Diagnostics mixed) — bridge
Core's tick/ownership model to vanilla `LevelTicks`.

`BoundaryEntityTransfer.java`, `EntityPushHelper.java`, `NestworldPushPredicate.java`,
`ProjectileImpactGuard.java`, `NestworldExplosionCache.java`, `WireNetworkColdStart.java`,
`RecipeCandidateIndex.java`, `NearestPlayerIndex.java`, `FeaturePlacementResilience.java`,
`OutboundBatchQueue.java`, `ChunkOutboundQueue.java`, `PairingOutboundQueue.java`,
`ConnectionAdmissionGate.java`, `PairingAdmissionGate.java`, `EntityBoundsHelper.java` — Core
race/ownership-safety logic expressed entirely in terms of Minecraft-specific mechanics (pistons,
explosions, redstone wires, packets, AABBs). Same §41.C pattern: the *invariant* being enforced
(ownership, ordering, no-lost-work) is Core; the mechanic it's hooked to is 100% Minecraft-specific.
Not split in this pass.

`NestworldRedstone.java` — leans ADAPTER (vanilla `DiodeBlock` circuit-specific fix, see
[[redstone-freeze-closed-vanilla-circuit-quirk]]); the underlying bug was a vanilla quirk, not a
Core execution-model concern.

### Core+Compatibility

`NestworldPins.java` — a Core ownership-override mechanism (forces a type to main-thread
ownership) whose entire *purpose* is mod compatibility (the escape hatch, per its own javadoc).

### Adapter

`NestworldMod.java` (Forge mod entry point/bootstrap). The old Mixin-error override and
replacement Fabric chunk event shim were removed: loader Mixins now retain their own failure
and callback behavior.

### Compatibility

`NestworldCompat.java`, `ModCallbackClassifier.java`, `ModCallbackRouter.java`
(`ModCallbackAttribution.java` is Diagnostics — see below, it's the measurement side of the same
project).

### Diagnostics (largest bucket — metrics/timing/attribution/audit/benchmark/jfr)

`region/jfr/*` (all 7: `NwDiagLog`, `NwjfrLog`, `PromoteDiagnosticWindow`, `RedstoneDiagFilter`,
`RegionPromoteEvent`, `RegionTickEvent`, `ScheduledTickLifecycleEvent`), `TickAttribution`,
`TickDurationHistory`, `EntityFairnessMetrics`, `NaturalSpawnerMetrics`, `TrackingMetrics`,
`MoveDecisionMetrics`, `BurstAdmissionMetrics`, `JoinBurstMetrics`, `CraftingBatchMetrics`,
`BatchApplyStats`, `PlayerAttackTiming`, `PlayerInteractionTiming`, `PlayerUseControlTiming`,
`PlayerUseItemTiming`, `PlayerUseItemOnBlockTiming`, `EntityInteractionTiming`,
`RecipeLookupCensus`, `RecipeLookupTiming`, `RecipeMatchProfiler`, `RecipeCandidateIndexShadowStats`,
`LastRecipeCacheAudit`, `PollTaskAttribution`, `DistanceManagerAttribution`, `GenerationAttribution`,
`WorldgenGlueAttribution`, `ModCallbackAttribution`, `HotspotDetector`, `BlockTickHeat`,
`BlockTickWatcher`, `ConnectionDiagnostics`, `MailboxAudit`, `SplitMergeEventLog`, `CpuBenchmark`,
`CpuHardwareInfo`, `CpuConcurrencyParameter`, `ChunkGenBenchmark`, `RegionLoopProbe`,
`NestworldDeploymentFingerprint`, `SparkBridge`, `RedstoneBatchAudit`, `PlayerInputDiagnostics`,
`PlayerInteractionWaitBudget`.

### Diagnostics — presentation/control surface (Core-adjacent, not pure Diagnostics)

`NestworldCommand.java` — the entire `/nestworld *` command surface. Mostly Diagnostics (read-only
status/inspection commands) but also contains live execution-policy toggles (pin/unpin,
free-running toggles, region tuning) — a genuine control-plane, not pure observability. Tag as
`DIAGNOSTICS+CORE` (control surface over Core execution policy).

`NestworldChat.java` — pure presentation formatter for the above. DIAGNOSTICS.

### Temporary

`PlayerAttackExperiment.java`, `PlayerEntityInteractExperiment.java`,
`PlayerInteractionExperiment.java`, `PlayerTickExperiment.java`, `PlayerUseItemExperiment.java`,
`PlayerUseItemOnBlockExperiment.java` — explicitly named "Experiment"; these are the measurement
scaffolding from the #31 Player Region Execution project, not intended as a permanent API surface.
Per §41.D: keep as-is, do not treat as a foundation for anything future-facing.

## Not yet classified / needs a second pass

This first pass is name/role-driven from accumulated session context, not a fresh read of every
file. Items most likely to need correction on a second pass: `NestworldRegionSystem.java` and
`NestworldDimensionRegion.java` (both huge, genuinely mixed, deserve their own dedicated
Core/Adapter split design rather than a one-line tag); the `api/` package decision (§28 audit,
above); and a dependency-direction check (§6) — confirming no `CORE`-tagged file imports something
that should be Adapter-only.

## Suggested next steps (per §39, in order)

1. Resolve the `api/` package question (§28) — explicit decision, not silence.
2. Dependency-direction pass (§6): grep CORE-tagged files for direct references to
   patch-only/vanilla-source concepts that shouldn't leak in, vs. ones already justified because no
   real instability exists (§8 — don't abstract needlessly).
3. Pick ONE of `NestworldRegionSystem.java` / `NestworldDimensionRegion.java` and produce a real
   §41.C split design (Adapter hook file + Core orchestration class) as the first concrete
   Core/Adapter boundary — proof of the pattern before repeating it elsewhere.
4. Standardize naming/lifecycle/ownership/execution-context/diagnostics contracts (§42) once at
   least one real split (step 3) exists to generalize from.
