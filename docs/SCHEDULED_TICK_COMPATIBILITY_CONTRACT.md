# Scheduled Tick Compatibility Contract (2026-08-31)

## Status: contract only — no implementation yet. Supersedes the framing (not the evidence) in
## `SCHEDULED_TICK_LOSSLESS_HANDOFF_SPEC.md`.

## 0. Reframing

The problem is not "`AtomicReference` loses scheduled ticks." That's one symptom. The actual
problem:

> NestWorldCore currently **removes** scheduled ticks from vanilla's `LevelTicks` (irreversibly —
> see Finding A) but does **not** fully take over vanilla's scheduling contract for them once
> removed. It has built a transport, not a scheduler.

Vanilla's own scheduler guarantees, implicitly, by being a single-threaded, single-structure
system: exactly-once execution, no-earlier-than-`gameTime` timing, priority/FIFO ordering, and
truthful `hasScheduledTick`/`willTickThisTick` answers for any code that wants to avoid
duplicate scheduling. The moment an entry leaves `LevelTicks` for a free-running region's own
storage, NestWorldCore becomes solely responsible for upholding *all four* of those guarantees,
not just delivery. This document specifies what "fully take over" means before any code is
written.

## 1. Ownership model

**No global map.** A `ConcurrentHashMap<BlockPos, ScheduledTick>` (or similar single shared
structure) would reintroduce a global lock/contention point across every region and undermine
the reason regions exist. Ownership must stay region-scoped, mirroring how entity/block-write
ownership already works elsewhere in this codebase:

```
WorldRegion
 └── ScheduledTickState          (new, one instance per region, owned by that region only)
      ├── pending                 -- entries handed off from main thread, not yet executed
      ├── due                     -- pending entries whose triggerTick <= region's view of gameTime
      └── dedupIndex              -- (pos, type) -> is there already an entry here?
```

A region only ever holds `ScheduledTickState` for chunks it owns. Split/merge changes which
region owns which chunks — see §6.

## 2. The single source of truth for "is this scheduled"

Vanilla code (see §4 for the full list) calls `hasScheduledTick`/`willTickThisTick` believing
they reflect *all* pending ticks. Post-fix, the true answer is the union of two places:

```
isScheduled(pos, type)
      │
      ├── still in vanilla LevelTicks (not yet routed — e.g. this main-thread tick's own
      │   collection hasn't reached it yet, or it belongs to a border-band/main-bucket chunk
      │   that never leaves vanilla's structure at all)
      │
      └── in a region's ScheduledTickState.dedupIndex (already routed, owned, pending or due)
```

Any call site that must answer "is X already scheduled" needs to consult **both**, in this
order: resolve the chunk's owning region first (cheap — the routing logic already does this
lookup for every scheduled tick today), then check vanilla's own structure only if that chunk
currently routes to the main-thread bucket (border-band/unowned), otherwise check that region's
`dedupIndex`. This is not two independent lookups merged after the fact — the owning-region
resolution *determines* which of the two stores is authoritative for that position, so there is
never a window where both are legitimately consulted for the same chunk at once.

## 3. Interception surface

Intercepting `scheduleTick()` alone (today's approach, via
`NestworldRegionSystem.nestworldIsRegionThread()`/`nestworldEnqueueScheduleTick`) is
insufficient. **Every** vanilla call site that calls `hasScheduledTick`/`willTickThisTick` must
also be redirected through §2's unified lookup, or those call sites will keep consulting only
vanilla's now-incomplete view and keep producing duplicates for owned, routed chunks.

## 4. Confirmed call sites requiring redirection (from source, not inferred)

`hasScheduledTick`/`willTickThisTick` are pure vanilla today — zero calls from `net.nestworld`
or from any existing patch. All of the following are vanilla dedup checks that must be redirected
to §2's unified lookup as part of this fix, not just `DiodeBlock`:

| Class | Site | Purpose |
|---|---|---|
| `DiodeBlock` | `checkTickOnNeighbor` | repeater/comparator: don't re-schedule if already pending |
| `ComparatorBlock` | line 116 | comparator's own compare-tick dedup |
| `TargetBlock` | lines 48, 99 | target block: don't duplicate its power-decay tick |
| `SculkSensorBlock` | line 108 | don't duplicate its power-decay tick |
| `ObserverBlock` | lines 57, 84, 95 | **three** separate sites: pulse start, powered-decay, unpowered re-arm |
| `RedstoneTorchBlock` | line 80 | anti-duplicate for torch burnout re-lighting |
| `LightningRodBlock` | line 106 | decay tick dedup |
| `PointedDripstoneBlock` | line 98 | drip tick dedup |

Observer and redstone torch are especially relevant: both are extremely common in real farms and
sit directly adjacent to comparators/repeaters in typical builds, so Finding B's duplicate-tick
risk is not a corner case for this fix — it's mainstream redstone.

## 5. Ordering and timing

Vanilla's guarantee, per `ScheduledTick.INTRA_TICK_DRAIN_ORDER` (confirmed from source): sort by
`TickPriority` first (7 levels: `EXTREMELY_HIGH(-3), VERY_HIGH(-2), HIGH(-1), NORMAL(0), LOW(1),
VERY_LOW(2), EXTREMELY_LOW(3)`, lower value = runs first), then by `subTickOrder` (a monotonic
insertion counter — FIFO within the same priority). `WorldRegion.ScheduledTickState.pending`
must preserve both: priority-ordered retrieval, FIFO within a priority tier, and must not run an
entry before `triggerTick <= region's own view of gameTime` (per contract item 3 in the prior
spec — `Level.getGameTime()` is confirmed global/unpatched, so "the region's view" should just be
the same global value, not a locally-virtualized one; free-running regions must not treat their
own `localTickCount` as a substitute for `gameTime` when deciding "is this due").

## 6. Migration (split/merge) — confirmed unhandled today

Grepped `RegionSplitManager.java` and `SplitMergeEventLog.java` for `scheduleTick`, `LevelTicks`,
`LevelChunkTicks`, `ScheduledTick`, and `pendingFreeRunningWork`: **zero matches in either file.**
This is not an oversight to patch around — it is unaddressed territory. When a chunk's owning
region changes (split creates a new region owning some of the old region's chunks; merge folds
one region's chunks into another), two things need an explicit answer this fix must provide:

- Any entries in the *old* owning region's `ScheduledTickState` for chunks that are moving must
  transfer to the *new* owning region's `ScheduledTickState` — atomically with the ownership
  change itself, not as a follow-up step (a window where a chunk has a new owner but its pending
  ticks still sit with the old owner is exactly the kind of gap Finding A/B already showed is
  dangerous).
- The dedup index must move with it, so a duplicate-check made against the new owner immediately
  after migration sees the same answer it would have seen against the old owner immediately
  before.

## 7. Invariants (testable, not just descriptive)

For any closed test window (no in-flight ticks at measurement time):

1. `scheduled == executed + pending + lost` and `lost == 0` (lossless — §1 of the prior spec).
2. No `(pos, type, triggerTick)` tuple is present in `executed` more than once (no duplication —
   Finding B).
3. For every executed entry, `executionRealTime`'s corresponding `gameTime >= triggerTick` (no
   early execution).
4. Within a single region and priority tier, execution order matches insertion order (FIFO
   preserved — §5).
5. Post-split/merge, `sum(pending across all regions)` is unchanged by the migration itself (§6 —
   migration must be a pure ownership transfer, not a source of loss or duplication in its own
   right).
6. `FREE_RUNNING` OFF → ON → OFF transitions preserve all of the above — a mode transition is not
   an exemption from any invariant above.

## 8. Regression matrix (Phase 3, per the phased rollout in the prior spec — not started)

**Vanilla mechanics to cover**, each OFF vs ON:

repeater, comparator, piston, observer, redstone torch, water, lava, crops (farmland growth
scheduling), fire, ice/snow (freeze/melt scheduled ticks), plus generic scheduled block updates
and scheduled fluid updates as their own bucket (mechanics not covered by the named list above).

**Specific scenarios, each mapped to which invariant(s) in §7 it's actually testing:**

- `schedule → pending → neighbor update → re-schedule attempt` — tests invariant 2 (dedup). This
  is the direct regression test for Finding B; run it against every class in §4's table, not just
  `DiodeBlock`.
- `schedule → pending → region lag (multiple main-thread ticks pass) → execute` — tests
  invariant 1 (lossless) and 3 (no early execution). This is the direct regression test for
  Finding A / the original organic-load symptom.
- `schedule → region split → ownership migration → execute` — tests invariant 5.
- `schedule → FREE_RUNNING ON → OFF → ON` — tests invariant 6.

Only after this matrix passes cleanly (§7 of the prior spec's Phase 3) does implementation
selection from that document's §6 (architectural candidates) get finalized — this contract
describes what any candidate must satisfy, not which one wins.
