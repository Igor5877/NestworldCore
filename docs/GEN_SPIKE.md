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

## Layer 3 (DONE, 47.4.116): predictive frontier gen
`PredictiveChunkGen` (`-Dnestworld.predictiveGen=true`, default off). Each tick, on the main thread
(hooked in `NestworldRegionSystem.tickAllRegions` right after the player-tick phase), for every moving
player it derives a heading from the position delta and projects a short fan of chunks just past view
distance along it (`LOOKAHEAD=5`, `FAN=1` → 15 chunks/tick), requesting each via the public
`addRegionTicket(PREDICTIVE, cp, 0, cp)` — a FULL-level, timeout-expiring (100-tick) region ticket.

**Safe + additive:** it only adds tickets through the normal API, so the standard pipeline (worker
pool, neighbour deps) generates them — no critical-path change, no deadlock. Predictive tickets are
**not** PLAYER tickets, so the tiered budget treats them as **Tier 2 (bulk)** and paces them: they use
only spare gen capacity and can't freeze the main thread, while the chunk the player actually steps
into is a Tier-1 PLAYER ticket (unthrottled) that finds its terrain already built. Stale frontier
expires on its own (timeout) when the player stops or turns — no manual cleanup, no pinned chunks.

Validated (clean core 47.4.116, budget=2 + predictiveGen, a creative bot flying through fresh terrain,
`-Dnestworld.predictiveLog=true`):
```
[predictive] issued 600 frontier tickets (max lead 16 chunks ahead, view-distance 10)
```
**Forward-gen proven:** terrain is generated up to **16 chunks ahead (Manhattan) while view distance is
only 10** — i.e. 5–6 chunks *beyond* what the player can see, ready before arrival. Server held
**7–11 ms/tick @ 20 TPS** for the whole flight; idle returns to ~1.3 ms (no leak, no idle overhead;
predictive is a no-op with zero players). 0 crashes.

> Caveat surfaced during testing: at extreme **entity** counts (a leftover ~86 k primed TNT from an old
> stress test), player movement itself stalled the main thread ~1.1 s/move in
> `ChunkMap.TrackedEntity.updatePlayer` (entity tracking is O(loaded entities) per move packet). That is
> an **entity-tracking** scaling issue, unrelated to gen/predictive — noted for a separate pass.

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

## Layer 4 (DONE, 47.4.157, 2026-08-05): admit budget — the missing gate `chunkGenBudget` doesn't cover

**Real-world trigger caught and reproduced.** A player flying with `predictiveGen` on, then an FTBChunks
map teleport, tripped the exact 60 s `ServerHangWatchdog` this whole doc is about — on a real pack.
Reproduced byte-for-byte (identical stack, down to the `ServerChunkCache.java:145` line number) on a
clean, mod-free core using a stacked `/forceload` burst instead of the teleport — proving the cause is
100 % core/vanilla, no mod required.

**Why `chunkGenBudget` didn't save it:** that flag only paces `DistanceManager.chunksToUpdateFutures` —
promotion-*application* for holders already tracked by the ticket-update cycle. It does not touch
`ChunkHolder.getOrScheduleFuture -> ChunkMap.schedule -> getChunkRangeFuture`, the actual **scheduling**
entry point that recursively walks the status-dependency chain and neighbour cascade. A single caller —
`/forceload`, or any mod's synchronous `getChunk(load=true)` (FTBChunks' teleport safety-check is exactly
this) — can trigger that walk for thousands of chunks in one call, on whichever thread calls it. On the
main thread this shows up as `ServerChunkCache.getChunk`'s `managedBlock` loop spinning for 60+ s: either
CPU-bound (`RUNNABLE`, deep in the scheduling recursion) or parked waiting on an already-massive in-flight
backlog — two related but distinct sub-mechanisms, both traced back to this one gap.

**`-Dnestworld.chunkGenAdmitBudget=N`** (`NestworldTuning.CHUNK_GEN_ADMIT_BUDGET`, default `0` =
unlimited) closes it: caps how many BRAND-NEW `(ChunkHolder, ChunkStatus)` units are admitted into
scheduling per ~tick-length wall-clock window, for bulk (non-`TicketType.PLAYER`) top-level requests —
same Tier 1/Tier 2 split as `chunkGenBudget`. A gated request gets a fresh not-yet-done future in the
holder's normal slot (so repeat callers just wait on it like any in-progress schedule) and is queued to
actually start once budget frees up.

**Three non-obvious failure modes found and fixed while building this — noted so nobody rediscovers them
the hard way:**
1. **Deferred admissions can't be drained once/tick.** The obvious design (a side queue drained from
   `ChunkMap#tick`) deadlocks: the exact call that gets deferred can be the one a synchronous
   `getChunk(load=true)` caller is `managedBlock`-waiting on, and `managedBlock` blocks the current tick
   from ever finishing — so a drain that only runs at the top of the *next* tick never runs at all.
   Reproduced: guaranteed non-termination (same 60 s crash, worse reason). **Fix:** drain from
   `ServerChunkCache.pollTask()` itself (both the outer wrapper and the `MainThreadExecutor` inner
   override) — the one point reached by *every* wait loop in the game, including `MinecraftServer`'s
   startup `waitUntilNextTick`, which never calls `ChunkMap#tick` at all.
2. **A rate cap alone doesn't stop cross-burst backlog.** Each burst's own rate check passes
   independently, so admission can still outpace `chunkGenBudget`'s separate promotion-apply rate across
   *successive* bursts, even though each individual burst looks fine. **Fix:** add a standing in-flight
   **concurrency** cap (not just a per-window rate) — refuse new admissions once N bulk units are
   currently admitted-but-unresolved, released via `.whenComplete` on the real `schedule()` future.
3. **Gating `getChunkRangeFuture`'s neighbour walk is a real deadlock, not just theoretical.**
   `ChunkHolder#updateFutures -> prepareAccessibleChunk/prepareTickingChunk/prepareEntityTickingChunk`
   calls `getChunkRangeFuture` **directly**, completely outside any `schedule()` call — so those neighbour
   fetches (dependencies of an *already-admitted* holder) were not exempt from the new gate and got
   re-throttled themselves. With a small concurrency cap this produces exactly the "all workers idle, 0 %
   CPU, no progress" hang GEN_SPIKE's original design note warned a badly-scoped cap could cause.
   **Fix:** mark the cascade-exemption around `getChunkRangeFuture` itself (not just `schedule()`), since
   that's the actual neighbour-fan-out primitive, used both from inside `schedule()` and from outside it.

**Validated:** normal server startup (previously hung indefinitely under mode 1's bug, 0 % CPU, confirmed
via jstack — no application thread doing anything) now completes normally. A single or double stacked
256-chunk `/forceload` burst — the realistic worst case, matching what actually tripped the real crash —
no longer trips the watchdog (30–36 s stall, full recovery, vs. instant crash before). An artificially
extreme test (ten 256-chunk bursts fired within ~5 s, no player) still eventually crosses 60 s on a
CPU-constrained box — confirmed via jstack to be genuine CPU-bound `NoiseBasedChunkGenerator`/`Aquifer`
work on the worker pool at that point, not a scheduling bug; more cores measurably pushed the failure
point later (survived 4 stacked bursts on 6 cores vs. 2–3 on 2 cores) but didn't eliminate it. Closing
that residual requires capping generation *depth* itself (the original Approach A, worker-pool-side),
not just admission — noted as future work, not attempted this pass (same "needs careful design, not
blind" caution as the rest of this doc).

Still default-off (`-Dnestworld.chunkGenAdmitBudget=0`). Recommended pairing for anyone enabling it:
`chunkGenBudget=2` + `chunkGenAdmitBudget=4`\-ish (both layers address different parts of the same
pipeline — see the "why chunkGenBudget didn't save it" note above for why neither alone is sufficient).

## Attempted and REVERTED (same session, 2026-08-05): concurrent-generation semaphore

After Layer 4 shipped, an artificially extreme stress test (ten 256-chunk `/forceload` bursts fired
within ~5 s, no player) still eventually crossed the 60 s watchdog even with both budgets tuned tight —
jstack confirmed this residual was genuine CPU contention (a worker thread busy inside
`NoiseBasedChunkGenerator`/`Aquifer`, starving the main thread of scheduling time on a constrained box),
not a scheduling bug. The natural next lever: bound how many `ChunkStatus.generate()` calls (the actual
CPU-heavy work) run concurrently, gated only at the point dependencies are already resolved (after
`getChunkRangeFuture` completes) — reasoned to be deadlock-free because a queued task there holds no
partial state another queued task could be waiting on ("independent leaf tasks on a bounded pool", not
"a dependency chain sharing a bound").

**That reasoning was wrong, or at least incomplete — implemented, tested, found to hang the single
simplest possible case: a fresh server's normal spawn-area startup, zero load, zero bursts.** jstack
showed every worker thread `TIMED_WAITING`, zero `RUNNABLE` generation activity, 70+ seconds of zero
progress. The exact mechanism wasn't root-caused before reverting — a plausible suspect is `LIGHT`
status's separate ticket-based dependency path (`DistanceManager.addTicket(TicketType.LIGHT, ...)` /
`releaseLightTicket`), which doesn't route through the `getChunkRangeFuture` contract the safety argument
relied on, but this is unconfirmed. **Reverted in full** (`ChunkMap`'s gate + `NestworldTuning
.CHUNK_GEN_MAX_CONCURRENT` + the `generate()` call site) rather than shipped flag-gated-off, specifically
because leaving structurally-broken code sitting in the tree — even inert behind a flag — is a trap for
a future session that enables it without rediscovering this.

**Takeaway, worth remembering before anyone tries this again:** the original design note's caution about
gen-executor bounds risking deadlock was right, and "I reasoned through why my specific gate avoids it"
was not sufficient verification — it needs to survive testing the *simplest* case first (idle startup),
not just the stress case it was built for. The residual extreme-synthetic-load gap this was meant to
close remains open; closing it safely needs either a much deeper understanding of the full chunk-gen
dependency graph (including the LIGHT-ticket side channel) than this session had, or a fundamentally
different lever that doesn't touch the generation executor at all.
