# CORE-PARKED-REGION-001 — runnable-work model of a non-free-running WorldRegion

**Status: AUDIT + DETERMINISTIC REPRODUCTIONS COMPLETE (2026-10-03). No scheduler change; fix not
started.** It is a blocking dependency of API-1B CLOSED (ORDERING within its deterministic timeout).

Audit question (owner):
> What constitutes runnable work for a non-free-running WorldRegion, and does dispatch eligibility
> account for every source of work whose progress requires a region tick?

## 1. Dispatch paths for a non-free-running (barrier) region

| Path | Initiator | Consumes | Eligibility |
|---|---|---|---|
| entity round (`RegionThreadPool.tickAllRegions` → `runOneTick(latch, null)`) | **pull**: the pool decides | inbound mailbox (all region-thread message types), `nestworldFlushOutboundPending`, `tickEntities`, snapshot | `!ownedEntityIds.isEmpty() \|\| lastWorkDeferred > 0` |
| work round (`runWorkRound` → `runOneTick(latch, work)`) | **push**: main hands concrete work | scheduled ticks, random ticks, block-entity ticks, player-tick experiment, `carriedWork` | dispatched whenever work is handed over |

A free-running region never uses either path: its own loop (`runFreeRunningTick`) drains
everything.

## 2. Inventory of work sources

| # | Work source | Consumed only by | Covered by dispatch eligibility? |
|---|---|---|---|
| 1 | **inbound mailbox, region-thread types** (ENTITY_IMPACT/PUSH/MUTATE, PLAYER_*) | entity round | **NO** |
| 1b | inbound mailbox, main-drained types (EXPLOSION_APPLY, BLOCK_WRITE) | main thread, phase 4b/4c, for all regions | n/a (independent of dispatch) |
| 1c | ENTITY_TRANSFER | main, `checkAndReassign` | n/a |
| 2 | **`nestworldOutboundPending`** (sender-side backpressure hold) | entity round of the **sender** | **NO** |
| 3 | `carriedWork` (budget remainder) | next round | yes (`lastWorkDeferred`) |
| 4 | pushed work rounds | work round | yes (push) |
| 5 | owned entities | entity round | yes |
| 6 | **`scheduledTicks`** (lossless free-running queue) | free-running loop only | **NO after demotion**: there is no barrier consumer. Already flagged by an existing DIAG in `demoteFreeRunning` ("#1 freeze") |
| 7 | **`pendingFreeRunningWork`** (latest-batch-wins slot) | free-running loop only | **NO after demotion** (same) |

The conceptual defect is visible: **the scheduler's notion of "runnable" (entities or deferred work)
is not the transports' notion ("has work queued for me").** At least four sources (1, 2, 6, 7) can
starve.

Sources 6 and 7 also overlap the retirement question CORE-WORK-RETIREMENT-001 (A1): the queues are
tied to an execution mode / region lifetime that the scheduler state machine does not know about.
The gates are **not merged**; the audit only records the shared abstraction gap.

## 3. Deterministic reproductions

`/nestworld testmutate mailbox parked-inbound|parked-pending` (MailboxLifecycleTests):
1. Isolate a region guaranteed to own no entities: a far quadrant cut away from spawn by explicit
   splits, then pinned so an auto-merge cannot retire it during the window.
2. Queue work for it.
3. Observe 15 s with **no trigger**.
4. Then a **real entity enters** the region, and the time to outcome is measured.

Exact accounting over every run with the region pinned. That is three code iterations: synthetic
owned-id trigger ×2 deploys, then the final real-entity trigger.

| Scenario | No-trigger windows (15 s each) | Outcome in the window | Real-entity trigger (final code) | Synthetic owned-id trigger (earlier iterations) |
|---|---|---|---|---|
| P1 inbound mailbox (source 1) | 7 | **0 of 7 dispatched**; every capture `mailbox=1, ownedEntities=0, deferredWork=0, freeRunning=false, dispatchEligible=false` | 2/2 delivered in 104 and 176 ms, exactly once, correct owner | 3/5 delivered (77, 101, 101 ms); 2/5 not within 10 s, because the fake id was removed by owned-id cleanup (§4) |
| P2 outbound pending (source 2) | 8 | **0 of 8 dispatched**; every capture `pending=1, ownedEntities=0, dispatchEligible=false` | 2/2 delivered in 68 and 141 ms, exactly once | 6/6 delivered in 75–101 ms (one with `orphaned=1`: delivered through a retirement drain) |

One unpinned development run of P2 was resolved inside the window by an auto-merge (§4); that is
why the region is pinned in the final code.

In addition, one fresh-world round SKIPPED both scenarios ("could not isolate an entity-free
region"; the reason is not differentiated further). Earlier development runs recorded below were
used to make the reproduction deterministic.

**Real-world evidence from other gates:**
- **API-1B-CLOSURE, integrated ORDERING** (three runs): r1 `mailbox=1, ownedEntities=0,
  dispatchEligible=false, oldestAgeMs≈5000`; with no trigger it drained only after a further
  10.0–11.2 s.
- **STRESS-001 F2:** intermittent MISSING_TERMINAL on a dedicated server, later delivered.

**Not reproduced here:** sources 6 and 7 (demotion stranding). They are static only, plus the
existing DIAG; reproducing them needs free-running mode and is the next audit step.

## 4. Observations made while building the reproduction
- **Incidental triggers decide latency today.** An auto-merge of the idle region retires it, and
  the CORE-MAILBOX retirement drain then delivers the work (observed: orphaned=1, delivered ~10 s
  later). An entity entering, or a periodic ownership reassignment, also triggers it. Latency is
  bounded only by such unrelated events.
- **A synthetic owned-id trigger is unreliable.** A fake UUID added to the owned set was removed by
  owned-id cleanup before dispatch (evidence: `owned=0, dispatchEligible=false`, thread alive /
  WAITING / in pool). This is not a scheduler fault: once the id is removed the region is
  legitimately ineligible again. The reproduction therefore uses a real entity.

## 5. Candidate fix shapes (NOT decided)
- **A.** One per-region runnable-work predicate aggregating sources 1, 2, 3, 6 and 7 via O(1)
  counters. A mailbox depth counter already exists; a region-thread-type counter and a pending
  counter would be needed. `tickAllRegions` dispatches on it.
- **B.** A producer-side wake: enqueueing into an ineligible region marks it dispatch-requested for
  the next round.
- **C.** Sources 6 and 7: on demotion (and retirement, see CORE-WORK-RETIREMENT-001), hand the
  queues over to the barrier path explicitly.

Whatever is chosen must keep the reason for the existing skip rule (idle regions are not woken
every tick, see the `tickAllRegions` comment). It must also be verified with P1/P2, the integrated
ORDERING fixture within its 5 s timeout, and the stress modes.
