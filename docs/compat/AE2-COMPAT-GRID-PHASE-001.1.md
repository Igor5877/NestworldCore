# AE2-COMPAT-GRID-PHASE-001.1 — narrow audit before the option-2 phase lease

**Status: AUDIT (2026-10-14). No code. The result changes the premise of option 2 as specified.**
Owner decision on 001: OPTION 2, a grid liveness / phase lease (LIVE → RETIRING → DEAD, with
generation and ABA guards).
- The owner's precondition: audit "what can happen after last-node removal until the end of an
  already active `Grid.on*Tick`".

AE2 15.4.10 (`fbfee05c…`) plus the island pack.

## 1. Who participates in grid phases (CONFIRMED)
- **AE2 services with phase hooks:** `StorageService`, `CraftingService`, `EnergyService`,
  `PathingService`, `TickManagerService`.
  - The other registered services (P2P, Spatial, Statistics) have none.
- **Addons:** `megacells DecompressionService.onServerStartTick` is the only one.
  - `ae2_ftbquest_detector`'s `DetectorBlockEntity` implements `IGridServiceProvider`, but it
    declares no phase hook.

## 2. Side effects of each phase (classification requested by the owner)
| Phase code | What it does | Class |
|---|---|---|
| `PathingService.onServerEndTick` | `updateControllerState`; on reboot it runs `PathingCalculation(grid).compute()` and `getPivot().beginVisit(...)`. Both traverse the node / connection graph. Then `achievementPost` (`getPivot().getLevel()`, the observed NPE), `postEvent(GridBootingStatusChange)` (grid event bus: device handlers), and `ChannelFinalizer`, which runs `finalizeChannels` → `notifyStatusChange(CHANNEL)` → node listeners → **block-entity code** | traversal + **EXTERNAL MUTATION** (node listeners) |
| `StorageService.onServerEndTick` | `updateCachedStacks`: `storage.getAvailableStacks` (mounted cells and **external inventories via storage buses**), then `postWatcherUpdate` → watcher nodes (level emitters and others: redstone or neighbour updates) | **EXTERNAL READ + EXTERNAL MUTATION** |
| `CraftingService.onServerEndTick` | `updateCPUClusters`, `craftingLinks.removeIf`, `tickCraftingLogic` per CPU: **pushes patterns into machines, moves items, extracts energy**; then interest and watcher updates | **EXTERNAL MUTATION** (item movement into foreign inventories) |
| `megacells DecompressionService.onServerStartTick` | rebuilds `patterns` from `cellHosts` (`IChestOrDrive.getOriginalCellInventory`) | read of cell hosts (other block entities) |
| Energy / TickManager | already under the domain (T1); T2 device code is suspended | covered |

## 3. Finding: "logical node removal" itself races the phase (CONFIRMED from code)
Option 2 as specified removes the node logically on time and defers only the teardown (pivot := null,
`removeNetwork`). But logical removal is `Grid.remove → every service.removeNode(node)`, and that
mutates **the same collections the same service's phase is iterating on main**:

| Service | `removeNode` mutates | Phase iterates |
|---|---|---|
| Crafting | `craftingWatchers`, `craftingLinks` (`link.removeNode`), `craftingProviders`, CPU clusters (crafting block entities) | `craftingLinks.values().removeIf`, `craftingCPUClusters` loop, providers inside `tickCraftingLogic` |
| Storage | `watchers.remove`, `nodeProviders.remove` → `providerState.unmount()` (changes the network storage) | `storage.getAvailableStacks`, watcher map |
| Pathing | controllers set, node state; the connection graph via the topology operation | `PathingCalculation` / `beginVisit` traversal |
| megacells | `cellHosts` (when the host node leaves) | `cellHosts` iterator |

Consequences:
- A RegionThread removing **any** node (not only the last one) while main runs that grid's phase is a
  data race on plain collections: CME, a torn iterator, or crafting pushing items to a provider that
  is being unmounted.
- The observed pivot NPE is the most visible symptom, not the whole race.
- A lease that defers only the teardown closes the NPE. It does **not** make the remainder of the
  phase safe, for the last node or any other.

## 4. What a sufficient lease has to cover (proposal for the owner)
The minimal invariant that closes this race class is not liveness alone:

> A grid's topology mutations (node add/remove into its services, connection changes) do not run
> concurrently with that grid's main-thread phase.

**2-bis, a per-grid phase/topology lease above T0.** Lock order: `P(grid) → T0 → domains`.
- **Main phase.** Acquire `P(G)` before `Grid.on*Tick(G)`. If the grid is already RETIRING or DEAD,
  skip. Release after.
  - The phase may take T0 and domains inside (energy calls from crafting): the order holds.
- **Topology entry (any thread).** Resolve the touched grids, then acquire their P locks in ascending
  order, before T0. Then do the existing T0 + domains + validate.
  - If the touched set changed under T0, release everything and retry. This is the same
    resolve/validate pattern as the domains.
- **Re-entry.** Main's own foreign code inside a phase that performs topology on the **same** grid
  is reentrant (same thread). That is vanilla-equivalent: AE2 already guards it with its own
  "cannot modify while …" checks.
- **Topology on another grid H from inside a phase (main).**
  - If `serial(H)` is above every P held: block, which is ordered.
  - Otherwise tryLock. On failure, defer that topology operation to the phase exit, not refuse it,
    because a topology operation has no refusal value. That needs a contract: which callers
    tolerate deferral? **OPEN.**
- **Liveness.** With `P(G)` held by the phase, the last-node removal cannot interleave, so the
  RETIRING state is only needed for the re-entrant same-thread case.
  - The generation / ABA rules from the owner's design still apply to that case and to `Grid.add`
    on a retiring grid.
- **Cost and risk.**
  - Main holds `P(G)` across foreign code: Crafting and Storage callbacks.
  - What that foreign code can do under `P(G)`: take T0 and domains (ordered); take other grids' P
    (ordered, or deferred). Region threads wait for `P(G)` *before* T0, so they hold nothing main
    needs while they wait.
  - This keeps option 1's coverage without putting unaudited code under the **domain** lock. The
    domain proof (M1/M2/M5c) is untouched, because P sits strictly above it.

## 5. Decision requested
- **(a) 2-bis.** Per-grid P lease above T0, covering topology versus phase. Closes the race class.
  The open contract is deferral of other-grid topology issued from inside a phase.
- **(b) Option 2 as written, liveness only.** Closes the observed NPE. Records the remaining
  service-collection race as **AE2-COMPAT-GRID-PHASE-002**, part of "storage / pathing / crafting
  outside the gate". It blocks full unpin; energy-only selective unpin is unaffected.

Either way, the owner's 5 deterministic scenarios plus stress apply. Under (a), add: "non-last node
removed during the Crafting/Storage phase → no CME, no item push to an unmounting provider".
