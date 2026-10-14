# AE2-COMPAT-QUEUE-001 — implementation and acceptance fixtures (results)

**Status:** IMPLEMENTED in `nestworld-compat-ae2` per QUEUE-001 §3 and QUEUE-001.1 §4 (owner
refinements). Fixtures PASS, and the negative controls fail as required.
- Full AE2 unpin stays **BLOCKED** by AE2-COMPAT-LEGACY-ORIENTATION-001, which is open pending an
  owner decision.

## What was implemented
| Part | Mechanism |
|---|---|
| `setChangedQueued` | four-state protocol (`glue/SetChangedProtocol`, `mixin/AEBaseBlockEntityMixin`)<ul><li>The GETFIELD of the flag in `saveChanges` becomes the producer CAS (P-LP); the flag's PUTFIELDs are removed.</li><li>`setChangedAtEndOfTick` claims (C-claim) before `setChanged()` and finishes with the exact CAS rules of QUEUE-001.1 §4.2.</li><li>Self-call means the same block entity **and** the same callback generation (thread-local context), not thread identity.</li><li>Every producer writes the word (seq++), so it happens-before the next claim.</li></ul> |
| `serverQueue` / `callQueue` | producer→main staging (`glue/QueueStaging`, `mixin/TickHandlerMixin`)<ul><li>Off-main producers (canonical `server.isSameThread()`) go to concurrent staging; main keeps AE2's path.</li><li>Drain LPs: right before `processQueue(serverQueue)` in server END, and at the head of `onServerLevelTickStart(level)`, before AE2's remove/process/put.</li><li>Level unload drops staged work for that level (counted). `shutdown` clears.</li></ul> |
| `blockEntities` reader | `getBlockEntities` returns a view synchronized on the repo monitor, the writers' lock, so `keySet().toLongArray()` and `remove(pos)` hold it. `callInit` runs outside it on a detached list |
| lifecycle | `nw$life` per AE2 block entity<ul><li>`FirstTickInfo.callInit` takes the lock and **revalidates inside it**: not removed, level present, chunk loaded, `getBlockEntity(pos) == this`, same block.</li><li>The cross-thread removal sites (`LevelChunk.setBlockEntity` replacement and `removeBlockEntity`) take the same lock around the **virtual** `setRemoved()`, so the most-derived override (AE2 and addons) is covered.</li><li>Order is `nw$life → T0 → domains`. `nw$life` inside a guarded section is a lock-order violation: initialization becomes `unaudited` and fails loud; removal becomes `unaudited` and proceeds without the lock, because taking it there could deadlock.</li></ul> |

## Fixtures (`glue/QueueFixtures`, `/nwcompatae2 queuefixture <case> <pos>`)
- Setup: a real AE2 inscriber and the real TickHandler.
- Hook points: P-LP, C-claim, C-body, C-finish.
- Foreign producers run on a plain thread, so they take the staged path.

Note on the foreign producer: it runs `saveChanges`' server-branch protocol part, but leaves out the
synchronous `level.blockEntityChanged(pos)`.
- The first run used the full `saveChanges`. From a plain thread (neither main nor a region thread),
  `getChunkAt` waits for main while main is held by the fixture.
- Evidence of that: 10 s callback stalls, and "Can't keep up … 18464ms".
- So the first run measured join timeouts, not the protocol. That run's failures were a fixture
  artifact and were replaced.
- Real producers are region threads, which reach the chunk through Core's fast path.

| Case (QUEUE-001.1 §1.4 / §4.2) | Expected callbacks | Result |
|---|---|---|
| a, IDLE | 1 | PASS (1) |
| b, immediately before dequeue (3 producers while QUEUED) | 1 | PASS (1) |
| c/d, foreign producer during the callback | 2: exactly one follow-up | PASS (2, requeue 1) |
| e, 10 foreign producers during one callback | 2 (coalesced) | PASS (2, requeue 1) |
| f, self-re-entry from this block entity's own callback | 1 (vanilla suppression) | PASS (1, `setChangedSelfSuppressed` 1) |
| completion boundary: producer before the finish CAS (still RUNNING) | 2 via DIRTY | PASS (2, requeue 1) |
| completion boundary: producer after the finish (IDLE) | 2 via a fresh publish | PASS (2, requeue 0) |
| staging stress: 8 threads × 2 000 × (server + level) callables racing several drains | all 32 000 run exactly once | PASS (32 000) |

| Negative control (temporary patch, reverted) | Fixture | Result |
|---|---|---|
| vanilla clear-after (RUNNING never becomes DIRTY) | DURING | **FAIL as required:** 1 callback, the foreign change was not notified |
| plain clear-before (no self suppression) | SELF | **FAIL as required:** 2 callbacks (an extra self-feedback callback) |

Regression on the same jar (sha256 `f1b61328…`):
- the T2 fixtures STALE / WAKE / ALERT / SELF all PASS;
- the kernel's 18 tests pass;
- 120 s of multi-region injection plus churn: 0 exceptions, NORMAL, TPS 20, 0 lifecycle-order
  violations.

## Integration subgates (owner, AE2-COMPAT-GATE-STATUS)
Fixtures: `glue/IntegrationFixtures`, `/nwcompatae2 integration …`, `rig/integration-run.sh`.
- **Region-thread host:** an armor stand whose `tick` runs fixture tasks (`ArmorStandMixin`, fixture
  mode only). Entity ticks of a free-running region are not coordinated by main.
- **Why not a ticking block entity:** block-entity ticks are dispatched and awaited by main
  (NestWorld's BE phase), so a task parked in one blocks main. Two earlier fixture designs failed on
  exactly that, and one run had the target in a non-free-running auto-split region. Those runs were
  discarded as fixture defects. All regions are now set free-running.

| Subgate | Result |
|---|---|
| **QUEUE-LIFE-001, DURING:** `NestWorld-Region-18` (later 21) removes the inscriber while main is inside `callInit` under `nw$life` | **PASS.** The remover was blocked on `nw$life`; `onReady` ran once; the removal completed after init; no ghost node, no resurrection |
| **QUEUE-LIFE-001, BEFORE:** the region removes before main takes `nw$life` | **PASS.** Revalidation inside the lock cancelled the init (`removed`); `onReady` 0; no ghost node |
| **QUEUE-STAGING-001:** a vanilla hopper on a RegionThread feeds a powered AE2 charger | **PASS.** 5 publishes from the region thread were staged; claims 9 = publishes 9 + requeues 0 (exactly once); final phase IDLE; 4 crystals charged and moved |

| Negative control (temporary patch, reverted) | Result |
|---|---|
| remover without `nw$life` (DURING) | **FAIL as required: GHOST.** The removed block entity kept a grid node; the remover was not blocked |
| no revalidation inside the lock (BEFORE) | **FAIL as required: GHOST.** `onReady` ran on the removed block entity |

Clean-jar regression (`5bf32db6…`):
- integration fixtures 3/3 PASS;
- queue fixtures 8/8 PASS;
- T2 fixtures 4/4 PASS;
- kernel tests pass.
- The integration run logged a **`PathingService` pivot NPE**. This is a new, separate finding:
  AE2-COMPAT-GRID-PHASE-001.

## Not covered yet (superseded by the subgates above, kept for history)
- **Lifecycle lock under a real cross-thread removal during `callInit`.**
  - Implemented, but no deterministic fixture yet.
  - A plain test thread cannot remove a block entity from a region-owned chunk safely. It needs a
    region-thread driven removal hook.
  - `initCancelled` stayed 0 in all runs.
- **Staging under real region-thread producers in the load runs.**
  - The injection and churn scenario does not call `addCallable` from region threads: the
    `queueStaged*` counters only reflect the fixture.
  - A scenario with AE2 cable-shape updates or neighbour notifications from region threads is still
    to be built.
