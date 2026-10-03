# CORE-PARKED-REGION-001 — runnable-work model of a non-free-running WorldRegion

**Status: CLOSED / VERIFIED / FROZEN (2026-10-03).** Option A implemented in the scope approved by
the owner (§6). The audit was frozen before the fix (§3, §3b). It is a blocking dependency of API-1B CLOSED (ORDERING within its deterministic timeout).

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
| 6 | `scheduledTicks` (lossless free-running queue) | free-running loop; **after demotion: the existing backlog recovery in `runWorkRound(SCHEDULED_TICK)`** (push, every tick, even with no entities) | **covered**, dynamically verified (§3b) |
| 7 | **`pendingFreeRunningWork`** (latest-batch-wins slot: RANDOM_TICK, BLOCK_EVENT batches) | free-running loop only | **NO after demotion**: even a dispatched barrier region never consumes it (§3b). This is a transition defect, not a liveness one |

The conceptual defect is visible: **the scheduler's notion of "runnable" (entities or deferred work)
is not the transports' notion ("has work queued for me").** After dynamic verification:
- sources **1 and 2** are **liveness** defects of a live barrier region;
- source **7** is a **transition** defect: no barrier consumer exists at all;
- source **6** is covered by the existing demotion recovery.

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

### 3b. Demotion reproductions (P3/P4, `parked-demotion`, free-running mode)

Setup:
1. An isolated, pinned, entity-free region is promoted to free-running.
2. Its worker is paused at the top of its loop iteration (test hook), so posting and demotion happen
   with no drain in between.
3. One **Tagged** scheduled tick (the real production representation) and one free-running work
   batch are posted, then the region is demoted.

Two runs, identical:

| Stage | `scheduledTicks` (source 6, P3) | `pendingFreeRunningWork` (source 7, P4) |
|---|---|---|
| 15 s, no trigger | **executed exactly once**: +1 local tick, push work round, DIAG "recovered 1 scheduled tick(s) … while demoted" | **not executed** (`pendingFreeRunningWork=1`, `dispatchEligible=false`) |
| 15 s, a real entity enters; the region is dispatched in barrier mode (+215 / +284 local ticks) | — | **still not executed**: the barrier `runOneTick` does not consume this queue |
| re-promotion to free-running | — | executed exactly once, after 0–7 ms, on the region thread |

**Test lesson (recorded):** the first P3 attempt posted an untagged Runnable, and
`RegionScheduledTicks.offerAll` **silently dropped it**. Production SCHEDULED_TICK routing always
tags BLOCK/FLUID entries (OTHER-kind untagged work is used only by block events, which go through
`pendingFreeRunningWork`), so this is a **latent hazard**, not an active loss. It should become an
explicit assertion or a counted terminal, never a silent drop.

## 4. Observations made while building the reproduction
- **Incidental triggers decide latency today.** An auto-merge of the idle region retires it, and
  the CORE-MAILBOX retirement drain then delivers the work (observed: orphaned=1, delivered ~10 s
  later). An entity entering, or a periodic ownership reassignment, also triggers it. Latency is
  bounded only by such unrelated events.
- **A synthetic owned-id trigger is unreliable.** A fake UUID added to the owned set was removed by
  owned-id cleanup before dispatch (evidence: `owned=0, dispatchEligible=false`, thread alive /
  WAITING / in pool). This is not a scheduler fault: once the id is removed the region is
  legitimately ineligible again. The reproduction therefore uses a real entity.

## 4b. Frozen conclusions → scope
- **CORE-PARKED-REGION-001 → A** (unified O(1) runnable-work predicate). Covers source 1 (inbound
  mailbox, region-thread types) and source 2 (sender outboundPending). Sources 3, 4 and 5 are
  already covered; source 6 is covered by the existing demotion recovery and stays as is.
  Invariant: if progress of work requires `runOneTick()`, the presence of that work makes a
  non-free-running region dispatch-eligible.
- **Source 7 (pendingFreeRunningWork across demotion) → not A.** It is a transition defect, so it
  goes to CORE-WORK-RETIREMENT-001 (or a sibling transition gate) as a C-type handoff decision.
  Its contents (RANDOM_TICK, BLOCK_EVENT) are latest-batch-wins by design, and that changes what
  "correct handoff" means.
- **Latent:** `offerAll` drops untagged work silently. Track it in the same transition gate.

## 5. Candidate fix shapes (A chosen for this gate; listed for history)
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

## 6. Fix (option A) — implementation contract and verification

**Normative invariant (owner):**
> For every accepted unit of work W: if progress(W) requires execution by `WorldRegion.runOneTick()`,
> then while W remains pending and its region remains ACTIVE, `WorldRegion.hasRunnableWork() == true`.
> A non-free-running ACTIVE region containing W therefore stays dispatch-eligible independently of
> owned entities, unrelated entity movement, split/merge, promotion, or any other external trigger.

**Implementation:**
- **Eligibility is a property of the transport path, not a list of types.** Every
  `RegionMessage.Type` must declare its `Consumer` (REGION_THREAD / MAIN_THREAD) as a constructor
  argument, so a new type cannot compile without one. `requiresRegionTick()` is the only thing the
  predicate asks.
- **`WorldRegion.hasRunnableWork()`** = owned entities ∨ deferred work of the region's thread ∨
  `regionTickInbound > 0` ∨ `outboundPendingCount > 0`. It is O(1) and walks no queue.
  `RegionThreadPool.tickAllRegions` dispatches on it.
- **Accounting rule (no false zero):** increment BEFORE the item becomes visible in its queue;
  decrement only AFTER it was actually taken out. That means a successful `remove`, a `poll`, or a
  consumer's iterator removal. So the counter is ≥ the pending items at every instant and equal at
  quiescence. All 11 mutation points are covered:
  - safe enqueue and its post-RETIRED remove;
  - retirement drain (inbound and pending);
  - the three consumer drains;
  - `sendOrQueue` and its post-RETIRED remove;
  - flush (orphan branch and requeue branch).
- **Requeue and redelivery** go through the same safe enqueue, so they are counted by the same
  rule. A message in flight between sender and destination inside one flush call is held by the
  executing thread, not by a queue awaiting dispatch.
- **Explicitly out of scope:** P4 (pendingFreeRunningWork transition), `offerAll` hardening,
  CORE-WORK-RETIREMENT-001, scheduler redesign, affinity, performance work.

**Verification** (scratch instances, build 1.20.1-47.4.258):

| DoD item | Result |
|---|---|
| P1 parked-inbound, no trigger | delivered in 25–101 ms (5 barrier runs + 1 free-running), exactly once, at the target's owner; afterwards `inboundCounter=0 pendingCounter=0 mailbox=0 pending=0 consistent=true runnable=false` |
| P2 parked-pending, no trigger | same; 25–101 ms (5 + 1 runs) |
| Negative (parked-idle) | isolated empty region: `hasRunnableWork=false/false`, +0 local ticks in 10 s; quiet server: 0 entity-less dispatches in 60 s (no busy loop) |
| Counter/queue consistency | every mailbox scenario and every stress run: all live regions `consistent`; C10 now also requires both counters = 0 on every retired region |
| Retirement (CORE-MAILBOX-MERGE-001) | mailbox fixtures 64/64 barrier (L2c skipped by design), 69/69 free-running; C10 0 violations; `unaccounted=0` |
| API-1A deterministic set | migration REROUTE→APPLIED; removed TARGET_NOT_FOUND; reroutelimit 5×REROUTE→REROUTE_LIMIT_DROPPED; ordering APPLIED + STALE_INTENT_DROPPED |
| API-1B dedicated fixtures | 19/19 PASS |
| Stress churn / pinned / unpinned | MISSING_TERMINAL 0; duplicateApply, wrongOwner, wrongIncarnation, staleIntentApply, callerObjectLeak, unboundedReroute all 0; `unaccounted=0`; C10 0/86–94; counters consistent; nothing queued at end. Entity-less dispatches during stress (cumulative 54 → 161 → 173) are regions that actually held work |
| Integrated ORDERING (real client, world A) | **PASS within the existing 5 s.** V1: ENQUEUE to entity-less r1 → REROUTE 1→2 → STALE_INTENT_DROPPED in < 1 s, no unrelated trigger; V2 applied once; final = diamond |
