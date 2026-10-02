# CORE-MAILBOX-MERGE-001 — region mailbox lifecycle fix (contract, implementation, evidence)

**Status: CLOSED / VERIFIED (2026-10-02).** Audit: `CORE-MAILBOX-MERGE-001-audit.md` (§1–§11,
owner-approved 2026-10-02). This document is the implementation contract the owner approved on
2026-10-02, including the nine corrections to the audit's §10 sketch. It also records how the
code meets each clause and the evidence that closes the gate.

## Closure property

> For every successfully accepted region message, region retirement must preserve exactly one
> observable outcome: legitimate application, successful re-resolution/redelivery, or an explicit
> contract-defined terminal disposition. Split, merge, retirement, or stale region identity alone
> may never make the message unreachable.

Accounting invariant (C11), checked by the transport property itself, not by the C+A mechanism:
```
always:            accepted = applied + explicit_terminal + still_reachable_in_live_transport
after quiescence:  accepted = applied + explicit_terminal          unaccounted = 0
```
Live transport means the mailboxes and `outboundPending` queues of live regions, plus the orphan
queues.

## Contract

### C1 — Lifecycle state machine (conceptual)
```
ACTIVE   -- an enqueue may be accepted
   │  retirement publication
RETIRED  -- no new enqueue is accepted; the retirement drain may still be running
   │  worker excluded + both transport queues handed off
DRAINED  -- inbound mailbox and outbound pending are empty
```
Implementation: `WorldRegion.nestworldRetired` and `nestworldDrained`, two one-way volatile
booleans. There is no enum.

### C2 — Linearization contract
Inside `nestworldWithRegionLocks`, which holds the retiring region's chunk write lock, in this
order (`RegionSplitManager.doSplit` / `doMerge`):
1. Publish the successor topology (`tree.split` / `tree.merge` → `grid.unregister`/`register`).
2. `nestworldMarkRetired()`: the volatile write happens after step 1. Any thread that observes
   RETIRED therefore also observes a topology that re-resolves to a successor owner.
3. `pool.remove(...)` stops the old worker. It now returns the stopped threads.
4. `nestworldDrainOnRetire()` moves every inbound message (all types) and every outbound-pending
   entry to the dimension's orphan queue, then sets DRAINED.

Invariants:
- When `nestworldTryEnqueue` can first see RETIRED, the topology already allows re-resolution.
- After RETIRED is published, no message can be accepted merely because the old region object
  still exists.

### C3 — Old-worker exclusion; join-timeout policy
Code facts:
- Every region-thread mailbox consumer (`applyPending*`) and `nestworldFlushOutboundPending` run
  inside `RegionThread.runOneTick` under `region.getChunkLock().writeLock()`.
- Both the barrier path and every free-running path (`runFreeRunningTick` → `runOneTick`) reach
  them only through that locked section.
- Retirement holds the same write lock.
- The main-thread consumers (4b/4c `EXPLOSION_APPLY` / `BLOCK_WRITE`) run on the main thread,
  which is the thread performing retirement.

Therefore:
- An old-worker consumption either finished before retirement took the lock (a legitimate
  pre-retirement consumer, with correct ownership because the topology was still the old one),
  or it starts after release.
- To close the "after release" case, `runOneTick` re-checks RETIRED immediately after acquiring
  the lock. On an entity round, a RETIRED region consumes nothing and ticks nothing
  (`workerSkippedRetired`).
- This lock-exclusion argument is the separate evidence that makes the drain safe without relying
  on `join`.

Join policy (`MailboxLifecycle.awaitWorkerExit`):
- The join runs after the locks are released, because joining under the lock could deadlock with
  a worker blocked in `writeLock()`.
- `join(RETIRE_JOIN_TIMEOUT_MS=50)` is kept short to bound the main-thread stall.
- A worker that is still alive is watched. If it exits later, `workerLateExit` is counted. If it
  is still alive after `RETIRE_EXIT_DEADLINE_MS=10000`, the outcome is **RETIREMENT_FAILED**: a
  WARN and a counter. The region stays RETIRED and is never re-activated.
- No drain correctness rests on the join. If any consumer path were found outside the lock, a
  drain after a join timeout would be forbidden for that path.

### C4 — Safe enqueue (the only way into `nestworldMailbox`)
```
if RETIRED                    -> not accepted; orphaned (REJECTED_RETIRED)
add; depth++
if !RETIRED                   -> ACCEPTED
if remove(msg) succeeds       -> depth--; not accepted; orphaned (REJECTED_RETIRED)
else                          -> ACCEPTED (acceptedRemoveFailed++)
```
**Proof obligation for the last line.** After RETIRED, the only possible takers are:
- the retirement drain, which uses `poll()` (a CAS) and orphans the message; or
- a consumer that ran before retirement (C3), which yields APPLY or TERMINAL.

"`remove()==false` → message disappears → no terminal" is impossible. It is proven by fixtures
L2b (drain took it: orphaned once, applied once) and L2b-consumed (worker took it before
retirement: applied once, never orphaned).

Note: the consumers' type-filtered drains remove through `ConcurrentLinkedQueue`'s iterator. That
removal is a plain write, not a CAS. Exactly-once against a producer's `remove()` therefore
relies on C3: a producer only calls `remove()` after observing RETIRED, and no iterator-draining
consumer of that region can run after RETIRED (region thread: lock plus re-check; main thread:
it is the retiring thread). Any future consumer that drains a region's mailbox outside this
exclusion must use `poll()`/`remove()`, not iterator removal.

Routing:
- `nestworldPostMessage`, `nestworldRequeueMessage`, `nestworldSendOrQueue` and
  `nestworldFlushOutboundPending` all go through C4. That includes the batch-timeout requeue in
  `NestworldDimensionRegion`.
- The sender-side pending queue uses the same add, re-check, remove shape
  (PENDING_SENDER_RETIRED).
- A flush whose destination is RETIRED orphans the entry (PENDING_DESTINATION_RETIRED) instead of
  waiting on a dead region's depth.
- The 27 producers are unchanged.

### C5 — `volatile` alone is not the fix
Correctness comes from the combination of:
- volatile publication;
- CLQ add/remove atomicity;
- the single one-way transition;
- old-worker exclusion (C3);
- the retirement drain;
- orphan re-resolution.

Removing any one of these reopens a loss path. This is also recorded at the field in
`WorldRegion`.

### C6 — An orphan is transport state, not a failure
An orphan is a message whose previously resolved destination is no longer authoritative and
therefore needs owner re-resolution. It is not failed and not dropped. A message becomes an
orphan for one of four reasons:
- `DRAINED_ON_RETIRE`: it was accepted, then the retirement drain moved it.
- `REJECTED_RETIRED`: the enqueue was rejected because the destination was RETIRED.
- `PENDING_SENDER_RETIRED`: it was held in the outbound pending of a sender that retired.
- `PENDING_DESTINATION_RETIRED`: it was held for a destination that retired.

Re-resolution then gives one of two results: redelivery to the current owner, or an explicit
terminal. Counters keep orphaned, redelivered and each terminal kind separate.

### C7 — Re-resolution by target identity (`MailboxRetargeting`, main thread)
Re-resolution runs at the start of `NestworldDimensionRegion.tick()` and right after
`splitManager.onTick()`. A split has two successors, so each message is re-resolved with its
producer's own rule:

| Type | Rule |
|---|---|
| `ENTITY_IMPACT`, `ENTITY_PUSH`, `ENTITY_MUTATE` | `findOwningRegion(uuid)`; for an entity no region owns at that instant, the region at its live position (an unowned entity races no region thread) |
| `PLAYER_ENTITY_INTERACT` | `findOwningRegion(target)` (producer rule) |
| `PLAYER_INPUT` | the captured input position (producer rule) |
| `PLAYER_ATTACK`, `PLAYER_USE_ITEM`, `PLAYER_USE_CONTROL` | the acting player's current position |
| `BLOCK_WRITE`, `PLAYER_BLOCK_INTERACTION` | the block position |
| `PLAYER_USE_ITEM_ON_BLOCK` | `PlayerUseItemOnBlockDispatcher.resolveOwnerPos`, the same rule as the producer (made package-private) |
| `EXPLOSION_APPLY` | partitioned (C8) |
| `ENTITY_TRANSFER` | C9 |

Re-addressing:
- `RegionMessage.redeliveredTo(dest)` makes a copy with the same audit id, payload and creation
  time, and with `redeliveries + 1`.
- `MailboxAudit.recordRedirected` makes the apply at the new destination count as correct
  routing, not a misroute.
- `ENTITY_MUTATE` keeps its opId and emits `phase=REDELIVER from=… to=…`.
- Delivery uses the destination's normal C4 enqueue, and the consumer re-validates exactly as for
  a fresh message.

Terminals (explicit, counted, recorded in `MailboxAudit`; never silent):
- `TARGET_NOT_FOUND`: no owner and no live target.
- `REDELIVERY_LIMIT_DROPPED`: `redeliveries >= MAILBOX_MAX_REDELIVERIES` (8).
- `TRANSFER_ALREADY_OWNED`: C9.
- `SPLIT_INTO_CHILDREN`: C8 parent.
- `DIMENSION_GONE`: reserved.

For `SetInventorySlot`, the terminals also feed `InventorySetTelemetry`
(`targetNotFound` / `redeliveryLimitDropped`), so the API-1B terminal accounting stays complete.

### C8 — `EXPLOSION_APPLY` semantic preservation
The batch's positions are partitioned by resolved owner:
- **One owner, and every position covered:** a plain redelivery.
- **Several owners:** the parent is closed with `SPLIT_INTO_CHILDREN` and one child batch is made
  per owner (`RegionMessage.childFor`):
  - each child carries the **same `Explosion` object** (identity and context) and the same
    `spawnDrops`;
  - each child has its own audit id, recorded as sent.
- **Positions no live region covers:** counted (`explosionPositionsNoOwner`, expected 0).

Fixture assertions:
- the input position multiset equals the union of the child batches;
- pairwise intersection of the child batches is empty;
- the explosion object and `spawnDrops` are identical in every child;
- each child is applied exactly once, at its own owner;
- the world state matches.

### C9 — `ENTITY_TRANSFER`
`SUPERSEDED_BY_RESCAN` was **not** adopted. It would be valid only if the boundary scan were an
authoritative and prompt re-evaluation, and the code does not show that. With
`EVENT_DRIVEN_OWNERSHIP`, `BoundaryEntityTransfer.checkAndReassign` revisits only dirty entities;
a stationary, already-keyed entity is next examined only by the periodic full pass. Per the
contract's fallback, an orphaned transfer is re-resolved by entity position:
- **If a live region already owns the entity:** the result is the explicit
  `TRANSFER_ALREADY_OWNED`. This is decided by checking the actual ownership state, never by
  assumption, and never creates a second owner.
- **If the entity is gone:** the result is `TARGET_NOT_FOUND`.
- **Otherwise:** the transfer is redelivered to the region at the entity's live position (the
  entity is unowned, so the read is safe on main) and applied by the next `checkAndReassign`.

In normal operation `ENTITY_TRANSFER` is posted and drained inside phase 3 on the main thread,
before split/merge (phase 6). It strands only if a retirement interleaves, which can happen via an
off-tick command or task. The fixtures construct exactly that.

### C10 — Depth and emptiness
- For a DRAINED region: `reportedDepth == 0`, `mailbox.isEmpty()`, and `outboundPending.isEmpty()`.
- Both the counter and the actual queues are checked (`MailboxLifecycle.c10Sweep` over a bounded
  registry of the last 1024 retired regions).
- The check is meaningful at quiescence, because a producer in the middle of its C4 sequence is
  transiently visible.
- A residual entry in a retired queue at quiescence would be exactly an "accepted after RETIRED"
  message, since the drain is a retired region's last consumer. C10 is therefore also the
  `acceptedAfterRetired == 0` check.

### C11 — Accounting
`MailboxAudit` (`-Dnestworld.mailboxAudit=true`) adds `recordOrphaned`, `recordRedirected`,
`recordTerminal` and `accountingReport(reachable)`. The report gives:
- accepted, applied, explicit terminal (by kind), still in flight, reachable in transport;
- `unaccounted` (in flight but in no live transport queue);
- the balance, duplicates and misrouted.

`MailboxLifecycle.reachableAuditIds()` walks the live transport. `/nestworld mailboxaudit` prints:
- the lifecycle counters;
- the C10 sweep;
- the C11 report.

The lifecycle counters and C10 are always on.

## Implementation map

| File | Change |
|---|---|
| `WorldRegion` | lifecycle flags, C4 safe enqueue, `nestworldMarkRetired`, `nestworldDrainOnRetire`, routed post/requeue/sendOrQueue/flush, `nestworldForEachQueuedAuditId` |
| `RegionSplitManager` | C2 order in `doSplit`/`doMerge`; join after lock release; test-only `doSplitAtForTest` (explicit cut) |
| `RegionThreadPool.remove` | returns the stopped worker(s) |
| `RegionThread.runOneTick` | C3 post-lock RETIRED check (entity round); test hook before the lock |
| `NestworldDimensionRegion.tick` | `MailboxRetargeting.redeliver` at tick start and after split/merge |
| `NestworldRegionSystem` | `MailboxLifecycle.resetAll()` on server stop |
| `RegionMessage` | `redeliveries` component (old 8-arg constructor kept), `redeliveredTo`, `childFor` |
| `MailboxAudit` | C11 accounting; test observation hook at the single apply site |
| `NestworldTuning` | `MAILBOX_MAX_REDELIVERIES`, `RETIRE_JOIN_TIMEOUT_MS`, `RETIRE_EXIT_DEADLINE_MS` |
| new `MailboxLifecycle` | orphan queues, counters, retired registry, exit watch, C10/C11 reports |
| new `MailboxRetargeting` | C7/C8/C9 re-resolution and terminals |
| new `MailboxLifecycleTestHooks`, `MailboxLifecycleTests` | test-only interleaving control, outcome observation, fixtures (`/nestworld testmutate mailbox <scenario>`) |
| `EntityMutationDispatcher` | outcome-observation hooks at APPLY / TARGET_NOT_FOUND / REROUTE_LIMIT_DROPPED |
| `InventorySetTelemetry`, `Api1B6DeterministicTests` | `redeliveryLimitDropped` counted as a terminal; churn report prints lifecycle, C10 and C11 |
| `PlayerUseItemOnBlockDispatcher` | `resolveOwnerPos` package-private (shared owner rule) |
| `NestworldCommand` | `testmutate mailbox` node; lifecycle report in `mailboxaudit` / `pressure` |

Every test hook is one volatile read when disarmed.

## Evidence (isolated scratch instance only, confirmed via `/proc/<pid>/cwd`)

Build provenance:
- All runs used build `1.20.1-47.4.246` (uncommitted tree; deployed with an installer and marker
  classes asserted inside the installed universal jar).
- First build (cm1): §7 datapack, the first fixture runs, and the first barrier churn,
  pinned/unpinned, API-1A, API-1B and matrix runs.
- Final build (cm3): differs from cm1 **only** in `MailboxLifecycleTests` (the `transfer`
  fixtures and the `ensureSplittable` setup helper). The final fixture runs and both final churn
  runs used cm3.

### Deterministic fixtures (`/nestworld testmutate mailbox all`, audit on)

| Scenario | What it proves | Barrier (cm3) | Free-running (cm3) |
|---|---|---|---|
| l1-split / l1-merge | L1: accepted, then retired in the same task → one APPLY at the successor | PASS | PASS |
| l2a | L2 enqueue race: REJECTED_RETIRED → orphan → one APPLY | PASS | PASS |
| l2b | C4 `remove()==false` with the drain owning the message: orphaned 1, applied 1 | PASS | PASS |
| l2b-consumed | C4 `remove()==false` with a pre-retirement consumer: applied 1, orphaned 0 | PASS | PASS |
| l2c | C3: worker blocked before the lock, skips after RETIRED; forced join timeout → watched → late exit, retirementFailed 0 | SKIP (needs free-running) | PASS |
| l3 / l4 | sender-retired / destination-retired pending → delivered | PASS | PASS |
| l5 | C8 multiset/disjoint/context; BLOCK_WRITE; world state | PASS | PASS |
| transfer / transfer-owned | C9 position redelivery → sole owner; TRANSFER_ALREADY_OWNED → still one owner | PASS | PASS |
| notfound / redeliverylimit | explicit terminals, never silent | PASS | PASS |
| every scenario | C10 0 violations; C11 `unaccounted=0`, duplicates 0, misrouted 0 | PASS | PASS |
| **total** | | **39 pass, 0 fail, 1 skip** | **43 pass, 0 fail** |

### §7 datapack (the audit's original reproduction; cm1; barrier)

| Run | Trace | Outcome |
|---|---|---|
| Before (audit, build 47.4.241) | opId 111: ENQUEUE to=1 → split 1 → no APPLY, no TERMINAL | stranded |
| After | opId 200: ENQUEUE to=4 → `Split Region{id=4} -> [8, 9]` → `REDELIVER from=4 to=9 redelivery=1` → APPLY on NestWorld-Region-9 → TERMINAL APPLIED | delivered; C10 clean; ACCOUNTING accepted=applied=200, unaccounted=0 |

### API-1B.7 `stress-churn` (60 s, 3 real bots, respawn/relogin chaos, forced split/merge every ~2 s)

| Run | Retired regions | Stranded | MISSING_TERMINAL | Orphaned → redelivered | C10 | C11 | Invariants* |
|---|---|---|---|---|---|---|---|
| Before (2026-10-01) | 44 | **171** | **171** | — | — | — | 0 |
| After, barrier (cm1) | 43 | 0 | 0 (46,512 submissions) | 188 → 188 | 0/44 | accepted=applied=48,286, unaccounted 0 | 0 |
| After, barrier (cm3) | 46 | 0 | 0 (46,058) | 180 → 180 | 0/47 | accepted=applied=47,970, unaccounted 0 | 0 |
| After, free-running (cm3) | 46 | 0 | 0 (46,354) | 191 → 189 (cumulative incl. preceding fixture run) | 0/67 | unaccounted 0 | 0 |

\* `duplicateApply`, `wrongOwnerMutation`, `wrongIncarnationMutation`, `staleIntentApply`,
`callerObjectLeak`, `unboundedReroute` (maxRerouteHop 1 of limit 5), `tokenCollision`,
`finalStateMismatch`. There was no RETIREMENT_FAILED in any run. The free-running run used the same
server instance as the preceding free-running fixture run. Its lifecycle counters are cumulative
since server start, so the churn and fixture shares cannot be separated from them. Its 4 explicit
terminals, and the rejected/pending/acceptedRemoveFailed counts, match the fixture scenarios that
produce them. The churn window's own guarantees are measured directly: stranded 0, MISSING_TERMINAL
0, C10 0 of 67, and C11 unaccounted 0.

### Regression
- **API-1B.7 `stress-pinned` / `stress-unpinned`:** PASS, MISSING_TERMINAL 0, invariants 0
  (unchanged).
- **API-1A deterministic set (`testmutate det`):** migration applies on the new owner; removed
  gives TARGET_NOT_FOUND; reroutelimit stops at hop 5 with REROUTE_LIMIT_DROPPED and nothing
  applied; ordering gives V2 APPLIED then V1 STALE_INTENT_DROPPED; visibility/sameowner rows are
  unchanged.
- **API-1B routed/direct fixtures:** all PASS. The set is snapshot, ordering, equalstrap,
  respawn-routed, relogin-routed, crossboundary-routed, window-respawn-routed,
  window-login-routed, worker-routed, offline-routed, slots, invalid, reroutelimit and generation.
- **`tests/modapi-compat/run-matrix.sh`:** 10 rows, 0 mismatches, 0 linkage errors. PKG-002
  packaging is untouched.

## Adjacent findings — recorded, not fixed (outside this gate)
- **A1 — Work rounds on a retired free-running region.** C3 skips ENTITY rounds (mailbox
  consumption and entity ticking) after RETIRED. Work rounds (`work != null`: scheduled ticks,
  `runWorkRound` batches) are not mailbox transport and keep their old behavior. A free-running
  worker blocked in `writeLock()` with a work round could still run it after retirement, and work
  posted to a retired region's free-running work slot is not handed off. Found by code reading;
  not reproduced. Needs its own gate (scheduled-tick lossless contract × retirement).
- **A2 — CLQ iterator removal is not a CAS** (C4 note). This is safe today by C3. It is a
  constraint on any future consumer.
- **A3 — L6 server-shutdown drain** (#15) is unchanged. Orphans and counters are reset on server
  stop.
- **A4 — Static per-JVM state.** The orphan queues, counters and test hooks are static and keyed
  by dimension, like `MailboxAudit`. Multi-MinecraftServer-per-JVM is still an open API-1B item.
- **A5 — Sync-result messages** (`PLAYER_BLOCK_INTERACTION`, `PLAYER_USE_ITEM*`,
  `PLAYER_ENTITY_INTERACT`). A terminal from re-resolution does not count down their latch; the
  dispatcher's existing bounded wait plus main-thread fallback and the `claimed` CAS decide the
  result, as for any slow delivery.
- **A6 — MODULE-RELOAD-001.** `/reload` fails, so the §7 datapack still needs a restart to load.
  This is the audit's side finding and is unchanged.

## Out of scope
- L6 shutdown;
- free-running scheduler changes;
- any Module or Observer work (Extension & Observability v2.0, MOD-API-REALWORLD-1);
- PKG-002 (frozen).
