# P2 — RegionThreadPool / Global Barrier Audit — Results

Ran 2026-08-12, audit-only per `docs/P2_REGIONTHREADPOOL_BARRIER_AUDIT.md`. No latch/timeout/dispatch
behavior changed. One pure-telemetry addition deployed (`RegionThreadPool.RegionSlowStats` +
`/nestworld regionslow`, zero behavior change, same discipline as the existing `PhaseStats`).

## Q1/Q2 — how much of awaitLatch() is genuine wait vs. main-thread work? RE-VERIFIED, CHANGED

The P0.1/P0.2 reading (`actual_latch_wait ≈ 0.014ms`, essentially zero) does **not** hold under this
session's later, longer-window data. Two independent live readings this pass:

| Window | tick_wall avg | actual_latch_wait avg | share |
|---|---|---|---|
| Idle, 42,208-tick cumulative (spans most of P1.5's runtime) | 4.994ms | 2.464ms | 49% |
| Fresh boot + 512-chunk gentest dispatch, 621 ticks | 15.642ms | 5.197ms | 33% |

`actual_latch_wait` is genuine idle park time (NOT doing pollTask work) inside `awaitLatch()`'s
busy-wait loop. Both readings show it as a substantial, non-negligible fraction of tick wall time —
the opposite of the earlier "main thread never genuinely idle-waits" conclusion. **Root cause of the
discrepancy: not identified as a regression** — most likely the P0.1/P0.2 reading (230 ticks, fresh
boot, minimal load) simply caught an unrepresentative low-wait window, and the true steady-state
behavior is what these longer/loaded windows show: main thread DOES spend meaningful real time
waiting on region threads, roughly a third to half of each tick. No timeouts occurred in either
window (0/621, 0/42,208) — the wait is normal-range, not pathological, just not "near-zero" as
previously stated. **This finding should replace, not append to, the P0.1/P0.2 near-zero claim.**

`GENERIC_OTHER`'s `CompletableFuture$UniApply`/`UniCompose` remains the dominant single main-thread
cost under load (worldgen continuation glue, already covered by P1's attribution work — not
re-litigated here). `VALIDATOR_BATCH` stayed 46-52% of `runAllUpdates_total` in both readings
(bounded, not pathological — consistent with the P0.2 fix holding).

## Q3/Q4 — which region is slowest, and does it correlate with stalls? ANSWERED, NEW FINDING

New `/nestworld regionslow` telemetry (per-region-id: times-slowest-in-round, own avg/max tick time).
Under the 512-chunk dispatch (921 ENTITY rounds observed):

| Region | slowest_in_round | share | avg tick | max tick |
|---|---|---|---|---|
| #3 | 462 | 50.2% | 4.284ms | 132.247ms |
| #7 | 419 | 45.5% | 3.277ms | 64.963ms |
| #1 | 26 | 2.8% | 2.191ms | 55.756ms |
| #5 | 12 | 1.3% | 2.097ms | 68.767ms |
| #4 | 1 | 0.1% | 0.889ms | 63.222ms |
| #6 | 1 | 0.1% | 0.678ms | 55.698ms |

**Not random — 2 of 9 active regions (#3, #7) account for 95.7% of "slowest this round."** This
correlates directly with `/nestworld status`'s own per-region entity/cost numbers: #3 (43 entities,
2.9-3.1ms cost) and #7 (35 entities, 2.3-2.6ms cost) are consistently the two highest-entity-count,
highest-cost regions in every reading this session. **The correlation is with entity/chunk load
concentration, not a structural defect in a specific region** — whichever region has the most owned
entities/chunk activity is the one that's usually slowest, which is expected and not itself a bug.
The relevant follow-up question for a future phase (not answered here, out of scope): whether
region-splitting is reacting fast enough to this concentration, or whether #3/#7 should have split
already — a separate investigation, not part of Q3/Q4's factual question.

Zero timeouts observed in either window, so no direct evidence (yet) of a slowest-region actually
CAUSING a global tick stall in this session's testing — the correlation established here is "which
region is closest to being the bottleneck," not "which region has already caused a stall."

## Q5 — what happens after the 30s timeout? (prior art, unchanged)

`awaitLatch()` logs an ERROR and breaks out of its wait loop (`RegionThreadPool.java:296-301`) —
does NOT interrupt, cancel, or otherwise signal the slow region thread. Confirmed by re-reading the
current code this pass, no change since the P0 audit.

## Q6 — can an abandoned region thread still mutate world state after main thread proceeds? CONFIRMED RISK

**Yes, confirmed by code trace, not hedged.**

1. `RegionThread.runOneTick()` acquires `region.getChunkLock().writeLock()` at entry and holds it for
   the ENTIRE method body (released only in the `finally` at the very end,
   `RegionThread.java:355-410`). If a region thread is still inside `tickEntities()`/`runWorkBudgeted()`
   past the 30s mark, it is genuinely still executing world-mutating code (`level.tickNonPassenger(entity)`,
   block ticks, etc.) — the main thread giving up on `awaitLatch()` does nothing to stop this.
2. **No cancellation mechanism exists anywhere in this path.** Grepped both files: zero
   `Thread.interrupt()` call sites targeting `RegionThread` from the pool, zero `volatile boolean
   cancelled/shouldStop` flag checked mid-tick. The only place `InterruptedException` is even caught
   is `awaitDispatch()`'s `tickSignal.wait()` (`RegionThread.java:303-304`), and nothing ever calls
   `.interrupt()` on these threads to trigger it.
3. **A second, previously-undocumented finding: the pool has no "is this region still busy" guard
   before dispatching the NEXT phase's work to it.** `RegionThreadPool.tickAllRegions()` /
   `runWorkRound()` build their `workers` list from `region.getOwnedEntityIds()`/`getLastWorkDeferred()`
   state only — never checking whether that region's `RegionThread` is still inside a PRIOR
   `runOneTick()`. `requestTick()`/`requestWork()` (`RegionThread.java:170-188`) unconditionally
   overwrite `tickLatch`/`pendingWork` under `synchronized(tickSignal)`, with no busy-check. Of the 5
   `awaitLatch()` call sites, only BLOCK_ENTITY (`NestworldRegionSystem.java:1042`,
   `runWorkRoundDropIfBacklogged`) has ANY backlog-awareness, and even that checks
   `getLastWorkDeferred()` — a stale, previous-round budget-cut count, not "is currently mid-tick
   right now." ENTITY, SCHEDULED_TICK, RANDOM_TICK, and BLOCK_EVENT rounds have no such check at all.
   **Consequence**: if a region thread is still stuck in an overrunning `runOneTick()` when a LATER
   phase in the same game tick (or the next tick's ENTITY round) tries to dispatch to it again, the
   new `requestWork()` call silently OVERWRITES `pendingWork` before the region thread ever consumed
   it — that work list (a real batch of scheduled/random block ticks) is discarded, never executed,
   with no log line, no error, no backlog carry-over. This is herein data loss, not merely delay: the
   `runWorkBudgeted()` carry-over mechanism only protects against the region's OWN budget cutting a
   round short, not against the pool re-dispatching before the region even started the prior round.
4. Corroborating evidence already in the codebase: `NestworldRegionSystem.nestworldApplyWithCascadeGuard`'s
   own doc comment (`NestworldRegionSystem.java:1327-1329`) states cascade-guard lock acquisition
   "always succeeds immediately... every region thread is already parked at the point this runs
   (step 4b/4c, after `pool.tickAllRegions()`'s barrier)" — an invariant this trace shows is false
   specifically in the timeout case (the one documented escape hatch from "always parked"). That
   particular call site degrades gracefully (bounded lock-acquire timeout + repost-to-mailbox retry,
   per its own doc), but it's evidence the "always-parked" assumption is not universally safe, and
   other main-thread code relying on it implicitly (without a timeout+retry fallback) would be more
   exposed. No such other call site was found in this pass, but a full sweep for "main-thread code
   that assumes all regions are parked" was not exhaustively performed — flagged as a good target for
   a future, narrower audit if Q7 work proceeds.

**Bottom line for Q6**: the correctness risk the user's ТЗ named is real and confirmed, with a
concrete mechanism (no cancellation + no busy-check on re-dispatch), and a second, previously
undocumented consequence (silent work-loss on overlapping dispatch) beyond the originally-suspected
"still mutating in the background" risk alone.

## Q7 — safe non-blocking model design

Explicitly deferred per the ТЗ's own instruction — not attempted in this audit.

## Deployment note

New telemetry (`RegionSlowStats`/`/nestworld regionslow`) required a full universal-jar redeploy
(same version string `47.4.204`, per the session's own `deploy-trap-stale-version-installer-skip`
lesson — built via `:forge:universalJar --rerun-tasks`, verified via `javap` before copying directly
into the running server's `libraries/.../forge-...-universal.jar`, NOT via `--installServer`).
Restart hit the session's known recurring stuck-shutdown issue (`shutdown-hang-jvm`, "Server thread"
listed as a stuck thread by AllTheLeaks after 130+s) — resolved via the established
kill-after-cwd-verification pattern, not a new problem. Clean reboot confirmed (`Done (7.283s)`, 0
NestWorld-specific errors/ownership violations in the boot log). One `"Can't keep up... 118 ticks
behind"` occurred once, consistent with the boot-transient pattern already seen in every P1.5 test
(A and E both had exactly 1) — not attributed to the new telemetry or the dispatch load. Server left
in production config after testing: `chunkGenBudget=2` (effective), `concurrency=4`, confirmed via
`/nestworld genbudget`/`genconcurrency` post-test.
