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

## Layer 5 (DONE, 47.4.158, 2026-08-05): concurrent-generation cap — narrowly scoped after a real deadlock

After Layer 4 shipped, an artificially extreme stress test (ten 256-chunk `/forceload` bursts fired
within ~5 s, no player) still eventually crossed the 60 s watchdog even with both budgets tuned tight —
jstack confirmed this residual was genuine CPU contention (a worker thread busy inside
`NoiseBasedChunkGenerator`/`Aquifer`, starving the main thread of scheduling time on a constrained box),
not a scheduling bug. The natural next lever: bound how many `ChunkStatus.generate()` calls (the actual
CPU-heavy work) run concurrently, gated only at the point dependencies are already resolved (after
`getChunkRangeFuture` completes) — reasoned to be deadlock-free because a queued task there holds no
partial state another queued task could be waiting on ("independent leaf tasks on a bounded pool", not
"a dependency chain sharing a bound").

**First attempt: gated every status. Reasoning was wrong — hung the single simplest possible case,**
a fresh server's normal spawn-area startup, zero load, zero bursts. jstack showed every worker thread
`TIMED_WAITING`, zero `RUNNABLE` generation activity, 70+ seconds of zero progress. Root-caused (not
left as a guess) by reading `ChunkMap.protoChunkToFullChunk` and every `ChunkStatus` registration:
`ChunkStatus.FULL`'s generation task is a bare `callback.apply(chunk)` — it literally just invokes
`protoChunkToFullChunk`, which reads **this same chunk's own prior-status future** via
`getFutureIfPresentUnchecked(FULL.getParent())` — a dependency edge that bypasses the
`getChunkRangeFuture` contract the safety argument relied on entirely. A queued FULL can hold a
concurrency slot while waiting on its own parent status's future, and that parent can itself be stuck
behind FULL's own slot in the queue — a genuine self-referential deadlock, confirmed by testing (not
just reasoned about) once the mechanism was understood.

Checked every other `ChunkStatus`'s generation task body the same way (`STRUCTURE_STARTS`, `BIOMES`,
`NOISE`, `INITIALIZE_LIGHT`, `LIGHT` — all have *access* to the same callback parameter FULL uses, since
they're registered with the same extended `register(...)` overload, but **none of them actually invoke
it** — they all do direct computation over the already-fetched neighbour list instead). Only FULL uses
the callback, and FULL does zero CPU-heavy work itself (it's a pure conversion, not generation) — so
excluding it from gating costs nothing against the throttling goal while removing the entire deadlock
class.

**Shipped: `-Dnestworld.chunkGenMaxConcurrent=N`** (`NestworldTuning.CHUNK_GEN_MAX_CONCURRENT`, default
`0` = unlimited) gates ONLY `NOISE`/`SURFACE`/`CARVERS`/`FEATURES` — an explicit allowlist, not a
FULL-only blocklist, so a future status with a similar hidden self-reference doesn't silently slip in
ungated. A queued generation runs once another gated generation's real future resolves and releases its
slot (`whenComplete`-chained, no thread ever blocks waiting on a permit).

**Validated** (2026-08-05, clean core, 2-core-constrained taskset, `chunkGenBudget=2` +
`chunkGenAdmitBudget=4` + `chunkGenMaxConcurrent=2` together):
- Normal server startup — previously hung indefinitely under the first (unscoped) attempt — completes
  normally.
- A single realistic 256-chunk `/forceload` burst (matching the actual real-world trigger) — the server
  is fully healthy immediately after: 28.8 ms/tick, 20.0 TPS, no watchdog trip.
- The artificially extreme ten-burst-in-5s stress test still eventually crosses 60 s (survived 2–3
  bursts before failing, consistent with pre-Layer-5 results within measurement noise) — this residual
  is the same genuine CPU-throughput ceiling identified above, and this layer doesn't claim to close it;
  it closes the *deadlock risk* of trying to, cleanly, so the remaining gap is now a pure hardware/
  scheduling question rather than an open safety question.

**Takeaway, worth remembering before extending this further:** "I reasoned through why my specific gate
avoids the deadlock" is not sufficient verification on its own — it must survive testing the *simplest*
case first (idle startup), not just the stress case it was built for, and the actual mechanism should be
root-caused with evidence (read the dependency's source, don't guess) before either shipping or
reverting. Do not add more statuses to `NESTWORLD_GATEABLE_STATUSES` without re-reading their
`ChunkStatus` registration the same way this session did for all of them.

## Core-count scaling (same session, 2026-08-05): the flags are a low-CPU safety valve, not a universal win

Ran the identical ten-burst-in-5s stress test at 2, 4, 8, and 10 real cores (via `taskset`), both with
the new flags tuned tight and with them fully OFF, to answer "how many cores until this stops mattering":

| Config | Cores | Bursts survived | Time/burst |
|---|---|---|---|
| `chunkGenMaxConcurrent=2` | 2 | 2–3 | 34–38 s |
| `chunkGenMaxConcurrent=2` | 4 | 3 | 24–27 s |
| `chunkGenMaxConcurrent=2` | 8 | 3 (**identical to 4 cores**) | 21–28 s |
| `chunkGenAdmitBudget=4` only | 10 | 4 | 19–26 s |
| **all flags OFF (baseline)** | **10** | **6** | **11–16 s** |

Two findings:
1. **`chunkGenMaxConcurrent=2` caps CPU usage at ~120–180%** regardless of cores available — 4 and
   8 cores gave *identical* survival counts because the flag itself became the ceiling, not the
   hardware. Confirmed live via `top`: `chunkGenMaxConcurrent=2` held the JVM at ~120% CPU on a 10-core
   box; disabling it let the same box burst to 500%+ during a wave of concurrent generations.
2. **On strong-enough hardware, the fully-unthrottled baseline outperforms every throttled
   configuration on this specific worst-case test** — 10 cores with everything OFF survived twice as
   many bursts as any throttled combination, because there was no artificial ceiling limiting how much
   of the real 10-core capacity got used.

**Operational implication:** these flags are a safety valve for *CPU-constrained* boxes (few cores, or
cores shared with a heavy modpack's other load) — they trade raw throughput for a bounded worst-case
main-thread starvation. On a box with cores to spare, they can *hurt* peak throughput under extreme
synthetic load by capping parallelism below what the hardware could actually sustain. There is no
single correct value: size `chunkGenMaxConcurrent`/`chunkGenAdmitBudget` to the box's real spare core
count (roughly: total cores minus what's needed for region threads + main thread + other mods), not
copied from this doc's examples, which were tuned for a deliberately-crippled 2-core test rig.

## Real-pack validation (ATM9, 419 mods, 2026-08-05): the flags alone don't cover a real heavy pack

Deployed `chunkGenAdmitBudget` to a real ATM9 server (GTCEU + Terralith + BiomesOPlenty + TerraBlender +
8 YUNG's structure mods) and reproduced the original watchdog crash byte-for-byte on a mod-free clean
core first, then tested the fix against the real pack directly via RCON `/forceload` bursts (256 chunks,
the command's own max) into genuinely virgin territory.

**Clean A/B on identical terrain** (same coordinates reused across runs — a crashed burst never writes
its region files, so the target chunks stay virgin and can be re-tested with a different flag value
without manual cleanup):

| `chunkGenAdmitBudget` | Result |
|---|---|
| 2 | hard crash (JVM killed instantly, no graceful save) |
| 4 | 3 consecutive bursts: 17.9 s / 33.1 s / **crash on #3** |
| 8 | 61.8 s (over the 60 s threshold!) — watchdog fired, but the main thread finished the tick moments later and the server survived via a graceful shutdown path |

**Lower budget was not safer** — the opposite of the synthetic core-scaling test above. On this pack's
much higher per-chunk cost (confirmed via crash-report thread dumps: the one RUNNABLE worker was
consistently inside `net.minecraft.util.CubicSpline`/`DensityFunctions$Spline` — the noise-router
evaluation that Terralith/BiomesOPlenty/TerraBlender blow up with hundreds of spline control points per
biome, vs. vanilla's ~10), throttling admission hard enough starves total throughput more than it saves
from thrash reduction — a real burst takes *longer* wall-clock at a lower budget, not shorter.

Adding `chunkGenMaxConcurrent=4` alongside `chunkGenAdmitBudget=4` closed the specific 3-burst-in-a-row
failure (48.0 s / 32.6 s / 28.2 s, all survived) — but this was still just moving the ceiling, not
removing the actual bottleneck. See below.

## Layer 6 (DONE, 47.4.160, 2026-08-05): parallel-dispatch fix — the actual root cause

The 60 s stall was never really about CPU cost. It was about **vanilla's `/forceload` being serial by
design**: `ServerLevel.setChunkForced` (`ServerLevel.java:1305`) calls `this.getChunk(x, z)` — a
*blocking* call — and `ForceLoadCommand.changeForceLoad` calls `setChunkForced` in a plain loop over
every chunk in the requested area (up to 256). Each iteration fully blocks on `managedBlock` before the
next one even starts. A 256-chunk burst therefore pays for the **sum** of all 256 chunks' generation
time on the main thread, one at a time — never the max of however many the worker pool could run in
parallel, even though each individual chunk's generation already fans out across the whole
`ForkJoinPool`.

**Fix** (patch-tracked: `ServerChunkCache.java`, `ServerLevel.java`, `ForceLoadCommand.java`):

1. `ServerChunkCache.getChunkFutureMainThread` — visibility only, `private` → `public`. Already existed
   as the non-blocking primitive underneath the blocking `getChunk`/`getChunkFuture` wrappers.
2. `ServerLevel.addForcedChunkAsync(x, z)` — new method, same SavedData/ticket bookkeeping as
   `setChunkForced`'s add branch, but returns the chunk's load future instead of blocking on it.
3. `ForceLoadCommand.changeForceLoad` — the "add" branch now calls `addForcedChunkAsync` for every
   position first (collecting futures, never blocking mid-loop), then does **one**
   `p_137686_.getServer().managedBlock(CompletableFuture.allOf(futures)::isDone)` after the loop. The
   "remove" branch is untouched (never blocked in the first place).

This still routes through `ChunkHolder.getOrScheduleFuture` → `nestworldTryScheduleNow`, so
`chunkGenAdmitBudget`/`chunkGenMaxConcurrent` still gate it exactly as before if enabled — the fix only
removes vanilla's *extra*, unnecessary seriality sitting on top of that gating, it doesn't bypass it.

**Real-pack result** — the same 3-burst-in-a-row test that crashed at every tested `chunkGenAdmitBudget`
value alone:

| Config | Burst 1 | Burst 2 | Burst 3 |
|---|---|---|---|
| `chunkGenAdmitBudget` alone (any value 2–8) | varies | varies | **crash** |
| fix + `admitBudget=4` + `maxConcurrent=4` | 48.0 s | 32.6 s | 28.2 s (survived) |
| **fix alone, both throttle flags OFF** | **40.2 s** | **20.0 s** | **21.4 s** (survived) |

The fix alone — with *no* throttling — was faster and more consistent than the fix combined with both
throttle flags. One run with the fix + throttle flags together on fresh untouched coordinates (build
47.4.160, `admitBudget=4` + `maxConcurrent=4`) still crashed at 60.00 s despite the fix being active; the
crash-report thread dump showed **every** `Worker-Main` thread `WAITING` (zero active generation work)
at the moment of the freeze — live `top` during the burst showed only ~2 threads pegged at 100%, well
under the hardware's 10 cores. This suggests the throttle flags can starve the parallelism the fix just
unlocked, rather than complementing it — not fully root-caused.

**Current recommendation for heavy packs:** ship the parallel-dispatch fix, leave
`chunkGenAdmitBudget`/`chunkGenMaxConcurrent` **off** (`0`, the default) and let the fix's own
parallel-future-then-wait-once shape use the box's real spare capacity directly. Only reach for the
throttle flags on a genuinely CPU-constrained box, and re-verify they don't fight the parallel dispatch
before trusting the combination.

## Layer 7 (measured, 2026-08-05): live per-thread monitoring — GC pressure, not scheduling, is the real ceiling

Two follow-ups after shipping Layer 6, both found by watching `top -bH` (per-thread CPU%, 0.5 s
sampling) live during a burst instead of only reading before/after timing numbers.

**A forgotten flag was still gating every "no throttle" test above.** `chunkGenBudget` (Layer 3/4, an
older flag from a *previous* session — caps the DistanceManager's FULL/ticking-promotion rate,
independent of `chunkGenAdmitBudget`/`chunkGenMaxConcurrent`) had been left at `2` on the real ATM9
deployment the whole time, including during every "both throttle flags OFF" run in the table above.
Disabling it too dropped the first-burst-into-virgin-territory time from 40.2 s to **28.7 s**. Lesson:
when re-testing "unthrottled" behavior on a long-lived deployment, grep the actual live
`user_jvm_args.txt` for every `chunkGen*` flag, not just the ones being actively iterated on — an older
flag from a different layer of this same doc is an easy thing to forget is still active.

**With every chunk-gen flag off, real concurrent Worker-Main utilization is still sparse — because GC,
not thread scheduling, is eating the CPU.** Sampling `top -bH -p <pid> -n 1` every 0.5 s through a full
28.7 s burst (fix active, all three `chunkGen*` flags off) found `Worker-Main`/`ForkJoinPool` threads
above 50% CPU concurrently in only a fraction of samples — usually 0–2 active, with brief spikes to 5–9
— never a sustained wide wavefront using most of the box's 10 cores. In the *same* samples, **G1
Concurrent GC threads sat at 80–99.9% CPU in 53 of ~85 samples (62% of the burst's wall-clock time)**,
directly competing with generation workers for the same cores. Heavy worldgen mods (GTCEU ore-vein data,
Terralith/BiomesOPlenty biome resolvers, structure piece lists) allocate a lot of short-lived garbage per
chunk; G1 can't keep the young generation clear fast enough without eating a large, sustained share of
CPU right alongside the ForkJoinPool.

This reframes the whole investigation: none of the chunk-gen flags in this doc — admit budget,
concurrency cap, or the Layer 6 parallel-dispatch fix — can produce anything close to linear scaling
with core count on a heavy pack, because the actual bottleneck for a big chunk burst is memory-allocation
pressure on the collector, not how many chunks get dispatched to workers at once. **Not investigated
further this session** — the natural next lever is JVM/GC tuning (larger young generation via
`-XX:G1NewSizePercent`, more/fewer `-XX:ConcGCThreads`, possibly evaluating ZGC/Shenandoah for lower
concurrent-collection overhead under this allocation pattern), not another chunk-gen-specific flag in
NestworldCore itself.

## Layer 8 (FIXED, commits fc03414bb/31d84d667, 2026-08-07): repeated-forceload freeze — admit-rate/concurrency conflation

A regression of the exact same watchdog symptom, found via a 9-call `/forceload` grid reproduction on
real ATM9 (419 mods): a bulk-forceload sequence would crash on call 4–6 (varying between runs) even
with Layer 6's parallel-dispatch fix and Layers 4/5's throttle flags all active (`chunkGenBudget=2` +
`chunkGenAdmitBudget=4` + `chunkGenMaxConcurrent=4`).

**Investigation ruled out, in order, with live evidence each time (not guessed):**
1. **GC pauses** — `-Xlog:gc*` showed zero GC activity during the freeze window (longest pause found
   anywhere in the whole test: 1.1 s). Directly contradicts the Layer 7 GC-pressure finding being the
   cause *here* — Layer 7's finding is real for raw generation throughput, just not what's freezing this
   specific repeated-call scenario.
2. **cgroup CPU throttling** — `cpu.max` unlimited, `nr_throttled=0`.
3. **Simple lock contention with one thread starved** — per-thread `pidstat -t` showed the Server
   thread's own CPU steady at 8–11% the whole freeze, no other single thread pegged near 100%.
4. **JDK Flight Recorder execution sampling** (round 6 of diagnostics) found the Server thread genuinely
   *executing* the whole 40+ s gap — not parked, not blocked — inside `DistanceManager`/`LightEngine`/
   `ChunkSerializer`/our own `nestworldDrainDeferredAdmissions()` call chain. This looked at the time like
   the real root cause (vanilla's ticket-propagation algorithm doing legitimately expensive work) and was
   the working theory for a Gemini design-review round recommending a ticket-batching fix.
5. **Root cause, found by directly testing the accumulation hypothesis** rather than accepting the JFR
   read at face value: cleared all forced-chunk tickets between every call (`forceload remove all` after
   each `add`) — the crash still happened, on a *clean* ticket slate. This ruled out cross-call ticket
   accumulation. Followed by an isolated single-call test (zero prior forceload calls that session, both
   on never-touched terrain and on terrain known already generated) — **both hit the same ~20 s stall**,
   ruling out fresh-terrain-generation cost too.
6. **Actual root cause:** `ChunkMap.nestworldHasAdmitCapacity()` (Layer 4's admit gate) checked
   `nestworldInFlight` against `CHUNK_GEN_ADMIT_BUDGET` — the SAME constant that also governs the
   per-window admission RATE. A routine forceload burst (9–16 chunks) needs up to ~4 gateable statuses
   per chunk (`NOISE`/`SURFACE`/`CARVERS`/`FEATURES`), producing 40–60+ competing top-level scheduling
   units — with the in-flight cap pinned at the rate's own small value (4), these serialize into 15+
   sequential rounds, each bounded by its slowest member. This is a different mechanism from Layer 7's
   GC-pressure finding (that's about raw allocation cost during real generation; this is about admission
   bookkeeping artificially serializing work that was never CPU-bound in the first place) — both are real,
   independent bottlenecks that happened to produce a similar-looking stall.

**Direct A/B confirming the diagnosis** (real ATM9, same pack/world, isolated single forceload call,
9–16 chunks, fresh coordinates each time):

| Config | Result |
|---|---|
| `chunkGenAdmitBudget=4`/`chunkGenMaxConcurrent=4` (shipped Layer 4/5 defaults) | 20 s+ (hit the Layer 6b timeout fallback) |
| Whole admit gate disabled | **0.78 s** — faster than vanilla stock Forge on the identical pack/world (10.33 s) |

**Fix — decouple the rate from the standing concurrency cap** (`ChunkMap.nestworldHasAdmitCapacity()`,
commit `31d84d667`): new `NestworldTuning.CHUNK_GEN_ADMIT_INFLIGHT_CAP`, independently configured,
default `0` = unlimited (rate-only pacing — the in-flight cap only re-engages if explicitly set).
`CHUNK_GEN_ADMIT_BUDGET` is now purely the per-window rate, no longer also a hard concurrency ceiling.

**Validated in two stages, per this doc's established discipline (gen-spike-repro first, then real
pack):**
- **gen-spike-repro**, `chunkGenAdmitBudget=4` (matching ATM9's problematic value), inflight cap left
  unset: a moderate forceload burst dropped from 20 s+ to **0.45 s**; a stacked 5×256-chunk stress burst
  (1280 chunks total, the exact shape that used to crash by the 4th burst per Layers 4/5's own
  core-scaling table) completed cleanly across all 5 calls (5.6–9.3 s each), no watchdog hit.
- **Real ATM9** (419 mods), restored production config (`chunkGenBudget=2`/`chunkGenAdmitBudget=4`/
  `chunkGenMaxConcurrent=4`, inflight cap still unset/unlimited): re-ran the original 9-call crash-repro
  grid that reliably crashed on call 4–6 every single run that day. **All 9 calls completed cleanly**
  (3.42–26.94 s each, first call slowest as expected for genuinely virgin terrain), zero crashes, zero
  timeout fallbacks.

**Also fixed alongside (commit `fc03414bb`), independently valuable:** the Layer 6b wait-timeout
(`FORCELOAD_WAIT_TIMEOUT_MS`, first shipped to bound the single-call case) used `CompletableFuture.
orTimeout()`, whose deadline fires from a JVM-wide shared scheduler thread — under the load this whole
investigation was chasing, that thread's own timing isn't guaranteed either. Replaced with a plain
`System.nanoTime()` deadline checked *inside* the `BooleanSupplier` that `managedBlock` itself polls on
the main thread, removing the dependency on a separate thread entirely. Stands on its own as defense in
depth regardless of the Layer 8 fix above.

**Takeaway for future chunk-gen throttle work in this doc:** a rate limiter and a standing concurrency
cap are different mechanisms even when they happen to share a config value that "seems like it should
be the same number" — conflating them silently changes what gets bounded (how fast work enters vs. how
much can be outstanding at once) and can make a safety mechanism actively harmful at the very scale
(ordinary, moderate bulk requests) it was never meant to constrain in the first place. When adding a new
throttle, ask explicitly which of the two it is before picking a default.

## Layer 9: first-call-in-a-tick escaped the burst budget (2026-08-10/11)

A real ATM9 crash (`crash-2026-08-10_19.33.51-server.txt`) reproduced exactly the failure class
`GETCHUNK_BURST_BUDGET_MS` (Layer prior to this one) was built to prevent — a single 60-second tick,
watchdog-killed — via a NEW trigger: EvilCraft's `WorldHelpers.foldArea` (called from
`EntityVengeanceSpirit.canSpawnNew`, itself triggered by a Draconic Evolution reactor explosion's
`LivingDeathEvent`) calling `Level.getBlockState()` → `getChunk()` repeatedly, all within one
main-thread event-handler invocation.

**Root cause, found by re-reading `ServerChunkCache.getChunk()`'s FULL+load=true path line by line**:
`GETCHUNK_BURST_BUDGET_MS`'s cumulative-per-tick check (`if (nestworldBurstBudgetRemainingNanos <= 0)
{ ...return proxy immediately... }`) only gated the SECOND and later `getChunk()` call in a tick. The
FIRST call's own wait deadline was still computed purely from `GETCHUNK_WAIT_TIMEOUT_MS` (20s default),
completely independent of the burst budget — so a single genuinely-cold chunk could still consume up to
the full per-call timeout before the burst mechanism ever got a chance to act. 2-3 such calls for
DISTINCT cold chunk positions in the same tick (exactly what a `foldArea` scan over several unloaded
chunks produces) could each independently burn close to 20s, stacking well past the 60s watchdog even
though no single call ever violated its own nominal bound — the same "bounding one call is necessary
but not sufficient" lesson `GETCHUNK_BURST_BUDGET_MS` itself was built to fix, reopened by an edge case
in its own accounting.

**Fix**: cap EVERY call's own deadline (including the first) to whichever is smaller — its individual
`GETCHUNK_WAIT_TIMEOUT_MS` bound, or the tick's remaining burst budget:
```java
long nestworldCallBudgetNanos = Math.min(
        GETCHUNK_WAIT_TIMEOUT_MS * 1_000_000L,
        Math.max(nestworldBurstBudgetRemainingNanos, 0L));
long nestworldDeadline = System.nanoTime() + nestworldCallBudgetNanos;
```
`GETCHUNK_BURST_BUDGET_MS = 0` (disabled) still degrades cleanly to the old pure-per-call-timeout
behavior (`nestworldBurstBudgetRemainingNanos` is `Long.MAX_VALUE` in that case), so the "0 = vanilla-
identical" semantics for both flags are preserved.

**What this does NOT solve** (explicitly scoped out, matching this doc's own established caution about
this critical path): the deeper "arbitrary third-party mod code gets a semantically meaningful READY/
DEFERRED/UNAVAILABLE result instead of a safe-but-possibly-stale proxy" question. For code THIS project
owns (Explosion, BLOCK_WRITE cascades), that already exists via the batch-check-defer-replay pattern
`NestworldGenPool`/the region admission budget implement. For opaque mod code calling vanilla's
synchronous `getChunk()` (EvilCraft here), there is no third option beyond "block" or "return a safe
stand-in now" without rewriting the mod — the `NestworldProxyLevelChunk` (VOID_AIR-until-resolved)
fallback is the existing, already-reviewed, honest answer for that case, unchanged by this fix. The
Watchdog itself was NOT touched, disabled, or its threshold changed — it remains the safety net; if a
60s tick recurs after this fix, that is a signal a DIFFERENT unbounded synchronous path exists
somewhere, not a reason to raise the limit.

**Validated**: added `/nestworld debugsyncburst <x> <z> <count>` — calls `Level.getChunk()` `count`
times in a tight loop within ONE command invocation (guaranteed same tick, unlike issuing `count`
separate `/nestworld debugsync` commands, which land in different ticks over separate RCON round-trips
and each get a fresh per-tick budget — a real methodology trap hit while testing this). 15 genuinely
cold chunks in one tick: **repro** — first call 1752ms, next few decreasing (363/242/428/281ms) as
remaining budget shrank, then 7-9ms each (near-instant proxy) once exhausted — TOTAL 3151ms. **ATM9**
(419 mods, real modded worldgen) — TOTAL 3005ms for the same 15-in-one-tick shape. Both essentially
exactly the ~3000ms burst budget, confirming the fix bounds the WHOLE tick's cumulative cost regardless
of how many cold chunks a single mod call touches — not just calls after the first. Zero exceptions,
zero Mixin errors, 20 TPS maintained on both servers throughout.

## Layer 10: the hard invariant — zero wait, not bounded wait (2026-08-11, same session)

The project owner pushed further after Layer 9: a bounded (3s) wait is still a wait — the real goal
should be a HARD INVARIANT: **"MAIN THREAD НІКОЛИ НЕ МОЖЕ БЛОКУВАТИСЯ НА WORLDGEN"** — not 3 seconds,
not 20 seconds, 0 seconds. Laid out a full architecture distinguishing "gameplay/mod thread" (never
waits) from "worldgen execution context" (`NestworldGenPool`, where synchronous dependency resolution
is fine because it's inside the controlled pipeline, not blocking a game tick) — and was explicit that
a FULLY general fix (arbitrary third-party mod code getting a proper suspend/resume continuation
instead of stale data) is impossible without bytecode transformation or rewriting the mod: "100%
гарантія від блокування + 100% семантична сумісність з абсолютно довільним модом одночасно —
неможлива. Це фундаментальне обмеження, а не недолік." Proposed the practical compromise instead: make
the EXISTING proxy-return machinery (Layer 6b/9) trigger with ZERO wait by default for the gameplay-
thread FULL+load=true path, keep the bounded-budget flags as an explicit opt-out fallback (not
removed), and add violation telemetry so any future regression is visible.

**Implementation — reused 100% of existing machinery, changed only WHEN it's invoked**:
`NestworldTuning.GETCHUNK_ZERO_WAIT_MAIN_THREAD` (default `true`) — the condition that previously only
triggered the immediate-proxy path when the burst budget was already exhausted
(`nestworldBurstBudgetRemainingNanos <= 0`) now ALSO triggers it unconditionally when this flag is on:
```java
if ((GETCHUNK_ZERO_WAIT_MAIN_THREAD || nestworldBurstBudgetRemainingNanos <= 0) && !completablefuture.isDone()) {
   ...return proxy immediately, same NestworldProxyLevelChunk as before...
}
```
Under the default, the bounded-wait code below (Layer 9's fix) becomes structurally unreachable for
the gameplay-thread FULL+load=true path — not just "should be near-zero," but dead code by
construction. `GETCHUNK_BURST_BUDGET_MS`/`GETCHUNK_WAIT_TIMEOUT_MS` are NOT removed — set
`-Dnestworld.getChunkZeroWaitMainThread=false` to fall back to the bounded-tolerance behavior.

**Explicitly still out of scope, matching the owner's own framing**: internal worldgen dependency
resolution (a chunk's own FEATURES/STRUCTURE_STARTS generation needing a neighbor chunk) still uses
the vanilla-identical blocking `managedBlock(completablefuture::isDone)` path (the `else` branch,
non-FULL/load=false calls) — this is INSIDE the controlled generation pipeline in the owner's own
framing, and remains the deadlock-risk area this doc has cautioned about since Layer 1. For operations
this project owns (Explosion, BLOCK_WRITE), the real "operation suspends, resumes once dependencies are
ready" pattern already exists via `NestworldGenPool`/the region admission budget — this flag is
specifically about the opaque-third-party-mod-caller case, where a semantically-perfect result isn't
achievable and a safe stand-in (VOID_AIR until resolved) is the honest tradeoff, same as before, just
now taken immediately instead of after up to 3s.

**Validated**: `/nestworld debugsyncburst` (Layer 9's same-tick test), 15 cold chunks — **repro**:
TOTAL 100ms (was 3151ms under Layer 9 alone). **ATM9**: TOTAL 11ms (was 3005ms). A harsher 50-chunk
repro run: TOTAL 311ms, and `/nestworld chunkstats` confirms the invariant quantitatively — `blocking:
0`, `total wait: 0 ms`, all 65 calls became `proxy creations`. Confirmed a burst-tested chunk still
resolves to real generated terrain in the background (not permanently stuck void) via forceload +
block-state check. **Real ATM9 organic load** (players/mobs, not just the synthetic test) since this
boot: 124,069 total `getChunk()` calls, **100.0% fast-path hits**, only 5 calls ever reached the
blocking branch at all (the out-of-scope internal-dependency path) — cumulative wait from those:
**0 ms** (rounds to zero, i.e. negligible). 20 TPS, 0 `EntityOwnershipGuard` violations, zero
exceptions, zero Mixin errors on both servers throughout.

This is the strongest form of the guarantee available without a mod-rewriting/bytecode-transform
undertaking: not "we tested N chunks and it didn't fall over," but "the gameplay thread cannot reach
a worldgen-blocking code path for this call shape — it's structurally excluded by default." The
Watchdog remains fully enabled and untouched throughout — if it ever fires for a chunk-related stall
again, that is now a strong, specific signal pointing at the one remaining scoped-out path (internal
worldgen dependency resolution), not a reason to raise its threshold.

## The "unlimited generation" test — reconfirms WHY the admission/promotion budgets exist

Asked directly: "а без обмежень по генерації?" (and without generation limits?). Temporarily disabled
`chunkGenAdmitBudget`/`chunkGenMaxConcurrent` on gen-spike-repro (`0` = unlimited/vanilla) and re-ran
the exact 256-fresh-chunk `/forceload` test from Layer 1. Result: chunks resolved almost instantly by
raw generation throughput, but the server hit a genuine **`Can't keep up! Running 13234ms or 264 ticks
behind`** — a real ~13s main-thread stall, live-reproduced on demand. Restored the original settings
afterward (`admitBudget=4`, `maxConcurrent=8` on repro) and confirmed clean recovery, 20 TPS, 0
`EntityOwnershipGuard` violations.

**Important nuance, caught while writing this up**: gen-spike-repro's `chunkGenBudget` (the SEPARATE
promotion-side budget — see below) was already `0`/disabled the entire time (commented out in
`user_jvm_args.txt`), in BOTH the "with admission limits" and "without admission limits" runs — so the
13s stall wasn't caused by toggling a promotion-side cap directly. It confirms something more specific
and, in a way, more interesting: admission-side pacing (`chunkGenAdmitBudget`/`chunkGenMaxConcurrent`,
which control how fast NEW generation work enters the pipeline) has a real SECONDARY effect of
smoothing the completion/promotion side too, purely by preventing 256 chunks from finishing generation
in a tight time window in the first place — even with the promotion-side budget itself left off. ATM9
runs with BOTH `chunkGenBudget=2` (promotion) AND `admitBudget=4`/`maxConcurrent=4` (admission) active
— the more defended default of the two servers.

**Reframed goal** (matching the owner's own correction): not "make main-thread integration
instantaneous" but "make sure 256 chunks completing doesn't turn into 10-13 seconds of ONE tick's
work" — admission pacing already does this indirectly; the dedicated `CHUNK_GEN_BUDGET` promotion cap
(next section) does it directly and explicitly.

## Layer 11: chunk-promotion latency telemetry (2026-08-11, same session)

The owner asked for exactly this after the unlimited-generation test: not just "does it fall over" but
ongoing visibility into the promotion step's own cost, so a REAL regression (or the point where it
starts becoming the bottleneck instead of generation itself) is visible before the next `Can't keep
up!` rather than after. Also correctly noted this project already has the "Approach A" mechanism
GEN_SPIKE.md's own original design section proposed and marked "not shipped yet" — it WAS shipped
since: `NestworldTuning.CHUNK_GEN_BUDGET` (`DistanceManager.nestworldFutureBudget`), a tiered per-tick
cap on `ChunkHolder.updateFutures()` calls (player-ticket chunks never throttled; bulk/forceload-driven
chunks paced, lowest ticket level first, deferred holders redriving automatically next tick) — this
section adds the missing piece: measuring it.

**Implementation**: every `updateFutures()` call site in `DistanceManager.runAllUpdates()` (all 3:
the unbudgeted path, the Tier-1 player-ticket path, the Tier-2 bulk-budgeted path) now routes through
`nestworldTimedUpdateFutures()`, which times the call and records the sample into a 2048-slot ring
buffer (same shape as `WorldRegion.tickDurationsNs`/`getPercentileTickMs`, sized larger since
individual promotion calls happen far more often than region ticks). `/nestworld chunkpromotion`
reports: `pending` (live gauge — `chunksToUpdateFutures.size()` right now), `applied` (cumulative since
boot), `avg`/`p95`/`p99`/`max` (from the rolling window).

**Validated**: baseline on gen-spike-repro (light ambient load): `applied=2,209`, `avg=0.481ms`,
`p95=0.072ms`, `p99=0.285ms`, `max=8.571ms`. Triggered the same 256-chunk `/forceload` batch (with
admission limits back on): `applied` jumped to `6,868` (+4,659 promotions from the batch — matches
~256 chunks × up to 4 gateable statuses each), `pending=0` throughout (never fell behind), latency
distribution actually IMPROVED under load (`avg=0.178ms`, `max=5.895ms`) — direct, quantitative
confirmation that the admission pacing keeps promotion cost small and smooth even during a real burst,
not just "TPS looked fine." ATM9 (real organic load, `chunkGenBudget=2` active): `pending=0`,
`avg=0.038ms`, `p95=0.093ms`, `p99=0.421ms`, `max=16.466ms` (one genuine tail outlier — worth watching
if it recurs, not alarming on its own). Zero exceptions, zero Mixin errors, 20 TPS, 0
`EntityOwnershipGuard` violations on both servers throughout.

## A further idea, deliberately NOT attempted tonight: parallel chunk-promotion/prepare-commit split

The owner's next proposal, after seeing the telemetry request through: split `updateFutures()`'s own
work into a PARALLELIZABLE "prepare" phase (thread-safe pieces: chunk-status-transition computation,
region-owned `ChunkHolder` state, immutable snapshot construction) and a minimal, still-main-thread-
only "publish/commit" phase (reference swap, state update, required listener notification) — using
this project's own region-ownership model to let each region prepare its own ready chunks
independently, so a 256-chunk burst becomes "region A: 8 ready, region B: 3 ready, ..." instead of one
undifferentiated main-thread queue. Framed explicitly as a natural fit for where Stage 5's own
architecture is heading (region independence + a controlled, still-centralized pipeline for what
inherently must stay global).

**Also explicitly self-cautioned by the owner**: this is NOT "just let 8 threads mutate `ChunkMap`" —
that "is almost guaranteed to open a new class of race conditions." Proposed classifying the promotion
path's pieces the same rigorous way this session's Entity Safety Layer audit classified cross-region
entity access, before touching anything:

| Piece | Parallel-safe? |
|---|---|
| Worldgen itself | already parallel |
| Immutable-data preparation | yes |
| Chunk-local state | yes, if ownership is guaranteed |
| Region-owned `ChunkHolder` state | yes |
| Global tickets | needs its own protocol |
| Player visibility | needs care |
| `ChunkMap`'s own global structure | needs care |
| Network publication | main-thread/Netty only |
| Mod callbacks on chunk load | very dangerous — arbitrary mod code, same "can't guarantee semantics for opaque callers" limit as Layer 10 |

**Why this is NOT implemented tonight, deliberately**: `ChunkMap`/`ChunkHolder` is exactly the class
this project's OWN history has repeatedly found subtle bugs in from changes of roughly this shape and
ambition — the "sand duper" incident, the phase-ordering double-tracking bug, the "invisible items"
desync (see project memory: `regionalized-tracker-fix`, `chunkholder-blockchange-race`). A change this
large, touching this specific class, deserves the same design-then-independent-review discipline every
other consequential architecture decision this session got (the Stage 5 tick-scheduler architecture,
the Entity Safety Layer's generic mutation primitive, Step 3's implementation) — not a rushed extension
of an already very long session. The owner's own closing guidance for THIS piece was explicitly
measure-first: gather real p95/p99 promotion latency on ATM9 under sustained load (now possible via
Layer 11's telemetry) and let that data — not a target throughput number — drive whether/how much
concurrency to introduce. Recorded here as a clearly-scoped, well-reasoned future direction, matching
this doc's own established "why not shipped yet" pattern (see the original Approach A/B section above)
— not abandoned, just correctly sequenced behind its own dedicated design pass.

## Layer 12 (FIXED, 47.4.199, 2026-08-11): `/forceload`'s own cross-call burst-budget gap — found by the owner's own scaling test

The owner's own explicitly-requested 256→512→1000→2000 fresh-chunk scaling series (see above) found a
**real, live Watchdog crash** on ATM9 during batch 1024: `ServerHangWatchdog detected that a single
server tick took 60.00 seconds`, crash report `crash-2026-08-11_01.21.11-server.txt`, stack rooted at
`ForceLoadCommand.java:179`. This is a *separate* code path from `ServerChunkCache.getChunk()` (Layers
9/10, above) — the exact same bug **class**, in a file not touched by this session's earlier work.

**Root cause**: `NestworldTuning.FORCELOAD_WAIT_TIMEOUT_MS` (20s) bounds a single `/forceload add`
call's own wait, but nothing bounded several such calls issued back to back — each independently got
its own full 20s allowance. The scaling-test script issued 4 separate `/forceload add` calls for batch
1024 (256-chunk vanilla per-command cap, tiled into a 32x32 area); if each got close to its own 20s
bound, 3+ in a row exceeds the 60s watchdog threshold — which is exactly what happened (issuance alone
measured 62.99s across the 4 calls before the crash).

**Fix** (`ForceLoadCommand.java` + `NestworldTuning.java`): the identical `Math.min(perCallTimeout,
remainingBudget)` pattern as Layer 9, but with two differences reflecting `/forceload`'s different
shape versus `getChunk()`'s hot per-tick path:
- New `FORCELOAD_BURST_BUDGET_MS` (default 3000ms) / `FORCELOAD_BURST_WINDOW_MS` (default 5000ms) —
  a **rolling wall-clock window**, not a per-tick reset. `/forceload` is an infrequent, administrative
  command whose own wait can itself span many ticks, so "reset every tick" doesn't naturally bound
  consecutive calls the way it does for `getChunk()`.
- Implemented as plain `private static` fields on `ForceLoadCommand` (main-thread-only, no
  synchronization needed — commands always run on the server thread).
- Every call's deadline is now `Math.min(FORCELOAD_WAIT_TIMEOUT_MS, remaining rolling-window budget)`,
  and every call's actual wait time (not just calls after the first) debits the shared budget.

**Validated** against the *exact* crashing scenario (4x `/forceload add`, 256 chunks each, 1024 total,
issued back to back) on both servers, with a stricter check than "command returned"/"position isn't
reported as unloaded" — see the methodology note below.

| | repro | ATM9 (real crash target) |
|---|---|---|
| 4-call issuance time (was ~63s, crashing) | 4.33s | 3.04s |
| Watchdog fired? | no | no |
| All 1024 chunks confirmed **real** terrain | yes, by t=27.6s | yes, by t=54.8s |
| TPS throughout | 20.0 (no dip) | dropped to 6.5–9.1 TPS for ~20s during the tail (t≈33–55s), then recovered to 20.0 |

**Methodology note — validating "operation actually finished," not just "chunk responds"**: the
scaling-test's original loaded-check (`data get block` not saying "not loaded") turned out to be a
false positive for this specific validation: a chunk that's still a Layer-10 `NestworldProxyLevelChunk`
stand-in also doesn't say "not loaded" (it's filled with `minecraft:void_air`, a real loaded-but-empty
chunk, by design). Re-checked with `execute if block X Y Z minecraft:void_air run list` at y=-60 (well
underground on ATM9) — this distinguishes "still a proxy" (predicate true) from "real generated
terrain" (predicate false, since real stone/deepslate isn't `void_air`) from "genuinely unloaded" (the
command itself fails to parse against an unloaded chunk, a distinct third signal). Confirms the crash's
own P0 concern: a fast command return is not proof the underlying work is done — Layer 12 only had to
prove the *command* doesn't block the main thread; separately confirming all 1024 chunks eventually
became real terrain (not stuck as proxies) is what closes the loop.

**The TPS dip is real, but it's the already-known GC/CPU ceiling, not a new bug**: ATM9 briefly ran at
~1/3 speed (`Can't keep up! Running ... 42–164 ticks behind`, 4 occurrences in the log around this
window) roughly 30–55s into the burst, before recovering cleanly to 20 TPS on its own. `jstat -gc`
showed active G1 young/concurrent-cycle activity across this window. This matches this doc's own
already-exhaustively-investigated Layer 7 finding (GC pressure, not scheduling, is the throughput
ceiling for raw chunk-gen+promotion volume — see `chunkgen-gc-pressure-bottleneck` /
`gc-algorithm-tried-no-help` / `heap-increase-fixes-gc-not-throughput` in project memory) — **not** a
new architectural gap, and not something Layer 12 was ever meant to solve. Layer 12's scope was
strictly "the main thread must never block past a bounded, shared budget waiting on `/forceload`'s own
completion" — that invariant now holds even under 4 back-to-back calls landing in the exact pattern
that crashed the server. The raw cost of generating+promoting 1024 chunks in under a minute is a
separate, already-diagnosed, deliberately-not-re-opened-tonight problem.

**A general NestWorld invariant, not just a one-off fix** (owner's framing, worth stating explicitly):
any synchronous operation that can wait on chunk/worldgen completion needs **both** (a) a per-call
timeout, **and** (b) protection against cumulative main-thread blocking across several such calls in
quick succession. A per-call timeout alone is not sufficient — Layer 9 and Layer 12 are two independent
discoveries of the same missing half of this invariant, in two unrelated files. Any *future* synchronous
wait added to this core (a new command, a new mod-compat shim) should be checked against this pattern
from the start, not discovered again via a crash.
