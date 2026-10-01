# NestWorld Mod API — architecture freeze (Phase API-0)

Design conversation, 2026-09-25. This document is the architectural freeze point before any
`nestworld-api` code is written (Phase API-1). Per its own governing rule: **implementation
decisions must not silently become API contract.** Anything not fixed here is still open and
must not be assumed by Phase API-1's skeleton.

## Status (updated same day, post-audit)

```
API-0     ARCHITECTURE BASELINE   -- completed, needed amendment (done, see below)
API-0.5   MOD API SAFETY MODEL    -- CLOSED (see "Phase API-0.5 — CLOSED" below)
API-1     PUBLIC API SKELETON     -- Milestone 1 (API-1A.5 through 1A.8) CLOSED, API-PKG-1
                                    (packaging) CLOSED -- see "Roadmap after Milestone 1" below.
                                    Module SPI track: MODULE-0 PARTIAL -> MODULE-0.5 RESOLVED ->
                                    MODULE-1 PASS (startup-loaded SPI foundation, real code,
                                    live-verified 2026-09-30) -- MODULE-2 (hot reload) PARKED.
                                    A critical packaging regression (:forge's own deployed jar
                                    couldn't boot post-API-PKG-1) was found and fixed during
                                    MODULE-1's verification -- see docs/PACKAGING.md. NEXT:
                                    API-1B (Inventory Slot Mutation), not started.
API-2+    (Forge compat, Core integration, tests, NeoForge) -- BLOCKED
```

## Documentation/Implementation Contract (standing rule, applies from Phase API-1 onward)

**Implementation + contract documentation + tests are one change, not three.** A class is not
done with only `implementation ✓, docs ✗, tests ✗`. In concurrency code specifically,
documentation is not a description of the code — it is part of the correctness contract itself.
A future contributor who sees `route(entity)` and does not know that a routing snapshot never
grants mutation authority (finding 6 below) can write code that compiles cleanly and quietly
destroys the guarantee. The javadoc is the only thing standing between "compiles" and "correct"
in that moment — it must be written as such, not as an afterthought once the code already works.

**Definition of Done** for any class under `net.nestworld.modapi` (or any future
architecturally-significant Core/API component):

```
implementation        ✓
class-level contract  ✓   (see template below)
public API JavaDoc    ✓   (short, machine-adjacent — does not repeat the design doc)
ownership/threading   ✓
failure semantics     ✓
architecture doc entry ✓  (this document, or a dedicated component doc for larger pieces)
tests                 ✓
```

**Scope discipline**: this full contract is for *architecturally-significant* components —
things that decide ownership, cross a thread boundary, or make a safety guarantee a caller
relies on (routers, dispatchers, backends). Plain DTOs/records/utility classes do not each need
a dedicated document — that becomes documentation noise fast. They still need sufficient
class-level javadoc; they attach to whichever component document they serve, rather than getting
their own.

**Template** for an architecturally-significant class's contract (goes in the class's own
component doc if one exists, e.g. `docs/contracts/<ComponentName>.md`, and is summarized —
never fully duplicated — in the class's own javadoc, which should link back to the doc):

```
<ClassName>

Responsibility:
  One sentence: what this class routes/owns/guarantees.

Does:
  - ...

Does NOT:
  - ... (as important as "Does" -- explicit non-guarantees prevent a caller from assuming
    a safety property this class was never designed to provide)

Threading:
  caller -> ...
  mutation -> ...

Invariant:
  <the specific guarantee a caller may rely on, stated as a rule, e.g.
  "routing snapshot != mutation authority">

Failure:
  <each known failure mode, named, with its resolution -- "removed target -> ...",
  "owner changed -> ...", "hop limit exceeded -> ..." -- not a generic "throws Exception">

Lifecycle:
  <when this instance is created, what it's scoped to, when/how it's torn down -- directly
  relevant given this session's own stale-static-state findings (BackendResolver's
  WeakHashMap rejection, NestworldRegionSystem.INSTANCE's flagged singleton risk)>

Evidence/tests:
  <which evidence backs each invariant/failure-mode above. Claim without evidence is not a
  guarantee -- but for concurrency properties specifically, a passing test is necessary, not
  sufficient: 10,000 clean runs do not prove thread safety if the access model itself is wrong
  (the same reasoning GMEIL already applies -- docs/gmeil/ARCHITECTURE.md: "No policy may
  become less restrictive merely because no race was observed"). Depending on the specific
  claim, back it with whichever combination actually supports it: tests, static reasoning
  about the access pattern, an explicit invariant proof, instrumentation, stress testing, or a
  runtime assertion that would catch a violation live. State which kind(s) of evidence back
  each claim -- do not list a bare test count as if it were automatically sufficient.>
```

This rule itself governs Phase API-1: no class under `net.nestworld.modapi` is considered
finished by this document's own standard without all seven Definition-of-Done items, and no
router/dispatcher-shaped class ships without its own filled-in contract using this template.

## Phase API-0.5 findings (2026-09-25, evidence-based — no separate Module API, no execution
engine)

Two follow-up audits and a 10-case study (`EntityMutationDispatcher`, player attack, block
interaction, item-on-block, AE2 grid, Mekanism transporter, Tom's Storage, scheduled tick,
cross-region entity transfer, `NestworldRegionSystem.INSTANCE`) converged on a materially
different, smaller shape than the original API-1 draft. Central finding: **the Mod API question
was framed at the wrong level of generality.** "Concurrency compatibility for mods" is not one
problem — the case study found at least three genuinely different problem classes, each needing
a different mechanism, not one shared abstraction:

- **(A) Ownership / execution routing** — "where should this operation run?" `EntityMutationDispatcher`
  and the player-action dispatchers are this class. A Mod API is a plausible fit here.
- **(B) Shared-state concurrency** — "how do multiple threads safely see/mutate this state?"
  AE2's `GridEventBus.EVENTS` and Mekanism's `TransporterManager.flowingStacks` are this class —
  both fixed with a bare concurrent-container swap, zero dispatch/ownership/routing involved.
  Mod API routing does not solve this.
- **(C) Publication / visibility** — "how does a new state safely become visible to readers?"
  Tom's Storage (`StorageTerminalBlockEntity.items`) is this class — fixed via atomic publication
  entirely inside the mod's own two methods, zero Core dispatch. This session's own investigation
  explicitly checked and rejected `EntityMutationDispatcher` as a fit for this case (no
  read/request-response channel), for a structural reason, not because closed-set operations were
  too narrow.

**A mod-compatibility fix (any of the above) must never automatically become public Mod API.**
Conflating "how NestWorld happens to have patched AE2/Mekanism/Tom's Storage" with "what a mod
author calls through `net.nestworld.modapi`" would leak every future compatibility fix into the
frozen ABI. These stay as `NestWorld Mod Compatibility Layer` mechanisms (coremod transformers,
atomic-publish patches, pin) — never re-exposed as capabilities.

### Findings, numbered

1. **Generic arbitrary-task execution** (`submitAt`/`submitEntity` accepting a caller-supplied
   `Runnable`/`ExecutionTask<T>`) — **REJECTED** as a safe public Mod API primitive. Stands from
   the original API-1 rejection; the case study adds no counter-evidence.
2. **A universal concurrency Mod API** — **REJECTED**. Routing (A), shared-state synchronization
   (B), and publication (C) are different problem classes; no single abstraction covers all three
   without leaking unsafe generality into whichever class didn't need it.
3. **Existing mod-compatibility fixes must not become public API.** They stay in the Mod
   Compatibility Layer, not `net.nestworld.modapi`.
4. **Ownership-routed operations remain a valid Mod API candidate** — class (A) only.
5. **Preferred routing model, generalized from the item-on-block precedent**
   (`PlayerUseItemOnBlockAdapter`/`Dispatcher` — see its own javadoc on why the routing snapshot
   is read-only):
   ```
   immutable request -> determine owner -> handoff -> reacquire fresh target/state
       -> validate -> execute semantic operation
   ```
6. **Routing snapshot != mutation authority.** A snapshot may determine *where* to send an
   operation. It never grants the right to mutate anything it referenced. This is the single
   strongest architectural guarantee to come out of the case study and should govern every future
   Mod API operation shape.
7. **Capturing arbitrary mutable references across execution contexts is not a safe API
   contract** — direct restatement of finding 1/6 as a general rule, not specific to `Runnable`.
8. **Result/Handle semantics remain UNDECIDED.** Not rejected outright (case 3/4's `CountDownLatch`/
   `AtomicReference`/`claimed`-CAS shape proves a real cross-thread result CAN be delivered
   safely) — but a general `ExecutionHandle<T>` must be driven by real request/response
   operations Mod API actually needs (e.g. `OperationHandle<DamageResult>`,
   `OperationHandle<InteractionResult>`), never designed as a generic `Future`-alike first and
   retrofitted to operations second.
9. **Generic `scheduleAt` is OUT OF SCOPE** until a concrete mod use case requires it. Vanilla
   scheduled block/fluid ticks (`RegionScheduledTicks`) are a fixed, game-determined shape, not a
   precedent for arbitrary mod-requested delayed execution — confirmed by direct reading, not
   merely unfound by grep. No longer tracked as a required API-1 element pending investigation;
   tracked as explicitly out of scope pending a real need.
10. **Shared-state races (class B) remain compatibility/implementation concerns**, not API
    surface, unless real evidence shows a genuine need for an ownership abstraction over them —
    see finding 11.
11. **Mekanism multi-region logical-network ownership is the one remaining, genuinely open
    question.** `TransporterManager.flowingStacks`' own investigation left explicitly UNKNOWN
    (not confirmed either way — `docs/RACE_CLASSIFICATION.md`) whether a single Mekanism logistics
    network's own tiles can be owned by more than one NestWorld region simultaneously. This is
    the one case in the entire study that could blur the A/B boundary: if one logical network is
    actively mutated from multiple region-owner contexts at once, the existing C1 concurrent-
    container fix guarantees the `Map` itself never corrupts (**data-race-free**) but says nothing
    about whether a multi-step network-wide operation (e.g. a transfer spanning several owners)
    completes as one coherent unit (**semantically race-free** — a strictly stronger property).

### The one remaining research gate before Phase API-1 can start

> Can a single Mekanism logistics network actually span and be actively mutated from more than
> one NestWorld region-ownership context at the same time — and if so, does the existing C1
> container-swap fix provide only structural thread-safety, or does it also happen to preserve
> the semantic correctness a multi-region transfer would need?

If the answer is "yes, genuinely multi-region, and semantic consistency needs real
serialization at the logical-network level" — this is the first real evidence a **Logical
Ownership Domain** concept (an ownership unit that isn't a spatial region) is needed, and Phase
API-0.5 continues with that concept in scope.

If the answer is "no" (either genuinely single-region in practice, or multi-region but the
existing per-key atomicity already happens to be sufficient) — Phase API-0.5 closes with a
materially simpler design: no `ExecutionDomain`, no generic executor, no second scheduler,
routing-only Mod API per findings 4-7 above.

### Research gate — answered (2026-09-25)

**Part 1 (can a network genuinely span multiple regions?)**: conditionally yes, but narrower than
it first looked. `WorldGrid`/`WorldRegion`'s chunk-granular, axis-aligned split model
(`WorldGrid.java:43-51`, `WorldRegion.java:589-594`) makes it geometrically possible for any
network straddling a future cut line. But `RegionSplitManager`'s own point-hotspot split-veto
(`RegionSplitManager.java:645-733`, `MAX_CUT_BAND_SHARE = 0.25`) — the *same* mechanism already
confirmed protecting the SkyBlock chicken-farm point-hotspot from being bisected — specifically
protects dense/busy tile clusters (exactly what an actively-used Mekanism factory generates) from
being split. A network is realistically split-prone only when it's large, spatially spread, and
comparatively idle (e.g. one long connecting pipe run between distant bases) — a narrower,
specific scenario, not "any Mekanism factory."

**Part 2 (does correctness hold if it does span regions?)**: yes. `InventoryNetwork` (decompiled
in full) is a pure topology/membership registry (`addTransmitter`/`removeTransmitter`/`commit`/
`deregister`, plus the single-key `calculateAcceptors` query already established in
`docs/RACE_CLASSIFICATION.md`) — it has no network-wide tick or atomic multi-tile operation to
violate. Every real state transition is Mekanism's own per-tile tick
(`LogisticalTransporterBase`), independently re-validated against real inventory state at the
moment of actual delivery, with an existing reject/reroute fallback built for ordinary
multi-tick transit staleness in vanilla — unrelated to and predating NestWorld. A hop ticked by
region A followed by the next hop ticked by region B is, from this validation logic's own
perspective, indistinguishable from "the same tile took one more real-time tick" — exactly the
staleness class Mekanism's own design already tolerates by construction.

**Result: no evidence found for a Logical Ownership Domain requirement.** Phase API-0.5 closes
with the simpler design from findings 4-7. Not investigated as part of this gate, flagged for a
separate future pass if ever relevant: `additionalFlowingStacks`'s producer (a pre-existing,
unrelated UNKNOWN already on record in `docs/RACE_CLASSIFICATION.md`), and Mekanism's other
still-open races (`PathfinderCache`, `FrequencyManager`) — neither re-examined for this same
multi-region question, since the gate as posed was specifically about `flowingStacks`/
`InventoryNetwork`.

## Phase API-0.5 — CLOSED (2026-09-25)

No Logical Ownership Domain, no generic executor, no second scheduler. The Mod API's scope is
routing-only (class A from the findings above), built on the item-on-block precedent (finding 5),
governed by "routing snapshot != mutation authority" (finding 6). `net.nestworld.modapi` is now
unblocked for an actual minimal-surface design pass (not yet started) — mod-compatibility fixes
(class B/C) stay in the Mod Compatibility Layer, never exposed as API capabilities.

Closed by evidence from real runtime/mod behavior (the 10-case study, the Mekanism gate), not by
inventing a universal abstraction and hoping it covers every case. The Mekanism result
specifically removed the last real basis for introducing a Logical Ownership Domain now:
multi-region topology is possible, but network semantic consistency does not require a single
owner — C1 solves exactly the shared-container-concurrency problem it needed to. Adding logical
domains now would have been speculative architecture, built ahead of any demonstrated need.

```
CONFIRMED:
  - generic arbitrary Runnable API rejected
  - routing != shared-state synchronization
  - routing != safe publication
  - routing snapshot != mutation authority
  - semantic operations may use ownership routing
  - target/state is reacquired and revalidated at execution
  - existing mod race fixes do not automatically become Mod API
  - Logical Ownership Domain is NOT currently required
  - generic scheduleAt has no demonstrated requirement
  - ExecutionHandle has no demonstrated generic requirement

MUST NOT INTRODUCE WITHOUT NEW EVIDENCE:
  - generic submitAt(Runnable)
  - generic submitEntity(Runnable)
  - universal ExecutionContext
  - logical ownership domains
  - second generic scheduler
```

### What this means for API-1: vertical, not the abstraction first

Do not return to the original eight-class list (`ExecutionBackend`, `ExecutionTask`,
`ExecutionContext`, `ExecutionHandle`, `BackendResolver`, ...). API-1 starts over from the first
real capability, not from a speculative SPI shell — that ordering (abstraction drafted before any
concrete capability proved it necessary) is exactly what produced the rejected first draft.

**First vertical slice: entity operation** — the case with the most evidence behind it
(`EntityMutationDispatcher`, finding 1 of the case study):

```
public Mod API
      |
semantic entity request
      |
Core adapter
      |
EntityMutationDispatcher
      |
resolve owner -> handoff -> reacquire/revalidate -> operation
```

Split into sub-phases, each a real deliverable, not a planning fiction:

```
API-1A  Entity capability contract        (the request/response shape, the invariants, the
                                            Documentation/Implementation Contract filled in --
                                            this becomes the reference implementation every
                                            later capability is built against)
API-1B  Forge 1.20.1 compatibility impl   (net.nestworld.platform.forge1201's side of it)
API-1C  NestworldCore implementation      (routes through EntityMutationDispatcher for real)
API-1D  Dedicated / integrated / Core tests
API-1E  Documentation/evidence closure    (Definition of Done, all 7 items, for this one slice)
```

**Only after this full slice is done** does the design ask: *"What duplicated implementation
pattern appeared across Forge-compat and Core?"* → *"Does it justify an internal abstraction?"*
Extraction follows repetition; repetition does not follow a pre-built abstraction. This is the
direct fix for how the original API-1 draft went wrong: an `ExecutionBackend`/`BackendResolver`
shape existed before any single capability had proven it was the right shape.

A second and third capability (block interaction is the next-best-evidenced candidate, per the
case study) follow the same A→E slice structure. Only once two or three slices land in a
noticeably repeated internal shape does extracting a shared internal SPI become a decision made
from evidence, not from guessing ahead of it — matching exactly how `EntityMutationDispatcher`
itself was proven out before this whole design conversation started.

### API-1A narrowed and specified (2026-09-25) — entity/damage variant, DEFERRED (2026-09-28)

**Superseded as the API-1A.0 choice by the decision below (API-1A.3) — kept as-is, not deleted,
because the routing-mechanism reasoning (target identity, UUID insufficiency, delivery-semantics
blocker) is generic and directly reusable when `damage()` is revisited as a later slice.**
`docs/contracts/EntityOperationRouter.md` is correspondingly parked, not active work.

API-1A.0 is exactly one public operation: `net.nestworld.modapi.entity.NestWorldEntities.damage(
Entity, DamageSource, float)`. The public facade accepts a live `Entity`/`DamageSource` for
mod-author ergonomics but strips both to identity + immutable value data at the facade boundary —
neither crosses into the async handoff. The internal routing mechanism behind it,
`EntityOperationRouter`, is a Core-internal contract, not public ABI — full specification in
**`docs/contracts/EntityOperationRouter.md`**, including:
- why `MinecraftServer` is runtime scope passed alongside a request, not part of target identity;
- why `UUID` alone is insufficient for entity lookup (confirmed against
  `WorldGrid.findOwningRegion`'s per-dimension scoping) and why dimension migration is a distinct
  internal termination reason from removal, not collapsed like `EntityMutationDispatcher`
  currently collapses them;
- **the DamageSource gate**: `causingEntity`/`directEntity` were confirmed, by direct trace
  through `LivingEntity.java`, to be actively *mutated* during normal damage application
  (`setLastHurtByMob`, `awardKillScore`), not merely read — carrying the live `DamageSource`
  unchanged across a region handoff is a demonstrated cross-region write hazard, not a
  theoretical one. `DamageRequest`'s payload therefore carries `Holder<DamageType>` and
  `Vec3` position directly (value-safe) but only UUIDs for `causingEntity`/`directEntity`,
  reconstructed fresh on the owner thread immediately before applying. This is stricter than
  `EntityMutationDispatcher`'s own current `Damage(DamageSource, float)` record, which still
  carries the live `DamageSource` as an accepted, documented tradeoff — that existing mechanism
  is unchanged by this document; whether to tighten it is a separate decision, not made here.
- delivery semantics remain an explicit, open blocker (not best-effort/at-most-once/exactly-once
  until reroute-vs-apply race behavior is verified);
- server-shutdown behavior is explicitly `UNRESOLVED for API-1A`, not assumed.

Six of `EntityMutationDispatcher`'s seven ops (`AddEffect`, `Ignite`, `ExtinguishFire`,
`Knockback`, `Kill`, `Discard`) are deliberately NOT part of API-1A — each needs its own
use-case justification before joining the public surface (API-1E), per the same rule that
blocked a blanket copy of internal capabilities into public API.

### API-1A.3 — Damage semantic boundary audit (2026-09-25) — OPEN, blocks API-1B

The DamageSource gate fixed *staleness* (UUID reconstruction instead of carrying a live
reference). It did not fix *mutation authority*. A full trace of `LivingEntity.hurt()`/`die()`
and everything reachable from them (real buildable source, not just the patch diff — NestWorld
patches only two hooks into this file today, `EntityOwnershipGuard.check` in `actuallyHurt` and
unrelated collision code, so everything else below is un-adapted vanilla+Forge behavior) found:

- **One confirmed `CROSS_OWNER` write, not theoretical**: `die()` calls
  `getKillCredit().awardKillScore(this, ...)` (`LivingEntity.java:1309`) — when the credited
  entity is a `ServerPlayer`, this mutates the ATTACKER's own scoreboard/stat/criteria state
  (`ServerPlayer.java:637-654`) on the TARGET's owner thread, with zero ownership check.
  Reconstructing `causingEntity` fresh by UUID on the target's thread does not prevent this — it
  makes the write correct-data but still cross-owner.
- This is **not a new hazard API-1A would introduce** — `Player.attack()`'s already-shipped path
  (`EntityMutationHelper.redirectFromMainThread` → `EntityMutationDispatcher.applyDirect` →
  `entity.hurt(source, amount)`, unchanged `DamageSource`) hits the exact same `awardKillScore`
  site today, in production, whenever attacker and target are owned by different regions.
  `EntityOwnershipGuard` does not catch it (scoped only to the target argument at every call
  site); `PlayerAttackDispatcher`'s region-resolution-by-attacker-position doesn't either (it
  protects the target side via the existing redirect, never checks the causing entity's owner).
- 11 separate `CALLBACK_UNKNOWN` sites: Forge fires `LivingAttackEvent`, `ShieldBlockEvent`,
  `LivingHurtEvent`, `LivingDamageEvent`, `LivingUseTotemEvent`, `LivingDeathEvent` (can veto
  death outright), `LivingDropsEvent`, `LivingKnockBackEvent`, `LootingLevelEvent` (also reads
  the killer's held-item enchantments cross-thread), an experience-drop event, and a
  mob-griefing gate — each dispatches to arbitrary `@SubscribeEvent` listeners across every
  loaded mod, unboundable by static analysis. No `MAIN_ONLY` sites found beyond what the
  existing routing model already assumes.

**Direct answers**: `damage()` is confirmed NOT single-owner once `awardKillScore` is accounted
for — full correctness needs either a bounded second-hop reroute of the kill-credit write to the
causing entity's actual owner (matches this project's existing fire-and-forget reroute idiom,
not a two-phase commit), or an explicit, documented decision to ship it as a known residual
hazard. Given the callback surface size and the confirmed second cross-owner write,
`damage()` is judged a **weaker first vertical-slice choice than block/item-on-block
interaction** (`PlayerUseItemOnBlockDispatcher`) — that operation resolves ownership by block
position, has no second independently-owned entity, and needs no such fix. This finding is a
direct consequence of API-0.5's own "routing snapshot != mutation authority" rule (§ above) —
the case study that produced that rule already had the evidence to flag `damage()` before it
was picked.

`EntityMutationOp.java`'s "theoretical residual read-hazard" javadoc has been corrected in code
to reflect this (confirmed-but-rare, not theoretical) — see the class itself for the updated
wording and citations.

**Decided (2026-09-28): (b) — swap the first vertical slice to block/item-on-block interaction.**
**Status of `damage()` is precisely DEFERRED — semantic boundary not proven — not "unsafe API."**
API-1A.3 proved one concrete `CROSS_OWNER` path (`awardKillScore`) and enumerated unbounded
`CALLBACK_UNKNOWN` sites; it did NOT prove that a safe contract for `damage()` is impossible in
principle. Revisit once a two-hop reroute pattern for cross-owner side effects (the
`awardKillScore` case) is proven on a real slice first, rather than proving that pattern for the
first time on the capability that most needs it. `docs/contracts/EntityOperationRouter.md`
stays as parked reference material — do not build against it until `damage()` is reopened.

### API-1A.0 — SWAPPED to block/item-on-block interaction (2026-09-28)

New first vertical slice: `net.nestworld.modapi.block.NestWorldBlocks.useItemOn(...)` (exact
public signature TBD in the next design pass), backed internally by the existing
`PlayerUseItemOnBlockDispatcher`/`PlayerUseItemOnBlockAdapter` pattern
(src/main/java/net/nestworld/region/PlayerUseItemOnBlockDispatcher.java,
net/nestworld/adapter/PlayerUseItemOnBlockAdapter.java). Chosen over `damage()` because:
- ownership resolves by **block position** (`resolveOwnerPos`) — exactly one owning region,
  no second independently-owned entity whose state gets mutated as a side effect;
- no analog to `awardKillScore` found in the existing dispatcher's call graph (not yet
  independently re-audited with the same rigor as API-1A.3 — that pass is still owed before
  this contract is written, not assumed clean by default);
- smaller, though not zero, Forge callback surface than `hurt()`/`die()`'s 11 sites.

**Not yet done**: the actual `docs/contracts/` write-up for this operation (mirrors
`EntityOperationRouter.md`'s shape: target identity, payload shape, threading, delivery
semantics, failure modes, evidence/tests) and a dedicated boundary audit of
`PlayerUseItemOnBlockDispatcher`'s own call graph equivalent to API-1A.3, before treating this
as contract-final. Per the Documentation/Implementation Contract standing rule above, that audit
and the contract doc happen before any code, same as `damage()`'s slice did.

### API-1A boundary audit #2 — block interaction — ALSO NOT single-owner (2026-09-28)

The owed audit above was run with API-1A.3's exact rigor. Result: **MULTI_OWNER, compounded by
an UNBOUNDED_CALLBACK_GRAPH — not SINGLE_OWNER.**

`PlayerUseItemOnBlockDispatcher` does not carry live references across its async handoff itself
(target block state is correctly captured/routed/reacquired/revalidated — the literal
`DamageSource` mistake is avoided). But `applyQueued` reacquires a *live* backing `ItemStack`
straight from the player's inventory (`UseOnContext`→`Player.getItemInHand`→
`LivingEntity.getItemBySlot`) and mutates it in place — durability, stack count — on the
BLOCK-owner thread, with no ownership check against the PLAYER's own owning region. Every
successful interaction also unconditionally fires `player.awardStat`/`CriteriaTriggers.*`
(`ITEM_USED_ON_BLOCK`, `PLACED_BLOCK`, `ITEM_DURABILITY_CHANGED`) against the player object —
same shape as `awardKillScore`, except firing on EVERY interaction, not just a kill. Confirmed
distinct from routing-key ownership: the routing `BlockPos` determines execution location, but
player-owned state is mutated as an unconditional side effect regardless. `EntityOwnershipGuard`
(the one existing detection mechanism for this class of hazard) is wired only to `hurt`/entity
position/removal — not to any `ItemStack` mutation or `Player.awardStat`/criteria path. Beyond
that: arbitrary mod `Item.useOn()` implementations, `setPlacedBy` hooks, and placement-event
listeners are `CALLBACK_UNKNOWN`, unbounded, same as `damage()`'s Forge-event surface. One real
asymmetry found in block interaction's favor: block-side writes (including a confirmed
adjacent-position case, `BedBlock.setPlacedBy` writing a second block at `pos.relative(FACING)`,
off the routed position) ARE already caught by a generic, pre-existing choke-point guard
(`Level.setBlock()`, `Level.java:230-246`) that has no analog for `damage()`'s `awardKillScore`
— real, but insufficient to make the operation single-owner overall, since the player-state and
callback-surface hazards are unguarded either way.

**Per this project's own explicit stopping rule**: this is the SECOND independently-audited real
Minecraft operation with a distinct actor/target shape to reduce to
`single-owner routing key → multi-owner / unbounded semantic operation`. The correct next step is
NOT a third candidate — trying another player-triggered interaction should be expected, by
default, to reproduce the same shape, since "actor mutates its own state as a side effect of a
target-routed operation" is structural to how vanilla Minecraft's interaction call paths are
written, not particular to either capability audited so far. **API-1A's own premise — that some
Minecraft operation has bounded single-owner semantics for a Mod API to build a first vertical
slice on — is challenged by evidence, not merely unproven.** API-1A is paused pending a decision
on how to proceed (see the architecture owner's directive covering this exact fork-in-the-road,
tracked in session history): whether to design toward multi-owner-aware routing (e.g. the
two-hop reroute pattern already floated for `damage()`, generalized), to scope the Mod API's
first surface down to genuinely single-owner primitives narrower than a whole vanilla
interaction (e.g. a raw block-state write with no actor-side effects), or to reconsider whether
whole-operation safety is the right guarantee for this API tier to offer at all.

## API-1 model correction (2026-09-28) — the actual result of the two failed audits

`damage()` and `useItemOnBlock()` were not failed attempts to be discarded. Their common failure
shape IS the result — it locates exactly which abstraction level NestWorld's Mod API can offer a
strong concurrency guarantee at.

**The governing finding, stated precisely, and the single most important result of the API
research so far:**

> **Routing ownership is a property of the dispatch target. Mutation authority is a property of
> the entire transitive operation graph.**

Both audited operations have the same shape — a semantic operation splits into a TARGET (owner
B) and an ACTOR (owner A), both sides get mutated, and the operation additionally passes through
an unbounded Forge/mod callback graph reaching unknown state:

```
             semantic operation
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
       TARGET               ACTOR
    target entity         attacker/player
    target block          inventory/stats
          │                   │
       owner B             owner A
          │                   │
          └─────────┬─────────┘
                    │
              Forge/mod hooks
                    │
                    ▼
              UNKNOWN state
```

`EntityMutationDispatcher` and `PlayerUseItemOnBlockDispatcher` correctly deliver the *start* of
an operation to the target's owner. The problem was never routing quality — it's that a vanilla
+ Forge semantic operation is itself a transitive mutation graph, and routing only ever bounds
one node of it.

### The corrected model: primitives vs. semantic orchestration

Not two flat capability tiers (`NestWorldEntities.damage()`, `NestWorldInteractions
.useItemOnBlock()` as foundational safety primitives) and not, on the other extreme, a generic
multi-owner transaction engine built to absorb any operation shape. A split:

```
                  MOD API
                     │
          ┌──────────┴──────────┐
          │                     │
   SAFE PRIMITIVES       SEMANTIC OPERATIONS
          │                     │
   bounded mutation       orchestration layer
   known authority         (future/research)
          │                     │
          ▼                     ▼
      owner A/B/...       several primitives
```

**Level 1 — Bounded Mutation Primitives (API-1A's real, corrected target).** Formal definition:

> **Bounded Mutation Primitive** — an operation for which Core can statically enumerate every
> mutable ownership domain it touches (not necessarily exactly one — a primitive may have
> multiple explicit, enforceable targets) and which contains no unbounded arbitrary-mod-callback
> graph.

Contract shape (same skeleton as `EntityOperationRouter`'s, generalized):

```
request → target identity → resolve owner(s) → handoff → fresh reacquisition → bounded mutation
```

Guarantee this tier can actually make, machine-enforceable, not a documented convention:

> An API primitive does not execute caller-supplied or mod-supplied code, and does not mutate
> state outside its documented mutation boundary.

Candidate shape (concrete signatures TBD, not committed): `blocks.setState(...)`,
`entities.setVelocity(...)`, `player.awardStat(...)`, `inventory.consume(...)` — **each only
becomes a real primitive after its own boundary audit**, same rigor as `damage()` and
`useItemOnBlock()` got, not granted by name. Audit #2 already found `Level.setBlock()` itself
can trigger neighbor-update behavior — it is a candidate to inventory, not an assumed-clean
primitive.

**Level 2 — Semantic Orchestration (explicitly NOT built now — future research only).**
`damage()` and `useItemOnBlock()` move here. An orchestration is a sequence of primitive phases
plus explicitly-untrusted callback phases:

```
SemanticOperation
    ├── phase target owner B     (a Level-1 primitive)
    ├── phase actor owner A      (a Level-1 primitive)
    ├── callback phase ?         (untrusted, unbounded — no guarantee offered)
    └── completion
```

This tier is not promised single-owner safety, now or as a near-term goal. The two-hop reroute
pattern floated for `awardKillScore` solves one edge of this graph, not the general orchestration
problem — do not generalize it prematurely (see below).

### Why not build multi-owner routing now (the rejected middle option)

Generic multi-owner routing looks tempting but degrades into a distributed transaction engine:

```
owner A → owner B → callback C → owner D → back to A
```

which immediately requires solving ordering, partial completion, deadlock, rollback, entity
migration during flight, callback reentrancy, lifecycle/shutdown mid-operation, duplicate
suppression, and inter-phase failure — none of which this project has evidence for yet. Worse,
it wouldn't even close the actual gap: a Forge callback (`@SubscribeEvent void onHurt(
LivingHurtEvent e) { /* arbitrary mod code */ }`) can touch arbitrary state Core cannot enumerate
in advance. Better routing does not bound an unbounded callback graph — that's a category error,
not an engineering gap to close with more routing sophistication.

### The corrected guarantee — precise, not weaker

**Retired** (too strong, poorly specified, refuted by two independent audits):
> Mod API makes Minecraft operations concurrency-safe.

**Replacement:**
> NestWorld Mod API provides bounded mutation capabilities for which Core can enforce documented
> ownership and threading invariants. An operation that enters the vanilla/Forge/mod callback
> graph is not declared a bounded primitive without separate proof.

This is a precision correction, not a downgrade of ambition — the old claim was never actually
true for `damage()` in production (the `awardKillScore` hazard already existed, unflagged, before
this design conversation started); the new claim is one this project can actually keep.

### API-1A.4 — Primitive Boundary Inventory (2026-09-28) — CLOSED, first positive result

Not a search for a third whole Minecraft operation. A systematic classification pass over
existing Core mutation surfaces, same transitive-call-graph-tracing rigor as the
`hurt()`/`useItemOnBlock()` audits (**not name-based classification**). Result (real source,
`projects/forge/src/main/java/net/minecraft/...` for vanilla, file:line evidence per row kept in
the full audit transcript, session history):

| Primitive candidate | Mutation set statically bounded? | Calls mod code? | Cross-owner possible? | Existing guard/router? |
|---|---:|---:|---:|---:|
| `Ignite`/`ExtinguishFire` (`Entity.setSecondsOnFire`/`clearFire`) | **YES** — one field | NO | NO | `EntityMutationDispatcher` — **live, shipped** |
| `Knockback` | NO | **YES** — `LivingKnockBackEvent`, pre-mutation, cancelable | n/a (disqualified upstream) | routed, not bounded |
| `Kill`/`Discard` | NO | **YES** — `stopRiding()`/`removeVehicle()` mutates a SECOND entity's `passengers`/`vehicle` with no ownership check, plus `canMountEntity` hook | **YES** — same shape as `awardKillScore`, ride-linkage axis | none effective |
| `AddEffect` | NO | **YES** — `MobEffectEvent.Applicable`+`.Added` | n/a (disqualified upstream) | routed, not bounded |
| `Level.setBlock()` (default flags) | PARTIAL — bounded only with `UPDATE_KNOWN_SHAPE` explicitly set | **YES** by default — `neighborChanged` cascade, mod-overridable, up to 2 blocks × 6 directions | **YES**, mitigated not eliminated | Stage-3 choke-point — write-authorization only |
| BlockEntity mutation | NO | **YES** — same cascade as above via `setChanged()` | **YES**, same mechanism | none |
| Player inventory raw slot ops (`setItem`/`add`/`removeItem`) | **YES** — own compartments only | NO | NO | none yet, trivial to add |
| `ItemStack` mutation (`setCount`/`shrink`/`grow`) | **YES** — pure field write | NO | N/A — **no location/owner identity at all** | none, and **none possible as a bare-`ItemStack` API** — see open question below |
| Stats/criteria (`awardStat`/`CriteriaTriggers`) | NO | ambiguous/effectively unbounded (data-driven) | N/A — **unowned global singletons** (`Scoreboard`, per-trigger-type listener map), not a second entity — a genuinely new hazard class, not previously catalogued | none |
| Movement: raw `setDeltaMovement`/`setPosRaw` | **YES** — one `Vec3`/position write | NO | NO | `EntityOwnershipGuard` hooked at exactly this chokepoint — **detection only, not enforcement** |
| Movement: `move()` (the full method) | NO | **YES** — `entityInside`/`stepOn`, arbitrary mod `Block` override, once per overlapping block per tick | YES-ish | none |
| Scheduled-tick registration | **YES** | NO | NO | `nestworldEnqueueScheduleTick` — **live enforcement**, not just detection |
| Scheduled-tick firing | NO | **YES** — arbitrary mod `Block`/`Fluid` override | YES-ish | none |
| Capability mutation (`IItemHandler` et al.) | bounded only for the unsubclassed base class in isolation; **NO by construction** for the general interface | **YES by construction** | undeterminable — backing object opaque to Core | none, none possible for the general interface |

**PASS all five bars** (`bounded mutation graph=YES`, `arbitrary mod callback=NO`,
`ownership determinable=YES`, `fresh reacquisition possible=YES`, `Core enforcement
possible=YES`): `Ignite`/`ExtinguishFire` (already shipped), raw `setDeltaMovement`/`setPosRaw`,
player inventory raw slot ops, scheduled-tick registration.

**Recommended first real API-1A primitive: `Entity.setDeltaMovement` →
`net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity, Vec3)`.** Cleanest trace of
any candidate (one field write, zero Forge events, zero neighbor cascade, zero cross-owner
touch), sits at `EntityOwnershipGuard`'s existing detection chokepoint (needs enforcement, not
new design), fits `EntityMutationDispatcher`'s existing closed-op-set pattern as one more case
(no new architecture), and has real mod-author utility (launch pads, knockback-style effects).
Secondary candidates worth carrying forward: player inventory raw slot ops (highest mod-author
utility on the list, needs a small new dispatcher mirroring `EntityMutationDispatcher`) and
promoting the already-shipped `Ignite`/`ExtinguishFire` to public API essentially as-is.

**Open design question, not a flat fail**: `ItemStack`'s own mutation IS bounded, but `ItemStack`
carries no identity to freshly reacquire by — a bare-`ItemStack`-accepting primitive would just
be a captured-reference escape hatch, the exact hazard already rejected earlier in this document.
Needs a slot-handle wrapper (inventory+index, or BE+index) design before it can pass, not
abandonment.

**New finding, outside API-1A's immediate scope, worth its own tracking**: stats/criteria mutate
*unowned global singletons* (`Scoreboard`, `SimpleCriterionTrigger`'s per-type listener map) from
region threads with no synchronization model — a hazard class distinct from every cross-ENTITY
hazard found so far in this session (`awardKillScore`, ride-linkage, neighbor cascades). Not yet
filed against `docs/RACE_CLASSIFICATION.md`'s standing taxonomy — flagged here, not silently
dropped, pending a decision on priority.

**Not a third consecutive negative result.** The two prior audits' negative results stand for
*whole semantic operations*; dropping to the *leaf-mutation* level, as the corrected model
predicted, does surface genuine Bounded Mutation Primitives.

### API-1A.5 — `setVelocity` contract (2026-09-28) — CLOSED, code not yet written

Chosen primitive: `Entity.setDeltaMovement(Vec3)` → `net.nestworld.modapi.entity.
NestWorldEntities.setVelocity(Entity, Vec3)`, reusing `EntityMutationDispatcher`/
`EntityMutationOp` verbatim (the same shipped mechanism `Ignite`/`ExtinguishFire` already use)
as one new `SetVelocity(Vec3)` case — **no new routing architecture**. Full normative contract:
**`docs/contracts/SetVelocityPrimitive.md`**. Highlights not to lose track of:

- `Vec3` confirmed genuinely immutable, no live-reference hazard of any kind (contrast with
  `DamageSource`) — safe to carry across the mailbox handoff completely unchanged.
- Delivery semantics are now **confirmed**, not TBD: best-effort, at-most-once, **NOT**
  never-lost. Two real silent-loss paths exist in the shipped mechanism this primitive
  inherits: target-not-found-at-apply (collapses removed/dimension-changed identically, no
  log) and no-mailbox-drain-on-shutdown (Core-wide gap, not new). Documented as fact, not
  promised away.
- **Corrected a standing doc inaccuracy**: this document previously described the (unbuilt)
  Mod API routing singleplayer through a dedicated-server-only region backend with a
  singleplayer `CompatibilityBackend` fallback. Direct trace confirms `NestworldRegionSystem`
  has **zero** dedicated/integrated branching today — full region sharding activates
  unconditionally on singleplayer too. `setVelocity` behaves identically on both server types
  *because that's what actually happens*, not because a fallback tier was designed and chosen.
  Whether to build a real compatibility-fallback tier for singleplayer later is a separate,
  still-unopened question.
- **Portability without NestWorldCore is an unresolved blocker, not a solved requirement.**
  Confirmed concretely (not just cautioned): no Gradle module seam exists — `net.nestworld.*`
  compiles into the same single `:forge` output jar as patched vanilla/Forge (`settings.gradle`,
  `build.gradle:905-913`); the two existing "optional dependency" precedents in this codebase
  (`NestworldFabricChunkEventsCompat`, `SparkBridge`) run the wrong direction (Core tolerating
  an absent third-party library, not a mod-facing API tolerating absent Core); the closest
  analog (`net.nestworld.api.NestworldApi`) has correct signature-level discipline but
  unguarded Core calls in its method bodies, meaning it would `NoClassDefFoundError` at first
  invocation with Core absent, not at class-load — untested in practice either way;
  `net.nestworld.modapi` doesn't exist as a package yet. **API-1A.6 ships Core-required only.**
  A real classloading experiment (not more code reading) is required before this configuration
  can be claimed to work — tracked as an open blocker, not assumed solvable by convention.

**Implementation plan (API-1A.6, not started):**
1. Add `record SetVelocity(Vec3 velocity) implements EntityMutationOp {}` to
   `EntityMutationOp.java`'s sealed interface (one new import: `net.minecraft.world.phys.Vec3`).
2. Add one `applyDirect` case in `EntityMutationDispatcher.java`: `entity.setDeltaMovement(
   sv.velocity())` — same shape as the existing `Ignite`/`ExtinguishFire` cases.
3. Write `net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity, Vec3)`: captures
   identity, calls `EntityMutationDispatcher.dispatch(entity, new EntityMutationOp.SetVelocity(
   velocity))`. No dedicated internal router class — unjustified for one primitive that maps
   directly onto an existing dispatcher case (would repeat API-1's original abstraction-before-
   repetition mistake).
4. API-1A.7: write the 11 evidence/tests listed in `docs/contracts/SetVelocityPrimitive.md`'s
   closing section — explicitly including tests that assert the CURRENT gap behaviors (dimension-
   change-collapses-to-removed, singleplayer-behaves-identically-to-dedicated) rather than
   aspirational fixed ones, so a future silent regression is caught either direction.
5. Explicitly deferred, not part of API-1A.6/1A.7: any Forge-without-NestWorldCore code path or
   test — blocked on the classloading experiment below.

**Unresolved blockers (tracked, not silently dropped):**
- Portability without NestWorldCore — **now API-1A.5P, dispatched** (see below). No longer just
  "needs a classloading experiment someday" — an actual PoC is running.
- `TARGET_CHANGED_DIMENSION`/`TARGET_REMOVED` collapse in `EntityMutationDispatcher` — inherited,
  Core-wide, affects every existing op (`Damage`, `Ignite`, etc.), not introduced by this
  primitive; still not fixed.
- No mailbox drain on `RegionThread.shutdown()` — Core-wide silent-loss path. **Now separately
  tracked as its own entry**, `docs/RACE_CLASSIFICATION.md` item 15 (runtime reliability debt,
  not a data race in the classic sense — flagged as its own category, not force-fit into the
  file's P0-P2 race-priority scheme). Not blocking API-1A.
- `GLOBAL_SHARED_UNOWNED` (`docs/RACE_CLASSIFICATION.md`, `Scoreboard`/`SimpleCriterionTrigger`)
  — confirmed not blocking this primitive, tracked separately, priority not yet decided.
- Whether singleplayer should eventually get a real compatibility-fallback tier, now that this
  document's prior description of one already existing has been corrected to "does not exist."

### API-1A.5P — Mod API Portability/Classloading Feasibility (dispatched 2026-09-28) — GATES API-1A.6

**Sequencing correction**: API-1A.6 (production implementation) does NOT start until this gate
reports a result. Building `net.nestworld.modapi.entity.NestWorldEntities` without a settled
artifact/classloading boundary risks the package silently becoming "the Core jar's own API" —
exactly the failure mode this gate exists to catch before it's expensive to unwind, not after.

Standing correction to carry into every future contract, discovered as a side effect of this
sequencing decision: **execution model (vanilla vs. region-sharded) and physical environment
(dedicated vs. integrated) are two independent axes, not one.** NestWorld always uses region
sharding on BOTH physical environments — "integrated" never implied a fallback execution model;
that implication in earlier drafts of this document was simply wrong (see
`docs/contracts/SetVelocityPrimitive.md`'s "Standing correction" box for the full diagram). State
both axes as separate, independently-verified claims in every future primitive contract.

**Scope, explicitly bounded**: a minimal throwaway proof-of-concept, NOT a NestWorldCore
modularization project and NOT a migration of production `net.nestworld.modapi` code (that
package still doesn't exist in `src/main/java` — the PoC lives entirely outside it, disposable).
Must answer, with actual build/run/classloading evidence (not code-reading alone):
1. Can an API artifact actually load without Core present?
2. Which Minecraft/Forge types are admissible in its ABI?
3. Can `Entity`/`Vec3` appear in public signatures without a Core dependency?
4. On ordinary Forge (no Core), who executes `setVelocity`, and on which thread?
5. How does Core install/substitute its implementation?
6. When is that implementation installed and removed?
7. What happens if the API is called before server start or after shutdown?
8. Does the same API artifact work on both dedicated and integrated Forge?

No SPI/ServiceLoader/registry unless the experiment proves one is needed — start with the
smallest mechanism the actual lifecycle constraints demand. No reflection used to paper over an
incorrect dependency boundary — the prototype's compile-time dependency graph must genuinely
exclude `net.nestworld.region.*`/`net.nestworld.adapter.*`/`EntityMutationDispatcher`/any
patched NestWorld class, verified (e.g. via `jdeps` or equivalent), not merely asserted.

**Result: PASS — feasible cleanly (2026-09-28).** Real PoC, real build/run/`jdeps` evidence, not
code-reading alone — full artifacts left in the session's scratch directory, disposable, nothing
touched under `src/main/java`/`patches/`/git-tracked files, no live/production server or world
touched (two pre-existing, already-isolated test rigs on this box were copied from, not run
in-place). Interpretation of "ordinary Forge without NestWorldCore" used: genuine stock,
unpatched Forge 1.20.1 (47.4.20) — the strictest reading, no ambiguity about patched-vanilla
counting as "Core."

- **Artifact loads with Core genuinely absent** — confirmed on stock Forge 47.4.20 AND on the
  real NestWorld-patched runtime (47.4.218) with zero `net.nestworld.region`/`adapter` classes
  on the classpath either way; class construction/linking succeeded both times.
- **ABI is Core-free, proven not asserted**: `jdeps` on the prototype `NestWorldEntities` shows
  its only non-JDK dependencies are `net.minecraft.world.entity.Entity` and
  `net.minecraft.world.phys.Vec3` — nothing else resolves, confirming the compile-time boundary
  actually holds.
- **Forge-only fallback (no backend installed)**: calls `entity.setDeltaMovement(velocity)`
  inline, synchronously, on the caller's own thread — no dispatch, no new thread. This answers
  "who executes it on ordinary Forge, and on which thread" directly from the built code, not a
  design intention — the fallback path triggers exactly when the static backend field is `null`.
- **Cross-boundary install, real not simulated**: a separately-compiled `CoreVelocityBackend`
  (the only file in the whole PoC referencing `net.nestworld.region.*`, substituting the
  already-shipped `Ignite` op since `SetVelocity` doesn't exist in production yet) genuinely set
  the neutral modapi jar's static backend field from Core-side code, observed in a real log line
  crossing the two independently-compiled artifacts. Install fires on `ServerStartingEvent`;
  uninstall fires on `ServerStoppingEvent`, confirmed from an earlier run of the same harness.
  Before-start/after-stop calls were reasoned through the install/uninstall design (both land on
  the Forge-only fallback by construction, no crash) rather than executed under a scripted timing
  harness — flagged honestly as reasoned, not run.
- **Same artifact, both physical environments**: identical step-for-step behavior on 47.4.20 and
  47.4.218, including hitting an identical downstream harness issue at the identical point (see
  next paragraph) — environment-independent for everything the artifact itself controls.

**Honest gap, not hidden**: true end-to-end runtime execution of `setDeltaMovement`/
`EntityMutationDispatcher.dispatch` inside a live server was not observed — every vanilla
(`net.minecraft.*`) symbol the test-mod scaffolding touched at runtime threw
`NoSuchMethodError`/`NoSuchFieldError`, while every Forge-owned symbol worked every time. Root
cause diagnosed, not hand-waved: **build-version skew** between this repo's current compiled HEAD
(47.4.233) and the two borrowed pre-existing test rigs' older bundled server jars (47.4.20 /
47.4.218) — an artifact of reusing existing test infrastructure across versions, affecting both
Core-present and Core-absent runs equally, so it does not undermine the comparison the gate
actually needed to make. **Recommendation carried into API-1A.6**: build/test the real
implementation against this repo's own freshly-built jar, not a borrowed older rig, to avoid
repeating this friction.

### API-1A.6 — implementation (2026-09-29) — IMPLEMENTED, NOT YET VERIFIED

**Status precision, per explicit instruction**: "IMPLEMENTED" and "VERIFIED" are different
claims — do not call this "production-proven" or "CLOSED" in the sense of done-and-trusted.
API-1A.7 (a real compatibility/concurrency test gate, not another design audit) is what
upgrades this to VERIFIED. Full status ladder now tracked in
`docs/contracts/SetVelocityPrimitive.md`'s header:
```
API-1A.5   CONTRACT DESIGNED       PASS
API-1A.5P  PORTABILITY PoC         PASS
API-1A.6   IMPLEMENTED             <- this section
API-1A.7   VERIFIED                <- NOT YET REACHED
```

Code written, compiled standalone against this repo's real build output, `jdeps`-verified.
Full normative detail in `docs/contracts/SetVelocityPrimitive.md` (revised alongside this
implementation, not left stale) — summary here:

- `EntityMutationOp.SetVelocity(Vec3)` + one new `EntityMutationDispatcher.applyDirect` case —
  same shape as `Ignite`/`ExtinguishFire`, no new routing logic.
- `net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity, Vec3)` — public facade,
  `jdeps`-confirmed zero `net.nestworld.region`/adapter/Core dependency (only
  `net.minecraft.world.entity.Entity`, `net.minecraft.world.phys.Vec3`, JDK types).
- **Corrected per architecture-owner review before landing**: the original PoC's bare
  `backend == null -> entity.setDeltaMovement(velocity)` was rejected as unsafe off-thread
  mutation. `ForgeVelocityExecution` now implements the real threading contract — same-thread
  apply, or capture-by-identity + `server.execute(...)` marshal + fresh reacquisition for a
  foreign-thread caller. Confirmed against real vanilla API
  (`Entity.getServer()`/`MinecraftServer.isSameThread()`/`.execute(Runnable)`/
  `.getLevel(ResourceKey)`), not assumed.
- `VelocityBackendRegistry` — explicit `UNINSTALLED → INSTALLED(server) → UNINSTALLED` state
  machine with a server-token staleness guard on uninstall, wired into
  `NestworldRegionSystem.onServerStarting`/`onServerStopping`. Deliberately not a bare
  forever-lived static field, per the explicit instruction not to repeat
  `NestworldRegionSystem.INSTANCE`'s own flagged JVM-global-singleton risk in this new surface.
- `NestworldVelocityBackend` (Core peer) — one-line delegate into `EntityMutationDispatcher`.
- **No "fallback" language anywhere in the code or contract** — Forge and NestWorld are
  documented as two peer execution semantics for one contract, per the explicit correction that
  a Core-is-real/Forge-is-fallback hierarchy was the wrong framing.
- 10 API-1A.6 acceptance criteria: 9 MET, 1 PARTIAL (full four-way dedicated/integrated ×
  Forge/NestWorld live-server evidence deferred to API-1A.7, which must be a real
  compatibility/concurrency test gate, not another design audit — see the contract doc's
  closing section for the 7 negative tests it must cover, given equal weight to the positive
  path).

**Definition of Done — API-1A.6, per explicit instruction not to mark DONE unless every row is
checked.** It is not:

| # | Requirement | Implementation | Documentation | Evidence | Status |
|---|---|---|---|---|---|
| 1 | Production implementation | `EntityMutationOp.SetVelocity`, dispatcher case, `NestWorldEntities`, `VelocityExecutionBackend`, `VelocityBackendRegistry`, `ForgeVelocityExecution`, `NestworldVelocityBackend` — all written | n/a | Real `:forge:compileJava` build: `BUILD SUCCESSFUL` | ✅ DONE |
| 2 | Class-level contract | Responsibility/Does/Does-NOT/Threading/Invariant/Failure/Lifecycle/Evidence template applied to all 5 new classes | This doc + `docs/contracts/SetVelocityPrimitive.md` | Code review (this session) | ✅ DONE |
| 3 | Public JavaDoc | `NestWorldEntities`/`setVelocity` fully documented, threading + failure semantics stated inline | Javadoc IS the contract per this project's standing rule | Code review | ✅ DONE |
| 4 | Ownership/threading semantics documented | Forge threading contract (same-thread vs. marshal+reacquire) + NestWorldCore's inherited region routing, both stated explicitly | `docs/contracts/SetVelocityPrimitive.md` "Forge threading contract"/"Ownership resolution" | Verified against real vanilla API before writing (`Entity.getServer()` etc. confirmed to exist) | ✅ DONE |
| 5 | Failure/delivery semantics documented | best-effort/at-most-once/NOT-guaranteed stated in 3 places (class javadoc, method javadoc, contract doc); silent-loss cases named, not hidden | Same files | Traced against real dispatcher/shutdown code (inherited from API-1A.5's verification pass, not re-derived) | ✅ DONE |
| 6 | Architecture documentation synchronized | Status ladder, implementation table, acceptance-criteria status all updated same session as the code | This document, this section | Diff review | ✅ DONE |
| 7 | Tests/evidence added | Standalone compile + real `jdeps` (zero Core deps confirmed) | `docs/contracts/api-1a7-findings.md` (full API-1A.7 evidence set) | **Real, run: deterministic migration/removed/reroute-limit/ordering tests, 6/6-path visibility matrix, full control-flow inventory, Forge dedicated+integrated+worker-thread (all physically verified), NestWorld dedicated (all physically verified), 60s/119,762-op correlated stress with all 5 critical invariants holding. NestWorld-integrated formally PARKED as an infrastructure gap (`API1A7-NW-INTEGRATED-001`), not silently skipped.** | ✅ DONE, with one explicitly scoped-out configuration |

**Overall: DONE, with an explicit scope carve-out.** API-1A.7 closed 2026-09-29 (see
`docs/NESTWORLD_API_ARCHITECTURE.md`'s "API-1A.7 — CLOSED" section below for the full,
precise status). `setVelocity` is **VERIFIED for Forge dedicated, Forge integrated, and
NestWorld dedicated** — NOT claimed for NestWorld integrated, which is a named, tracked
infrastructure gap, not a silent omission. Do not read "VERIFIED" as "every configuration this
API will ever run on has been tested" — read it as exactly the scope stated.

**A Phase API-1 skeleton was drafted the same day this document was written, then deleted the
same day** (`ExecutionTask`, `ExecutionContext`, `ExecutionOutcome`, `ExecutionFailedException`,
`ExecutionHandle`, `ExecutionBackend`, `BackendResolver`, `NestWorldAPI` — all 8 files, untracked,
removed via `rm` before ever being committed; recoverable from session history only, deliberately
not git history, if a specific signature is ever wanted again). Two things forced this:

1. **Namespace**: `net.nestworld.api` — where the draft was written — is not free. A full
   codebase audit found `docs/CORE_ADAPTER_CLASSIFICATION.md` already has an on-record ruling
   (#94) scoping that exact package to "a narrow Metrics/Diagnostics facade... **not the seed of
   the future general Mod API (§29)**... this package's shape must not be allowed to pre-determine
   that broader design later." A sub-package (`net.nestworld.api.execution`) was considered and
   also rejected — architecturally it is still the same namespace regardless of Java's package
   boundary. The corrected target root, aligned with the already-anticipated §29: **`net.nestworld.modapi`**
   (`net.nestworld.modapi.execution`, `.world`, `.entity`, ... — execution becomes one capability
   of the future Mod API, not the whole of it).
2. **Safety, not just naming**: see "Why API-1 was blocked" below — this is the substantive
   reason, the namespace issue alone would only have caused a rename.

Updated package map:

```
net.nestworld.api       -> Core metrics/diagnostics (existing, #94-scoped, unchanged)
net.nestworld.adapter   -> vanilla/Forge call-site -> Core dispatcher (existing, unchanged)
net.nestworld.modapi    -> portable API for mods (this document's actual target -- surface TBD)
net.nestworld.platform  -> Forge/NeoForge loader integration (not started)
```

### Why API-1 was blocked: generic `submitAt(Runnable)` is a sanctioned escape hatch

A full-codebase audit (2026-09-25, read-only, no code changes) found that NestworldCore's
existing execution machinery is **deliberately never generic**. `EntityMutationDispatcher` — the
closest existing analog to `submitEntity` — dispatches a *closed, sealed set* of operations
(`Damage`, `AddEffect`, `Ignite`, `ExtinguishFire`, `Knockback`, `Kill`, `Discard`), never an
arbitrary caller-supplied closure; its own javadoc states this is deliberate: "kept narrow so this
doesn't grow into a universal world-mutation bus." Every one of `RegionMessage`'s 13 message kinds
is similarly closed/specific — there is no generic "run this arbitrary task" message type
anywhere in the mailbox layer today.

This turns out not to be an accident this design skipped past — it is a load-bearing safety
property. Region ownership, as currently proven throughout this whole project (ownership
assertions, ownership-based routing, the entity reroute logic below), guarantees **where** code
runs. It says nothing about **what a `Runnable` is allowed to touch once it is running there**:

```java
NestWorldAPI.submitAt(posA, () -> {
    entityFromRegionB.setHealth(...);   // captured reference, ownership check never runs
    networkInRegionC.modify(...);       // same
    level.setBlock(posD, ...);          // same
});
```

A generic `ExecutionTask<T>` accepted as public API is a **sanctioned escape hatch around the
ownership model**, not a thin contract layered over it. `submitAt`/`submitEntity` as originally
drafted (arbitrary closure, `ExecutionHandle<T>` completion/cancellation/exception machinery
modeled on `CompletableFuture`) must not be assumed as Phase API-1's shape until this is resolved
— see Phase API-0.5 below.

**Also corrected, from the same audit**: the "Shutdown / unload semantics" section below
previously listed ownership-change reroute as an unresolved open question. It already ships,
for the entity case: `EntityMutationDispatcher.applyQueued` re-resolves ownership immediately
before applying, reroutes up to `NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS` hops, then
drops+logs past the limit, and no-ops if the target was removed. **This does not generalize to
every future resource** — block/position reroute and generic-task reroute remain genuinely
undecided; only the entity case is proven. Do not read the existence of this mechanism as
evidence that a generic `submitAt` would inherit the same safety for free — it is exactly the
closed-operation-set design (not a generic-task design) that makes this reroute rule tractable to
implement and audit in the first place.

---

## API-1A.7 — confirmed ordering inversion; Temporal Semantics decided, NOT yet implemented (2026-09-29)

**Status correction**: `docs/contracts/api-1a7-findings.md`'s `API1A7-FORGE-002-RETRY` entry
already resolved the worker-thread test as **PASS** — it is not a remaining gap. Actual
remaining PENDING items: Forge integrated (client), NestWorld integrated (client), true
same-JVM lifecycle restart, 3 of 5 visibility-matrix rows (Forge server/worker,
NestWorld same-owner/direct), Phase E stress, final evidence review. See the findings log for
the full, current status table — this section covers only the decision made from what IS
already in, not a status re-summary.

### The finding: a controlled, mechanism-explained semantic race — ownership-safe, temporally wrong

`docs/contracts/api-1a7-findings.md`'s `API1A7-SEC11-ORDERING` entry is authoritative (real
opId-tagged log evidence, not inference): submitting `V1` then `V2` on the same entity, where
`V1` gets delayed by a region migration/reroute and `V2` doesn't, produces `V2` applying before
`V1`. **This is not a contract violation** — no ordering was ever promised — but it exposes a
real gap: for a setter-shaped primitive, the wrong outcome isn't "message order differs from
send order," it's "an older *intent* silently overwrote a newer one":
```
t0: setVelocity(V1)   -- older intent
t1: setVelocity(V2)   -- newer intent
              actual apply order: V2, then V1
              final state: V1   <- semantically wrong for a setter
```

### Decision (2026-09-29): `LATEST_INTENT_WINS`, not FIFO — and NOT implemented yet

**Explicitly rejected**: a global/dispatcher-level FIFO sequence counter. That would silently
convert every future primitive into a command-stream abstraction, which is wrong for most of
them — `setVelocity`/`setFireTicks` are state *replacement*, but `addVelocity`/`giveItem`/
`removeItem`/`scheduleTick` are each-command-matters or have their own native ordering. A single
dispatcher-wide FIFO is too strong an assumption to bake in generically.

**Decided model for `setVelocity` specifically**: versioned latest-intent, not delivery
ordering.
```
SetVelocity { target, velocity, intentVersion }

V1/version=100 -- delayed/rerouted
V2/version=101 -- applies first, sets lastApplied=101
V1/version=100 arrives -- 100 < 101 -> STALE_INTENT_DROPPED, no mutation
final state: V2   <- correct
```
Critical constraints on the eventual implementation (recorded now so they aren't lost, NOT yet
built):
- **`intentVersion` is assigned at submission (the API call itself), never at mailbox-enqueue or
  at `applyQueued()`** — assigning it later would measure scheduling order, not caller intent
  order, defeating the entire point.
- **Versioning is scoped per logical target + operation family, never global.** A `TargetKey`
  is roughly `(server/runtime generation, entity identity, operation family = SET_VELOCITY)` —
  entity A's sequence and entity B's sequence have no causal relationship and must never be
  compared. A bare `AtomicLong` global counter is explicitly wrong.
- **Dimension migration stays out of scope for this fix.** Target identity is still
  `(dimension, uuid)` — an entity crossing dimensions is still a different `TargetKey`
  (`TARGET_NOT_FOUND` today, per the existing documented gap). Do not silently change dimension
  semantics as a side effect of adding ordering.
- **Concurrent (not sequential) callers get NO causal-ordering promise, only arbitration.**
  Proposed contract wording (not finalized — "the exact wording still needs to survive contact
  with the implementation"): *"Calls accepted sequentially through the same logical submission
  domain preserve latest-intent semantics. Concurrent submissions may be arbitrated by the
  runtime; no ordering between genuinely concurrent callers is implied beyond the resulting
  assigned version."*
- **`LATEST_INTENT_WINS` is orthogonal to delivery, not a delivery upgrade.** A newer version
  can still be silently lost (target removed, server shutdown mid-flight) — in that case the
  OLDER, already-applied intent's state simply persists, which is correct, not a violation.
  Ordering and delivery remain two separate, independently-stated guarantees:
  ```
  DELIVERY: best-effort, at-most-once           (unchanged)
  ORDERING: a stale accepted intent must not overwrite a newer applied one   (new)
  ```

### Second decision: execution-path timing is explicitly NOT part of the public contract

`API1A7-SEC18-CONTROLFLOW`'s finding — whether a caller learns the outcome synchronously is
"an ACCIDENTAL side effect of which internal branch fires... not a designed
submission-vs-application distinction" — is treated as load-bearing, not a curiosity. Standing
rule for `setVelocity` and every future Bounded Mutation Primitive: **a direct/synchronous
implementation path is an optimization, never a read-after-write guarantee.** `setVelocity()`
returning must never be read by a mod as "the mutation is visible now," even on paths where
that happens to be true today (same-thread direct-apply, backpressure fallback) — those two
paths working synchronously today is incidental, not contractual, and a mod that depends on it
will break the moment ownership/timing shifts it onto the async path.

### Standing model for all future primitives: Temporal Semantics Classification (architecture-level, not yet public API)

Every future primitive's boundary audit (mirroring API-1A.4's Primitive Boundary Inventory
table) gets a SECOND, independent classification axis alongside ownership:

| Primitive | Ownership | Temporal class |
|---|---|---|
| `setVelocity(V)` | ENTITY_SINGLE_OWNER | `LATEST_INTENT_WINS` |
| `setFireTicks(N)` | ENTITY_SINGLE_OWNER | `LATEST_INTENT_WINS` |
| `addVelocity(Δ)` | ENTITY_SINGLE_OWNER | `ORDERED_COMMAND` (not yet audited) |
| `inventory.set(slot,x)` | PLAYER_SINGLE_OWNER | likely `LATEST_INTENT_WINS` per slot |
| `inventory.add(x)` | PLAYER_SINGLE_OWNER | `ORDERED_COMMAND` (every call matters) |
| scheduled tick | WORLD/SPATIAL | native scheduler semantics, not yet mapped |

Not a public enum yet — this is an internal architecture-classification concept, not committed
API surface. Full model, once formalized:
```
Bounded Mutation Primitive
        |
        +-- Ownership Contract     (API-1A.4's existing axis)
        +-- Delivery Contract      (best-effort/at-most-once, already established)
        +-- Temporal Contract      (NEW: LATEST_INTENT_WINS / ORDERED_COMMAND / native / ...)
        +-- Visibility Contract    (NEW: execution-path-timing-not-contractual, established above)
```

### What happens next — explicitly sequenced, not started yet

**No versioning code is written today.** API-1A.7's remaining measurement gaps (listed at the
top of this section) close FIRST, without a semantic fix folded in — per the architecture
owner's explicit instruction not to let a design correction interrupt an unfinished measurement
pass. Once API-1A.7 fully closes, a NEW, separate gate — **API-1A.8 — Temporal Contract** — does
the actual design correction: define the 4-contract model formally for `setVelocity`
(Ownership: single entity owner; Delivery: best-effort/at-most-once; Temporal:
`LATEST_INTENT_WINS`, stale-intent-must-not-overwrite-newer-applied; Visibility: async, no
read-after-write guarantee), implement it, and **re-run the exact same V1/V2 deterministic test
this gate already built** — success criterion: `submit V1→V2` still may `apply V2→V1` (routing
timing is allowed to differ), but the FINAL STATE must be `V2` (the stale `V1` intent gets
`STALE_INTENT_DROPPED`, not silently applied over a newer state).

**Explicitly not a dispatcher bug.** `EntityMutationDispatcher` fulfills its own stated contract
today — best-effort, at-most-once, no ordering promised, no ordering violated. The inversion is
a previously-unstated gap in the PUBLIC API's own temporal contract, made visible by
verification for the first time — this is exactly why measurement came before any corrective
mechanism was bolted on, per this project's own standing discipline throughout this gate.

### API-1A.8 — Temporal Contract & Correction — VERIFIED, CLOSED (2026-09-29)

Scope held exactly to what `setVelocity` needs — **not a generic ordering framework**, per
explicit instruction. Implementation:

- **`net.nestworld.modapi.entity.SetVelocityIntentTracker`** (new, Core-free, `jdeps`-confirmed):
  the entire mechanism. `TargetKey(dimension, entityId)` is the ordering domain — operation
  family is implicit (this class exists only for `SET_VELOCITY`) and deliberately NOT
  generalized into a shared key shape for other future primitives.
  `assignVersion(key)` — called at submission time, strictly increasing per key.
  `tryAccept(key, version)` — called immediately before the actual mutation; returns `false`
  (STALE_INTENT_DROPPED) if a newer version already won.
- **`NestWorldEntities.setVelocity`** assigns the version FIRST, before delegating to either
  peer — this is what makes it reflect caller intent order rather than scheduling order,
  regardless of which peer (Forge or NestWorld) ends up executing the call.
- **`EntityMutationOp.SetVelocity`** gained an `intentVersion` field. **`EntityMutationDispatcher
  .applyDirect`** — the single chokepoint every actual mutation passes through regardless of
  which `dispatch()`/`applyQueued()` branch got there — now checks `tryAccept` immediately
  before `entity.setDeltaMovement(...)` and returns `boolean` (false = stale, skip). The
  generic `dispatch()`/`applyQueued()`/reroute machinery itself is completely unaware this
  mechanism exists — it just carries the `SetVelocity` record's fields opaquely, exactly the
  scope discipline requested.
- **`ForgeVelocityExecution`** independently checks the same `tryAccept` at both its apply
  points (same-thread and post-marshal), using the SAME shared `SetVelocityIntentTracker` —
  one version domain regardless of which peer is active.
- **Lifecycle**: `SetVelocityIntentTracker.resetAll()` wired into
  `NestworldRegionSystem.onServerStopping`, alongside the existing `VelocityBackendRegistry
  .uninstall` call — a new server generation (including an integrated-server world restart in
  the same JVM) never sees stale version state from a previous world.
- **Orthogonality preserved**: delivery semantics (best-effort/at-most-once) are completely
  unchanged — `LATEST_INTENT_WINS` only decides which of two competing intents wins
  arbitration, never whether a winning intent is guaranteed to actually apply.

**Regression fixture preserved deliberately, not rewritten**: `Api1A7DeterministicTests
.runOrderingTest` (API-1A.7's `SEC11-ORDERING` finding) is the SAME test, SAME interleaving,
now instrumented to assert the NEW expected outcome — `opId`-tagged `TERMINAL` line for V1 must
read `STALE_INTENT_DROPPED` (not `APPLIED`), V2's must read `APPLIED`. The original pre-fix
finding is kept intact in `docs/contracts/api-1a7-findings.md`, unedited — per explicit
instruction not to rewrite old findings as if `LATEST_INTENT_WINS` had always existed.

**Build verified**: real `:forge:compileJava` — `BUILD SUCCESSFUL`. `jdeps` re-confirmed zero
Core dependency in `net.nestworld.modapi.entity` with the new tracker included.

**Live regression evidence gathered (steps 5-7) — full evidence in
`docs/contracts/api-1a8-findings.md`:**
- **Step 5, the critical regression — PASS, definitive.** Replaying `API1A7-SEC11-ORDERING`'s
  exact interleaving: V1 (`intentVersion=1`) now terminates `STALE_INTENT_DROPPED` (was
  `APPLIED`, silently overwriting V2, before this fix); V2 (`intentVersion=2`) terminates
  `APPLIED`. Confirmed via the same opId-tagged TERMINAL log lines the original finding used —
  directly comparable, not a different measurement.
- **Step 6 — migration/removed/reroute-limit regression — PASS, unchanged.** All three
  API-1A.7-confirmed deterministic tests produce identical results with `intentVersion`/
  `tryAccept` now in the path — the mechanism adds zero observable side effect absent real
  contention.
- **Step 7 — short stress — PASS, with an honest nuance.** 42,432 ops/20s, 2,828 migrations,
  zero crashes/violations/duplicates. Two dedicated attempts to organically reproduce
  `STALE_INTENT_DROPPED` under contention (rapid-fire burst, 15-thread concurrent burst) did NOT
  reproduce it — root-caused to RCON submission serializing at the Server thread, not a gap in
  the fix; the rejection path itself stays verified by Step 5's deterministic scenario, not
  reclaimed here as independently reproduced under stress. Flagged precisely, not overclaimed.

**API-1A.8 is VERIFIED and CLOSED.** The confirmed ordering inversion is resolved exactly as
designed — `LATEST_INTENT_WINS`, no FIFO, no generic ordering framework, no regression to any
of API-1A.7's other confirmed behavior.

**Precision clarification, added to `SetVelocityIntentTracker`'s own javadoc, repeated here
because it matters for reading every finding above correctly**: `LATEST_INTENT_WINS` does NOT
mean "the last wall-clock call wins." It means the caller whose `assignVersion()` call executes
second (per the `TargetKey`, serialized by the underlying `ConcurrentHashMap`/`AtomicLong`) wins
— `assignVersion()` IS the linearization point. For two genuinely concurrent callers, "who was
last" is otherwise ill-defined; putting the decision anywhere else (e.g. at `tryAccept` time,
which is what routing/marshaling/reacquisition timing would produce) would make the winner
depend on scheduling, not on caller intent — exactly the ambiguity this mechanism exists to
remove. This is a precise, provable contract, not an approximation.

## Milestone 1 — first Bounded Mutation Primitive complete (2026-09-29)

**Do not extend `setVelocity` further.** It has already served its purpose: proving portability,
ownership routing, migration/reroute, lifecycle, delivery model, visibility model, and now
temporal semantics — on ONE primitive, deliberately, so the methodology could be established
without also debugging a second capability at the same time. Further value now comes from
testing a DIFFERENT ownership family and the real external-packaging boundary, not from further
polishing this one primitive.

**The finished per-primitive architecture, as actually built and verified**:
```
setVelocity(entity, velocity)
        |
        +-- TargetKey = (dimension, UUID)
        |
        +-- intentVersion = assignVersion(TargetKey)     <- linearization point
        |
        v
   platform backend
   +--------+--------+
   |                 |
 Forge           NestWorld
   |                 |
 marshal        ownership routing
   |            mailbox/reroute
   +--------+--------+
            v
   fresh target resolution
            |
            v
   tryAccept(TargetKey, intentVersion)
            |
     +------+-------+
     |              |
  newer/current    stale
     |              |
     v              v
setDeltaMovement   DROP
                  STALE_INTENT_DROPPED
```

**Four concerns, deliberately kept separate — easy to accidentally conflate, must not be**:
```
Ownership    -> who has the right to mutate this entity (region/thread routing)
Delivery     -> best-effort / at-most-once (can a request be silently lost)
Temporal     -> LATEST_INTENT_WINS (which of several competing intents wins)
Completion   -> submission does NOT mean completion (no read-after-write guarantee)
```
Every future Bounded Mutation Primitive's boundary audit should classify all four
independently, not assume one implies another.

## Roadmap after Milestone 1 — explicitly sequenced, not started

```
API-1A ✅ CLOSED (Milestone 1)
   |
API-PKG-1 ✅ CLOSED (2026-09-29)
   |
   +------------------+------------------+
   v                                     v
API-1B                              MODULE-0    PARTIAL (2026-09-29)
Inventory Slot Mutation             MODULE-0.5  RESOLVED (2026-09-29)
NEXT                                MODULE-1    ✅ PASS (2026-09-30) -- startup foundation
                                     MODULE-2    PARKED -- hot lifecycle/reload research
```

**API-PKG-1 — Packaging gate — CLOSED, all 5 hard criteria met with real evidence.** Full
detail: `docs/PACKAGING.md`. `net.nestworld.modapi.entity` now physically lives in its own
Gradle subproject (`projects/modapi`, module `:modapi`), not the monolithic `:forge` compile
unit:
```
nestworld-mod-api-forge-1.20.1.jar
        |
        +-- zero Core dependencies       CONFIRMED -- jdeps on the actual PUBLISHED jar (not
        |                                  compiled-classes-dir): only net.minecraft.*/
        |                                  org.slf4j.*/java.* resolve, zero net.nestworld.region/
        |                                  adapter, zero net.minecraftforge.*
        +-- stable public ABI            unchanged -- same 5 classes, source untouched, only
        |                                  their physical location moved
        +-- Maven-consumable              CONFIRMED -- published to mavenLocal()
        |                                  (~/.m2/repository/net/nestworld/
        |                                  nestworld-mod-api-forge-1.20.1/1.0.0-SNAPSHOT/),
        |                                  real Maven-coordinate resolution in the consumer,
        |                                  not a project(...) dependency
        +-- external test mod             CONFIRMED -- genuinely separate Gradle project
                                           (scratchpad/api-pkg-1-consumer/), own settings.gradle/
                                           build.gradle, mavenLocal() repository entry
                  |
          +-------+--------+
          v                v
     stock Forge       NestWorldCore
          |                |
   PASS, physically    PASS, physically verified -- same consumer jar, byte-
   verified (Motion    identical, unchanged, routed through the REAL region-aware
   NBT match)          NestworldVelocityBackend/EntityMutationDispatcher path
                        (intentVersion=1 confirmed in the log -- API-1A.8's
                        LATEST_INTENT_WINS mechanism active end-to-end through
                        the external artifact)
```
**Module dependency graph built**: `:modapi` and `:forge` both `compileOnly`/depend on `:clean`
(the genuinely unpatched vanilla+Forge project already used as `:forge`'s own patcher parent —
chosen deliberately over `:forge` itself, since `:forge` bundles NestWorld's patches
monolithically and `FORGE-001` already found un-strippable Core coupling baked into patched
vanilla's own bootstrap); `:forge` additionally depends on `:modapi` (`implementation`) so Core
code keeps resolving `net.nestworld.modapi.entity.*`. No dependency cycle. Both
`:modapi:compileJava` and `:forge:compileJava` verified independently, twice (once by the
dispatching pass, once independently re-confirmed by the coordinator against the final repo
state).
**Not done, correctly out of scope**: integrated-environment testing for the consumer project
(the packaging boundary was the new thing under test; `setVelocity`'s own integrated-Forge
behavior was already proven in API-1A.7 and isn't re-tested here). Real Maven Central/a real
repository, a versioning policy, and which distribution shape to recommend externally are all
explicitly deferred, not decided by this gate.
**Incidental, non-blocking finding**: the reused test-mod template had hardcoded `@Mod(...)`
mod-id string literals left over from an earlier mod, mismatched against its templated
`mods.toml` — a real Forge mod-loading trap (annotation literals don't auto-follow
`gradle.properties` template substitution), not a `net.nestworld.modapi` defect. Fixed in the
scratch consumer project; worth remembering if this template gets reused again.

**API-1B — Inventory Slot Mutation (after API-PKG-1)**. Chosen specifically because it tests a
DIFFERENT ownership family, not just a second entity setter:
```
API-1A: Entity identity (dimension + UUID)              -> entity-owned field
API-1B: Player identity + slot                          -> inventory-owned state
```
If the same process (bounded boundary -> identity -> reacquisition -> ownership -> temporal
class -> delivery -> verification) works for this genuinely different ownership shape, that is
real evidence of a repeatable methodology for building this Mod API — not evidence that
`setVelocity` happened to be a well-chosen one-off.

### API-1B boundary audit — CLOSED (2026-09-30), research only, no code written

Full detail in session history (fork report) — summary here. Grounded in real vanilla source
(`Inventory.java`, `AbstractContainerMenu.java`, `ContainerHelper.java`, `ItemStack.java`), not
assumption.

**Target identity, with a genuine asymmetry from `setVelocity` worth carrying forward**:
`(player UUID, Inventory-flat-slot-index 0-40)`. Two slot-numbering schemes exist in vanilla —
only one is stable: `Inventory`'s own flat index across its three compartments (`items[36]`,
`armor[4]`, `offhand[1]`) is real identity; `AbstractContainerMenu`'s per-GUI slot index
(assigned sequentially at menu construction, `slot.index = this.slots.size()`) is ephemeral and
**must be excluded** — trusting it would repeat the "routing snapshot != mutation authority"
mistake at the identity layer itself. Unlike `setVelocity`, **player UUID lookup needs no
dimension component** (`PlayerList.getPlayer(UUID)` is a server-wide map, not per-dimension,
unlike `level.getEntity(uuid)`) — but ownership ROUTING still needs dimension, read fresh off
the reacquired player (`player.level().dimension()`), never captured at submission. Identity-
for-lookup and identity-for-routing are different fields here in a way they weren't for
`setVelocity` — don't conflate them in the eventual contract.

**Fresh reacquisition**: `server.getPlayerList().getPlayer(uuid)` → `.getInventory()`. "Player
not found" (offline) is this primitive's `TARGET_NOT_FOUND` equivalent — offline inventory data
lives in a separate NBT file, a different write path, out of scope.

**Ownership authority**: zero existing NestWorld code touches player `Inventory` anywhere
(confirmed by grep — genuinely open, not already assumed elsewhere like some of MODULE-0's
extension points were). `ServerPlayer` is not special-cased in `EntityMutationDispatcher`/
`WorldGrid`/`NestworldOwnershipAssertions` — a player is just an `Entity` for ownership
purposes today. Default, evidenced conclusion: inventory mutation routes through the same
ownership mechanism already proven for `setVelocity` (whichever region owns the player entity).
**Actor/target shape confirmed genuinely simpler than `damage()`/`useItemOnBlock()`**: raw
`Inventory` manipulation touches only the player's own state, no second entity — real
supporting evidence for `PLAYER_SINGLE_OWNER`, explicitly scoped to raw `Inventory` calls, NOT
the `Slot`/click/crafting path (correctly excluded, same reason `useItemOnBlock()` was rejected
for being too broad).

**Operation taxonomy — each independently audited, prior guesses tested against real code, not
repeated uncritically**:

| Op | Bounded? | Callback surface | Temporal semantics |
|---|---|---|---|
| `SET` (`Inventory.setItem`) | YES — one list-element write, zero Forge/event references in the whole file | NONE at this layer | **Guess CONFIRMED**: `LATEST_INTENT_WINS` per `(player, slot)` — wholesale replacement, structurally identical to `setVelocity`, an older "set to X" losing to a newer "set to Y" is the correct outcome |
| `INSERT` (`Inventory.add`) | **NO** — can write across multiple slots in one call, branches on creative-mode ability, **mutates the caller-supplied `ItemStack` parameter in place** (`setCount(...)` on the input) — the same class of live-caller-object-mutation hazard Milestone 1 rejected for `DamageSource` | none found in this exact path, but the shape alone disqualifies a naive `setVelocity` port | **Guess partially WRONG, corrected**: not `ORDERED_COMMAND` because "every call matters" abstractly — `LATEST_INTENT_WINS`-style dropping is **actively wrong** here: a stale insert silently dropped under a void, fire-and-forget contract means the item is simply **destroyed**, a real duplication/loss bug class, not "correct outcome, wrong timing" |
| `EXTRACT` (`Inventory.removeItem`) | Bounded to one slot, but quantity-sensitive and silently clamps (asking for more than available returns less, never errors) | none found | **Open question, not resolved**: a stale extract being dropped isn't destructive (item stays put) — but a void, no-return-value shape doesn't fit EXTRACT at all, since the caller needs to know how much they actually got. May need a genuinely result-bearing contract, in tension with `docs/contracts/SetVelocityPrimitive.md`'s own deliberate "no `CompletableFuture`/`MutationResult`" scoping — flagged as real tension, not glossed over |
| `COMPARE/CONDITIONAL` | — | — | **Not evidenced anywhere** — no compare-and-swap-shaped method exists on vanilla `Inventory` (full-class grep). Per `docs/MODULE0_AUDIT.md`'s own discipline ("do not invent extension points merely because they sound useful"), **dropped from the candidate set entirely** |

**Recommendation**: `SET` (`Inventory.setItem`) is the strongest, narrowest first API-1B slice —
same shape of choice as `setVelocity` itself was among a larger candidate set. The only
operation of the four that is genuinely bounded with zero new machinery needed. `INSERT` needs
its own future gate with `DamageSource`-gate-level rigor on the caller-object-mutation hazard
before it's safe to route across any async boundary. `EXTRACT` needs its own future gate to
resolve the result-bearing-contract tension. **Do not batch all four into one slice** — exactly
the mistake the `damage()`/`useItemOnBlock()` double-audit taught this project to avoid.

**Decided (2026-09-30): GO for `SET`, NO-GO for the combined Inventory API.** `INSERT`/`EXTRACT`
remain separate, deliberately deferred future gates — not batched with `SET`.

### API-1B.5 — Inventory Set-Slot Primitive — contract CLOSED (2026-09-30), no code written

Full contract: `docs/contracts/InventorySetSlotPrimitive.md`. Four contract-closing items the
architecture owner required before any implementation, all resolved with evidence:

1. **`ItemStack.copy()` independence — confirmed directly against real source** (not the audit
   fork, the coordinator's own read): `copy()` builds a new instance from `serializeCaps()`
   (fresh capability data, not a shared handler) and deep-copies NBT via `tag.copy()`
   (`CompoundTag.copy()` recursively calls `Tag::copy` on every nested tag). The
   `(Item, int, CompoundTag)` constructor's `forgeInit()` calls `deserializeCaps(...)`
   **immediately**, not lazily — the copy's capabilities are a freshly-reconstructed, fully
   independent instance from the moment `copy()` returns. Zero shared mutable references
   survive. `SetSlotRequest` carries this snapshot, never the caller's live `ItemStack`.
2. **Slot identity formalized as ABI**: `TargetKey = (UUID playerId, int slot)`, `slot`
   EXCLUSIVELY `Inventory`'s own stable flat index (0-40) — `AbstractContainerMenu`'s
   per-GUI-instance slot index must never leak into the public surface at all.
3. **Routing identity separated from mutation identity, same principle as `setVelocity`**:
   dimension is NOT part of `TargetKey`. Genuine asymmetry from entity lookup confirmed:
   `PlayerList.getPlayer(uuid)` is a server-wide map, needs no dimension component at all
   (unlike `level.getEntity(uuid)`, which only searches one `ServerLevel`).
4. **Temporal semantics formalized with a real linearization point**: `LATEST_INTENT_WINS` per
   `(playerId, slot)` — independent version domains PER SLOT, `assignVersion` at submission,
   `tryAccept` immediately before the actual `Inventory.setItem` call, identical discipline to
   `SetVelocityIntentTracker`.

**New finding this gate produced, not merely closed a checklist item — a real, evidence-traced
hazard**: respawn creates a genuinely new `ServerPlayer`/`Inventory` incarnation, UNCONDITIONALLY,
every time (`PlayerList.respawn` constructs `new ServerPlayer(...)` always;
`ServerPlayer.restoreFrom`'s `Inventory.replaceWith(...)` call is conditional on
`keepInventory`/spectator/End-conquest — in the ordinary death-respawn case it never runs, so the
new inventory isn't even populated from the old one). Real hazard: a pre-respawn `setSlot` intent
still in flight when respawn happens, with no post-respawn write yet landed for that slot, would
still be "the highest version seen" and get ACCEPTED — writing pre-death item data into a fresh
post-respawn inventory. **Resolution**: a `currentIncarnation(UUID)` counter, kept OUT of
`TargetKey` (same "identity stays minimal, routing/execution state travels as a payload field"
principle `setVelocity` already established for dimension), bumped via the real, confirmed
`PlayerEvent.PlayerRespawnEvent`. `tryAccept` additionally rejects on an incarnation mismatch,
regardless of version. Directly reuses two already-validated patterns rather than inventing a
third: `ModuleGeneration`'s identity-plus-counter shape (MODULE-1) and MODULE-0.5's
`QUIESCING`-sentinel-generation trick, applied here as a respawn-sentinel.

**Evidence/tests proposed** (API-1B.6+, not yet written — design-only gate, matching how
`setVelocity`'s own Evidence/tests section predated API-1A.7): direct path, worker-thread
submission, migration/reroute, logout-before-apply, respawn-before-apply, stale-intent inversion,
two different slots concurrently (per-slot version independence), **caller mutates the original
`ItemStack` after submission** (the single most important test — slot content must match the
submission-time snapshot, never the caller's later mutation), reroute limit, lifecycle reset,
stress.

**Not yet started**: implementation (API-1B.6) and live verification (API-1B.7) — architecture
owner's decision on when to proceed, not made by this gate.

### API-1B SET-SLOT — PRIMITIVE VERIFIED / DISTRIBUTION & TRANSPORT BLOCKED

**Status (2026-10-01, API-1B.7 complete)**: API-1B SET-SLOT — PRIMITIVE VERIFIED / DISTRIBUTION & TRANSPORT BLOCKED. Order to "API-1B fully CLOSED"
(architecture owner): PKG-002 (next) → CORE-MAILBOX-MERGE-001 → API-1A per-op logging →
reproducible stock-Forge probe in the repo → integrated server → multi-`MinecraftServer` JVM
(proof that server A cannot affect server B's identity/version state). No new Mod API primitive
before that.
- Verified: NestWorld (direct + region-routed) and genuine stock Forge 47.4.20; deterministic
  fixtures with real respawn/relogin; stress pinned/unpinned/churn, ~46k submissions each, all
  primitive invariants 0 (`duplicateApply, wrongOwnerMutation, wrongIncarnationMutation,
  staleIntentApply, callerObjectLeak, unboundedReroute`, plus `finalStateMismatch`).
- Incarnation model: object identity (`PlayerIncarnationRegistry`, reference `==`), temporal domain
  `(player, slot, incarnation)`; the event-driven model was refuted live (fixture E) and removed.
  This also closed the stock-Forge incarnation blocker — confirmed on a real stock-Forge server.
- **Blocked — delivery**: CORE-MAILBOX-MERGE-001 (`RACE_CLASSIFICATION.md` #16). Under forced region
  churn, 171/171 missing terminal outcomes were messages stranded in retired regions' mailboxes.
  Core gate; API-1A/1B routing tests re-run after its fix.
- **Blocked — distribution**: API1B7-PKG-002. A consumer mod embedding `:modapi` cannot boot on
  NestWorld (`Modules nwinvprobe and forge export package net.nestworld.modapi.entity`). API-PKG-1's
  "same consumer jar on both platforms" claim is no longer true. Distribution model now proven
  by experiment and decided (`docs/contracts/PKG-002-distribution-model.md`: provider mod
  `nestworld_modapi`, NestWorld ships it as jar-in-jar, consumers `compileOnly`, consumer
  jar-in-jar of the provider allowed, shading API classes forbidden, Core enforces an explicit
  verified API range). **Implemented and verified on real artifacts** (provider artifact
  b092a61a8 + jar-in-jar packaging): built-in provider only, compileOnly consumer and consumer
  jar-in-jar (form B) on both NestWorld and stock Forge, dedup, unverified-version refusal, and
  **E7 PASS** (ABI-incompatible provider refused by FML before any Core class loads). Open:
  finding PKG-002-S5 — a provider that lies about its version passes metadata checks and fails at
  first call (`NoSuchMethodError`); mitigation proposed, decision pending.
- Fixed in this milestone: PKG-001 (`a8a2567b2`, `:modapi` shipped un-reobfuscated).
- Follow-up: the `[API1A7]` per-op INFO phase trace (committed, `setVelocity` path) can stall the
  main thread under load (API1B7-STRESS-001); setSlot's own per-op lines are now fixture-gated.

Full status: `docs/contracts/InventorySetSlotPrimitive.md` (updated in place, not a new doc).
Implementation landed: `net.nestworld.modapi.inventory.*` (`projects/modapi`, the standalone
Core-free module — `InventoryTargetKey`, `SetSlotRequest`, `InventorySlotVersionTracker`,
`PlayerIncarnationTracker`, `InventorySetBackend`(+`Registry`), `ForgeInventorySetExecution`,
`NestWorldInventory` facade), `EntityMutationOp.SetInventorySlot` +
`EntityMutationDispatcher.applyDirect`'s new case (incarnation checked strictly BEFORE version,
per the contract's own load-bearing ordering requirement), `NestworldInventorySetBackend` (Core
peer, `net.nestworld.region`), and `NestworldRegionSystem` wiring (backend install/uninstall,
both trackers' `resetAll()` on server stop). `:modapi:compileJava` and `:forge:compileJava` both
pass clean.

**Real architectural correction found during implementation, not merely a checklist item**: the
contract's own respawn-mechanism recommendation (self-registering `PlayerIncarnationTracker` to
the Forge event bus, "works correctly on ordinary Forge with NestWorldCore entirely absent") is
**impossible as originally specified** — confirmed by direct `:modapi:compileJava` failure
(`package net.minecraftforge.common does not exist`), not assumed. `:modapi` compiles only
against `:clean` (genuinely unpatched vanilla), which carries none of Forge's own event-bus/
common API — those classes exist in this repo only inside `:forge`'s own patched sourceSet,
inseparable from NestWorldCore per FORGE-001. Fix: `PlayerIncarnationTracker` stays a pure
passive counter; the actual `@SubscribeEvent` bridge (`onPlayerLoggedIn`/`onPlayerRespawn`)
moved into `NestworldRegionSystem` (Core), which already has full Forge access. **This reopens a
gap the contract did not anticipate**: on ordinary Forge with NestWorldCore absent, nothing
calls `bump()`. (An earlier revision called this "fails open, not unsafe" — withdrawn: without
an incarnation signal a pre-respawn request can be applied to the NEW player's inventory, which
violates the declared respawn semantics. OPEN BLOCKER; fix belongs in a stock-Forge
runtime-integration bridge, not in `:modapi`.)

Two more implementation-time findings, both resolved with evidence (see class javadocs for the
full reasoning, not repeated here):
- `NestworldVelocityBackend`'s Core-peer shape does NOT transfer to inventory unmodified: it
  receives an already-resolved live `Entity` from its caller, so it never needs a UUID→player
  lookup. `NestworldInventorySetBackend` receives only a UUID-carrying `SetSlotRequest`, and
  `PlayerList.getPlayer(uuid)` reads a plain (non-concurrent-safe) `HashMap` — confirmed by
  direct source read — so this peer marshals to the main thread BEFORE resolving the player,
  mirroring `ForgeInventorySetExecution`'s own discipline, not assumed transferable from velocity.
- The 8 new files were initially placed in `:forge`'s own sourceSet instead of the standalone
  `:modapi` module — caught before compiling (would have silently never shipped in the published
  Maven artifact) — moved to `projects/modapi/src/main/java/net/nestworld/modapi/inventory/`.

**API-1B.7 (deterministic test harness for the four named critical fixtures) — WRITTEN, compiles
clean, NOT YET RUN against a live server**: `net.nestworld.region.Api1B6DeterministicTests`,
reusing `Api1A7DeterministicTests`' exact barrier-pause/mutate-state/release/observe discipline
and its region-grid scaffolding (widened from `private` to package-private for reuse, not
duplicated). RCON surface: `/nestworld testmutate invdet <snapshot|ordering|respawn|
crossboundary>` (requires running AS a real connected player — `SetInventorySlot` rejects any
non-`ServerPlayer` target, so this harness needs a real attached `Inventory`, unlike
`setVelocity`'s disposable-`ArmorStand` harness).
- **Fixture A (`snapshot`)**: caller mutates its own `ItemStack` after `setSlot()` returns —
  applied slot content must reflect the submission-time snapshot only.
- **Fixture B (`ordering`)**: same-incarnation `LATEST_INTENT_WINS` under a forced region
  migration, SEC11-style — V1 delayed at a barrier, V2 submitted and applied first, V1 released
  and must lose arbitration (`STALE_INTENT_DROPPED`) despite reaching its own apply-phase routing.
- **Fixture C (`respawn`)**: an intent paused at barrier C (post ownership-recheck, pre-mutation)
  must be rejected if `PlayerIncarnationTracker.bump()` fires while it's paused — proves the
  incarnation check inside `applyDirect` itself catches this, not merely routing-level staleness.
- **Fixture D (`crossboundary`, new this gate, not one of the original 11 evidence items)**: an
  old-incarnation intent carrying a numerically HIGH version must be rejected regardless of its
  version, while a new-incarnation intent carrying a numerically LOW (post-reset) version must be
  accepted — proves temporal ordering never leaks across the incarnation boundary in either
  direction, and that `InventorySlotVersionTracker.resetForPlayer` (triggered by `bump`) actually
  resets the counter, not just that `bump` ran.

**Not yet started**: live execution of the four fixtures (the actual DoD closure for API-1B.7),
plus the remaining ~7 items from API-1B.5's original evidence list that have no dedicated test
yet (worker-thread submission, logout-before-apply, two-slots-concurrently, reroute limit,
lifecycle-reset-across-server-generations, stress) — see `docs/contracts/InventorySetSlotPrimitive.md`'s
per-item status table for the full honest breakdown.

**MODULE-0 — Module SPI Boundary — CLOSED PARTIAL (2026-09-29).** Design/audit gate only, per
directive. Full detail: `docs/MODULE0_AUDIT.md`. Extension-point inventory, privilege boundary
(`privileged != unrestricted`, confirmed to hold structurally as long as entity/region mutation
stays routed through `EntityMutationDispatcher`-shaped mechanisms), lifecycle contract draft,
resource ownership matrix. Verdict PARTIAL: three unresolved gaps (generation semantics,
execution-context vocabulary vs. GMEIL, unload-guarantee wording) — not a blocker, but not
enough to design MODULE-1 from directly. Explicit lesson carried forward from Milestone 1:
`docs/contracts/api-1a7-findings.md`'s "Lifecycle identity replacement: PASS" finding proved
distinct identity across sequential server generations — it did NOT prove the stronger claim
that all resources (executors, listeners, classloader references) from a prior generation are
actually released. For a Module SPI, THAT stronger claim becomes the actual correctness
question, not a nice-to-have.

**MODULE-0.5 — Lifecycle & Execution Contract Resolution — CLOSED RESOLVED (2026-09-29).**
Design only, per directive. Full detail: `docs/MODULE0.5_LIFECYCLE_CONTRACT.md`. Closed all
three MODULE-0 gaps: G1 (generation semantics — identity model, 5-state work model, generation
check at `QUEUED -> RUNNING` mirroring `SetVelocityIntentTracker.tryAccept`'s placement,
`RUNNING` work bounded by timeout rather than force-dropped), G2 (execution vocabulary — new
`ModuleExecutionContext { MAIN_ONLY, REGION_LOCAL, CORE_OWNED }`, deliberately NOT reusing
GMEIL's `ExecutionPolicy`, composes rather than collides), G3 (unload guarantee reformulated
with an `ASSERTABLE`/`TESTABLE`/`NOT VERIFIABLE` honesty framework and a `ModuleContext`-owned
vs. module-owned resource split).

**MODULE-1 — Startup-Loaded Module SPI Foundation — CLOSED PASS (2026-09-30).** Real
implementation this time, not design-only — the architecture owner's explicit "GO" for
production code. Full detail: `docs/contracts/Module1Foundation.md`. New package
`net.nestworld.module`: `ModuleId`/`Version`/`Generation`/`Identity` (generation carried
through from day one, not decorative, per explicit instruction), `ModuleLifecycleState`
(`DISCOVERED -> LOADED -> ENABLED -> RUNNING -> QUIESCING -> DISABLED` + `FAILED`, no
`UNLOADED` — no classloader isolation this pass, process restart is the unload boundary),
`ModuleExecutionContext` (MODULE-0.5's G2 decision), `NestWorldModule`/`ModuleContext`/
`ResourceHandle`/`ResourceLedger` (revokes in REVERSE registration order, tolerant of a
throwing handle), `ModuleFailure` (attribution, never rollback), `ModuleRegistry` (the
orchestrator). Two test modules (`ExampleModule`: 3 registrations, deliberately boring;
`FailingExampleModule`: fails before its 3rd registration) proved, live, with real RCON
evidence: the central ledger-lifecycle test (register A/B/C → quiesce → reject late
registration → ledger empty → `DISABLED`, with the listener's own live tick counter confirming
revocation had a REAL effect, not just a bookkeeping one) and the failure-injection test
(module never reaches `RUNNING`, straight to `FAILED`, ledger revoked in reverse order).
**Honest limitation found and documented, not hidden**: command revocation is soft (execution
refuses to run) not a real Brigadier tree removal — confirmed via `javap` that this project's
Brigadier version (1.1.8) has no `removeChild`/`removeCommand` API.

**Higher-priority incidental finding from MODULE-1's own verification pass**: this was the
first fresh rebuild+boot of `:forge`'s own deployed jar since API-PKG-1 — it silently could not
boot at all (`net.nestworld.modapi` missing from `universalJar`'s actual output; a
`project(...)` dependency gives compile classpath, not fat-jar inclusion). Fixed in
`build.gradle`, independently re-verified with a real installer + real boot. Full writeup:
`docs/PACKAGING.md`. Lesson for any future extraction: verify the CONSUMING project's own
deployed artifact still boots, not only that an external consumer of the extracted artifact
does — API-PKG-1's own verification checked the latter and missed the former.

**MODULE-2 — hot lifecycle/reload research — PARKED, not started.** Per explicit instruction:
MODULE-1's PASS does not mean hot reload is next. `ModuleClassLoader` isolation, true hot
load/reload, generation-tagged async (scheduled/mailbox) capabilities all remain deliberately
unimplemented, each needing its own future gate once a real capability surfaces the need.

## API-1A.7 — CLOSED (2026-09-29)

Full raw evidence: `docs/contracts/api-1a7-findings.md` (the authoritative record — this section
is a classified summary, not a replacement). Per explicit instruction, the result is stated
precisely rather than forced into a binary PASS/FAIL:

```
API-1A.7 VERIFIED

Verified configurations:
  Forge dedicated        -- route trace, worker-thread threading contract (physically verified),
                             full visibility rows, correlated stress (119,762 ops, 5/5 invariants)
  Forge integrated       -- physically verified: Render thread -> ForgeVelocityExecution ->
                             integrated-server execution context, real entity mutation confirmed
  NestWorld dedicated    -- route trace, deterministic migration/removed/reroute-limit/ordering
                             tests, same-owner visibility, correlated stress (5/5 invariants)

Not verified:
  NestWorld integrated
    reason: missing patched-client test harness (API1A7-NW-INTEGRATED-001, PARKED as an
    infrastructure gap after 3 genuine attempts -- NOT "not achievable," current
    infrastructure just doesn't support it yet; does not block this closure)

Known semantics (now evidence-backed, not just asserted):
  - best-effort, at-most-once delivery (confirmed: zero loss in a 119,762-op stress run;
    two documented silent-loss paths remain: target-not-found-at-apply, shutdown-mid-flight)
  - bounded reroute (hop limit 5, confirmed both deterministically and under stress -- max
    observed hop count under stress was 1, well inside the limit)
  - path-dependent implementation timing: 2 of 6 real execution paths are synchronous today
    (NestWorld same-owner/direct, Forge server-thread/direct), 4 of 6 are async (NestWorld
    mailbox, NestWorld reroute, Forge worker-thread, Forge-integrated Render-thread) -- ALL
    6 physically measured, not assumed
  - no public completion guarantee: `setVelocity()` returning must never be read as "the
    mutation is visible now," even on the paths where that happens to be true today
  - ordering inversion is possible (confirmed deterministically, mechanism identified,
    NOT a contract violation -- no ordering was ever promised) -- decided fix:
    `LATEST_INTENT_WINS`, versioned per-TargetKey, NOT yet implemented (deferred to API-1A.8)
  - zero ownership violations, zero duplicate applies, zero stale-owner applies observed
    across all deterministic tests AND a sustained real-world-shaped stress run

Known infrastructure gap:
  NestWorld integrated harness (tracked as API1A7-NW-INTEGRATED-001, a separate future
  engineering deliverable -- building a client-capable ForgeGradle project against the real
  patched build -- not a variation on this gate's own approach)
```

**This is the actual result of the whole gate, not a formality.** Two full audits (`damage()`,
block interaction) were rejected before `setVelocity` was even chosen as API-1A.0; the primitive
itself required a portability PoC, a full implementation pass, and — after multiple
harness-fixing iterations across ~10 dispatched verification passes — a real ordering-inversion
finding that changes how every future Bounded Mutation Primitive must be designed. **API-1A.8
(Temporal Contract) does not start automatically from this closure** — it is a separate,
explicitly-scoped future gate, per standing instruction not to chain a design-correction phase
directly onto a measurement phase's own momentum.

---

# SUPERSEDED CONTENT BELOW — original pre-audit draft, kept for the record only

Everything from here to the end of this document is the **original API-0/API-1 draft**, written
before the two follow-up audits and the 10-case study. It is fully superseded by "Phase API-0.5
findings" and "Phase API-0.5 — CLOSED" above — including its `submitAt`/`submitEntity(Runnable)`/
`ExecutionHandle`/`ExecutionContext`/`BackendResolver` design, which was explicitly rejected. Do
not treat anything below this line as current design. Kept intact rather than deleted, matching
this document's own "git is better reference material, but a decision's reasoning is still worth
keeping" precedent (see the Milestone Plan section further down, which already carried a similar
note before this one did).

## Phase API-0.5 — Mod API safety model (SUPERSEDED — see "Phase API-0.5 — CLOSED" above)

**Central question**: does the NestWorld Mod API need generic `submitAt`/`submitEntity(Runnable)`
at all, or should the portable surface primarily export **closed-set semantic operations**, using
the already-proven `EntityMutationDispatcher` model, extended to other resource kinds?

Three candidate shapes to evaluate, not pre-selected:

1. **Closed-set capability API** — `NestWorldEntities.damage(...)`, `NestWorldWorld.setBlock(...)`,
   `NestWorldNetworks.mutate(...)`. Core knows the semantics of every operation, can validate and
   route each one specifically. Directly extends the proven `EntityMutationDispatcher` pattern.
   Known limitation: for AE2/Mekanism-shaped mods with large, open-ended internal logic,
   enumerating every needed operation as a named method may be impractical — this is a real risk
   to this option, not a reason to dismiss it outright.
2. **Constrained execution context** — a third option, not yet explored, distinct from both a bare
   closed-set API and a bare generic `Runnable`:
   ```java
   context.withEntity(entity, access -> {
       access.mutate(...);
   });
   ```
   The callback does not receive unrestricted world-execution capability — only a capability/token
   scoped to the specific ownership domain it was granted for. Needs its own safety-contract design
   (what can `access` actually do; can it escape its granted scope by capturing outer references
   the same way a bare `Runnable` can) before it can be considered safer than option 1 rather than
   merely differently-shaped.
3. **Hybrid** — closed-set API for the common/audited cases, an explicitly-marked "advanced/trusted"
   execution capability (something closer to the original `ExecutionContext.submit(...)` draft) for
   mods with genuinely complex logic that closed-set operations cannot practically cover — with the
   explicit contract that the advanced tier is a trust boundary, not a universal thread-safety
   guarantee, and mods using it accept responsibility the closed-set tier does not require.

**`ExecutionHandle<T>`-shaped completion is provisionally deferred, not rejected outright.** The
audit found *zero* existing precedent for `Future`/handle-shaped completion anywhere in
`net.nestworld.region` — every dispatcher today is fire-and-forget or synchronous-inline. If
adopted, cancellation semantics must be pinned down before the type becomes public API, not
discovered afterward:
- not yet started (still queued) → cancel deletes it, guaranteed no-run;
- already dequeued/claimed by the backend → cancel must not silently no-op *or* silently still
  run — the caller needs to know which happened;
- ownership changed between submit and run → cancel, or reroute per the entity-case precedent?
- already executing → interrupt, or let it finish?
- server stopping mid-flight → what terminal outcome does the handle resolve to?

None of this blocks writing this document's status section, but all of it blocks calling
`ExecutionHandle<T>` frozen ABI.

**Also to resolve in this phase, carried over from the original audit** (flagged `UNKNOWN`, not
`MISSING` — genuinely not investigated yet, not confirmed absent): whether anything in
`net.nestworld.region` already provides a `scheduleAt(delay)`-shaped mechanism for arbitrary
(not fixed-shape vanilla scheduled block/fluid tick) work, or whether this needs building from
scratch alongside whichever shape Phase API-0.5 settles on for `submitAt`/`submitEntity`.

Phase API-0.5 is a design/decision phase, not an implementation one — same discipline as Phase
API-0 itself. Nothing under `net.nestworld.modapi` should be written until this phase's central
question has an answer captured here, with reasoning, the same way this document's other
decisions are captured.

## The problem this solves

A normal Forge mod has client/common/server parts. Its server-side logic is adapted to call
NestWorld's execution API instead of touching Forge/vanilla threading directly. The same mod jar
is installed on both a dedicated server (where NestWorldCore is present) and on players' clients
(where NestWorldCore may or may not be present).

Three real deployment shapes must all work, and **the mod's own code must never branch on which
one it's in**:

```
Dedicated + Core         Dedicated, no Core        Singleplayer (IntegratedServer)
Mod -> API -> Region     Mod -> API -> Compat       Mod -> API -> Compat
  execution backend         backend (Forge/         backend (Forge/NeoForge,
                             NeoForge semantics)      Core absent or dormant)
```

The mod always writes exactly one thing:

```java
NestWorldAPI.submitAt(level, pos, task);
```

Never:

```java
if (NestWorldCore.isLoaded()) ...
if (server.isDedicatedServer()) ...
if (FMLEnvironment.dist == ...) ...
```

## Three concepts, three packages — do not conflate

A naming collision was caught during design and must stay resolved:

| Package | Status | Meaning |
| --- | --- | --- |
| `net.nestworld.adapter` | **Existing**, closed (`Adapter/Core Architecture ТЗ`, #94-#97 PASS) | Narrow **call-site adaptation**: `PlayerAttackAdapter`, `PlayerInteractionAdapter`, `EntityInteractionAdapter`, `PlayerUseItemAdapter`, `PlayerUseItemOnBlockAdapter`, `PlayerUseControlAdapter` — wraps six specific vanilla/Forge call sites and feeds them into Core's own dispatcher. Forge-1.20.1-specific. Not loader-portable, not third-party-mod-facing. |
| `net.nestworld.platform` | **New** (this document) | **Loader/version integration**: `forge1201`, and eventually `neoforge1211` — server lifecycle events, registry API, networking, version-specific Minecraft classes, Forge↔NeoForge differences. Owns the default `CompatibilityBackend` for its loader. |
| `net.nestworld.api` | **New** (this document) | **Stable, loader-agnostic execution contract** third-party mods compile against: `NestWorldAPI`, `ExecutionBackend`, `ExecutionHandle`, `ExecutionContext`, `BackendResolver`. Contains no loader-specific or Core-internal types. |

`net.nestworld.adapter` keeps its existing name and scope unchanged — it is not touched by this
work and is not a synonym for `net.nestworld.platform`.

## Layering

```
                    MOD
                     │
              NestWorld Mod API  (net.nestworld.api — loader-agnostic)
                     │
              Execution Contract
                     │
         ┌───────────┴───────────┐
         │                       │
 Platform Adapter          Execution Backend
 (net.nestworld.platform.*)  (installed per MinecraftServer)
         │                       │
 ┌───────┴────────┐       ┌──────┴───────┐
 │                │       │              │
Forge 1.20.1  NeoForge   Compatibility  Region
 Adapter       1.21.1      Backend       Backend
               Adapter   (per-platform)  (NestworldCore)
```

**Platform Adapter** owns loader/version differences: events, server lifecycle, registry API,
networking, version-specific Minecraft classes. It ships a `CompatibilityBackend` implementation
for its own loader (`ForgeCompatibilityBackend` in `nestworld-adapter-forge-1.20.1`,
`NeoForgeCompatibilityBackend` in a future `nestworld-adapter-neoforge-1.21.1`) and is
responsible for installing it as the baseline backend on every `MinecraftServer` it sees starting
— dedicated or integrated, with or without Core present.

**Execution Backend** owns execution semantics: where a task runs, who the owner is, how handoff
happens, ordering, scheduling, completion. Two families:
- **Compatibility backend** (one per platform adapter): owner = the platform's own server thread.
  Vanilla/Forge/NeoForge semantics, no parallelism, always correct, always available.
- **Region backend** (`NestworldCore`, dedicated-server-only): owner = target region. Region
  ownership / mailbox / handoff — NestworldCore's existing internal execution model, exposed
  through this contract instead of being called directly.

Concretely, for Forge 1.20.1 today:

```
Dedicated + Core:     Mod -> API -> NestworldCore installs RegionExecutionBackend
                                     -> region ownership / mailbox / handoff
Dedicated, no Core:   Mod -> API -> Forge 1.20.1 Adapter -> ForgeCompatibilityBackend
                                     -> MinecraftServer.execute(...)
Singleplayer:         Mod -> API -> Forge 1.20.1 Adapter (client-side) -> ForgeCompatibilityBackend
                                     -> IntegratedServer main-thread execution
```

A future NeoForge 1.21.1 path is architecturally identical at the API layer; only the adapter and
(if ever built) a NeoForge-targeted Core differ. See "Platform parity != implementation parity"
below — this is not a minor footnote, it is the reason the adapter/backend split exists at all.

## Backend resolution — per-`MinecraftServer` instance, not global

A `static volatile ExecutionBackend` was the first draft and is **rejected**: the API module
outlives any single `MinecraftServer`. Singleplayer in particular creates and destroys an
`IntegratedServer` every time a world opens and closes, inside a JVM that keeps running (the
client). A single global field would hold a stale backend — with stale references to a closed
world's region trees, executors, or server instance — across that boundary.

```java
ExecutionBackend backend = NestWorldAPI.backend(server); // resolved per instance
```

**Storage**: `ConcurrentHashMap<MinecraftServer, ExecutionBackend>`, no `synchronized` wrapper.
No more than 0-1 live entries at a time in every realistic deployment (one dedicated-server
process, or one active integrated server) — a plain concurrent map lookup on the hot path is not
a performance concern here and does not need optimizing further.

**`WeakHashMap` was considered and rejected as a safety net.** A `RegionExecutionBackend` (or any
real backend) necessarily holds a reference back to the `MinecraftServer` it serves, to do its
job. `value → server` is a strong path back to the weak key, which defeats `WeakHashMap`'s
collectibility entirely — the map entry is never eligible for GC no matter how "weak" the key
slot is. **Explicit `uninstall()` on the stopping lifecycle event is not a fallback with a GC
safety net behind it — it is the only correctness mechanism, full stop.**

**Defensive check**: `install(server, backend)` must check for an existing, not-yet-uninstalled
entry for that exact key and treat it as a bug (loud log, or a thrown exception in development
builds) — it means some prior lifecycle's `uninstall()` was skipped. This does not fix a missed
uninstall; it makes one immediately visible instead of a silent leak.

## Lifecycle

Every active platform adapter installs its own compatibility backend as the unconditional
baseline, on its loader's server-starting event, for **every** `MinecraftServer` it sees —
dedicated or integrated, with or without Core:

```
platform starting-event(server)
        │
        ▼
NestWorldAPI.install(server, new CompatibilityBackend(server))
```

If NestworldCore is also present **and** the server is a real dedicated server, Core installs
its own backend afterward, on the same event (ordering matters — Core's platform-lifecycle
registration must run after the platform adapter's own):

```
if (server.isDedicatedServer()) {
    NestWorldAPI.install(server, new RegionExecutionBackend(...)); // overrides the compat backend
}
```

On singleplayer, or on any dedicated server without Core, this second install never happens —
the compatibility backend installed by the platform adapter stands as the permanent backend for
that server instance's whole lifetime.

Symmetric teardown:

```
platform stopping-event(server)
        │
        ▼
NestWorldAPI.uninstall(server)
```

After `uninstall`, `backend(server)` for that instance has no entry. This is fine — the instance
is going away and nothing should be calling `submitAt` against it again. The NEXT
`MinecraftServer` instance (world reopened, server restarted) gets a fresh install from its own
starting event; nothing carries over.

**Open question for Phase API-1**: what does `backend(server)` return if called for a server
instance whose starting event has not fired yet (or ever)? Options: throw immediately (loud,
forces correct call ordering); or the resolver itself — not any backend implementation — falls
back to a trivial inline synchronous execution as an absolute last resort. This must be decided
before the skeleton is written, not discovered by whichever behavior falls out of the first
draft.

## Async-first: `ExecutionHandle<T>`

The base primitive is `submit*() -> ExecutionHandle<T>`. Synchronous waiting is a **separate**,
explicit operation, never baked into the primitive itself. Reason: a naive
`submitAt(...).join()` from inside a task already running in region A, targeting region B, while
B's own in-flight task is blocked joining something owned by A, is a straightforward deadlock —
exactly the A→B→A cycle `docs/gmeil/WAIT_MODEL.md` already requires detecting before blocking.
`ExecutionHandle` should mirror `CompletableFuture<T>`'s shape (familiar, well-understood
semantics) rather than inventing new vocabulary, but the compatibility backend and the region
backend must agree on what blocking/waiting is allowed to mean — see Open questions.

## Reuse GMEIL's vocabulary — do not invent a second one

`docs/gmeil/` already has carefully-considered answers for most of `ExecutionHandle`'s hard
questions, for the same underlying problem (a caller on one execution context needing a result
from another). Align with it explicitly rather than drifting into parallel terminology:

- **Ordering** — `docs/gmeil/MAILBOX_CONTRACT.md`'s `DEFERRABLE_COMMAND` ("explicit ordering and
  completion accounting"). `submitAt` for the same owner (position or entity) should carry the
  same per-owner FIFO guarantee that contract already describes; cross-owner ordering is
  explicitly *not* guaranteed, same as today's mailbox.
- **Termination / failure** — `docs/gmeil/FAILURE_MODEL.md`'s `SUCCESS`, `FAILED_CONTAINED`,
  `FAILED_ESCALATED`, `FAILED_FATAL`. `ExecutionHandle<T>` should resolve to one of these, not a
  bespoke enum. An exception thrown by a task is captured on the handle (`Future`-style), never
  left to propagate on the executing thread directly.
- **Cancellation / timeout** — `docs/gmeil/WAIT_MODEL.md`: "a timeout after the receiver has
  claimed the request must not replay it on main"; cancellation is real only before the backend
  has claimed/started the task, best-effort otherwise. Unknown opaque callbacks are not
  retry-safe by default — the same rule applies to a cancelled-too-late `submitAt` task.

## Shutdown / unload semantics (open, must resolve before Phase API-3)

What happens to a pending (submitted, not yet executed) task when: the server is stopping, the
target chunk unloads, the target entity is removed, or region ownership of the target changes
before the task runs. Not decided yet. Candidate rule, to validate during Phase API-3: the
backend re-resolves ownership/target validity immediately before running the task (mirroring
`docs/gmeil/MAILBOX_CONTRACT.md`'s "the receiver must resolve ownership again immediately before
apply") and resolves the handle as `FAILED_CONTAINED` with a clear reason if the target is no
longer valid, rather than either running against stale state or hanging forever.

## Public API surface (frozen for Phase API-1)

```java
ExecutionHandle<T> submitAt(ServerLevel level, BlockPos pos, ExecutionTask<T> task);
ExecutionHandle<T> submitEntity(Entity entity, ExecutionTask<T> task);
ExecutionHandle<T> scheduleAt(ServerLevel level, BlockPos pos, Duration delay, ExecutionTask<T> task);
ExecutionContext currentContext();
boolean isOwnedByCurrentContext(ServerLevel level, BlockPos pos);
boolean isOwnedByCurrentContext(Entity entity);
```

Nothing else is frozen yet. Anything added later goes through the same freeze discipline this
document represents — no quietly growing the surface inside an implementation change.

### `ExecutionContext` naming collision — must be resolved before Phase API-1, not during it

`net.nestworld.gmeil.ExecutionContext` already exists (task/parent IDs, tick/phase, key/policy,
region, token, lease — per `docs/gmeil/POLICY_MODEL.md`). The public, mod-facing
`net.nestworld.api.ExecutionContext` proposed here must be a deliberately thin, sanitized view —
never Region/Mailbox/BSPNode/RegionThread-shaped — but it is not yet decided whether it *wraps*
the internal GMEIL context, is backed by it, or is a genuinely separate concept that happens to
share a name (GMEIL classifies opaque third-party code for thread-safety policy; this context
gives an *adapted* mod an ownership/execution token). Pick one deliberately in Phase API-1's
design, don't let them drift apart by accident.

### Hard boundary: no Core internals leak into `net.nestworld.api`

`Region`, `Mailbox`, `BSPNode`, `RegionThread`, `WorldRegion`, or any other Core-internal type
must never appear in `net.nestworld.api`'s public surface, in a method signature, or in a
returned object's declared type. `RegionExecutionBackend` (living in `NestworldCore`, not in the
api module) may use all of them internally — that boundary is exactly what makes the contract
loader- and Core-portable in the first place.

## Platform parity != implementation parity

**This is the rule that justifies the whole adapter/backend split, not a footnote.** Forge 1.20.1
and a future NeoForge 1.21.1 target are allowed — expected — to use fundamentally different
instrumentation techniques underneath the identical API contract:

- Forge 1.20.1 (current core): `patches/` + `genPatches` — vanilla source forked and compiled
  directly into one fused, non-modular jar.
- NeoForge 1.21.1 (if ever built): **cannot** use that technique. Confirmed live, 2026-09-05,
  with a real `java.lang.module.ResolutionException`: NeoForge's ModLauncher builds a genuine
  JPMS module layer at startup, and JPMS hard-forbids two modules ("nestworld" and "minecraft")
  from sharing a package ("split package") — a JVM-level rule, not a NeoForge quirk. The
  confirmed working mechanism instead: Mixin (NeoForge ships it built-in) for behavior
  modification, plus NeoForge's Data Attachments API (`net.neoforged.neoforge.attachment.*`) for
  extra runtime state on vanilla objects. See `docs/NEOFORGE_1211_DIFF_AUDIT.md` and
  `docs/B3_MIXIN_VERTICAL_SLICE.md` for the full evidence trail.
- Also already confirmed real for a NeoForge adapter's lifecycle hookup specifically: NeoForge
  has no single `TickEvent` — it's split into separate `ServerTickEvent` / `PlayerTickEvent` /
  `LevelTickEvent` / `EntityTickEvent`. Whatever hooks `install`/`uninstall` to server
  starting/stopping will need real per-platform restructuring, not a mechanical rename.

The API must never require a shared instrumentation technique between platforms — only a shared
contract. If supporting NeoForge ever forces a change to `net.nestworld.api` itself, that is a
signal the abstraction boundary leaked Forge/Core specifics into the contract, and the boundary
needs fixing, not the NeoForge side.

## Milestone plan

*(Original plan as first drafted, kept intact for the record — superseded by the "Status"
section at the top of this document. `nestworld-api`/`net.nestworld.api` below should be read as
`net.nestworld.modapi`, and Phase API-1 as BLOCKED BY API-0.5, not as the immediate next step.)*

```
Phase API-0 — Contract               docs/NESTWORLD_API_ARCHITECTURE.md (this document)
Phase API-1 — Skeleton                nestworld-api: ExecutionBackend, ExecutionHandle,
                                       ExecutionContext, BackendResolver
Phase API-2 — Forge 1.20.1 compat     net.nestworld.platform.forge1201, ForgeCompatibilityBackend,
                                       lifecycle registration
Phase API-3 — Core integration        RegionExecutionBackend, install/uninstall, mapping onto
                                       existing dispatcher/ownership/mailbox primitives
Phase API-4 — Compatibility tests     Forge dedicated without Core; Forge integrated (singleplayer)
                                       without Core; Core dedicated; stop -> new MinecraftServer
                                       instance -> confirm no stale backend; backend leak-detector
                                       fires correctly if uninstall is skipped
Phase API-5 — NeoForge 1.21.1         separate platform implementation, same API contract,
                                       deliberately NOT started alongside API-1
```

**NeoForge is deliberately excluded from Phase API-1.** Forge 1.20.1 + Core must first prove the
abstraction boundary is right on their own. NeoForge afterward becomes the portability test: if
supporting it requires changing `nestworld-api`, the boundary was wrong and Forge/Core specifics
leaked into the contract — better to find that out on the second platform than to have designed
the contract *for* two platforms and gotten it wrong for both.

Phase API-4's "stop -> new `MinecraftServer` instance" test is the one that actually *proves* the
per-instance backend lifecycle fix, not just argues for it on paper — same discipline as
`NestworldPins`'s existing `selfTestPersistence()` (a fresh instance standing in for a crash,
verifying accumulated state survives correctly across the boundary).

## What is explicitly not decided yet

- Exact return value / resolver behavior for `backend(server)` before any starting event has
  fired for that instance.
- Whether `net.nestworld.api.ExecutionContext` wraps, is backed by, or is independent of
  `net.nestworld.gmeil.ExecutionContext`.
- Exact cross-owner ordering guarantee (if any) beyond per-owner FIFO.
- Exact shutdown/unload/ownership-change resolution rule for a pending task (candidate proposed
  above, not validated).
- Whether `ExecutionHandle<T>` literally extends `CompletableFuture<T>` or only mirrors its
  shape.

None of these block writing `docs/NESTWORLD_API_ARCHITECTURE.md` itself, but all of them block
calling Phase API-1 "done" — they must be resolved, in code, with the reasoning captured, before
the skeleton is considered frozen.
