# AE2-COMPAT-DESIGN-001.5 — T2 marker semantics across suspension

**Status: DESIGN-1.5 (2026-10-14).**
- Replaces 001.4 §2.5 (option (a) is rejected by the owner) and 001.4 §2.4 (the continuation gate
  is extended).
- Also amends 001.4 §3.2 (the assertion's thread identity).
- The rest of 001.4 stands.
- Production unpin stays **NOT approved**. Full AE2 unpin is **BLOCKED** by AE2-COMPAT-QUEUE-001.

## 1. Owner rulings on 001.4 (2026-10-14)

| Item | Ruling |
|---|---|
| T2 suspend/resume (kernel `suspended`) | APPROVED |
| 001.4 §2.5 option (a), keep the markers and accept a lost wake | **REJECTED.** A lost wake is a liveness/correctness failure, not an acceptable window |
| 001.4 §3.2 registry snapshot | WITHDRAWN. AE2 `ServerGridRepo` semantics are preserved; only a canonical main-server-thread assertion is added |
| TickHandler queues | ACCEPTED as **AE2-COMPAT-QUEUE-001**, class MULTI_PRODUCER_MAIN_CONSUMER. Fixed in this addon by a thread-safe producer→main handoff, **not** by AE2-specific code in Core. Structure only after a queue-semantics audit (`docs/compat/AE2-COMPAT-QUEUE-001.md`) |
| Contract split | AE2-COMPAT-ENERGY-001 (this design line) and AE2-COMPAT-QUEUE-001 are separate. QUEUE-001 blocks full AE2 unpin but does not reopen the verified kernel |
| Mixin glue | APPROVED once the T2 marker semantics are fixed (this document) |

Binding principle: **the old tick's identity may survive a suspension as a token; the old tick's
behavioural suppression must not.**

## 2. Marker audit — what each AE2 marker does (CONFIRMED, decompiled 15.4.10)

| Marker | Read by | Effect while set |
|---|---|---|
| `currentlyTicking` (node) | `alertDevice`, `sleepDevice`, `wakeDevice` | The call on that node returns `false` with no state change. This is **behavioural suppression**. |
| `currentlyTickingQueue` (queue) | `removeFromQueue` only (`if (currentlyTickingQueue != queue && queue.isEmpty()) upcomingTicks.remove(level)`) | Prevents removal of the map entry of the queue being iterated. It suppresses no device operation, and it keeps `queue` identical to `upcomingTicks.get(level)` across the callback. |

Who relies on the `currentlyTicking` suppression? CONFIRMED self-call path:
- `MolecularAssemblerBlockEntity.tickingRequest` calls `updateSleepiness()`, which calls
  `sleepDevice(self)` / `wakeDevice(self)`, and then returns `SLEEP` / `IDLE`, consistent with that
  call.
- In vanilla the self-call is a no-op, and the returned modulation alone decides.
- The same helpers (`updateSleepiness`, `IOPort.updateTask`, `InterfaceLogic` plan update,
  `IOBus.updateRedstoneState`, `StorageBus` monitor change) are also called from inventory and
  config change paths. Those run on the device's **own region thread**, which is foreign to the
  T2 driver (main), and in this pack can overlap the device's tick.

Producers of `wakeDevice` from outside the device's tick: Inscriber, VibrationChamber, Charger,
MolecularAssembler, Interface, IOPort, IOBus, StorageBus, ME P2P and `P2PService`. Under NestWorld
these run on region threads, so they **can** land during a suspended tick of the same node. That
interleaving is impossible in vanilla.

Consequence:
- The suppression is needed only for **self-calls made within the device callback's own dynamic
  extent**. That is vanilla behaviour and is kept.
- Applied to foreign threads it causes the rejected lost wake, so it is removed for them.

## 3. T2 model

### 3.1 State held by the glue (`@Unique` fields)

| Where | Field | Meaning |
|---|---|---|
| `TickTracker` | `long nw$gen` | Incremented at the head of `TickManagerService.addToQueue` and `removeFromQueue`. Every scheduling change outside the T2 loop goes through one of them: alert, sleep, wake (via `updateQueuePosition`), `removeNode` and `addNode` (a new tracker). |
| `TickManagerService` | `TickTracker nw$suspendedTt`, `long nw$suspendedGen`, `Thread nw$suspendedThread` | The identity token of the tick in progress. Set under the section just before suspension and cleared after the continuation decision. |

### 3.2 Sequence
```
tickQueue (AE2) under T1 section for this grid:
  tt = poll(); currentlyTicking = node                       (AE2)
  ── @WrapOperation unsafeTickingRequest ─────────────────────────────── glue
  │ token := (tt, tt.nw$gen, Thread.currentThread())
  │ currentlyTicking := null           ← suppression does NOT survive suspension
  │ kernel.suspended(phaseSpec, () -> original(tt, diff))    (device at depth 0)
  │ — re-entered under the current mode —
  │ decision := valid(token)           ← evaluated once, under the section
  └──────────────────────────────────────────────────────────────────────
  finally currentlyTicking = null                            (AE2)
  tt.setLastTick / tt.setCurrentRate  ── gated by decision
  SLEEP → sleepDevice(node)            ── gated by decision
  awake.containsKey(node) → queue.add  ── replaced by decision
```
```
valid(token) := grid live (pivot != null, grid identity unchanged)
             && awake.get(node) == tt                    // identity, not containsKey
             && tt.nw$gen == token.gen                   // nothing rescheduled tt meanwhile
             && upcomingTicks.get(level) == queue        // queue generation
```
- If the queue clause is false while the others hold, `kernel.unaudited("T2 queue identity")`
  fires. `currentlyTickingQueue` stays set (§2), so this is a should-never shape: fail loud and
  serialize.
- When `valid` is false, **all four** continuation effects are skipped: last tick, rate, SLEEP and
  re-queue. The intervening operation's state wins.
- Skipping the field writes is mandatory. `TickTracker` orders by `lastTick + currentRate`, so
  writing either field while `tt` is inside a `PriorityQueue` would break the heap invariant.

### 3.3 Calls on the suspended node during the callback (glue wrappers on alert / sleep / wake)
Each is an ordinary outer AE2 operation with its synchronous, honest return value.

| Caller | Operation | Behaviour |
|---|---|---|
| **Same thread as `nw$suspendedThread`**, node == `nw$suspendedTt.node` | alert / sleep / wake | `return false`, no change. This is vanilla self-call semantics, limited to the callback's own dynamic extent. While the callback runs, main executes nothing but the callback, so "main thread during suspension" is exactly that extent. |
| Foreign thread | `alertDevice` | Vanilla body. `tt` is re-queued through `updateQueuePosition`, so `nw$gen` changes. The glue's `addToQueue` hook first sets `tt.lastTick := currentTick` when `tt == nw$suspendedTt`, so the alert means "next server tick", not a second tick in the same phase loop. Returns `true`. |
| Foreign thread | `wakeDevice` | Vanilla returns `false` because the node is in `awake` (it is only polled, not sleeping). That is the lost-wake case. Glue rule: if `awake.get(node) == nw$suspendedTt` and the tracker has not been rescheduled since suspension, perform `lastTick := currentTick` then `addToQueue` (so `nw$gen` changes), and return `true`. This is a completed, synchronous state change: the node is scheduled. |
| Foreign thread | `sleepDevice` | Vanilla body: awake → sleeping, rate := max, `removeFromQueue` (so `nw$gen` changes). Returns `true`. The continuation is skipped, so the node stays asleep. Linearization: the sleep happened after the tick. |
| Any thread | `removeNode` / `addNode` | Vanilla body. The identity clause, or `nw$gen`, invalidates the token. |

Why this is not M3:
- No call returns a value describing an effect that has not happened.
- `wakeDevice` returns `true` only after the node is actually in the queue.
- Nothing is replayed later; the continuation only refrains from overwriting newer state.

Deviation from vanilla, recorded:
- A foreign wake landing mid-tick schedules the node at `currentTick + currentRate`. In the vanilla
  linearization "wake after a SLEEP result" it would be `currentTick + maxTickRate`.
- Ticking sooner is within the contract devices already accept from `alertDevice`.
- The device's modulation for that tick is discarded. That modulation was computed before the
  wake's cause was visible.

### 3.4 Acceptance fixtures (glue stage, real AE2 classes)

1. **Owner's stale-continuation test.** The old `tt` enters the callback. During the callback the
   node is removed, the same node is re-added, and a new tracker `tt2` is installed. Then the
   callback returns.
   - The old continuation must **not**: re-queue `tt`, sleep `tt2`, remove `tt2`, or tick the node
     twice in a period.
   - Expected: exactly one tracker for the node in the queue, and it is `tt2`. `t2StaleTracker = 1`.
2. **Negative control for 1.** Replace the identity clause with AE2's `containsKey`. The test must
   detect the zombie or double tick.
3. **Lost-wake fixture.** The device returns `SLEEP`, and a foreign `wakeDevice` arrives during the
   callback. Expected: the wake returns `true`, the node is awake and queued for the next tick, and
   is ticked again.
   **Negative control:** keep the `currentlyTicking` suppression for foreign threads. The test
   must detect the node asleep with no pending tick.
4. **Same-phase double-tick fixture.** A foreign `alertDevice` during the callback. Expected: the
   node is ticked once in this phase and once on the next tick.
5. **Self-call fixture.** A MolecularAssembler-shaped device calls `sleepDevice(self)` and then
   returns `IDLE`. Expected: vanilla result, i.e. the node is awake and re-queued, and the
   self-call returned `false`.

Counters: `t2Suspensions`, `t2StaleTracker`, `t2ForeignAlert`, `t2ForeignWake`,
`t2ForeignSleep`, `t2SelfCall`.

## 4. Main-thread assertion (amends 001.4 §3.2)
- Identity: `ServerLifecycleHooks.getCurrentServer().isSameThread()`.
  - Checked: `BlockableEventLoop.isSameThread()` is `Thread.currentThread() == getRunningThread()`.
  - `MinecraftServer.getRunningThread()` returns the unpatched `serverThread` field. NestWorld does
    not patch either, so a `RegionThread` returns `false`.
- **Not** `Platform.assertServerThread()` / `SidedThreadGroups`: `RegionThread` inherits the SERVER
  thread group, so that check passes on region threads (COMPAT-THREAD-IDENTITY-001).
- Sites:
  - `ServerGridRepo.updateNetworks`;
  - the four `TickHandler` grid traversals;
  - `readyBlockEntities`;
  - the `processQueue` drains.
- On violation: a diagnostic record with the 001.3 §5 fields, and the counter
  `mainAffinityViolation`. Dev runs fail loud. Production applies `kernel.unaudited(...)`, so the
  addon moves to DEGRADED serialization rather than continuing silently.
