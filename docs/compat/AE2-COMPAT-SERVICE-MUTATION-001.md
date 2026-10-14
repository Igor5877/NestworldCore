# AE2-COMPAT-SERVICE-MUTATION-001 — StackWatcher and P2P frequency mutation semantics

**Status: AUDIT (2026-10-14). No code.**

Owner rule:
- state consumed only by the next phase → staging;
- a synchronous postcondition → mutation under P(G);
- unknown → fail-closed.

Already approved: `IStorageProvider.requestUpdate` / `ICraftingProvider.requestUpdate` → main
staging, coalesced per (grid, provider, kind). Publication before the drain linearization point of
the corresponding phase means it is applied in that phase; after it, by the next one.

AE2 15.4.10 (`fbfee05c…`).

## 1. StackWatcher add / remove / reset / setWatchAll (CONFIRMED, decompiled)
**Structure.** `StackWatcher` keeps a watcher-local set `myInterests` and mirrors it into the
service's shared `InterestManager` (a multimap plus a watch-all set). `destroy()` = `reset()` +
`destroyed = true`, and every operation is a no-op once destroyed.

**Callers.** Exactly two hosts:
- `StorageLevelEmitterPart.configureWatchers`: reset both watchers; then `add(key)` or
  `setWatchAll(true)`; then `updateReportingValue` and `updateState`.
- `AbstractMonitorPart` (storage monitors): reset; then `add(configuredItem)`.
- `configureWatchers` is reached from `updateWatcher`, which runs inside `addNode` (the topology
  path, under P(G)), and from config or upgrade changes on the part's thread.

| Question | Answer | Evidence |
|---|---|---|
| Does the caller rely on membership after return? | **No.** The only post-call read is `updateReportingValue` → `grid.getStorageService().getCachedInventory()`, the cached stack list, not watcher membership | `StorageLevelEmitterPart:191` |
| Who consumes membership? | Only the phases: Storage `updateCachedStacks` → `postWatcherUpdate` (`interestManager.get(key)` / `getAllStacksWatchers`), and the Crafting phase's interest updates | `StorageService:66/76/87`, `CraftingService:115` |
| Can add→remove before the drain be coalesced? | Yes, to the final membership. **FIFO replay per watcher** gives the same final state without needing coalescing logic | — |
| Does order matter? | **Yes, within one watcher**: `reset(); add(k)` must not become `add(k); reset()`. Across watchers, no | `configureWatchers` |
| Interaction with `destroy()` | `destroy()` runs in `removeNode`, the topology path under P(G). A staged operation replayed after it sees `destroyed` and is a no-op. Under P(G), main's replay (inside its phase) and the topology destroy cannot overlap, so P gives happens-before | `StackWatcher` |

**Classification: PHASE-DEFERRED → staging.**
- Contract: a per-grid FIFO of watcher operations.
  - Executed **directly** when the calling thread already holds `P(G)`: the topology path inside
    `addNode`, or main inside a phase.
  - **Staged** otherwise.
- Drained on main at the head of the grid's phase window, under `P(G)`, before the Storage and
  Crafting phases read the interests.
- The watcher-local `myInterests` is mutated at replay as well, so the whole operation executes on
  main, under P.

## 2. P2PService.updateFreq (CONFIRMED, decompiled)
`updateFreq(t, freq)` removes `t` from the `inputs` / `outputs` maps, calls `t.setFrequency(freq)` on
the part, re-inserts, and runs `updateTunnel(old/new, …, configChange=true)`. That calls
`onTunnelConfigChange` / `onTunnelNetworkChange` on every tunnel of both frequencies: ME and
capability P2P defer through `addCallable`; Light P2P calls `markForUpdate`.

| Question | Answer | Evidence |
|---|---|---|
| Does the caller read the result after return? | **Yes.** The memory-card path calls `updateFreq`, then `onTunnelConfigChange()`, then `exportSettings(MEMORY_CARD, data)`, which writes the tunnel's **frequency** (set synchronously by `updateFreq`) into the card | `P2PTunnelPart:196–203` |
| Does uniqueness depend on synchronous map state? | **Yes.** `newFrequency()` picks an unused frequency by consulting the service maps; deferred updates could hand out duplicates | `P2PTunnelPart:193` |
| Can old→new→old be coalesced? | Not safely: callers and the card already observed the intermediate frequency | — |
| Synchronous side effects? | `updateTunnel` on old and new frequencies: config and network change callbacks. Mostly deferred by AE2; Light P2P is synchronous `markForUpdate` | `P2PService:82–102` |

**Classification: SYNCHRONOUS POSTCONDITION → not stageable.**

**However, P(G) does not address P2P's real race:**
- `P2PService` has **no phase hook**: no grid phase iterates `inputs` / `outputs`.
- The concurrent readers are the **transfer paths**: `getInput()` / `getOutputStream()` →
  `P2PService.get(grid).getInput / outputs`, called by FE / item / fluid tunnels on whichever region
  thread is moving energy or items through them, at any time.
- So the hazard is `updateFreq` (memory card, part thread) or `add/removeNode` versus transfer
  readers on other region threads.
  - That is the P2P cross-thread map race, already in the "P2P outside this gate" exclusion.
  - It is not a topology-versus-phase race.
- Taking `P(G)` for `updateFreq` would satisfy the owner's rule formally, but would not protect the
  transfer readers.
- **Recommendation:**
  - leave `updateFreq` outside the P(G) set;
  - record **AE2-COMPAT-P2P-001** (P2P service maps: synchronous-postcondition writers versus
    region-thread transfer readers) as its own finding;
  - it blocks full unpin, and it blocks any energy unpin that admits FE P2P (FE P2P forwards
    energy).
  - An owner decision is needed if the rule is to be applied literally anyway.

## 3. Resulting GRID-PHASE boundary
| Operation | Mechanism |
|---|---|
| synchronous topology (add/remove node, connections, merge/split) | **P(G)**: non-main all-or-nothing with no wait; main reentrant and blocking; progress invariant |
| `IStorageProvider.requestUpdate` / `ICraftingProvider.requestUpdate` | **main staging**, coalesced per (grid, provider, kind) — approved |
| `StackWatcher` operations | **main staging**, FIFO per watcher; direct when the caller holds P(G) — this audit |
| `P2PService.updateFreq` | **not P(G), not staging**: synchronous postcondition; real race is with transfer readers → AE2-COMPAT-P2P-001 (outside GRID-PHASE) |
| unknown provider / service / hook | startup compatibility manifest → eligibility UNKNOWN → DEGRADED / no unpin — approved |
