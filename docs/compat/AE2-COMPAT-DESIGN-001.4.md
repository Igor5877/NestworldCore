# AE2-COMPAT-DESIGN-001.4 — T2 suspension, TickHandler phase contract, M1 detector scoping

**Status: DESIGN-1.4 (2026-10-14).**
- Records the owner's three Design-1.4 decisions.
- Records the phase-contract audit those decisions required.
- Specifies the T2 continuation the Mixin glue must implement.
- Everything else in 001.3, 001.2, 001.1 and 001 stands. Production unpin stays **NOT approved**.

Evidence: AE2 15.4.10 jar `fbfee05c…`, decompiled under `/root/mod-compat-audit/src`.
Confidence words follow MOD-COMPAT-1: CONFIRMED / STRONGLY INDICATED / CORRELATED / HYPOTHESIS /
UNKNOWN.

## 1. Owner decisions (2026-10-14)
1. **T2.** The suspend/resume direction is approved. Keeping the outer operation in the in-flight
   count while `unsafeTickingRequest` runs is **rejected**, because it can self-deadlock with
   NORMAL → QUIESCING.
   - Suspend must fully leave guarded execution and decrement in-flight.
   - Arbitrary device code then runs at depth 0.
   - Resume goes through a fresh outer entry under the current mode, and all mutable state is
     revalidated.
   - No stale iterators or references may be carried across a suspension.
2. **TickHandler.** A registry snapshot under T0 was approved, with iteration done without T0.
   Before implementation, the following had to be established:
   - whether snapshots are per traversal or shared across the start/end phases;
   - whether start/end pairing must be preserved.

   Further requirements:
   - Dead or stale networks need identity/liveness validation.
   - "A network created mid-tick starts ticking next tick" is a design choice pending this audit,
     not proven semantics.
   - T0 is never held across `Grid.onServerStartTick` / `onServerEndTick`, device callbacks or
     mod callbacks.
3. **M1 detector scoping.** Enforcement applies in energy sections; topology sections only count
   and record diagnostics. Generic lock-order and inversion enforcement stays active everywhere.

Gate for the glue: it may proceed only if the T2 continuation retains no unvalidated mutable
iterators or references across a suspension. §2.3 shows that AE2's own continuation **does**
retain them, and specifies the replacement. §3 shows that the registry snapshot is **not needed**,
and why.

## 2. T2 — suspension contract

### 2.1 Kernel primitive (implemented)
`Ae2Kernel.suspended(resumeSpec, device)`:

| Step | What happens |
|---|---|
| precondition | Outermost frame only: depth 1, holding an outer entry, not inside an admission continuation. Anything else → `unaudited(...)`, so the operation fails loud and the mode moves to QUIESCING. |
| leave | Exactly the outermost exit of `guarded()`: <ul><li>release domains and T0, or G in DEGRADED;</li><li>decrement the in-flight stripe;</li><li>if not NORMAL, try to complete the transition;</li><li>wait for this section's own admissions (001.3 invariant: the detector's call returns only after its continuation);</li><li>deliver M1 notifications in FIFO order, stopping at the first exception.</li></ul> |
| device | Runs at depth 0. Any AE2 call it makes is a fresh **outer** entry: it parks in QUIESCING and takes G in DEGRADED. The suspended caller is not counted, so it cannot hold the transition back. |
| resume | `enterOuter(resumeSpec)` under the mode current at that moment. The spec is resolved and validated again, so a domain that moved, for example after a merge during the device tick, is the one re-acquired. |
| exceptions | <ul><li>If a delivery or admission exception occurs at leave, the device code is not run, but the section is still re-entered and then the exception propagates. AE2's unwinding code (`finally { currentlyTicking = null; }`) therefore always runs guarded.</li><li>If the device code throws, the section is re-entered and then the exception propagates.</li><li>If resume itself fails (validation did not converge, which is already fail-loud), the frame is marked not-entered and `guarded()` skips its exit, so nothing is double-released.</li></ul> |

Tests (`SuspendTest`, 7 tests):

| Test | What it checks |
|---|---|
| Mandatory deadlock scenario | M holds D1 and suspends. Its device code enters D2 while B's detection holds QUIESCING. A background mutator keeps entering D4. Expected: no hang; one transition; one admission; the device entry and M's resumed tail both run under G; zero NORMAL/G overlap; zero in-flight leak. |
| Deferred delivery and fresh entry | Notifications deferred before a suspension are delivered before the device code. During the device code: no section, no domain held, in-flight 0. Resume counts as a new outer entry. |
| Re-resolve on resume | The domain migrates during the device code; the new domain is the one held after resume. |
| Delivery exception | The device is skipped and the section is re-entered. |
| Device exception | The section is re-entered, then the exception propagates. |
| Nested suspension | Unaudited, then DEGRADED; no lock leak. |
| Suspension outside a section | Unaudited. |

Long run (`-Pnw.iterations=50000 -Pnw.stressOps=100000`):
- the deadlock scenario passes 5,000 iterations;
- the full kernel suite (18 tests) is green;
- log: `/root/mod-compat-audit/kernel-suspend-long.log`.

**Negative control** (the rejected design: the stripe is kept incremented for the device call). The
mandatory scenario fails at iteration 0 with `M hung (suspend/QUIESCING deadlock)`. That confirms
the owner's rejection with a reproducible result.

The control was a temporary source patch. It was run once and reverted, and is not committed.

### 2.2 Where T2 runs
CONFIRMED: `TickManagerService.tickQueue` is reached only through `tickLevelQueue`, from
`onLevelEndTick` and `onServerEndTick`. Those are called only by `TickHandler`'s level-END and
server-END loops on the server main thread (`MinecraftServer.tickChildren` posts both level events
on main; §3). So:
- T2 is driven by one thread.
- It sits inside the per-grid phase wrapper T1, which is an outermost, non-topology section.
- Device code on main cannot re-enter `tickLevelQueue`.

### 2.3 What AE2's continuation retains across `unsafeTickingRequest` (CONFIRMED, decompiled)
```java
TickTracker tt = queue.peek(); ... queue.poll();
this.currentlyTicking = tt.getNode();
try { mod = this.unsafeTickingRequest(tt, diff); }      // ← device code: suspension point
finally { this.currentlyTicking = null; }
tt.setLastTick(this.currentTick); ... tt.setCurrentRate(newRate);
if (mod == SLEEP) this.sleepDevice(tt.getNode());
else if (this.awake.containsKey(tt.getNode())) queue.add(tt);
```
These values are retained across the call:

| Retained | Kind | Hazard once other threads may mutate the grid during suspension |
|---|---|---|
| `tt` | reference | The node may be removed (merge/split/destroy) and **re-added to the same service** with a new tracker `tt2`. `awake.containsKey(node)` is still true, so the stale `tt` is re-queued: the node ticks twice per period, and `tt` becomes a zombie that `removeFromQueue` (which removes `awake.get(node)`) never deletes. Vanilla has the same check, but only same-thread device code could trigger it there. |
| `queue` | reference to `upcomingTicks.get(level)` | The map entry cannot be removed while `currentlyTickingQueue == queue` (`removeFromQueue` checks it), and the queue is re-read via `peek()` on each iteration. Safe while the marker is set, but asserted rather than assumed. |
| `currentlyTicking` / `currentlyTickingQueue` | per-service in-progress markers | §2.5. |
| `this.currentTick` | field | Incremented only in `onServerStartTick` on main; T2 runs on main, so it cannot change during suspension. It is re-read anyway. |

**Verdict:** AE2's continuation does **not** satisfy the owner's condition as written, so the glue
must not only wrap the call.

### 2.4 Required continuation (glue specification)
The suspension point is `@WrapOperation` on the `unsafeTickingRequest` invocation inside
`tickQueue`. The wrapper calls `kernel.suspendedUnchecked(phaseSpec, () -> original.call(...))`,
where `phaseSpec` is the T1 spec of this service's grid, resolved again on resume.

After resume, everything AE2 does next is gated by one validation, evaluated under the re-entered
section:
```
valid(tt) := service grid is live (pivot != null, grid identity unchanged)
          && awake.get(tt.getNode()) == tt                // identity, not containsKey
          && upcomingTicks.get(tt.getNode().getLevel()) == queue
```
- `@WrapOperation` on `Map.containsKey` (in `tickQueue`, with `@Local TickTracker tt` and
  `@Local PriorityQueue queue` from MixinExtras) returns `valid(tt)`. If false, the stale tracker
  is **not** re-queued.
- `@WrapOperation` on `sleepDevice` in `tickQueue` applies the device's SLEEP decision only if
  `valid(tt)`. A stale tick result must not put the node's new tracker to sleep.
- `tt.setLastTick` / `setCurrentRate` on an invalid `tt` are left alone. That tracker is no longer
  reachable from the service, so these writes have no effect.
- If the queue-identity clause is false while the other two hold, this is not a shape the audit
  allows. The glue calls `kernel.unaudited("T2 queue identity")`: fail loud and serialize.
- Counters: `t2Suspensions`, `t2StaleTracker`, `t2StaleSleep`.

With this gate, nothing read before the suspension is used after it without validation. That is
the owner's condition for the glue to proceed.

Fixture (glue stage, deterministic): during a suspended device tick, a second thread removes and
re-adds the same node to the same grid. Expected: exactly one tracker in the queue, the stale `tt`
not re-queued, `t2StaleTracker = 1`, and the node ticked once per period afterwards.

### 2.5 In-progress markers across suspension — OPEN decision for the owner
The markers stay set during suspension: AE2 sets `currentlyTicking` before the call and clears it
in `finally` after resume. Effects:
- **Same-thread device code** (the device ticking itself): `alertDevice` / `sleepDevice` /
  `wakeDevice` on its own node return `false`. This is vanilla-equivalent.
- **Foreign threads** (a region-thread device alerting or waking the suspended node): they also get
  `false`, with no state change. The boolean is honest ("no completed state change"), and the
  outcome is a valid linearization: the call happens during the node's tick.

  This is still a **semantic divergence**. In vanilla, a foreign alert can never land inside
  another node's tick, so it is always applied before or after. Possible effect: a lost wakeup.
  The device returns SLEEP and stays asleep despite new work, until the next alert.
  Liveness only: no state corruption, no item loss.

Options:
- **(a) Keep the markers.** Accept the window and count `t2ForeignOpOnTicking{alert,sleep,wake}`.
  The canary gate treats a non-zero count with a stuck device as a finding.
- **(b) Replay after the tick.** Return `false` now and apply the call after the continuation.
  This brings back deferred application of a boolean-returning operation, the M3 shape the owner
  withdrew.

**Recommendation: (a)**. It adds no new semantics, and the counters make the window observable.
The decision is the owner's. The glue implements (a) behind the counter until ruled otherwise.

## 3. TickHandler phase contract — audit result

### 3.1 Facts (CONFIRMED, decompiled AE2 + NestWorld `MinecraftServer.tickChildren`)
- **The registry is already double-buffered by AE2.** `ServerGridRepo` has `networks` plus
  `toAdd` / `toRemove`.
  - `addNetwork` / `removeNetwork` are `synchronized` and only touch `toAdd` / `toRemove`.
  - `networks` is mutated **only** in `updateNetworks()`, also `synchronized`.
  - `updateNetworks()` is called only from `onServerLevelTickStart` (once per level, before that
    level's grid loop) and from `onUnloadLevel`.
- **All traversals and all `updateNetworks()` calls run on the server main thread.**
  - NestWorld posts `onPreLevelTick` / `onPostLevelTick` from `MinecraftServer.tickChildren` on
    main, around `tickAllRegions`. `ServerTickEvent` START/END are posted from `tickServer`, also
    on main.
  - Region threads, free-running or not, never post these events.
  - `onUnloadLevel` (`LevelEvent.Unload`) runs on main at shutdown or dimension unload.
- **Consequence.** Region-thread `addNetwork` / `removeNetwork` (from `Grid.create` and grid
  death during topology work) only reach the synchronized side buffers. `networks` is never
  mutated during a traversal, because traversal and promotion are both on main and sequential.
  **There is no iteration race on the registry.** My Design-1.3 gap note ("the registry is iterated
  unguarded against T0 topology") overstated it: the race does not exist at the registry level.
- **Per-traversal vs shared.**
  - Server START and server END traverse the same `networks` content as the last level-START
    promotion.
  - Each level START promotes and then traverses.
  - Level END traverses the set promoted at that level's START.

  So the set is fixed between promotions, which happen only at level START. The order within one
  server tick is:
  ```
  server START (set S0) → [level L1: START promote → S1, traverse S1; regions; END traverse S1]
                       → [level L2: START promote → S2 …] … → server END (set Sn)
  ```
- **Start/end pairing.**
  - A grid created during L1's region work is promoted at the next level START, which is L2 in
    the same tick, or L1 in the next tick when there is only one level. From then on it receives
    level END and server END.
  - It misses the server START of that tick.
  - The only server-START work is `TickManagerService.currentTick++`, plus the other services'
    start hooks. In vanilla AE2 these hooks tolerate a missing first START, because grids are
    created inside level ticks in vanilla too.
  - NestWorld does not change *when* grids become visible. It changes only *which thread* created
    them, and the buffer absorbs that.
- **Dead grids.**
  - A grid whose last node left has `pivot == null`. `removeNetwork` puts it in `toRemove`, and it
    stays in `networks` until the next promotion.
  - Every `Grid.on*Tick` checks `pivot != null` first. Under NestWorld that read happens on main
    while a region thread may be finishing the grid's death.
  - The T1 phase wrapper therefore checks liveness **after** acquiring the grid's domain, through
    the spec's `validate()`. A grid that died in between is skipped under the lock, and AE2's own
    check sees the stable value.

### 3.2 Decision implemented
- **No registry snapshot and no T0 on the traversal path.** AE2's own buffering already gives a
  per-traversal stable set, and T0 is held across no phase callback.
  - This replaces the approved "snapshot under T0" with a smaller change. The owner should confirm
    the substitution.
  - The snapshot is unnecessary given the facts above. Adding it would only add a second buffer
    with its own pairing semantics.
- **Dev assertion** (counted in production, fail-loud in dev only):
  - `ServerGridRepo.updateNetworks` and `TickHandler` grid traversals must run on the server main
    thread.
  - A future NestWorld change that ticks levels off main would break the premise of §3.1, and this
    assertion is how that would be noticed.
- **T1 phase wrapper.**
  - Takes the grid's domain (outermost, non-topology) around each `Grid.on{Server,Level}{Start,End}Tick`
    invocation **per grid**, never around the whole loop.
  - Validates `pivot != null` and grid identity under the lock, and skips if dead.
  - T0 is never held. Device code inside the END phases leaves the section through §2.
- "Created mid-tick starts at the next level START" is **vanilla AE2 behaviour that NestWorld keeps
  unchanged**. It is not a new design choice and not a NestWorld guarantee beyond that.

## 4. M1 detector scoping (decision 3)
- **Energy sections** (E1–E11, the T1 wrapper of `EnergyService` phases) are **enforced**: a
  foreign participant's notification that would run under the lock is deferred (M1), and an
  out-of-order acquisition is refused (M2).
- **Topology sections** (G1/G2, under T0) are **count/diagnostic only** for the M1 detector. The
  counter `m1InTopology{class}` is reported with the 001.3 §5 diagnostic fields.
  - This holds because topology sections run node listeners and `setGrid` callbacks that AE2
    already orders.
  - Deferring them would change AE2's own topology semantics, which no audit has covered.
- **Generic lock-order and inversion enforcement stays active everywhere**, topology included:
  - `nested()` refuses lower-order blocking;
  - T0 requested while domains are held → `unaudited`.

## 5. New finding outside the energy domain — AE2 `TickHandler` deferred-work queues
Found during §3. **Recorded, not reproduced, not fixed.**

```
Race: appliedenergistics2 hooks.ticking.TickHandler.{serverQueue, callQueue, blockEntities}
  writers (any thread that runs AE2 block-entity or part code, i.e. region threads unless pinned):
    serverQueue (ArrayDeque, unsynchronized) ← addCallable(null, …)
        AEBaseBlockEntity.saveChanges → setChangedAtEndOfTick   (70 call sites of saveChanges)
        SpatialIOPortBlockEntity transition callback
    callQueue (HashMap<Level, ArrayDeque>, unsynchronized: get / put / add) ← addCallable(level, …)
        Platform.notifyBlocksOfNeighbors, CableBusContainer.updateNeighborShapeOnSide,
        CapabilityP2PTunnelPart / MEP2PTunnelPart connection updates
    blockEntities (ServerBlockEntityRepo: add/removeChunk synchronized) ← GridHelper.onFirstTick
  readers (server main thread):
    processQueue: poll loop on serverQueue (server END) and callQueue[level] (level START,
        via remove → process → put → addAll merge)
    readyBlockEntities (level END): levelQueue.keySet().toLongArray() and levelQueue.remove(..)
        WITHOUT the repo monitor
```

| Statement | Confidence |
|---|---|
| The three structures are unsynchronized on at least one side of a cross-thread access. | CONFIRMED (code) |
| Writers are reached from region threads whenever AE2 block entities or parts tick unpinned. That is the island configuration that produced the 2026-10-05 EnergyService race. | STRONGLY INDICATED |
| The race is observed live. | UNKNOWN (no log evidence collected) |

Expected effects. All are HYPOTHESIS until observed.
- **Corruption of an unsynchronized `ArrayDeque` or `HashMap` under concurrent add/poll:**
  - lost callbacks;
  - `poll()` returning `null`, which gives an NPE that `processQueue`'s `catch (Exception)` turns
    into an `AELog.warn`, so it is swallowed;
  - an exception on the region thread during a resize.
- **A lost `setChangedAtEndOfTick`.**
  - `setChangedQueued` stays `true` for that block-entity instance, so its deferred `setChanged()`
    (comparator / neighbour output update) is never queued again until the chunk reloads.
  - Chunk persistence is **not** affected: `saveChanges` calls `level.blockEntityChanged(pos)`
    synchronously first.
- **A lost `callQueue` item:**
  - neighbour updates missing;
  - cable shape not refreshed;
  - P2P tunnel not reconnected until the next trigger.
- **`callQueue` merge window.** Between `remove(level)` and `put(level, queue)` a concurrent
  writer may create a new queue. That queue is merged by `addAll`, but any element added to it
  after the merge is lost.
- **`readyBlockEntities`.** An unsynchronized `toLongArray` / `remove` runs concurrently with a
  synchronized `computeIfAbsent(...).add`. Possible outcomes: a lost first-tick init, meaning a
  block entity that never joins its grid, or a list mutated while main iterates it.

Scope consequences:
- This addon (energy domain) does not cover it.
- It is another reason, besides storage, pathing and P2P, why **whole-AE2 unpin stays forbidden**.
- It is a **precondition for the unpin review** of any AE2 block-entity type whose code calls
  `saveChanges` / `notifyBlocksOfNeighbors`, which in practice is all of them.

Candidate fix classes, for the owner to choose; none started:
- A core-side collection transformer of class C1/C2. The queues become concurrent structures, and
  the `callQueue` merge becomes `computeIfAbsent` plus a drain without the remove/put dance.
- Main-thread marshalling of `addCallable` from region threads.

Owner read-only check for live evidence on an island:
```
grep -c "appeng.hooks.ticking.TickHandler.processQueue\|Chunk .* was unloaded while we were readying" logs/latest.log
```

## 6. Next steps
1. Owner confirms or rejects: §2.5 option (a); §3.2 substitution (no snapshot); §5 recording.
2. Glue proceeds per §2.4 and §3.2. The condition of decision 1 is met by the §2.4 validation gate.
3. The glue-stage fixture of §2.4 joins the gate list (001.3 §8 item 2, transition-race tests).
