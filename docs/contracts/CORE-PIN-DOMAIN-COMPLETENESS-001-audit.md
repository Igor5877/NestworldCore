# CORE-PIN-DOMAIN-COMPLETENESS-001 — audit (no code)

Status: **AUDIT COMPLETE — contract DECIDED 2026-10-28 (§7)**. Opened by owner decisions 2026-10-28 (7) after
AE2-PIN-LEAK-001 (AE2-COMPAT-CLOSURE-001). Question: what does a pin (entity type / BE type / mod namespace)
guarantee, and which world-execution entry points of a pinned mod still run on region threads?

## 1. Historical contract — what was promised

| source | wording | implies |
|---|---|---|
| commit `afafae17d` (2026-06-14) | "extend thread-pinning to block-entity types" | ticking |
| commit `0d7231315` (2026-06-14) | "**pin whole mods to main-thread ticking** … forces every entity AND block entity from that namespace onto the main thread — the escape hatch for a mod that isn't thread-safe at all" | mechanism: ticking; intent: whole unsafe mod |
| `NestworldPins` class javadoc | "Registry of entity types pinned to main-thread ticking … trading that type's parallelism for **100% correctness**" | mechanism: ticking; claim: full correctness |
| `NestworldPins.pinnedMods` | "every entity AND block-entity from these mods is pinned to main. The escape hatch for **a whole mod that isn't thread-safe**" | intent: whole mod |
| `docs/ARCHITECTURE.md` | "entities/block-entities can be pinned to **main-thread ticking** (vanilla behaviour, just not parallelised) … isolate, don't block" | ticking |
| `docs/NESTWORLD-ADMIN.md` | "тип/мод, що не thread-safe, **тікається** на main-потоці точно як у ваніли (паритет ціною паралелізму)" | ticking, "parity" claim |
| `docs/RACE_CLASSIFICATION.md` (AE2 energy) | "`/nestworld pinmod ae2` **alone does not cover** other mods injecting through capabilities" | limitation already acknowledged |
| mod-namespace auto-pin (`modCallbackErrorCounts`) | pins a mod after errors **caught outside its own tick** (callback class, toms_storage 2026-09-07) | uses a tick pin as the remedy for callback-class failures |

Finding (CONFIRMED by code and history): the **implemented** contract of pin and pinmod is
**"the ticks of this type / mod's entities and block entities run on main, not region-parallel"** (historical
contract B in the owner's terms). Several texts **overclaim** it ("100% correctness", "a mod that isn't thread-safe
at all", "parity"), and the mod auto-pin applies the tick pin to callback-class failures it does not move. So:
not an implementation bug against the documented mechanism, but a **contract that is narrower than its own
claims** — and insufficient for AE2.

## 2. What a pin intercepts today (all Core consumers of `NestworldPins`)

| consumer | effect |
|---|---|
| `NestworldDimensionRegion.blockEntityTypePolicy` → `runBlockEntityPhase` (and `BlockEntityExecutionDomain`) | pinned BE type / mod → BE tick on main |
| `tickPinnedEntitiesOnMain` | pinned entity type / mod → entity tick on main |
| `BoundaryEntityTransfer` (line 157) | pinned entity never assigned to a region |
| `noteBlockEntityTickError` / `noteEntityTickError` / mod-callback counts | auto-pin bookkeeping only |

No other router consults pins (grep of every `NestworldPins`/`getPins()` use; remaining hits are comments/reuse).

## 3. Entry-point classification for a pinned mod's code

Region threads execute world work by **position** (interior region) regardless of pins. A pinned mod's code runs
on a region thread whenever a region-thread operation reaches it.

| # | entry point | routed by | pin | evidence |
|---|---|---|---|---|
| 1 | BE tick | `runBlockEntityPhase` | **PIN_COVERED** | code; scope-1 fixture `pinned` |
| 2 | entity tick (+ region ownership) | `tickPinnedEntitiesOnMain`, `BoundaryEntityTransfer` | **PIN_COVERED** | code |
| 3 | `neighborChanged` / `updateShape` / `onPlace` / `onRemove` (block callbacks of the pinned mod's blocks) | caller's thread (any region-thread `setBlock` cascade) | **PIN_NOT_COVERED** | CONFIRMED: AE2 S2 stack `MekChurn$DriverBE.tick (region) → setBlock → neighborChanged → MBCalculator` |
| 4 | BE lifecycle: construction, `setBlockEntity`/`clearRemoved`, `setRemoved`, `onLoad` | caller's thread | **PIN_NOT_COVERED** | CONFIRMED: region-thread placement/removal of `ae2:crafting_storage` (S1 mechanism); AE2 readiness itself is AE2-queued on main (scope 2) |
| 5 | scheduled block/fluid ticks of the pinned mod's blocks | `routeScheduledTick` → `interiorRegionFor` | **PIN_NOT_COVERED** | code (no pin check) |
| 6 | random ticks | `queueRandomTick` per region | **PIN_NOT_COVERED** | code |
| 7 | block events (`triggerEvent`) | `runBlockEventsPhase` by position | **PIN_NOT_COVERED** | code |
| 8 | capability callbacks (another mod's region-thread BE calls into the pinned mod's BE/capability) | caller's thread | **PIN_NOT_COVERED** | code; acknowledged in RACE_CLASSIFICATION (AE2 energy) |
| 9 | comparator / redstone reads (`getAnalogOutputSignal`, `getSignal`, `hasAnalogOutputSignal`) | caller's thread | **PIN_NOT_COVERED** | code |
| 10 | player interaction (use, attack, use-on-block, entity interact) | main by default; region thread for the #31.x opt-in offload experiments | **COVERED ONLY INCIDENTALLY** (default main); **PIN_NOT_COVERED** when an offload flag is on — the dispatchers do not consult pins | code |
| 11 | Forge event handlers of the pinned mod fired by region-thread world work (`BlockEvent.*`, `EntityJoinLevelEvent`, …) | firing thread | **PIN_NOT_COVERED** | code |
| 12 | the mod's items inside vanilla entities/BEs (item-entity tick, `inventoryTick`, hopper/dispenser behaviours) — the ticking type is vanilla, not the pinned namespace | owner of the vanilla type | **PIN_NOT_COVERED** | code (namespace check is on the entity/BE type) |
| 13 | the mod's own server-tick / level-tick event handlers | main | **NOT_APPLICABLE** (already main) | — |
| 14 | worldgen / chunk-load callbacks | gen pool / chunk load | **NOT_APPLICABLE** to this gate (separate threads, separate audits) | — |

Summary: pin covers **2 of 12** applicable entry classes (tick/ownership). The AE2 leak is classes 3 and 4.

## 4. Contract options (for decision — no implementation chosen)

- **A. Pin = tick pin (status quo, made honest).** Fix the overclaiming texts; state explicitly that callbacks,
  scheduled/random ticks, block events, capabilities, events and items are not covered; mod auto-pin's "callback"
  trigger documented as best-effort. Pinned-host safety for callbacks then belongs to compat layers (addons).
  Cost: docs only. Consequence for AE2: AE2-BE-READY-001 (production) and AE2-MB-MODIFICATION-001 must be fixed in
  the addon (e.g. ingress gating of AE2 callbacks), not by Core.
- **B. Pinmod = full MAIN execution domain for the mod (owner's candidate).** Every world entry of the mod runs on
  main. Mechanical problem: classes 3/8/9/11 are **synchronous calls inside a caller's operation** (a
  `setBlock` → `neighborChanged`; a comparator read; a capability call that returns a value). They cannot be
  deferred to main without changing vanilla's synchronous update/return semantics, and cannot be run on main
  synchronously without the region thread waiting on main (forbidden pattern, §4). So B per call is not
  implementable as stated; only per **location**.
- **C. Pin zone (candidate derived from B):** pinmod makes the **chunks that host the pinned mod's blocks/BEs**
  part of the MAIN execution domain — the same mechanism the border band already uses (band ⇒ main for BE
  ticks, scheduled ticks, block events). Region threads then do not tick or route work there; synchronous
  callbacks triggered *inside* those chunks run on main naturally. Remaining gap: region-thread cascades that
  reach *into* a pin zone from outside (neighbour of a zone chunk, capability call from a region BE into a zone
  BE) — the same reach problem the band width bounds today; needs its own audit (zone halo vs reach).
  Cost: Core work (zone index, routing for 1/5/6/7, split/merge interaction, performance).
- **D. Separate semantics** (owner's suggestion): `pin <type>` stays a tick pin (A); `pinmod <ns>` gets the
  stronger guarantee (C), possibly as a new mode (`pinzone`) so existing pins keep their meaning.

## 5. Consequence for R1-a scope 3

Scope 3 delivers topology callbacks "in the host's current BE execution domain". Under A, a pinned AE2 host's
domain is MAIN for delivery, while AE2's own synchronous callbacks (classes 3/4) still arrive on region threads —
scope 3 can be built on the canonical resolver unchanged, and the pinned-host ingress problem stays an addon
issue. Under C/D, the resolver gains a zone term (pinned host's position ⇒ MAIN) and scope 3 inherits it without
code changes of its own, because it already resolves through `resolveBlockEntityExecutionDomain`.

Either way **scope 3 does not need a new pin mechanism to be correct**: it needs the answer to "is a pinned host's
domain MAIN" (yes under A, C, D — already what the resolver returns). What differs is whether the AE2-PIN-LEAK-001
remainder is closed by Core (C/D) or by the addon (A).

## 6. Decision requested

1. Normative pin contract: A, C, D, or other.
2. Whether the overclaiming texts are corrected now (independent of 1).
3. Whether scope 3 may proceed on the current resolver semantics (pinned host ⇒ MAIN) while 1 is decided.

## 7. Owner decision 2026-10-28 — refined D

```
CORE-PIN-DOMAIN-COMPLETENESS-001
Current pin contract                     CONFIRMED TICK-ONLY
pin <type>                               TICK PIN — its BE/entity ticking does not execute region-parallel
pinmod <mod>                             MOD-WIDE TICK PIN — same guarantee for all types attributed to the mod
                                         NOT guaranteed: neighbour callbacks, capabilities, scheduled/random
                                         ticks, block events, Forge callbacks, arbitrary synchronous calls
Arbitrary callback serialization         NOT GUARANTEED
Docs claiming full correctness/parity    INCORRECT — fixed (separate docs commit)
Existing AE2 callback leak               NOT a violation of the implemented historical pin contract;
                                         tick-only pin is insufficient for full AE2 compatibility
Future stronger mechanism                PINZONE <mod> — spatial MAIN execution zone (new mode; existing pinmod
                                         semantics NOT changed retroactively)
PINZONE                                  SEPARATE CORE DESIGN GATE: CORE-PINZONE-001 (zone construction/removal,
                                         halo/reach, adjacent blocks, capability reach, redstone propagation,
                                         split/merge, zone lifecycle/movement, performance)
R1-a scope 3                             APPROVED TO START NOW — routes its own AE2 topology callbacks explicitly
                                         through the resolver (pinned host ⇒ MAIN); does not rely on pin
AE2-PIN-LEAK-001                         OPEN
AE2-MB-MODIFICATION-001                  CONFIRMED
AE2 pin removal                          NOT APPROVED
```

Not changed in this pass (runtime text, accurate for ticking): the auto-pin log line and the headers written into
`nestworld-pins.txt` / `nestworld-be-pins.txt` say "(vanilla parity)" about the tick itself.
