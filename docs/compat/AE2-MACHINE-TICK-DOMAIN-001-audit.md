# AE2-MACHINE-TICK-DOMAIN-001 — AE2 device ticks in the device's own region (audit + design proposal, no code)

Status: **AUDIT + DESIGN PROPOSAL — STOP for owner review** (2026-11-07). Owner direction: optimise the addon so that
nothing changes for players (no rebuilding); the per-grid variant (AE2-GRID-TICK-DOMAIN-001) is rejected by data.

## 1. Data (owner pack project-infinity, isolated copy `/root/pi-smoke`, AE2 unpinned, addon 0.7.5, 120 s)

Grid census (`/nwcompatae2 gridcensus report`): 87 grids, 18.4 ms/tick of grid phases; **one grid = 15.9 ms** (3147 nodes,
overworld 3113 + nether 34 joined by 27 QNB blocks) — multi-level, so a whole-grid move off main is impossible; the other
86 grids are 2.6 ms together. Evidence: `/root/perf1-evidence/infinity-2026-11-07/grid-census-copy.txt`.

Device census (`/nwcompatae2 gridcensus devices`, wall time of each `tickingRequest`), top rows:

| ms/tick | device class | calls in a REGION domain |
|---|---|---|
| 4.12 | ExtendedAE `PartExExportBus` | 80 % |
| 3.98 | ExtendedAE `PartExImportBus` | 86 % |
| 1.14 | AE2 `ExportBusPart` | 87 % |
| 0.62 | AE2 `StorageBusPart` | 90 % |
| 0.43 | AE2 `ImportBusPart` | 80 % |
| 0.29 | AdvancedAE `ImportExportBusPart` | 34 % |
| ≤ 0.18 | all others (interfaces, assemblers, cells, …) | — |

Device ticks ≈ 10.9 ms/tick; **import/export buses ≈ 9.7 ms**, ≈ 80–87 % of their calls belong to buses whose host is in a
region interior. Upper bound of main-thread relief from moving import/export bus ticks: **≈ 8 ms/tick**.
Evidence: `/root/perf1-evidence/infinity-2026-11-07/device-census-copy.txt`.

## 2. What an import/export bus tick touches (AE2 15.4.10 `IOBusPart.tickingRequest` → `doBusWork`)

| state | owner | notes |
|---|---|---|
| bus part fields (config, upgrades, round-robin slot, pulse) | the bus host BE (its region) | |
| the facing neighbour's capability (item/fluid handler) | the neighbour BE — same position as the AE2-NEIGHBOR-AFFINITY pin | export inserts, import extracts |
| grid network inventory (`StorageService` → `NetworkStorage` insert/extract, cached inventory for fuzzy) | the grid (global, shared by every device and by main's grid phases) | not thread-safe |
| grid energy (`StorageHelper.poweredExtraction`/insert, energy service) | the grid | already a kernel domain (ENERGY-001) |
| crafting (`attemptCrafting` with a crafting card → `CraftingService` job request) | the grid | |
| tick scheduling continuation (`setLastTick`, `setCurrentRate`, sleep, requeue) | `TickManagerService` queue of the grid (main) | the 18:57 crash was this queue mutated off main |

Storage buses are **excluded**: main's `StorageService.onServerEndTick` reads every storage bus's external inventory, so
the neighbour must stay on main (it is affinity-pinned today).

## 3. Proposed mechanism ("T3", builds on the existing T2 suspension)

T2 already runs a device tick outside the grid section and applies the continuation (`setLastTick` / rate / sleep /
requeue) only after revalidating the tracker identity (`awake.get(node) == tt`, generation). T3 changes *where* the
device tick body runs, not the continuation:

1. In `tickQueue`, for a due tracker whose device is an import/export bus whose host BE domain is a REGION: do not call
   `unsafeTickingRequest` inline. Capture `(tt, generation, diff)`, mark the tracker in-flight, enqueue the call to the
   host's BE execution domain (`BlockEntityExecutionDomain.enqueueForBlockEntityDomain`, non-cancellable).
2. On the region thread: revalidate (host not removed, node still in this grid, generation unchanged); run the tick
   under the grid's kernel domain (`Specs.grid`) — grid inventory / energy / crafting are then serialised against main's
   grid phases and other threads; then publish `(tt, generation, modulation)` to the grid's completion queue.
3. Main, at the start of the grid's next level-end phase: drain completions; apply the T2 continuation exactly as today
   (same gates), or drop it as stale (counted). While in flight the tracker is not re-ticked.
4. The bus's neighbour must not be affinity-pinned to MAIN for such buses (bus and neighbour then share the region
   thread); affinity remains for every device that still ticks on main (border band, pinned, storage buses, interfaces).

Player-visible change: a region-domain bus's work and its rate decision land ≤ 1 tick later. No world/config change.

## 4. Proof obligations before any code (this is the risky part)

1. **Lock order.** Region thread: own chunk write lock (its tick) → grid kernel domain. Main: grid kernel domain (T1
   phase) → may main then need a region's chunk lock? Every main-side access to a REGION-owned chunk while holding the
   grid domain must be either impossible or bounded (`tryReadLock` + timeout / fallback). Required: a written inventory of
   main-under-T1 call paths that touch world state (devices still on main, storage-bus external reads, crafting pushes,
   pattern providers), the same way D1's lock proof was written.
2. **Exactly-once tick.** A tracker in flight is never ticked twice; completion after topology change / grid merge /
   split / node removal is stale → dropped and counted; nothing waits.
3. **Grid identity.** A grid can merge/split while a tick is in flight (QNB, cable placement): the region side
   revalidates node → grid, the main side revalidates tracker generation; GRID-PHASE P(G) semantics preserved.
4. **No main wait.** Main never waits for a region device tick (completion is asynchronous); region waits only for the
   grid domain (bounded by a main phase). Hold-time telemetry like D1.
5. **Interaction with T0 / M1 / staging.** Device tick on the region is an outermost kernel entry with the existing
   staging rules; nothing external under T0.
6. **Affinity switch-over.** When a bus moves between MAIN and REGION domain (split/merge/band), its neighbour pin must
   follow before the next tick of either side.

## 5. Verification plan

- Deterministic fixtures with negative controls: (a) region bus tick + concurrent main grid phase on the same grid
  (OVERLAP style, T3 off → race reproduced / T3 on → serialised); (b) node removed / grid merged while in flight → stale
  completion dropped, no double tick, no lost items (item accounting across the bus); (c) split moving the bus to MAIN
  mid-flight.
- Copy-of-pack soak: device census before/after (main ms/tick), 0 tick failures, `unauditedPaths` 0, item conservation
  check on a fixture network.
- Only then a live canary.

## 6. Decisions requested

1. Scope: import/export buses only (≈ 9.7 ms of 10.9), storage buses stay on main — agree?
2. One-tick latency of bus work/rate in a region domain — acceptable?
3. Proceed to the lock-order proof (§4.1) as the next step, before implementation?

## 7. Owner decisions 2026-11-07

```
AE2-MACHINE-TICK-DOMAIN-001
Phase 0  inventory / profiling           DONE
Phase 1  lock-order + reentrancy proof   REQUIRED (stricter than D1) — before any code
Phase 2  import/export buses only        APPROVED CONDITIONALLY (on Phase 1)
         exact allowlist by class + mod version (ExtendedAE included), not "all IGridTickable"
Storage bus                              OUT OF SCOPE
Other AE2 machines                       OUT OF SCOPE
Max scheduling delay                     1 tick ACCEPTED
Concurrent same-machine ticks            FORBIDDEN (at most one in-flight per tracker; a late region task never
                                         spawns a second tick)
```
Invariants: one scheduled tick → at most one execution; FASTER/SLOWER/SLEEP applied only to the same tracker
generation; host removed / domain changed → stale, not applied; queue backlog bounded.

Phase 1 must cover the full graph — region chunk/write lock, AE2 grid / T0 / domain locks, storage, energy, crafting,
adjacent capability / inventory, HostDomainCallbackDelivery, main end-tick paths. Forbidden cycle: REGION chunk lock →
waits grid lock while MAIN grid lock → waits / enters region-owned state. Any such path without bounded fail-fast →
design rejected as is. Red flag even without a formal deadlock: an external mod callback under a wide AE2 grid lock
(re-entry into AE2). Paths to check explicitly: bus tick → network storage → capability → external inventory →
callback → world access; bus tick → energy extraction → grid service callback; main grid phase → listener →
host / world lookup → region-owned state.

Evidence after implementation: main / region machine time p50/p95/p99; offloaded / inline ticks; stale results;
in-flight collisions; queue delay p50/p95/p99/max; grid-lock wait p50/p95/p99/max; region-lock wait; deadlock watchdog
hits; duplicate / lost item movement = 0 — item conservation over N minutes under split/merge, unload, bus removal
mid-flight and domain change: source_before + created = source_after + destination_after + in_transit.
