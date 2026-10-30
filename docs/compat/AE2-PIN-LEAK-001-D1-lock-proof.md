# AE2-PIN-LEAK-001 D1 — lock-order proof (design gate, no code)

Status: **PROOF WRITTEN — owner review required before D1 code** (owner decisions 2026-10-30 (15): "per-host
lifecycle mutual exclusion APPROVED only after complete lock-order proof"; "general region→main synchronous waiting
NOT APPROVED"). Sources: Core 47.4.401 (branch tree at `71e487684`), AE2 15.4.10, addon 0.5.0.

## 1. The mechanism under proof

```
region thread R, inside LevelChunk.setBlockState(pos, newState)
  existing BE X at pos has the lifecycle protocol (NwAeBlockEntity)
  AND the write changes the block (newState.getBlock() != oldState.getBlock() → X is removed/replaced)
  AND the current thread is not X's BE execution domain
  AND X's domain is MAIN by compatibility policy (pinned)
  AND R is not inside a kernel section (KERNEL.inSection() == false)
  AND R holds no other nw$life (thread-local depth 0)
→ acquire X.nw$life
→ section write → onRemove (drops, breakCluster) → removeBlockEntity → setRemoved (node destroy under T0)
→ release X.nw$life
otherwise: unchanged (unlocked, as today); the two refusal cases are counted (lifeAdmissionSkippedInSection,
lifeAdmissionSkippedNested).
```

Main readiness of X already holds `X.nw$life` for `revalidate + onReady` (QUEUE-001.1, FirstTickInfoMixin); main
removal of X takes it around `setRemoved` (LevelChunkMixin). D1 only widens the region-side critical section to start
before the section write, closing the "AIR written before setRemoved" window (S1).

## 2. Locks and waits that exist on the paths (inventory)

| resource | where taken | bounded? | relevant holder while waiting for nw$life? |
|---|---|---|---|
| region R chunk write lock (`StampedLock`) | `RegionThread.runOneTick`, whole tick | held by R | **yes — R holds it while D1 waits** |
| region chunk lock (other takers) | `RegionSplitManager` / `applyBatchWithCascadeGuard` (`tryWriteLock` + timeout); `BoundaryManager` ghost snapshot (`tryReadLock` + timeout); `RegionChunkView.getBlockEntity` deep foreign read (`tryReadLock` + timeout) | all bounded | — |
| region chunk lock, **unbounded** `readLock()` | `RegionChunkView.getBlockState` foreign fallback; `CrossRegionCapabilityBus.resolveFromRegion` | unbounded | **no callers in Core 401** (grep: `getChunkView()` is used only by `Level.getBlockEntity` → `getBlockEntity`; `getCapabilityBus()` has no callers) |
| T0 + grid domain locks (addon kernel) | AE2 topology / services | blocking, ordered (T0 → domains ascending) | held by sections; kernel rule: `nw$life → T0 → domains` |
| `X.nw$life` (`ReentrantLock`) | FirstTickInfoMixin (readiness), LevelChunkMixin (removal), D1 | blocking | the subject |
| section `PalettedContainer` semaphore | inside `LevelChunkSection.setBlockState` (palette write only) and chunk save / BulkSectionAccess | blocking, short | never held while taking nw$life |
| `ChunkHolder.nestworldBlockChangeLock` | `blockChanged` / broadcast — after `setBlockState` returned | blocking, short | never held while taking nw$life |
| main-side world access to region chunks | `Level.getBlockEntity` on main → raw (`nestworldGetBlockEntityRaw`), `getBlockState` vanilla, `setBlock` on main direct (the foreign-write deferral applies to RegionThreads only) | **no lock, no wait** | — |

## 3. Lock-order graph (edges = "holds A, may wait for B")

```
R(region thread)   : L_R(write) → X.nw$life          (D1)
R in D1            : X.nw$life → T0 → domains        (setRemoved → node destroy)
R in D1            : X.nw$life → section semaphore   (palette write; also nested setBlock of cluster members)
R in D1            : X.nw$life → blockChangeLock     (nested setBlock broadcast)
main readiness     : X.nw$life → T0 → domains        (node create / connections)
main readiness     : X.nw$life → section semaphore, blockChangeLock (main setBlock of FORMED/POWERED states)
main removal       : X.nw$life → T0 → domains        (setRemoved)
```

Absent edges (required for acyclicity) and why they cannot exist:

| must not exist | reason |
|---|---|
| T0/domain → nw$life | kernel rule; FirstTickInfoMixin throws on init inside a section, LevelChunkMixin removes unlocked inside a section, D1 refuses inside a section |
| main holding nw$life → L_R | main never takes region chunk locks on the readiness/removal path: reads raw, writes direct (§2); the only unbounded L_R takers have no callers; bounded takers are not on these paths |
| main holding nw$life → wait for a region round / mailbox reply | readiness runs in AE2's level-tick-end handler, after main's BE/entity rounds; region rounds and the bounded player-interaction latches are entered only from the dimension tick / packet handling, never from inside onReady / setRemoved |
| section semaphore / blockChangeLock → nw$life | neither critical section calls back into AE2 lifecycle code |
| nw$life(X) → nw$life(Y) on one thread | D1 refuses nested acquisition (thread-local depth); readiness / removal do not take another host's lock |
| R in D1 → wait for MAIN work | inside the D1 section R runs only X's own removal: section write, AE2 `onRemove` (drops = entity add, `breakCluster` = cluster/MB state, member `setBlock` property changes), `setRemoved` (T0). Section write, cluster break, member `setBlock` and `setRemoved`: no main wait (code). Member `setBlock`s that leave R's interior are deferred to the owner mailbox (asynchronous). Drops (`addFreshEntity` on R): **STRONGLY INDICATED** no main wait — a routine region-thread path (item drops, spawns), not individually re-audited here; D1's contention/wait telemetry under load is the confirmation |

Every cycle would need an edge into `X.nw$life` from a resource held while `X.nw$life` is held by the other party.
The only resource held by R before `nw$life` is `L_R`, and no holder of `X.nw$life` waits for `L_R`. T0/domain holders
never wait for `nw$life`. Hence the graph is acyclic → **no deadlock** under the stated invariants.

## 4. Hold time / stall bound

- R waits at most one main critical section of X: `revalidate + onReady` of X (node create, in-world connections,
  first multiblock calculation) or main removal of X. Per host, once per readiness — not a scheduler wait.
- Main waits at most R's D1 section of X (state write, drops, cluster break, node destroy). Exists today for
  `setRemoved`; D1 extends it by the section write and `onRemove`.
- Required telemetry: `lifeAdmissionAttempts`, `lifeAdmissionContended`, `lifeAdmissionWaitNanos`,
  `lifeAdmissionMaxWaitNanos`, `lifeAdmissionCompleted`, `lifeAdmissionSkippedInSection`, `lifeAdmissionSkippedNested`.

## 5. Invariants this proof depends on (must stay true; to be listed in the D1 commit)

1. Main never takes a region chunk lock while it may hold an AE2 `nw$life` (today: no main-side lock on world
   access; the two unbounded `readLock()` paths stay without main-thread callers).
2. Main never waits for a region round or a region mailbox reply from inside AE2 readiness or removal.
3. Kernel order `nw$life → T0 → domains`; D1 refuses inside a section.
4. D1 never nests two hosts' `nw$life` on one thread.
5. A pinned host's block is written only by the chunk-owning region thread or by main (foreign region writes are
   deferred to the owner — `Level.setBlock` patch).

## 6. Residual (unchanged by D1)

`breakCluster` / cluster destroy on R inside the D1 section vs a MAIN multiblock calculation of a *different* cluster
— the global static `MBCalculator.modificationInProgress` (AE2-MB-MODIFICATION-001). D1 serializes per host, not per
static. To be re-measured after D1 + D2.
