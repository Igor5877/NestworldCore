# AE2 grid domain — roadmap (owner, 2026-11-12)

Companion to `AE2-GRID-DOMAIN-001-TZ.md`. Status column is updated as work lands; nothing here is implemented unless
the status says so. Invariants of the TZ (§3″ ownership, §3″.1 participant classes, §3″.2 typed transit, escrow,
GAMEPLAY_EQUIVALENCE, dup 0 / loss 0) apply to every item.

## Priorities

| prio | items |
|---|---|
| **P0** | 1 Model A ownership to production · 2 typed transit · 3 storage-bus versioned snapshots |
| **P1** | 6 per-grid scheduler · 27/28 conflict-set / connected-component parallelism · 9 incremental storage index |
| **P2** | 15 crafting parallel planning · 10 bus batching · 19 ExtendedAE / AdvancedAE adapters |
| **P3** | 20/21 adaptive promotion/demotion · 29 cost-aware placement · 12 cache/NUMA affinity |

## Items

| # | item | essence | status |
|---|---|---|---|
| 1 | Grid-domain ownership → production | grid phase + own AE2 hosts in a domain, MAIN/region skip them, epoch/barrier, fallback B | IN PROGRESS (rig: B correctness PASS; A ownership core + class rules implemented) |
| 2 | Typed transit | `InventoryOp`, `NeighborNotifyOp`, `ScheduleTickOp`, `BlockMutationOp`, maybe `CapabilityOp` — each with own semantics, never generic `Runnable` | IN PROGRESS: InventoryOp for vanilla AE2 import/export (BusTransit on the worker); world side-effect ops NOT YET (only detected by Phase-9 assertions) |
| 3 | Versioned inventory snapshots for storage bus | owner publishes `InventorySnapshot(version, …)`; AE2 computes availability off-thread; mutation `InventoryTxn(expectedVersion)`; stale ⇒ retry/rescan | NOT STARTED |
| 4 | Intra-grid parallelism | split phases: inventory scans, crafting calc, channel/pathing reads, bus planning, provider preparation, independent subnetworks; commit/topology serialized | NOT STARTED (after A, profile first) |
| 5 | READ SNAPSHOT → PARALLEL COMPUTE → SERIAL COMMIT | crafting planner, storage availability, import/export planning, pattern matching, interface restocking | NOT STARTED |
| 6 | Per-grid scheduler | small grids grouped, heavy grids own worker, predicted cost, sticky assignment, work stealing only for stateless/read phases | NOT STARTED (one "ae2" domain today) |
| 7 | Hot-grid isolation | max slice, phase budget, starvation protection, heavy-grid classification | PARTIAL (BusTransit GRID_ISOLATION scheduler; domain level not yet) |
| 8 | Incremental topology/pathing | dirty component, affected nodes, generation, localized recompute | NOT STARTED |
| 9 | Incremental storage index | `item → count / sources / version`, owner reports deltas | NOT STARTED |
| 10 | Bus batching | `InventoryBatchOp[target, ops[]]`: one handoff, one generation check, one result batch | NOT STARTED |
| 11 | Operation coalescing | merge same-tick requests of one bus where semantics allow (ExtendedAE) | NOT STARTED |
| 12 | AE2-domain ↔ RegionThread affinity | batch transit to the dominant region, cache/NUMA locality | NOT STARTED (single NUMA today: moderate) |
| 13 | Local-owner fast path | `sameOwnerEpoch && sameDomain → direct typed op`, explicit | PARTIAL (claimed class-1′ neighbour ⇒ inline in BusTransit) |
| 14 | Capability snapshot/cache | handler presence, side layout, handler generation — never raw mutable capability objects across threads | NOT STARTED |
| 15 | Crafting CPU parallel planning | dependency graph, recipe expansion, availability, subtree planning parallel; allocation/reservation serial | NOT STARTED |
| 16 | Pattern provider / interface precomputation | plan from snapshot, owner applies a short commit | NOT STARTED |
| 17 | P2P data-plane separation | control/topology serial, transfer planning on a worker path (H1/H2/H3 base) | NOT STARTED |
| 18 | Explicit support matrix | FULL_DOMAIN_SAFE / TRANSIT_SAFE / SNAPSHOT_SAFE / MAIN_ONLY / UNKNOWN per addon/integration; GridOwnership decides from it | PARTIAL (allowlists in GridOwnership; to become a registry view, WP1) |
| 19 | ExtendedAE / AdvancedAE adapters | one adapter/gate per feature family, not treated as AE2 | NOT STARTED |
| 20 | Adaptive mode selection | `gridCpuCost − transitCost − barrierCost > threshold ⇒ A` | NOT STARTED |
| 21 | Dynamic promotion/demotion | B ↔ A only at barrier/epoch | PARTIAL (barrier flips + demotion requests implemented) |
| 22 | Per-grid telemetry | grid CPU, transit count/cost, snapshot hit/stale, retries, worker/MAIN wait, claimed hosts, unsupported reasons, scan cost, planner cost | PARTIAL (gridcensus, worker status, ownership counters) |
| 23 | Crash-safe transit journal | opId, PREPARED/COMMITTED/ROLLED_BACK, source/destination generation, accounting | PARTIAL (WP3-B records persisted + recovery owner; escrow formalisation pending) |
| 24 | Bounded backpressure | queue limits, per-target pressure, throttle, fallback/degrade | PARTIAL (BusTransit budget/backlog; per-target limits not yet) |
| 25 | Core read-mostly world snapshot API | block state, loaded chunks, BE identity/type, ownership, selected metadata | NOT STARTED (ownership snapshot exists) |
| 26 | Domain-aware world side effects | scheduleTick, neighbour updates, block events, comparator updates, setBlock/removeBlock routed to owner | NOT STARTED (finding: LevelTicks.schedule from domain, 2026-11-12) |
| 27 | No global barrier between grids | conflict-set scheduling: disjoint grids parallel, shared host/component serialized | NOT STARTED (today: all AE2 phases serialized — CME fix) |
| 28 | Connected-component execution domains | grids linked via shared hosts/P2P/MM grouped temporarily | NOT STARTED |
| 29 | Cost-aware placement | size, churn, transit fan-out, foreign inventories, crafting load | NOT STARTED |
| 30 | Target | MAIN < 30–35 ms; heavy grid < 35–40 ms; overlap > 70–80 %; wall p95 < 50 ms; stable 20 TPS; no dup/loss/race; no global MAIN barrier | — |
