# AE2-COMPAT-GRID-PHASE-001.2 — topology reentrancy and synchronicity audit (in-phase topology)

**Status: AUDIT (2026-10-14). No code.**
- Owner decision on 001.1: direction **(a)**, a per-grid phase/topology exclusion `P(G)`.
- Normative invariant: for one grid, an active `Grid.on*Tick` phase **XOR** a structural topology
  mutation of that grid.
- Order: `P (grid-serial order) → T0 → domains`. Acquiring `P` after T0 or a domain is a lock-order
  violation.
- The phase boundary is the outer `Grid.on*Tick`, so addon services such as megacells are covered
  automatically.
- Implementation stays BLOCKED until this audit is reviewed.
- Option (b), the pivot liveness lease, survives only as defense in depth. It is one defect class,
  not a separate GRID-PHASE-002.
- **Selective energy unpin is also BLOCKED on GRID-PHASE-001**: a region-thread block-entity removal
  reaches `Grid.remove` without running any Pathing or Crafting tick.

AE2 15.4.10 (`fbfee05c…`). "In-phase" means reachable on main while a `Grid.on*Tick` of some grid
A is on the stack.

## 1. Topology mutations that can arise inside a phase
| # | Operation | Source (in-phase path) | Same / other grid | Must complete before return? | Safe to defer? |
|---|---|---|---|---|---|
| 1 | `GridHelper.createConnection` (merge) | `ToggleBusPart.updateInternalState` ← `onNeighborChanged` ← redstone change from a **level emitter** (Storage phase `postWatcherUpdate`) or an **energy level emitter** (E8 `EnergyWatcher.post`, delivered after the domain release but still inside `Grid.onServerEndTick`) | either; the toggle bus joins its two nodes' grids | **yes**: the returned `IGridConnection` is stored in `this.connection` | **no** |
| 2 | `IGridConnection.destroy` (split) | same toggle-bus path (redstone off) | either | **yes**: the caller nulls `this.connection`, believing it destroyed; a later `create` on a deferred-but-alive connection throws "Connection … already exists" | **no** |
| 3 | `GridHelper.createConnection` / `destroy` (quantum link) | `QuantumCluster.updateStatus` ← `QuantumBridgeBlockEntity.onChangeInventory` ← a singularity inserted by an **export bus or interface in a T2 device tick**, or by a **Crafting push** | other grid (the two QNB ends) | **yes** (the connection is stored) | **no** |
| 4 | `GridNode.destroy` via `setRemoved` → `mainNode.destroy()` | **annihilation plane** `ItemPickupStrategy` → `level.removeBlock` of an AE2 block, in a T2 device tick | either | effectively yes: `ManagedGridNode` drops its reference; the block entity is gone | not proven; treat as **no** |
| 5 | P2P ME tunnel connections | `MEP2PTunnelPart.tickingRequest` → `TickHandler.addCallable(updateConnections)` | other | no | **already deferred by AE2** (vanilla async) |
| 6 | Spatial IO transition | `SpatialIOPort` → `addCallable(transitionCallback)` | other | no | **already deferred by AE2** |
| 7 | new AE2 block placed by the **formation plane** (`ItemPlacementStrategy.useOn`) | T2 device tick | — | — | the node is created at first-tick ready (`readyBlockEntities`, level END, **before** that level's grid loop), so not in-phase |
| 8 | arbitrary foreign topology: a foreign machine receiving a Crafting push, or an external inventory behind a storage bus, that places or breaks AE2 blocks synchronously | Crafting or Storage phase | any | unknown | **unknown → treat as no** |

**Result.**
- In-phase topology is **synchronous**, and in AE2's own code (rows 1–4) it is **not defer-safe**:
  the callers keep the returned connection, or assume the destroy happened.
- The two deferred cases (5, 6) are AE2's own asynchronous paths and need nothing.
- So the protocol must let a phase on A perform **synchronous** topology on other grids, without
  deadlock and without deferral.

## 2. Can every operation resolve its affected grids before the first structural change?
Yes, with the same resolve / validate / retry pattern as the domains (001 §3):

| Operation | Affected set |
|---|---|
| `createConnection(a, b)` | `grid(a) ∪ grid(b)`, plus grids created inside (fresh) |
| `connection.destroy()` | `grid(sideA) ∪ grid(sideB)`, plus fresh split grids |
| `GridNode.destroy()` | `grid(this) ∪ grids of connected nodes`, plus fresh |

- Fresh grids are unpublished when created inside the operation, so their P is uncontended.
- Merge propagation touches only the losing grid's nodes, which is already in the set.

## 3. Callbacks under T0 / domains at the moment of mutation
| Callback | When | Under T0 / domain? |
|---|---|---|
| G2 node listeners (`onGridChanged`, `notifyStatusChange`) | `setGrid` | **no**: deferred to the outermost exit (implemented) |
| E8 watcher, power-status and notify-all callbacks | Energy phase | no: deferred after the domain release |
| `StorageService.removeNode → providerState.unmount()` | `Grid.remove` | yes, but it only unmounts. Watchers are refreshed in the next Storage phase (`updateCachedStacks`), **not synchronously** (CONFIRMED) |
| `StorageService.addNode → mount() → provider.mountInventories(...)` | `Grid.add` | **yes**: a storage bus resolves external inventories, i.e. **foreign capability code under T0 + domain**. This already exists in the current glue. It is not a new path, but it is a **residual** for the M5c proof, which covers energy participants only. Recorded; out of this audit's scope |
| Other services' `add/removeNode` | `Grid.add/remove` | yes: AE2 bookkeeping only (Crafting watchers, links, providers, CPU clusters; Pathing controllers) |

## 4. Protocol shape that satisfies §1 to §3 (proposal)
`GridPhaseGate`, per grid: serial, generation, and a lock `P(G)`. Optionally a deferred queue, but
by §1 nothing in AE2 is defer-safe, so v1 has **no deferral**.

**Asymmetric acquisition. This is the key to synchronous in-phase topology without deadlock.**
- **Phase owner (main).** `phaseEnter(G)` blocks on `P(G)`.
  - Skip if the grid is RETIRING or DEAD (liveness generation, defense in depth).
  - `phaseExit(G)` releases.
  - Main's in-phase topology on another grid X **blocks** on `P(X)`, in any serial order.
- **Topology from any non-main thread.** Acquire the whole affected P set **all-or-nothing with
  `tryLock`**. On any failure, release every P already taken, back off and retry.
  - A non-main thread therefore **never waits for a P while holding a P**.
  - Then T0, then the domains, then validate the grid set; if it changed, release everything and
    retry.
- **Main topology outside a phase** (commands, BE ready, unload): blocks on its P set in ascending
  order. Main holds nothing else.

**Why no deadlock:**
- A thread holding P can wait on another P only if it is main, which is the only blocking P-acquirer
  besides ordered acquisitions outside a phase.
- Non-main threads hold P sets only while running toward T0 / domains, which the order permits.
  They never wait on P.
- Main inside a phase holds only P locks, never T0 or a domain across a topology call:
  - rows 1–2 run after the E8 domain release, or in the Storage phase, which holds no domain;
  - rows 3–4 run in T2 device code at depth 0.
- So main's waits on P end when the non-main holder finishes or backs off. That holder waits only on
  T0 / domains, held by threads that do not wait on main.
- Re-entry: the same thread on the same grid (main inside A's phase doing topology that touches A)
  is reentrant. AE2's own guards ("Cannot modify … while …") keep the vanilla semantics.

**Starvation.**
- Non-main topology can lose to main's phases repeatedly. Phases are short and occur once per tick
  per grid.
- Bounded by backoff plus a counter, `gridPhaseTopologyRetries`.
- If needed, v2 adds a "topology pending" flag that makes `phaseEnter` yield one tick. Not now.

**Liveness and ABA (owner's design, kept as defense in depth).**
- `Grid.add` on a RETIRING grid revives it with `generation++`, under P.
- The finalizer checks state, generation, node count and active phase count.
- Under P, last-node removal cannot interleave a phase, so RETIRING arises only in the same-thread
  re-entrant case.

## 5. Tests to add (in addition to the owner's 5 scenarios plus stress)
- **Toggle-bus re-entry.** A level emitter in grid A, through redstone, toggles a bus joining B and
  C with `serial(B) < serial(A)` during A's Storage phase. Expected: a synchronous connection,
  no deadlock, no exception.
- **Region topology on B while main holds `P(A)` and wants `P(B)`.** Expected: the region backs off,
  main proceeds, the region retries.
- **Non-last node removal during the Crafting / Storage phase.** Expected: the region waits; no CME;
  no push to an unmounting provider.
- **Negative controls:**
  - with no P, the CME / NPE reproduces;
  - with blocking non-main P acquisition, a deadlock is detected (watchdog fixture).

## 6. Decision requested
- Approve the asymmetric P protocol (§4), with no deferral in v1.
- Approve the §3 residual: storage-bus `mountInventories` under T0 + domain gets a separate record.
