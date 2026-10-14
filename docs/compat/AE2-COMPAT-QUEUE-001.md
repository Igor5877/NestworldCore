# AE2-COMPAT-QUEUE-001 — AE2 `TickHandler` deferred-work queues

- **Class:** MULTI_PRODUCER_MAIN_CONSUMER.
- **Status:** OPEN. CONFIRMED FROM CODE. NOT YET REPRODUCED IN PRODUCTION. First recorded in
  AE2-COMPAT-DESIGN-001.4 §5 (2026-10-14).
- **Owner rulings (2026-10-14):**
  - ACCEPTED as a finding.
  - Fix in `nestworld-compat-ae2` through a thread-safe producer→main handoff that preserves AE2's
    original queue semantics.
  - No AE2-specific code in Core, and no domain lock.
  - Not a blanket switch to concurrent collections: the structure must follow the audited semantics.
  - Separate contract from AE2-COMPAT-ENERGY-001; the verified kernel is not reopened.
  - **Full AE2 unpin is BLOCKED** until this is closed.

Evidence: AE2 15.4.10 (`fbfee05c…`), decompiled under `/root/mod-compat-audit/src`.

## 1. Semantics audit (the questions required before any structure is chosen)

| Property | `serverQueue` | `callQueue[level]` | `blockEntities` (`ServerBlockEntityRepo`) |
|---|---|---|---|
| Type | `ArrayDeque<ILevelRunnable>`, unsynchronized | `HashMap<Level, ArrayDeque>`, unsynchronized on `get` / `put` / `add` | `Map<Level, Long2ObjectMap<List<FirstTickInfo>>>`. Writers are `synchronized`, the **reader is not** |
| Producers | `addCallable(null, …)`: `AEBaseBlockEntity.saveChanges` (setChanged at end of tick; 70 call sites) and the `SpatialIOPort` transition | `addCallable(level, …)`: `Platform.notifyBlocksOfNeighbors`, `CableBusContainer.updateNeighborShapeOnSide`, Capability/ME P2P connection updates, and test-world actions | `GridHelper.onFirstTick` ← `AEBaseBlockEntity.scheduleInit` ← `clearRemoved` (block entity joins a loaded chunk) |
| Producer threads under NestWorld | any thread running AE2 block-entity or part code: region threads (unpinned), main (pinned, or the callbacks themselves) | same | the chunk-load / BE-add path: region or main thread |
| Duplicates | Prevented **producer-side** by the non-volatile flag `AEBaseBlockEntity.setChangedQueued` (§2). The queue itself allows them | allowed (no dedup) | allowed per chunk list. `queuedForReady` is a counter, and `readyInvoked` guards against double ready |
| Order | FIFO; no ordering contract between producers is visible | FIFO per level | FIFO per chunk list. Chunks are processed in key-array order (hash order) |
| Remove / cancel | none | `callQueue.remove(level)` on level unload (pending items dropped) | `removeChunk(level, pos)` on chunk unload; `removeLevel` on level unload. **Cancel semantics exist** |
| Producer expects a synchronous effect | no: deferred by design (end of server tick) | no: next level START | no: ready on the first level END where the chunk's block entities tick |
| Consumer phase (main) | server END, after all grids' `onServerEndTick`; 25 ms budget, and the rest carries over | that level's START, before the grid loop; 25 ms shared budget | that level's END, before the grid loop, only for chunks where `Platform.areBlockEntitiesTicking` |
| Enqueue during drain | A same-thread callback that enqueues during `processQueue` is processed **in the same drain** (`while (!isEmpty)`), subject to the budget | `remove(level)` → process → `put(level, queue)`, then the new queue is merged with `addAll`. Same-thread enqueue during the drain runs **next tick** | an init that schedules another init: appended to a fresh list, processed on a later END |
| Operation enqueues operation | yes (as above) | yes (as above) | yes (as above) |
| Exceptions | Per element: `catch (Exception)` → `AELog.warn`, continue. **This also swallows an NPE from a corrupt `poll()`** | same | Crash report → `ReportedException` (fatal) |

## 2. Hazards beyond the raw collection race
- **`setChangedQueued` flag (`AEBaseBlockEntity`).** The producer does `add(callback)` and then
  `setChangedQueued = true`. The consumer callback does `setChanged()` and then
  `setChangedQueued = false`.
  - If main runs the callback between the producer's `add` and its flag write, the flag ends up
    `true` with nothing queued. The block entity's deferred `setChanged()` is then never queued
    again until reload.
  - This happens **with a perfectly thread-safe queue too**, so a handoff alone does not close it.
    The glue must order "flag set" before "publish", or replace the flag with an atomic
    test-and-set wrapped around the producer.
  - Chunk persistence is unaffected: `saveChanges` calls `level.blockEntityChanged(pos)`
    synchronously. What is lost is the comparator / neighbour output update.
- **`callQueue` merge window.** A producer can `get` the old queue object while main holds it
  outside the map. An element added after main's `addAll` is lost: the old queue is dropped.
- **`readyBlockEntities` reads without the monitor.** `toLongArray()` and `remove(pos)` run
  concurrently with the synchronized `computeIfAbsent(...).add(...)`. A list obtained by a producer
  just before main removes it can be mutated while main iterates it, or the producer's add can
  land in a list that is already detached. Effects: a lost first-tick init (a block entity that
  never joins its grid) or a `ConcurrentModificationException`, which is fatal here.
- **Chunk-unload thread.** `ChunkEvent.Unload` is posted from `ChunkMap.scheduleUnload` through
  `unloadQueue`. In vanilla this is drained on main. Under NestWorld it is STRONGLY INDICATED main
  and UNKNOWN for region-managed chunk processing. To verify at the glue stage with the
  main-affinity counter.

## 3. Proposed handoff (for owner review; nothing implemented)
Principle: **main-thread producers keep the exact vanilla path; only off-main producers are
redirected**, into staging that main merges at a defined point of the consumer phase.
"Off-main" is decided with the canonical identity `server.isSameThread()` (001.5 §4).

| Structure | Off-main producer | Merge point (main) | Why it preserves semantics |
|---|---|---|---|
| `serverQueue` | Offered to an MPSC staging queue (FIFO per producer) | At the head of `onServerTickEnd`'s `processQueue`: drain staging → `serverQueue`, then the vanilla loop | These producers already expect "end of this or a later server tick". Same-thread re-entrant enqueue still runs in the same drain, as in vanilla. The exception path is unchanged |
| `callQueue[level]` | Per-level MPSC staging | At the head of `onServerLevelTickStart(level)`, **before** `remove(level)`: append staging to the level's queue (created if absent), then the vanilla remove/process/put/merge | Main is the only thread that touches the HashMap, so the merge window disappears. Level unload drops that level's staging together with `callQueue.remove(level)` (vanilla drop semantics) |
| `blockEntities` | **No staging.** Writers are already `synchronized`, and their order relative to `removeChunk` / `removeLevel` is an existing contract (add, then unload, must cancel) | `readyBlockEntities` takes the repo monitor to snapshot the key array and to `remove(pos)` each chunk list. `callInit` runs **outside** the monitor, because it is mod code | This preserves cancel semantics exactly: a staging log would have to replay add / remove in order and gains nothing. It is the minimal correct change: the missing half of an existing lock |
| `setChangedQueued` | Glue wraps `saveChanges`: an atomic test-and-set on a `@Unique AtomicBoolean` replaces the plain flag, set **before** publishing. The callback clears it **before** calling `setChanged()` | — | Restores "at most one queued, never none while dirty". A clear-before-run lets a concurrent producer enqueue a fresh callback that will see the new state |

Bounds and observability:
- Staging depth is counted: `queueStaged{server,level}` and `queueDropped{levelUnload}`.
- No budget change. The 25 ms budget still applies to the merged queue, so off-main work can
  carry over exactly like vanilla backlog.

## 4. Fixtures required before closure
1. Concurrent producers, many threads, `addCallable(null)` against the main drain. Expected: every
   callback runs exactly once, no NPE warn, and the order is preserved per producer.
2. `callQueue` merge window: a producer stalled between `get` and `add` while main drains (test
   hook). Expected: no element lost.
3. `setChangedQueued` interleaving: the callback runs between the producer's publish and its flag
   write. Expected: the next `saveChanges` queues again, so the flag is never stuck.
4. `readyBlockEntities` against a concurrent `scheduleInit` for the same chunk. Expected: the init
   runs exactly once, or is cancelled by a subsequent `removeChunk`. Never lost, never a CME.
5. Level unload with staged items: dropped and counted, never applied to an unloaded level.
6. Negative controls: remove the merge-before-remove (fixture 2), and restore the plain flag
   (fixture 3).

## 5. Live-evidence check (owner, read-only)
```
grep -c "appeng.hooks.ticking.TickHandler.processQueue\|Chunk .* was unloaded while we were readying" logs/latest.log
```
