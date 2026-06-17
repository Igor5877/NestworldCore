# Live chunk-gen spike — finding, design, and why it's not shipped yet

## Measured finding (2026-06-16, 47.4.111, 299-mod pack)

`/forceload add` of **256 fresh chunks** (heavy custom gen: TerraBlender / LostCities / YUNG) on the
modpack froze the server for **~60 seconds (1199 ticks behind)**. spark of the freeze:
- **Generation itself runs on the `Worker-Main` pool** (off-main) — that part is fine.
- The **main `Server thread` is stuck in `managedBlock`** (`MinecraftServer.m_6367_` pollTask →
  `ServerChunkCache.getChunk` → `CompletableFuture$AsyncSupply.run`): it drains a **flood of
  chunk-promotion tasks** (the main-thread portion of each chunk's status upgrade to FULL) as the 256
  chunks complete — thousands of tasks in one between-tick drain → the tick can't advance.
- Region threads were **idle** during this (it's a main-pipeline problem, not region-block).
- Structures/features dominate the gen cost (~2× the noise cost), as expected for this pack.

**Pre-generation is not an option** — the world is 1,000,000 × 1,000,000 with custom gen → terabytes.
On-demand gen only writes the explored area to disk (generated once, then loaded), so disk grows with
exploration, not world size.

**Scope nuance:** 256-at-once is the *worst case* (mass forceload / teleport / elytra). A player
*walking* generates only a few chunks/sec → the worker pool + gradual promotion handle that without a
freeze. So the spike mainly hits **mass-gen** events, not normal walking.

## Design (Gemini-validated) — two approaches

### A. Cap promotions per tick (the real fix, more invasive)
In the main thread's chunk-promotion processing (Gemini points at `ChunkMap.tick` /
`ServerChunkCache`'s task drain that applies completed `CompletableFuture`s and advances
`ChunkHolder` status), keep a `promotionsThisTick` counter reset each tick; once it hits
`MAX_PROMOTIONS_PER_TICK`, stop applying newly-completed chunk futures and defer them to next tick.
Generation still runs unbounded on workers (no dependency deadlock); the main thread just integrates
results gradually → the 256 chunks stream in over many ticks instead of freezing one.

### B. Limit concurrent gen stages (simpler, but deadlock risk)
A semaphore bounding how many chunks are in the expensive `FEATURES`/`FULL` stages at once. Less
invasive (doesn't touch the main loop) but **risks deadlock**: chunk gen has cross-chunk dependencies
(FEATURES reads neighbours), and a fixed bound on the gen executor can deadlock if dependent tasks
can't get a permit. Vanilla uses a work-stealing pool precisely to avoid this. **Only safe if the
bound is on *starting new top-level requests*, not on the dependency-bearing executor itself.**

### Critical safety (both)
- **Never starve:** player-standing-in chunks (`TicketType.PLAYER`), chunks with ticking entities,
  ticket-expiry processing, already-FULL chunks. Budget applies only to *promotions toward* FULL.
- **Tiered budget:** PLAYER / FORCELOAD tickets bypass or get a generous separate allowance, in the
  `ChunkTaskPriorityQueueSorter` priority order. Capped chunks **must** be deferred to the *next*
  tick (eventual processing), never dropped → no indefinite block / no player falling through world.
- **Results are bit-identical** — gen is deterministic; rate-limiting changes only *when* chunks
  appear (streamed), not their contents.

## Why not shipped yet
This is the **world-loading critical path** — a wrong cap = chunks never load = unplayable world
(worse than the current recoverable spike). The void-air chunk-read change earlier already regressed
TPS once on this path. Approach A's exact promotion point is non-trivial in 1.20.1's future-based
pipeline; approach B has a real deadlock edge. Implementing this safely needs: careful injection,
deadlock-freedom reasoning, and a full validation cycle (forceload before/after + chunks still load +
persist across restart + no corruption) — done with review, not blind.

## Test plan (when implemented)
1. **Trigger:** `/forceload add` 256 fresh chunks (reproducible mass-gen) + a bot walking on the clean
   core (gentle real exploration).
2. **Before/after** on the *same* fresh region: peak `ticks behind` / MSPT during gen. Target: the 60s
   freeze becomes a flat stream (a few chunks/tick) at steady TPS.
3. **Correctness:** chunks actually generate (terrain present at the coords); **persist** (restart →
   loaded not regenerated, `.mca` present); **no corruption / no crash**; spawn entities in the
   generating region → safe; a budget-deferred chunk a player stands in still loads promptly (no
   fall-through / no infinite wait).
4. **Flag-gate it** (`-Dnestworld.chunkGenBudget=N`, default off / unlimited) until validated, so
   default behaviour is untouched.

## Layer 3 (later): predictive frontier gen
Use the measured **70 % idle region-thread capacity** to pre-generate ahead of player movement
(velocity vector → chunks ahead) on a low-priority budget, so the chunk is ready before arrival →
seamless exploration with no pre-gen. Bigger undertaking; build on A/B first.

## Implemented + measured (47.4.114, flag-gated default-off)

`NestworldTuning.CHUNK_GEN_BUDGET` (`-Dnestworld.chunkGenBudget=N`) caps chunk-holder future-updates
(FULL/ticking promotions) per tick in `DistanceManager.runAllUpdates`; deferred holders stay in
`chunksToUpdateFutures`, re-driven next tick (per-tick reset in `ServerChunkCache.tick`), highest
priority (lowest ticket level) first. Deadlock-free, bit-identical.

Measured (256-chunk `/forceload` burst, heavy modpack gen):
- baseline (off): **1199 ticks behind (~60 s freeze)**
- budget=8: 729 · budget=2: **636 (~32 s)** → a **partial ~47 % reduction**, not elimination.

Why partial: the cap paces the terminal promotion, but the heavy cost is generation itself
(NOISE→FEATURES→FULL, cascading to neighbours via getChunkRangeFuture), scheduled via
getOrScheduleFuture — NOT the capped path. Full fix needs pacing gen scheduling (cross-chunk
deadlock risk → careful design, not blind).

No regression on vanilla: clean core, a real bot teleported into fresh terrain → 40–105 ticks behind
with budget OFF vs 40–99 ON (vanilla gen is cheap, no freeze, budget is a no-op). The win is specific
to HEAVY custom gen. Chunks still generate, 0 crashes, bot stayed online. Default off.

## Tiered cap (47.4.115, Gemini-validated 2026-06-17)

Gemini's review (gemini-2.5-flash) confirmed the budget is the *right layer* — it already throttles the
**initial ticket-driven gen requests** (the `chunksToUpdateFutures` entry), while the neighbour cascade
(`getChunkRangeFuture` → `getOrScheduleFuture`) runs unbounded on the worker pool, so it is
deadlock-free by construction. Its one strong recommendation: **never throttle player-driven gen.**

Implemented in `DistanceManager.runAllUpdates`: the budget is now **tiered**. Each holder in
`chunksToUpdateFutures` is checked for a `TicketType.PLAYER` ticket (`nestworldHasPlayerTicket`, a
read-only `tickets.get` — no allocation):
- **Tier 1 — player-driven** (chunk a player stands in or within view distance): promoted **every tick,
  unthrottled**, so live exploration stays smooth and no player falls through the world. Its neighbour
  cascade was never in this set, so no deadlock.
- **Tier 2 — bulk** (mass `/forceload`, distant/teleport tickets, no player ticket): paced by the
  per-tick budget, lowest ticket level first; deferred holders re-drive next tick.

Validated on the clean core (47.4.115, budget=2, a real bot, `doMobSpawning false`, sparks
`1a2510D1Nt` + `xwrsmb225A`):
- **Tier 2** — `/forceload` of 256 fresh chunks far from the bot: server held **5–9 ms/tick @ 20 TPS**,
  no freeze, terrain generated + persisted (`r.1367.*.mca`).
- **Tier 1** — bot teleported through three fresh regions: player gen ran **unthrottled** (brief MSPT
  bumps 32–65 ms, recovered to 20 TPS immediately), **bot stayed online** the whole time, terrain
  persisted (`r.976/1015/1016.*.mca`).
- **0 crashes / 0 tick-loop errors**; bit-identical world. The contrast (bulk paced low, player allowed
  to spike) is the tiering working as designed: on heavy custom gen the same mechanism caps the bulk
  freeze (the measured ~47 % at budget=2) while keeping interactive play unthrottled.

Still default-off (`-Dnestworld.chunkGenBudget=0`). Remaining work = **predictive frontier** (Layer 3
below): pre-generate ahead of player movement on idle region-thread capacity, leaving the throttle only
as a safety valve for mass forceload — Gemini agreed this is the superior end-state for normal play.
