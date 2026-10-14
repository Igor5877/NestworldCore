# AE2-COMPAT-QUEUE-001.1 — `setChangedQueued` clear ordering and `blockEntities` cancellation

**Status: AUDIT (2026-10-14) — for owner review. No code.** Narrow follow-up to AE2-COMPAT-QUEUE-001.

Owner rulings this audit answers:
- `serverQueue` / `callQueue` staging: APPROVED IN PRINCIPLE.
- `setChangedQueued` CAS: approved, pending a proof of the consumer-side clear.
- `blockEntities`: BLOCKED pending the cancellation race audit.

AE2 15.4.10 (`fbfee05c…`); NestWorld tree at `9eb88d161`.

## 0. Phase linearization point for staging (owner wording, adopted)
> Work published before the phase-drain linearization point is eligible for the current consumption
> phase; work published after it is eligible for the next one.

The drain linearization point (LP) is the main thread's take of the staging snapshot at the head
of the consumption phase:
- `onServerTickEnd`, before `processQueue(serverQueue)`;
- `onServerLevelTickStart(level)`, before `callQueue.remove(level)`.

Staging is an MPSC queue, so offer → take gives happens-before. Main-thread producers keep the
vanilla path, and their same-drain or next-tick behaviour is unchanged (QUEUE-001 §1).

## 1. `setChangedQueued`: clear ordering

### 1.1 Vanilla protocol (CONFIRMED, decompiled)
```
producer  saveChanges():          level.blockEntityChanged(pos);           // chunk dirty, synchronous
                                  if (!setChangedQueued) { addCallable(null, setChangedAtEndOfTick); setChangedQueued = true; }
consumer  setChangedAtEndOfTick(): setChanged();  setChangedQueued = false;   // clear AFTER the work
```
- Same-thread re-entrancy in vanilla: if `setChanged()` (comparator and neighbour updates)
  re-enters `saveChanges()` on this block entity, the flag is still `true`, so nothing is queued.
  Vanilla deliberately suppresses self-re-enqueue. That bounds feedback loops through the block
  entity's own neighbour updates.
- What a callback delivers: `setChanged()` **reads the block entity's state at call time**. So a
  callback "covers" every modification that happened before it starts reading.

### 1.2 Why neither ordering alone is right
| Consumer clear | Failure |
|---|---|
| after the work (vanilla) | A foreign producer modifies the block entity during `setChanged()`, sees `true`, and skips. The flag is cleared afterwards, and **that change is never notified** (the owner's case). |
| before the work (`true → false`, then run) | The foreign case is fixed. But same-thread re-entry during `setChanged()` now enqueues again, and the vanilla loop processes it **in the same drain** (`while (!isEmpty)`). That replaces vanilla's bounded behaviour with up to 25 ms of self-feedback callbacks per tick. This changes semantics the owner asked to keep. |

### 1.3 Proposed protocol: four states, with the vanilla self-suppression kept
A `@Unique AtomicLong` on `AEBaseBlockEntity` packs `phase` (2 bits) and `seq` (a counter). Every
producer call increments `seq` (a write, so every producer establishes happens-before with the
consumer's next CAS). Phases:

```
IDLE ──P:CAS──▶ QUEUED ──C:claim──▶ RUNNING ──C:finish──▶ IDLE
                                      │ ▲
                         P(foreign)   ▼ │ C:finish fails
                                   RUNNING_DIRTY ──C:finish──▶ QUEUED (+ re-enqueue on main)
```
| Actor | Step | Linearization point |
|---|---|---|
| Producer (any thread, after modifying the block entity) | `IDLE → QUEUED`: publish the callback (staging if off-main, the vanilla queue if main).<br>`QUEUED`: no-op apart from `seq++`.<br>`RUNNING`: if the caller is the runner thread inside the callback's own extent, vanilla suppression (no-op apart from `seq++`); otherwise `→ RUNNING_DIRTY`.<br>`RUNNING_DIRTY`: no-op apart from `seq++`. | **P-LP** = its successful CAS on the atomic, which always writes |
| Consumer (main, inside `setChangedAtEndOfTick`) | claim `QUEUED → RUNNING` **before** `setChanged()` reads any state. | **C-claim LP** |
| | `setChanged()` | — |
| | finish `RUNNING → IDLE`. If the CAS fails because the state is `RUNNING_DIRTY`: `→ QUEUED`, and re-enqueue through the main-thread vanilla path. | **C-finish LP** |

"Runner thread inside the callback's extent" is decided with the same rule as the T2 self-call in
001.5 §3.3. During the callback, main executes only that callback, so main plus `RUNNING` means
self-re-entry.

### 1.4 Required cases: callbacks received
In each case M is the block-entity modification, made before the producer's `saveChanges()`. A
notification is **covered** iff some callback's C-claim LP comes after M.

| Case | Interleaving | Callbacks (this M) | Covered? |
|---|---|---|---|
| a | P before any callback is pending (`IDLE`) | 1 | yes: the claim follows P-LP, which follows M |
| b | P **immediately before dequeue** (state `QUEUED`, the pending callback not yet claimed) | 0 extra; the pending one runs | yes: its claim follows P-LP, and `seq++` gives the happens-before for M |
| c | P **immediately after dequeue**: claimed (`RUNNING`) but `setChanged()` not yet started, or mid-way | 1 extra (re-enqueue on finish) | yes: the extra claim follows M. The first callback may or may not have seen M, so this is redundant at worst, and `setChanged()` is idempotent |
| d | P **during the callback**, foreign thread | 1 extra | yes, as in c |
| e | Several foreign Ps during one `RUNNING` | 1 extra in total (`RUNNING_DIRTY` absorbs them) | yes: one claim after all of them |
| f | Same-thread re-entry during the callback (from `setChanged()`'s own neighbour updates) | 0 extra | **vanilla semantics** (§1.1): intentionally not covered, exactly as vanilla. Bounded |
| g | P (main, pinned or vanilla path) while `IDLE` | 1 | yes |
| h | Block entity removed while `QUEUED` | 1 (runs on the removed block entity) | vanilla-identical: vanilla also runs it |

Bound: a callback is re-enqueued only by a foreign producer that reached `RUNNING_DIRTY` during it.
So the number of callbacks is at most the number of foreign `saveChanges` calls that overlap a
running callback, plus one. No self-sustaining loop exists that vanilla does not already have.

The re-enqueue goes onto `serverQueue` from main, so it may run in the same drain (vanilla
behaviour for main-thread enqueue during a drain).

Glue: `@WrapMethod` on `saveChanges` and `setChangedAtEndOfTick`. The plain `setChangedQueued`
field is no longer read or written by the wrapped bodies; it is mirrored for any external
reflection reader, as `phase != IDLE`.

### 1.5 Fixtures (deterministic, test hooks at P-LP, C-claim, C-finish)
- Cases a–h, each asserting the exact callback count from the table and "covered".
- Negative control 1: vanilla clear-after. Case d must lose the notification.
- Negative control 2: plain clear-before. Case f must produce extra same-drain callbacks.

## 2. `blockEntities`: cancellation

### 2.1 What cancels pending initialization (CONFIRMED)
| Path | Mechanism | Thread under NestWorld |
|---|---|---|
| Chunk unload | `ChunkMap.scheduleUnload`: post `ChunkEvent.Unload` (AE2 `removeChunk`), then `level.unload(chunk)`, which runs `clearAllBlockEntities`: `onChunkUnloaded`, then `setRemoved` | **main**. `processUnloads` drains `unloadQueue` only inside `ChunkMap.tick()`, which runs on main (Core's own audit in `NestworldTickOwnership` javadoc, and `NestworldDimensionRegion:1058`) |
| Level unload | `removeLevel` | main |
| Block removed (break, replace, piston, explosion, …) | `LevelChunk.setBlockState` → BE removal → `setRemoved()` (AE2: `mainNode.destroy()`) | **the owning region thread** |
| Vanilla guard at init time | `readyBlockEntities` skips `info.blockEntity().isRemoved()` | main |

**Consequence:**
- The owner's scenario "main extracts, then a region `removeChunk`, then `callInit`" **cannot
  occur**: `removeChunk` and `readyBlockEntities` are both main-only, so they are sequential.
  Asserted with the main-affinity check, not assumed.
- The real cancellation race is the third row. The `isRemoved()` check on main and `setRemoved()`
  on the region thread form a check-then-act race:
  ```
  main:   isRemoved()==false ─────────▶ callInit → onReady → mainNode.create(...)
  region:            setBlockState(air) → setRemoved → mainNode.destroy()
  ```
  - If `destroy` runs before `create`, a grid node is created for a removed block entity. That is
    a ghost member of the grid (channels, idle power), never destroyed.
  - Interleaving inside `create` / `destroy` races `ManagedGridNode` internals.
  - Neither a collection monitor nor a cancel bit read before `callInit` closes this: both are
    TOCTOU against a remover on another thread.

What `onReady` does, which is what must not happen after removal:
- `AEBaseBlockEntity.onReady`: `readyInvoked++`; if `pendingOrientationChange != null`,
  `level.setBlockAndUpdate(pos, orientedState)`. This is a **world write from main**, using the
  block entity's cached state.
- `AENetworkBlockEntity` / `AENetworkPowerBlockEntity.onReady`: `mainNode.create(level, pos)`, and
  `markForUpdate()`.

### 2.2 Proposed protocol: per-block-entity lifecycle exclusion
A Mod API region-submit is not available: the API deliberately forbids generic `Runnable`
submission, and the owner ruled out a new primitive. So initialization stays on main and is
excluded against removal per block entity:
- `@Unique ReentrantLock nw$life` and a state `PENDING | INITIALIZING | READY | REMOVED` on
  `AEBaseBlockEntity`.
- **Initializer** (main, `readyBlockEntities`):
  1. take the repo monitor; snapshot the keys and `remove(pos)`; release the monitor;
  2. per item: `nw$life.lock()`;
  3. if the state is `REMOVED` or `isRemoved()`, skip (`initCancelled++`);
  4. else `INITIALIZING`, `callInit`, `READY`;
  5. `unlock`.
- **Remover** (any thread), `@WrapMethod` on `setRemoved` and `onChunkUnloaded`:
  1. `nw$life.lock()`;
  2. state `REMOVED`;
  3. original body (`destroy`);
  4. `unlock`.
- Result: `onReady` (create) and `setRemoved` (destroy) are mutually exclusive per block entity,
  in one of two orders:
  - init then removal (the node is created, then destroyed);
  - removal then a skipped init.

  Both are vanilla-reachable orders.
- **Lock order:** `nw$life` → T0 → domains (create and destroy are G1 topology entries).
  - Rule enforced by the kernel: acquiring any `nw$life` while `inSection()` is an unaudited path,
    so it fails loud and the addon moves to DEGRADED.
  - That excludes the only inversion shape (topology code that removes another block entity while
    holding T0).
  - Device callbacks that break blocks (for example the annihilation plane) run at depth 0 (T2),
    so they hold no lock.
- `callInit` is AE2 code held under a per-block-entity lock. Its blocking calls are T0 and domain
  acquisition, which are ordered after `nw$life`. Nothing inside it takes another block entity's
  `nw$life`; asserted the same way.

### 2.3 Residual: not closed by 2.2 (recorded, HYPOTHESIS, needs an owner decision)
The remover's real linearization point is `LevelChunk.setBlockState(air)`, which happens **before**
`setRemoved()`. In the window between them, main's `onReady` can:
- under `nw$life`, see the state as not `REMOVED`;
- execute `setBlockAndUpdate(pos, orientedState)` from the block entity's **cached** state;
- write the AE2 block back into a position that was just broken. That is a resurrection or
  duplication shape: the item has already dropped.

Notes:
- Reachable only when `pendingOrientationChange != null`, which is set when legacy-format
  orientation NBT is loaded. So rare, but real for old worlds.
- This is a world write from main into a region-owned chunk. Core's general block-write ownership
  handling applies: whether such a write is routed or guarded is a Core property, not an AE2 one.
- Option α: glue skips the orientation write unless `level.getBlockState(pos) == cachedState`.
  That is still TOCTOU against the owning region.
- Option β: defer the orientation migration to the block entity's own server tick on its owning
  region, so it runs on the same thread as any removal.
- Recommendation: β. It is an addon-side change to AE2 timing (the migration runs on the first
  owner tick instead of first-tick ready), and needs the owner's OK.

### 2.4 Fixtures
1. A remover thread calls `setRemoved` while main is between the extraction and `callInit`
   (hook). Expected: init skipped, no node, `initCancelled = 1`.
2. A remover calls `setRemoved` while `onReady` holds `nw$life`. Expected: destroy after create,
   no ghost node (grid node count back to baseline).
3. Concurrent `scheduleInit` for the same chunk during extraction. Expected: the init is not lost;
   it runs on a later END.
4. Negative control: remove `nw$life` from the remover. Fixture 1 or 2 must show a ghost node.
5. Lock-order assertion: a synthetic `setRemoved` inside a guarded section must be reported as
   unaudited.

## 3. Decisions requested
1. §1.3: the four-state protocol, with vanilla self-suppression kept for same-thread re-entry
   (case f).
2. §2.2: per-block-entity lifecycle exclusion, with initialization on main.
3. §2.3: option β for the legacy orientation write, or accept α / leave it as recorded.
