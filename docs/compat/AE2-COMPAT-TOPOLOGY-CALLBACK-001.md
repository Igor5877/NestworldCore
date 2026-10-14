# AE2-COMPAT-TOPOLOGY-CALLBACK-001 — callbacks inside the add/remove topology path

**Status: AUDIT (2026-10-14). No code.** This is the blocker for the P(G) implementation (owner,
2026-10-14).

Scope: everything that runs inside `Grid.add` / `Grid.remove` (every service's `addNode` /
`removeNode`), the node listeners, grid events and addon hooks. For each callback:
- foreign code?
- can it wait for main?
- can it mutate topology?
- can it recurse into another grid?
- must it run before `addNode` returns?

AE2 15.4.10 (`fbfee05c…`) plus the island pack's grid services: megacells `DecompressionService`
and the ftbquest detector, which declares no phase hook.

## 1. Callbacks in add/remove (CONFIRMED, decompiled)
| Service · call | What runs | Foreign code? | Waits for main? | Mutates topology? | Other grid? | Must complete before return? |
|---|---|---|---|---|---|---|
| Storage `addNode` → `ProviderState.mount()` → `provider.mountInventories(mounts)` | Storage bus: `if (hasRegisteredCellToNetwork()) mounts.mount(this.handler, priority)` mounts the **pre-built** handler, with **no capability lookup** at mount time. Drive / chest / interface mount cell or internal inventories (AE2 plus addon cell handlers built from item NBT) | AE2 / AE2-addon code only. The owner's concern that `mountInventories` might call foreign capability code is **not borne out**: the external capability lookup happens in the storage bus's own update path (§3), not at mount | no | no | no | yes (it is the add itself) |
| `NetworkStorage.mount` / `unmount` | list add/remove; queued while `mountsInUse` | no | no | no | no | yes |
| Storage `addNode` → `watcher.updateWatcher(iw)` | the block entity stores the watcher (level emitter, …) | AE2 / addon | no | no | no | yes |
| Storage `removeNode` → `watcher.destroy()`, `providerState.unmount()` | bookkeeping; watchers are refreshed only in the Storage phase | no | no | no | no | yes |
| Crafting `addNode` → `craftingProviders.addProvider(node)` | `ICraftingProvider.getAvailablePatterns` / `getPatternPriority`: pattern providers, AE2 and addons (ExtendedAE, AdvancedAE) | AE2 / addon | no | no | no | yes |
| Crafting `addNode` → `watchingNode.updateWatcher`, `requester.getRequestedJobs()` → `addLink` | block entity stores the watcher; reads its links | AE2 / addon | no | no | no | yes |
| Crafting `removeNode` → `link.removeNode()` | `CraftingLinkNexus`: `request.setNexus(null)`; fields | no | no | no | no | yes |
| Pathing `addNode` / `removeNode` | sets, flags, `repath()` (a flag) | no | no | no | no | yes |
| P2P `addNode` / `removeNode` → `updateTunnel` → `onTunnelNetworkChange` | ME P2P: `TickHandler.addCallable(updateConnections)`, **already deferred by AE2**. Capability P2P (FE / item / fluid): `addCallable(sendBlockUpdate)`, **deferred**. Light P2P: `setLightLevel` → `host.markForUpdate()`, a synchronous vanilla block update and render-sync side effect | AE2 code; Light P2P touches vanilla world update machinery | no | no (ME / capability defer) | tunnels are in the **same** grid's P2P service | bookkeeping yes; tunnel updates are deferred |
| Statistics `addNode` / `removeNode` | per-chunk counters | no | no | no | no | yes |
| TickManager `addNode` | `tickable.getTickingRequest(node)`: device code, AE2 / addon | AE2 / addon | no | no | no | yes |
| Energy `addNode` / `removeNode` | audited set (001.1 §2, ALLOWLIST-001); M5c barrier | audited | no | no | no | yes |
| megacells Decompression `addNode` / `removeNode` | `List.add` / `remove` of the owner | no | no | no | no | yes |
| G2 node listeners (`onGridChanged`, `notifyStatusChange`) | block-entity code | yes | — | possibly | possibly | **deferred after the outermost exit** (implemented): not under P / T0 / domains |

**Result for the add/remove path.**
- Under `P → T0 → domains`, only AE2 code and AE2-addon bookkeeping run.
- No foreign-mod capability or world loading, no wait for main, no synchronous topology mutation
  (ME and capability P2P defer through `addCallable`), no synchronous recursion into another grid.
- The only vanilla world side effect is Light P2P's `markForUpdate`, a non-blocking block update.
- The affected-set-known-before-acquisition rule holds for add/remove.

## 2. Unknown future providers (fail-closed recommendation)
- The table holds for the services and providers present in the pack.
- A new `IGridServiceProvider`, or a new storage / crafting provider implementation from another
  addon, could change it.
- Recommendation: extend the startup gate (M5c style) with an allowlist of
  `IGridServiceProvider`, `IStorageProvider` and `ICraftingProvider` implementations, against the
  pack scan. Unknown → the P(G) layer is inactive, fail-closed.

## 3. NEW: structural service mutations outside topology (same defect class as GRID-PHASE-001)
Device code mutates grid-service structure **without** any `Grid.add` / `remove`, through AE2's
public API, typically on the block entity's **region thread**:

| Entry | Mutates | Callers (AE2 15.4.10) |
|---|---|---|
| `IStorageProvider.requestUpdate(node)` → `StorageService.refreshNodeStorageProvider` → `ProviderState.update()` = unmount + mount | `nodeProviders` state, `NetworkStorage` priority lists | `DriveBlockEntity`, `ChestBlockEntity`, `StorageBusPart` (after **its own foreign capability lookup** on a neighbour change), `FormationPlanePart`, `StorageLevelEmitterPart` |
| `refreshGlobalStorageProvider` | global providers | API |
| `ICraftingProvider.requestUpdate(node)` → `CraftingService.refreshNodeCraftingProvider` = remove + add provider | `craftingProviders` | `PatternProviderLogic` (and addon providers) |
| `StackWatcher.add` / `remove` / `reset` / `setWatchAll` → `InterestManager` | storage / crafting interest maps that the phases iterate | level emitters, crafting watchers |
| `P2PService.updateFreq` | P2P maps | `P2PTunnelPart` (memory card / frequency change) |

These run concurrently with that grid's main-thread Storage / Crafting phase. That is the same race
GRID-PHASE-001 describes, reached without topology.
- **P(G) must cover them as structural mutations.** The normative invariant already lists
  "provider/watchers collections".
- They are `void` "please refresh" requests that are idempotent per node.
  - **Candidate for proven defer-safe** handoff to main at the phase boundary, the QUEUE-001 staging
    pattern. The effect becomes visible at the next phase, which is already the case: the Storage
    phase is where AE2 reads it.
  - Otherwise they take `P(G)` like topology: non-main all-or-nothing with no wait.
  - Owner choice.

## 4. Consequence for P(G) design
- Add/remove callbacks are compatible with holding `P → T0 → domains`. The owner's deadlock scenario
  "foreign `mountInventories` waits for main" is not present in the audited pack, and §2's gate
  keeps it so.
- The P(G) structural set must include §3. Option per entry: P acquisition (non-main no-wait), or a
  defer-safe main handoff.
- With that, P(G) can proceed to implementation once the owner approves §3's choice and §2's gate
  extension.
