# Dependency-Direction Audit (§6) — Pass 1

Follows §95 in the roadmap agreed 2026-09-05: #95 (this doc) → #94 (api/ package status) → #96
(first real §41.C split). Scope: every file tagged CORE (single or dual) in
`docs/CORE_ADAPTER_CLASSIFICATION.md`. For each Minecraft/Forge dependency found, classified per
the table the user specified:

| Column | Meaning |
|---|---|
| залишити | реально потрібна Core семантиці |
| Adapter | потрібна лише для інтеграції Minecraft |
| кандидат на refactor | потрібна через стару архітектуру |
| Compatibility/Adapter | compatibility-specific |
| окремо зафіксувати | сумнівна |

No code moved in this pass — audit only.

## Methodology note (important correction mid-pass)

An import-statement-only grep (`^import net\.minecraft`) **undercounts real coupling** in this
codebase: several files reference Minecraft/Forge types by fully-qualified name inline without an
`import` line (matches the existing project convention already seen in `NestworldCommand.java`,
which fully-qualifies `net.minecraft.network.chat.MutableComponent`). `RegionMessage.java` showed
**0 import-line hits but 17 inline fully-qualified references** — it would have been wrongly
reported clean. All numbers below use the inline-reference grep
(`net\.minecraft(forge)?\.[A-Za-z0-9_.]+` anywhere in the file body), not import lines alone.

## Confirmed clean (0 references, either method) — pure Core

`SplitAxis`, `RegionScheduler`, `RegionPhase`, `NestworldTickOwnership`, `NestworldLiveTuning`,
`EntityOwnershipRecheck`, `CpuCapacityPlanner`, `AdmissionController`,
`chunk/ChunkSchedulerOwnershipGuard`, `chunk/ChunkRequestPriority`,
`chunk/ChunkRequestMisrouteGuard`, `chunk/ChunkRequestDuplicateGuard`. No table needed — these stay
CORE, unconditionally.

## Per-file findings

### Stable coordinate/value types — залишити (keep), not worth abstracting per §8

`BlockPos`, `ChunkPos`, `Direction`, `Vec3` appear across `WorldRegion`, `WorldGrid`,
`RegionMessage`, `BoundaryManager`, `BoundarySignalQueue`, `CrossRegionCapabilityBus`,
`RegionSplitManager`, `chunk/RegionChunkScheduler`, `chunk/ChunkRequest`, and the dispatcher family.
These are plain coordinate/vector records that have not changed shape across any Minecraft version
in this project's history. Per §8 ("абстрагувати потрібно нестабільну семантичну межу, а не весь
Minecraft") these do **not** need an Adapter-side Core equivalent — the instability that would
justify abstraction doesn't exist here. **Decision: залишити, closed, no follow-up.**

### `RegionMessage.java` — CORE, but payload variants are game-semantic by necessity

Inline refs: `BlockPos`, `InteractionHand`, `InteractionResult`, `Explosion` (+ nested
`NestworldExplosionBatch`), `BlockState`, `BlockHitResult`, `Vec3`. This is the Mailbox message
envelope (§21, Core) carrying different payload kinds (interaction replay, redstone signal,
explosion). The envelope/queueing/ordering *mechanism* is Core; the payload *content* naming
specific Minecraft interaction/explosion types is unavoidable — a mailbox message about "replay
this interaction" must describe the interaction in the vocabulary of the game being played. This
mirrors §16 more than §6: not a version-instability problem, a genuine semantic one. **Decision:
залишити.** Not a refactor candidate — trying to abstract `InteractionResult`/`BlockHitResult`
behind a Core-generic type would be exactly the "abstract everything" anti-pattern §8 forbids, since
these types are stable and the coupling is inherent to what a mailbox message *is* here.

### `RegionSplitManager.java` — CORE, but reads world state directly (стара архітектура)

Inline refs: `BlockPos`, `ServerLevel`, `Mth.floor`, `Entity`. Split/merge cost calculation reaches
into `ServerLevel`/`Entity` directly to measure region cost. The *decision logic* (thresholds,
hysteresis, veto rules) is unambiguously Core; the *cost measurement* reaching into live
`ServerLevel`/`Entity` state is exactly the §7 pattern (`ServerLevel`→`World`,
`Entity`→`EntityExecution`) that an Adapter-side cost-sampling hook should eventually provide.
**Decision: кандидат на refactor** — low urgency (this file already has clean threshold constants
isolated, per the recent `SPLIT_MS_THRESHOLD`/`MERGE_MS_THRESHOLD` visibility work), but flagged for
when a `World`/`EntityExecution` Core abstraction gets designed (likely alongside #96).

### `NestworldTuning.java` — CORE, references an Adapter-zone class by name (fine)

Inline refs: `ProxyLevelChunk` (a NestworldCore-authored class living under the vanilla
`net.minecraft.world.level.chunk` package for classloading reasons — itself Adapter-zone per the
patches physical rule), `net.minecraft.world.level.levelgen` (partial/package ref). This is a Core
config file naming an Adapter component to gate a feature flag — legitimately fine, this is what
Core→Adapter capability wiring is supposed to look like (§15), not a boundary violation. **Decision:
залишити, no follow-up.**

### `RegionThread.java`, `RegionThreadPool.java` — CORE, стара архітектура

`ServerLevel`, `Entity` used as the execution-unit type directly. This is precisely the §7 named
example (`ServerLevel`→`World`, `Entity`→`EntityExecution`). These are the two most central
execution classes in the whole codebase, so this is the highest-value, highest-risk refactor
candidate in the audit — **not attempted in this pass**. **Decision: кандидат на refactor**,
explicitly deferred to a dedicated future step, not #96 (too risky to combine with the first
boundary-pattern proof).

### `RegionTree.java` — CORE, single low-risk case

`CompoundTag` (NBT) — used for region-tree persistence serialization. Real Core need is "serialize
my state"; the concrete format is Minecraft's NBT. **Decision: кандидат на refactor**, low priority
(one call site, easy to wrap behind a Core `PersistenceState` writer per §17 whenever Persistence
subsystem gets its own pass — not blocking anything now).

### `BoundaryManager.java`, `BoundarySignalQueue.java` — CORE+ADAPTER (confirmed mixed, matches pass-1 tag)

`BlockEntity`, `BlockState`, `LevelChunk`, `ServerLevel` used to read/write actual world state at
region borders (not just coordinates). This is real Minecraft-object manipulation woven into Core
boundary logic. **Decision: кандидат на refactor**, target shape: an Adapter-side "boundary world
access" interface (read/write block+BE at a position) that Core's boundary invariant logic calls
through. Good secondary candidate for the §41.C pattern after #96's first proof, since it's smaller
in scope than `NestworldRegionSystem`/`NestworldDimensionRegion`.

### `NestworldOwnershipAssertions.java`, `EntityOwnershipGuard.java` — CORE+ADAPTER (pass-1 correction)

`Entity`, `Level`, `BlockEntity` used directly as the types being asserted-against
(`assertEntityOwner`/`assertBlockOwner`/`assertBlockEntityOwner`). Textbook §7 case
(`Entity`→`EntityExecution`, `Level`→`World`, `BlockEntity`→`ChunkContext`-adjacent). **Decision:
потрібна лише для інтеграції Minecraft → Adapter-translatable, стара архітектура.** The *ownership
resolution algorithm* itself (whose region owns this position/entity) has zero Minecraft-specific
logic and is genuinely Core — only the assertion's *subject type* is coupled. Re-tag from pass-1's
plain CORE to **CORE+ADAPTER**.

### `CrossRegionCapabilityBus.java` — highest-priority flag: loader-specific, not just version-specific

`Capability`, `LazyOptional` from `net.minecraftforge.common.capabilities` — this is **Forge's own
capability system**, which has no equivalent shape in NeoForge (diverged after the Forge/NeoForge
split) or vanilla Fabric. Every other flagged dependency in this audit is a *version*-instability
concern (Minecraft 1.20→1.21 renames methods); this one is a *loader*-instability concern (Forge vs
NeoForge vs Fabric have fundamentally different capability APIs). Per §11 ("кожна підтримувана
Minecraft version повинна мати власний Adapter") this is exactly the kind of dependency that would
force a NeoForge Adapter to look completely different at this call site, not just retarget a
renamed method. **Decision: Adapter, and flagged as the single most urgent item in this audit** —
if/when a NeoForge or Fabric adapter is ever pursued, this file is guaranteed to need a real
capability-abstraction seam, not a mechanical port.

### `EntityMutationDispatcher.java`, `EntityMutationOp.java`, `EntityMutationHelper.java` — CORE, mostly

`ServerLevel`, `Entity`, `LivingEntity` (container/target types, same §7 Adapter-candidate pattern
as elsewhere) vs. `DamageSource`, `MobEffectInstance` (mutation *payload vocabulary* — same
reasoning as `RegionMessage`'s payload types: genuinely game-semantic, not version-instability).
**Decision:** container types → кандидат на refactor (low priority, same bucket as
RegionThread/BoundaryManager); payload types (`DamageSource`, `MobEffectInstance`) → залишити,
closed.

### `ChunkPrefetch.java`, `PredictiveChunkGen.java` — CORE+ADAPTER (confirmed, pass-1 said "mixed-leaning")

`ChunkPrefetch` uses `net.minecraft.world.level.chunk.storage.IOWorker` — an internal
Minecraft chunk-IO class with no public-API stability guarantee at all (this is the single most
version-fragile type found in this entire audit; it has historically not even been intended as a
stable extension point). `PredictiveChunkGen` uses `TicketType` (Forge/vanilla chunk-loading ticket
API) plus `ServerLevel`/`ServerPlayer`. **Decision: Adapter** for both `IOWorker` and `TicketType`
specifically — genuine "потрібна лише для інтеграції Minecraft," and `IOWorker` in particular
deserves its own flag as a second priority item (version-fragile internal API, not just a
capability-model concern like `CrossRegionCapabilityBus`).

### Player-action dispatcher family — **pass-1 correction: re-tag CORE → CORE+ADAPTER**

`PlayerAttackDispatcher`, `PlayerInputDispatcher`, `PlayerInteractionDispatcher`,
`PlayerUseControlDispatcher`, `PlayerUseItemDispatcher`, `PlayerUseItemOnBlockDispatcher`,
`EntityInteractionDispatcher` import 2–10 Minecraft types each (`ServerLevel`, `ServerPlayer`,
`InteractionHand`, `InteractionResult`, `ItemStack`, `BlockPos`, `BlockState`, `BlockHitResult`,
`Vec3`, `Level`, `BlockPlaceContext`, `UseOnContext`, `BlockItem`). Pass 1 tagged this whole family
plain CORE (Messaging/Dispatch) — **this audit finds that's too generous.** Each dispatcher's real
job splits cleanly in two: (a) a thin, genuinely Core part — "resolve the owning region, check
ownership, route through the mailbox" (§20/§21, zero Minecraft-specific logic) — and (b) the bulk of
each file — "know exactly which vanilla method to call for *this* action type, with *these* exact
parameter types" (`Player.attack(Entity)` vs `Player.interactOn(Entity, InteractionHand)` vs
`UseOnContext`-based block placement) — which is precisely the Adapter's job per §4
("version-specific method interception," "version-specific execution entry points"). **Decision:
re-tag the whole family CORE+ADAPTER.** This is the clearest, most repeatable §41.C split candidate
in the codebase (seven near-identically-shaped files, same decomposition applies to all seven) —
worth strong consideration for #96 over the two single giant orchestrator files, *if* the goal is a
pattern that generalizes easily to many files rather than one deep, risky split.

### `NestworldPins.java` — user's specific question: answered, re-tag CORE+COMPATIBILITY → CORE

Only 3 references: `BuiltInRegistries`, `ResourceLocation`, `EntityType` — all used for
ID-parsing/registry lookup at pin/unpin/load time, never in the hot path (`isPinned`/`isBePinned`
are `Set.contains()` checks against pre-resolved keys — already §9-compliant). The ownership
contract — "is this type allowed onto a region thread, or must it run on main" — **is the entire
file**; there is no separable "Minecraft-specific compatibility realization" piece sitting next to
it. The only Minecraft coupling is `EntityType<?>` used as an identity key for the fast-path set,
which is minimal and non-hot-path-costly. **Decision: this is not actually a CORE+COMPATIBILITY
split candidate — re-tag plain CORE (Ownership).** "Compatibility" in pass 1 described *why* the
feature exists (a mod-compat escape hatch), not *what layer the code is in* — the taxonomy in this
ТЗ is structural, and structurally this file is clean Ownership-subsystem code, about as clean as
anything in the audit.

## Summary of re-tags from pass 1

| File | Pass 1 | This audit |
|---|---|---|
| `NestworldPins.java` | CORE+COMPATIBILITY | **CORE** |
| `NestworldOwnershipAssertions.java` | CORE | **CORE+ADAPTER** |
| `EntityOwnershipGuard.java` | CORE | **CORE+ADAPTER** |
| `BoundaryManager.java` | CORE | **CORE+ADAPTER** (confirmed, was implicit) |
| `BoundarySignalQueue.java` | CORE | **CORE+ADAPTER** |
| `PlayerAttackDispatcher` + 6 siblings | CORE | **CORE+ADAPTER** |
| `RegionSplitManager.java` | CORE | CORE (refactor-candidate noted, tag unchanged — decision logic dominates) |
| `RegionThread.java`, `RegionThreadPool.java` | CORE | CORE (refactor-candidate noted, tag unchanged — highest-value future work, deferred) |

## Priority flags for follow-up (not #95/#94/#96 blocking, but tracked)

1. **`CrossRegionCapabilityBus.java`** — Forge `Capability`/`LazyOptional` is loader-specific, not
   version-specific. Most urgent if NeoForge/Fabric portability is ever pursued.
2. **`ChunkPrefetch.java`** — `IOWorker` is a version-fragile internal Minecraft class with no
   stability guarantee.
3. **`RegionThread.java` / `RegionThreadPool.java`** — highest-value, highest-risk `ServerLevel`→
   `World`, `Entity`→`EntityExecution` translation per §7's own named example. Deliberately not
   touched in #96 (too central to risk on the first attempt).

## #96/#97 DONE — pattern validated and generalized

`PlayerAttackDispatcher.java` became the reference §41.C split (#96, approved). The pattern was
then mechanically repeated (#97, no architecture "improvement," exactly the same shape each time)
across the rest of the family:

| Dispatcher | Vanilla call extracted | Adapter class |
|---|---|---|
| `PlayerAttackDispatcher` | `Player.attack(Entity)` | `PlayerAttackAdapter.invoke` |
| `PlayerInteractionDispatcher` | `BlockState.use(Level, Player, InteractionHand, BlockHitResult)` | `PlayerInteractionAdapter.invoke` |
| `PlayerUseItemDispatcher` | `ServerPlayerGameMode.useItem(Player, Level, ItemStack, InteractionHand)` | `PlayerUseItemAdapter.invoke` |
| `PlayerUseControlDispatcher` | `Player.releaseUsingItem()` / `LivingEntity.stopUsingItem()` | `PlayerUseControlAdapter.release` / `.stop` |
| `PlayerUseItemOnBlockDispatcher` | `ItemStack.useOn(UseOnContext)` + creative count-preservation quirk | `PlayerUseItemOnBlockAdapter.invoke` |
| `EntityInteractionDispatcher` | `Player.interactOn`/`ForgeHooks.onInteractEntityAt`/`Entity.interactAt` (formerly private `runInteraction`) | `EntityInteractionAdapter.invoke` |
| `PlayerInputDispatcher` | **none** — shadow-mode only, never mutates, nothing to extract | n/a |

`net.nestworld.adapter` now has 6 classes, all single-purpose static-method wrappers, no
interface/factory/registry/DI/reflection anywhere — each is as thin as a patch, matching the "if
this needs 15 interfaces the boundary is wrong" constraint. `./gradlew :forge:compileJava` BUILD
SUCCESSFUL after both #96 and #97. `PlayerInputDispatcher` is correctly untouched: it's already
pure Core (its Minecraft-type usage is purely positional/accessor, matching the "stable coordinate
type, keep" bucket from this audit, not a vanilla-invocation site).

Per the user's fixed roadmap: next is reviewing all 7 dispatchers together for boundary
consistency, before touching any other kind of Core code (e.g. the two giant orchestrators,
deliberately still not attempted).

## Boundary-consistency audit (all 7, 2026-09-05)

Re-verified by grepping each dispatcher's live code (not javadoc) for any remaining vanilla
gameplay-invocation call — confirmed zero hits outside comments/RegionMessage/diagnostics calls —
and by measuring each adapter class (21-36 lines, exactly 1-2 `public static` methods, no
interface/factory/registry/reflection anywhere in `net.nestworld.adapter`).

| Dispatcher | Core boundary | Adapter boundary | Forge dep. | Version dep. | Hot path |
|---|---|---|---|---|---|
| PlayerAttackDispatcher | PASS | PASS | PASS (none) | PASS | PASS |
| PlayerInteractionDispatcher | PASS | PASS | PASS (none) | PASS | PASS |
| PlayerUseItemDispatcher | PASS | PASS | PASS (none) | PASS | PASS |
| PlayerUseControlDispatcher | PASS | PASS | PASS (none) | PASS | PASS |
| PlayerUseItemOnBlockDispatcher | PASS | PASS | PASS (none) | PASS | PASS |
| EntityInteractionDispatcher | PASS | PASS | PASS (isolated in Adapter) | PASS | PASS |
| PlayerInputDispatcher | PASS | Adapter required: **NO** | n/a | n/a | PASS |

Three specifically-requested deep checks:

- **`PlayerUseItemOnBlockAdapter`**: the creative-count-preservation wrapper is confirmed to be
  pure vanilla `ServerPlayerGameMode.useItemOn()` behavior replication (item count doesn't drop in
  creative), not NestWorld execution policy — it exists only because the region-executed path
  bypasses that method entirely and must reproduce its side effect. No NestWorld-specific
  conditionals live in the adapter.
- **`EntityInteractionAdapter`**: `EntityInteractionDispatcher.java`'s live code no longer branches
  on `atPos == null` anywhere — that decision (plain interact vs. positioned interact vs. the Forge
  hook) is entirely inside the adapter now; `dispatch()`/`applyQueued()` just pass `atPos` through
  unconditionally. `ForgeHooks` (loader-specific, the audit's priority flag) is now physically
  isolated in `net.nestworld.adapter`, not mixed into Core.
- **`PlayerInputDispatcher`** (positive control): confirmed genuinely Adapter-free — its only
  Minecraft-typed calls are positional/accessor (`serverLevel()`, `getX()`, `getZ()`, `getUUID()`),
  the same "stable, keep in Core" bucket as `BlockPos`/`ChunkPos` elsewhere in this audit; the file
  is shadow-mode and never calls a gameplay-mutating vanilla method at all, so there is nothing to
  extract. Proves the rule stayed "extract the vanilla invocation where one exists," not "give
  every dispatcher an adapter file."

**Net result: 7 dispatchers → 6 adapters → 1 dispatcher stays adapter-free by design.** No shared
interface, no `PlayerActionAdapter` umbrella, no registry/factory/DI anywhere in
`net.nestworld.adapter`. No false-abstraction findings.

**#94/#95/#96/#97 closed 2026-09-05.** No #98 — the user explicitly declined opening a new task
just to continue mechanically isolating dispatchers; this stage is done as-is.

## The refined §6 principle (record for all future audits of this kind)

> Наявність `net.minecraft.*` у Core-файлі сама по собі не є порушенням §6. Порушенням є
> залежність Core від нестабільної Minecraft/loader execution semantics, якщо цю залежність можна
> ізолювати в Adapter без втрати справжньої Core-семантики.

This is *why* `BlockPos`/`ChunkPos`/`Direction`/`Vec3`, `RegionMessage`'s payload types, and
`NestworldPins`'s `EntityType<?>` key were correctly left CORE earlier in this document — the type
reference itself was never the problem. Do not read this ТЗ as license to strip Minecraft imports
repo-wide; it explains precisely why most of them should stay.

`net.nestworld.adapter` is an **Adapter zone**, not yet an "Adapter framework" — there are too few
distinct integration *types* so far (only player-action dispatch) to know whether a shared
structural contract would even be justified. Revisit only once chunk-loading, persistence, world
access, capability integration, scheduler integration, and lifecycle hooks have each needed their
own Adapter split — if a repeated shape emerges then, it can become part of a real Adapter
architecture; if not, small static adapters remain the right answer.

`RegionThread.java`/`RegionThreadPool.java` remain deliberately untouched — highest-value,
highest-risk in this whole audit (the textbook §7 `ServerLevel`→`World`/`Entity`→`EntityExecution`
case), and a rushed split here risks doing more harm than good. Not picked up casually.

## Recognized next milestone (not started, not scoped, no task opened)

A **1.21.1 NeoForge Adapter Portability Test**: build a second real Adapter (NeoForge 1.21.1)
alongside the existing Forge 1.20.1 one and observe where Core genuinely doesn't survive the
version/loader change — the real test of Core Foundation stability (§44), more valuable than
continuing to mechanically isolate every remaining `net.minecraft.*` reference.
`EntityInteractionAdapter`'s `ForgeHooks` isolation is the first small concrete proof this boundary
also helps with loader portability, not just version portability.
