# Scheduled Tick Lossless Handoff — Phase 1 Spec (2026-08-31)

## Status: investigation closed, root cause proven, fix NOT started

## 1. Background

Live symptom reported: under `FREE_RUNNING`, comparators/repeaters sometimes don't fire on
their first trigger, and fluid flow sometimes silently stalls, with no crash/exception. Three
independent investigation branches were run and closed:

- **Organic evidence** (real gameplay load, Region 15): `block produced=2279 consumed=723
  overwritten=1556` (68.3%), `fluid produced=115 consumed=79 overwritten=36` (31.3%). Strong
  correlation, but not a controlled experiment.
- **Synthetic redstone/fluid circuits** (repeater→piston, water/lava cobblestone generator):
  closed as inconclusive/misleading. Three unrelated methodology bugs were found along the way
  (`/setblock` replace-mode suppresses neighbor updates; RCON `run say` always returns an empty
  body regardless of condition result, giving false negatives; `DiodeBlock` input reads from
  `pos.relative(FACING)`, not the opposite side — the test rig's repeater was built backwards).
  Region 3 and Region 10 (used for these tests) were also found to be **structurally incapable
  of ever exercising the free-running interior path** — see §2.
- **Controlled deterministic producer** (`/nestworld scheduledtickstress`): closes the loop.
  Calls `ServerLevel#scheduleTick` directly from the main thread — exactly like vanilla's own
  scheduling call — for N positions in a **confirmed interior** chunk of Region 15. A single
  scheduled tick (`count=1`) was posted and lost on the first attempt:
  `SCHEDULED_TICK_LOST region=15 kind=BLOCK pos=(6250,200,6250) producerTick=416 gameTime=1320499`.
  Repeated at larger scale (30 ticks) with the same result. This is a deterministic, reproducible
  regression, not a correlation.

**This finding is closed and does not need to be re-litigated.** The rest of this document is
about the fix's design constraints, not further proof-gathering.

## 2. Why the border-band checks matter for future test rigs

`NestworldDimensionRegion.interiorRegionFor(cx, cz)` (NestworldDimensionRegion.java:252) requires,
for BOTH axes independently:

```
cx >= region.minChunkX + BORDER_BAND_CHUNKS  AND  cx <= region.maxChunkX - BORDER_BAND_CHUNKS
cz >= region.minChunkZ + BORDER_BAND_CHUNKS  AND  cz <= region.maxChunkZ - BORDER_BAND_CHUNKS
```

With `BORDER_BAND_CHUNKS=2` (default), a region needs **at least 5 chunks of span on both axes**
for any interior chunk to exist at all. Region 3 (`chunks(-7,-15)-(-1,-13)`, 7×3) and Region 10
(`chunks(-7,-5)-(-5,2)`, 3×8) both fail this on one axis — **every position in those regions is
permanently border-band**, routing to the main-thread bucket unconditionally, regardless of
circuit correctness or timing. `/nestworld scheduledtickstress` reports `interior=<id>` or
`interior=NO (border-band or unowned)` for its base position specifically so this can be checked
before trusting a `produced=0` reading as "no loss" instead of "wrong test location."

## 3. Vanilla `LevelTicks` lifecycle (read from source, not inferred)

`LevelTicks<T>` (`net.minecraft.world.ticks.LevelTicks`) holds one `LevelChunkTicks<T>` per
chunk in `allContainers`, plus `nextTickForContainer` (earliest due tick per chunk, for cheap
"is anything due" scanning).

`tick(gameTime, maxTicks, callback)`:

1. `collectTicks` → `sortContainersToTick` finds chunks with a due entry, `drainContainers`
   **pops** entries from each due chunk's own queue (`levelchunkticks.poll()`) into
   `toRunThisTick`, up to `maxTicks` (65536 in our call site) or until no more chunks are due.
2. `runCollectedTicks` drains `toRunThisTick`, and for **each** entry calls
   `callback.accept(pos, type)` — this is the exact point where NestworldCore's
   `routeScheduledTick` lambda runs.
3. `cleanupAfterTick` clears `toRunThisTick`/`alreadyRunThisTick`/`toRunThisTickSet`
   **unconditionally**, at the end of the same `tick()` call.

**Finding A (irreversibility):** by the time `routeScheduledTick` sees an entry, step 1 has
already popped it out of `LevelChunkTicks` — vanilla's own queue no longer has any record of it.
There is no "put it back into vanilla" option once NestworldCore has taken the entry. Any loss
from this point on is *our* loss, not vanilla's, and nothing in vanilla will retry it.

**Finding B (duplicate-scheduling exposure):** `hasScheduledTick`/`willTickThisTick` — the two
methods vanilla code (`DiodeBlock.checkTickOnNeighbor`, among others) calls to avoid re-scheduling
an already-pending tick — only consult `allContainers` (empty for anything already popped in step
1) and `toRunThisTickSet` (cleared at the end of *every* `tick()` call, valid only within the same
call). **Once NestworldCore holds an entry in a free-running region's own pending storage across
multiple real ticks (exactly the backlog scenario that causes loss today), vanilla is blind to
its existence.** A second neighbor-change on the same position during that window will see "no
tick pending" and schedule a **second, duplicate** tick. When the region eventually drains both,
the position ticks twice — violating contract item 2 (§4) independently of the loss problem.
**A lossless handoff that does not also address Finding B is an incomplete fix**: it would trade
"sometimes lost" for "sometimes duplicated" under sustained backlog.

## 4. Lossless Scheduled Tick Contract (accepted, not yet implemented)

For BLOCK and FLUID scheduled ticks specifically (not random ticks, not block events — those
have their own, separately-accepted "may skip a round, self-heals" semantics):

1. No scheduled tick may be silently lost.
2. No scheduled tick may execute twice.
3. No scheduled tick may execute before vanilla's `gameTime` says it's due.
4. Relative order/priority must stay vanilla-compatible (see `ScheduledTick.INTRA_TICK_DRAIN_ORDER`
   / `TickPriority` — not yet audited for this fix; in scope for Phase 2 design).
5. Backlog may grow, but its size must be observable via metrics, never silently reset.
6. If a region can't keep up, the tick stays pending — it is never replaced by a newer batch.
7. Must hold uniformly across: `FREE_RUNNING`, normal barrier-synced region execution, split/merge,
   and mode transitions between them.

## 5. Extended instrumentation model (Phase 1 deliverable, additive to existing counters)

Current `ScheduledTickHandoffMetrics` tracks produced/consumed/overwritten (block/fluid/other,
per region) plus a bounded detail log of overwritten entries (region, kind, pos, producerTick,
gameTime) — sufficient to have proven §1's finding. Not yet tracked, needed before Phase 2 work
starts:

- **routed**: already equal to `produced` today (routing and posting are the same call site) —
  worth keeping as a distinct counter once Phase 2 introduces a real per-entry queue, so
  "entered NestworldCore's ownership" and "successfully hit the region's inbox" can diverge if
  Phase 2 adds any intermediate step.
- **executed**: distinct from `consumed`. Today "consumed" means "the region's free-running loop
  drained the batch and called the Runnable" — it does NOT confirm `DiodeBlock.tick()` (or the
  fluid equivalent) actually ran its logic (`nestworldRunBlockTick`'s underlying `state.is(block)`
  check can still no-op it, same as vanilla). For the invariant below, "executed" should count
  Runnable invocations regardless of the inner no-op check (matching what vanilla itself would
  count as "ran the tick"), separate from the true redstone-logic-level outcome.
- **duplicated**: new. Needs a per-(pos,type,triggerTick) dedup window check at drain time —
  design pending Phase 2, informed directly by Finding B above.
- **pending**: derivable (produced − executed − lost so far), but should be exposed directly as a
  live gauge per region, not just derived after the fact.
- **Invariant to assert in a closed test window**: `scheduled == executed + pending` (i.e. no
  entry silently vanishes from the accounting — lost entries must show up explicitly as `lost`,
  not just be absent from both `executed` and `pending`). Today's `overwritten` counter already
  satisfies "lost is explicit," so this invariant is really `produced == consumed + overwritten`
  plus (once "executed" exists) `consumed == executed` per the no-hidden-no-op requirement above.

## 6. Candidate architectural directions (NOT decided — Phase 2 scope)

The current mechanism is `WorldRegion.pendingFreeRunningWork`: an `AtomicReference<List<Runnable>>`
with `getAndSet` overwrite semantics — a mailbox, not a queue. Options considered, none selected:

- **(a) Queue instead of AtomicReference.** Simplest change, but does not by itself address
  Finding B (vanilla still can't see what's queued), and needs an explicit unbounded-growth
  policy per contract item 5 (a metric, not a silent cap).
- **(b) Region-owned pending-tick structure mirroring `LevelChunkTicks`.** A per-region structure
  that vanilla's `hasScheduledTick`/`willTickThisTick`-equivalent checks could also consult (by
  patching those call sites, the same way `scheduleTick` itself is already redirected via
  `NestworldRegionSystem.nestworldIsRegionThread()`/`nestworldEnqueueScheduleTick`). Closes
  Finding B properly but is architecturally heavier — needs its own ordering/priority semantics
  mirroring `ScheduledTick.INTRA_TICK_DRAIN_ORDER`.
  Genuine open question flagged by the user, not yet resolved: **at what level should "pending"
  ownership live** — still routed through `NestworldDimensionRegion.routeScheduledTick`'s bucket
  step (current shape), or held one level up so it's visible to vanilla's own dedup checks
  (`LevelTicks`-adjacent, not `WorldRegion`-adjacent)? Needs a closer read of exactly which call
  sites invoke `hasScheduledTick`/`willTickThisTick` before deciding.
- **(c) Defer the pop itself.** Modify vanilla's `LevelTicks`/`LevelChunkTicks` so a free-running
  region's due entries aren't removed from vanilla's own container until the region confirms
  execution. Most vanilla-faithful (keeps `hasScheduledTick` truthful automatically), but touches
  vanilla's own collection class more deeply than any patch so far in this codebase — highest risk,
  needs the most scrutiny before being seriously considered.

## 7. Phased rollout (accepted plan, not started)

1. **Phase 1 — spec + instrumentation** (this document + §5's additions). Diagnostic only, no
   behavior change. **Current phase.**
2. **Phase 2 — minimal lossless handoff.** Implements one of §6's directions (or a combination),
   closing both Finding A (loss) and Finding B (duplication). Flagged off by default.
3. **Phase 3 — vanilla regression.** Correctness matrix: redstone (repeater/comparator/lamp/
   piston), fluids (water/lava, including the cobblestone/obsidian generator mechanics that
   tripped up this session's own test rig), crops/farmland, ice/snow. OFF vs ON, both must match.
4. **Phase 4 — modded regression.** Same matrix against real mod-added scheduled-tick users
   (item sorters, mod machines with delayed redstone output, etc.) before considering
   default-on.

Only after Phase 3 passes cleanly does §6's direction get chosen for real; do not pre-select an
implementation before that data exists.
