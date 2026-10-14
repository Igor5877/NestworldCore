# AE2-COMPAT-GRID-PHASE-001 — implementation and fixtures (results)

**Status:** IMPLEMENTED in `nestworld-compat-ae2` per the owner's approval:
- `P(G)`;
- Storage / Crafting `requestUpdate` staging;
- StackWatcher FIFO staging;
- the startup manifest.

Deterministic fixtures PASS, and the negative control fails as required. **Not CLOSED:** owner review
is pending, and AE2-COMPAT-P2P-001 / SOLARFLUX-COMPAT-001 / LEGACY-ORIENTATION-LIVE-001 stay open.

## What was implemented
| Part | Mechanism |
|---|---|
| `P(G)` (`glue/GridPhaseGate`, `mixin/GridMixin`) | A fair `ReentrantLock` per grid.<ul><li>**Phase boundary:** the outer `Grid.on{Server,Level}{Start,End}Tick` (all four), so every `IGridServiceProvider`, megacells included, is covered without naming it.</li><li>**Main** blocks on `P(G)`.</li><li>**Non-main topology** (connection create/destroy, node setGrid/destroy, `Grid.create`): resolve the affected grids → sort by serial → all-or-nothing `tryLock` → on failure release all, bounded backoff, rediscover/revalidate. After 16 failures, block on **one** contended P while holding none (fair queue = the progress invariant), then retry the set.</li><li>Order `P → T0 → domains`. Inside a kernel section (after T0) P is only taken with `tryLock`; failure → `pNestedUnresolved` plus unaudited.</li><li>Instrumentation: `pTryFailures`, `pRetryCount`, `pWaitNs`, `pMaxRetry`, `pWriterStarvationWarning`, `pNestedUnresolved`.</li></ul> |
| staging (`glue/Staging`, `glue/GridStaging`) | Decision rule:<ul><li>phase **active** → STAGE always, even from main;</li><li>caller holds `P(G)` (structural context) → RUN;</li><li>main outside a kernel section → RUN under `P(G)`;</li><li>otherwise STAGE.</li></ul>**Drain cut:** main, under `P(G)`, at the head of `Grid.onServerEndTick` (the phase in which Storage and Crafting read). Watcher ops are drained in FIFO order, then the coalesced rebuilds. A dead grid drops its staged work, counted |
| Storage / Crafting `requestUpdate` | `StorageService.refreshNodeStorageProvider` / `refreshGlobalStorageProvider` and `CraftingService.refreshNodeCraftingProvider` are wrapped. Coalesced per (provider, kind) |
| StackWatcher | `add` / `remove` / `reset` / `setWatchAll` are wrapped. The grid is found through an `InterestManager` back-reference bound in the `Grid` constructor. FIFO, no coalescing; AE2's own `destroyed` check makes operations on a destroyed watcher no-ops |
| startup manifest (`compat-manifest.tsv`, `StartupGate`) | 47 entries: `IGridServiceProvider` 10, `IStorageProvider` 11, `ICraftingProvider` 26. Fields: interface, internal class, mod id, version, jar sha256, audit level. AE2 classes: `MANUAL+BYTECODE-SCREEN-1`; addon classes: `BYTECODE-SCREEN-1` (a depth-1 screen of the add/remove-path methods for world, capability, blocking or topology calls; all clean). An unknown provider gives `COMPAT_MANIFEST_UNKNOWN_PROVIDER mod=… class=… interface=…` (or `…_VERSION_MISMATCH`) → `manifestEligible=false` plus kernel unaudited → DEGRADED. The layer stays on: no fail-open |

## Fixtures (`glue/GridPhaseFixtures`, `/nwcompatae2 gridphase …`, `rig/gridphase-run.sh`)
| Fixture | Result |
|---|---|
| staging `boundary`: a foreign `requestUpdate` published at the drain cut, before the drain | **PASS**: applied in this phase |
| staging `boundary`: a foreign `requestUpdate` published after the drain (`drain-done`, same phase) | **PASS**: left staged and applied by the next phase (staged 2, drained 2) |
| staging `coalesce`: 3 requests before the cut | **PASS**: staged 1, coalesced 2, drained 1 (one rebuild) |
| staging `activephase`: main calls `watcher.add` and `requestUpdate` inside the **active** phase | **PASS**: both staged, not visible in that phase, applied at the next boundary |
| staging `watcherfifo`: `add(B)`, `reset`, `add(A)`, `add(B)` | **PASS**: 4 staged, 4 drained in order; final membership {A, B} |
| staging `watcherdestroyed`: an operation staged, then the watcher destroyed before the drain | **PASS**: dropped (membership unchanged) |
| **OVERLAP (deterministic):** main parks inside the active server-END phase of grid G; a RegionThread (armor-stand host) destroys a node of G (`mainNode.destroy()`, synchronous topology, no world write) | **PASS 6/6.** The region never completed during the phase: 17 `tryLock` failures each, then escalation; it completed right after the phase |
| region-thread topology stress (single-node grid toggled every tick, 60 s) | 1 201 toggles, 0 exceptions, 0 starvation warnings. **Not discriminating:** the same stress *without* P(G) also showed 0 exceptions (3 601 toggles), so it is not evidence for the mechanism; OVERLAP is |

| Negative control (temporary patch: P lock removed from phase and topology, drain and hooks kept; reverted) | Result |
|---|---|
| OVERLAP | **FAIL as required, 6/6**: topology completed *while* main's phase of G was active (0 `tryLock` failures) |
| staging cases | boundary / coalesce still PASS; `activephase` / `watcherfifo` / `watcherdestroyed` deviate. Their decisions depend on P (RUN-under-P vs STAGE) |

**Regression on the final jar:**
- integration (LIFE ×2, STAGING) 3/3;
- queue 8/8;
- T2 4/4;
- 0 exceptions.
- LEGACY disk path: PASS.
  - drive east / inscriber south migrated;
  - no legacy keys saved;
  - no re-migration.
  - The live-world `/setblock` with legacy NBT now reached `onReady`, and the fail-closed skip was
    counted (`liveSkipped=1`, state not written): **LEGACY-ORIENTATION-LIVE fail-closed observed
    directly**.
- 0 exceptions throughout.

## Discarded fixture designs and rig defects (kept as evidence, not results)
- **`/nestworld freerun <id>` is a TOGGLE.** Blind loops flipped regions back off, so region-thread
  fixtures were intermittently hosted by non-free-running regions. Replaced by
  `rig/freerun-all.sh`, which parses the full status and toggles only regions that are not yet
  free-running.
- **`rcon.py` read only the first 4 096-byte packet**, so the region list was truncated. Fixed with
  a marker packet.
- **A region-thread `level.removeBlock` can be routed or deferred by Core** (the block was still
  present right after the call). Fixtures now detect this and report **INVALID**, not FAIL. OVERLAP
  uses a direct node destroy instead.
- Two earlier negative-control runs were void:
  - one deployed a stale negative jar;
  - one patch removed the whole phase, including the hooks.
- **`rcon.py` busy-spun when the server closed a connection mid-read** (99% CPU for 18 minutes;
  it hung one legacy run). Reads now fail on EOF.
- Accumulated forceloads (35+ regions) made the layout unstable. Every rig script now starts with
  `forceload remove all`.
