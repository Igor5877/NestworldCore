# AE2-COMPAT-001 — Applied Energistics 2 energy service vs NestWorld region threads

**Status: AUDIT-1 PASS (owner, 2026-10-14).**
- Direction A approved.
- Design-1 promising; implementation blocked on Design-1.1 (`AE2-COMPAT-DESIGN-001.1.md`).
- B parked; C is a production mitigation.
- Only the Energy subsystem is in scope. A whole-AE2 unpin after this addon is forbidden: storage, pathing and P2P stay outside.

## Exact version
| Item | Value |
|---|---|
| Jar | `appliedenergistics2-forge-15.4.10.jar` |
| sha256 | `fbfee05c6674cb6b…` (full value in `/root/mod-compat-audit/jars/SHA256SUMS`) |
| Copies compared | 13 local copies, all identical, including the SkyBlock island deploy set (`skyblock-island-deploy/mods_new_extract`) and `AE2-15.4.10-PRODUCTION-ORIGINAL-backup` |
| Island jar | **not yet hashed on the island itself** (owner to run; see Open items) |
| Code inspected | the installed bytecode, decompiled with Vineflower 1.10.1 into `/root/mod-compat-audit/src/` (outside the repository); no upstream source used |

## Observed incident
At least 19 skipped ticks on the island `airisminhistory` (Core 47.4.234, free-running on),
2026-10-05 … 2026-10-12. See `docs/contracts/OPS-INCIDENT-2026-10-05-ae2-energy-race.md`.

Failure stack:
```
IllegalStateException: Cannot modify energy requesters while energy is being injected.
  EnergyService.addRequester(515)
  EnergyService.storagePowerChangeHandler(179)       <- GridPowerStorageStateChanged RECEIVE_POWER
  GridEventBus.postEvent / Grid.postEvent
  ControllerBlockEntity.emitPowerStateEvent(131)
  StoredEnergyAmount.sendEvents / setStored / extract
  AEBasePoweredBlockEntity.extractAEPower
  EnergyService.extractProviderPower(338)           <- sets ongoingExtractOperation on this thread
  EnergyService.extractAEPower / onServerEndTick(244)
  TickHandler.onServerTickEnd                        <- Server thread, ServerTickEvent END
```

## Shared state
`appeng.me.service.EnergyService`, one per `Grid`. It holds no synchronization. Its fields are
plain, non-volatile:

| Field | Type | Role |
|---|---|---|
| `requesters` | fastutil `ObjectRBTreeSet` | storages that can receive power; iterated, and `it.remove()` called, during inject |
| `providers` | fastutil `ObjectRBTreeSet` | storages that can give power; iterated, and `it.remove()` called, during extract |
| `ongoingInjectOperation` | `boolean` | phase guard, true only inside `injectProviderPower` (reset in `finally`) |
| `ongoingExtractOperation` | `boolean` | phase guard, true only inside `extractProviderPower` (reset in `finally`) |
| `globalAvailablePower`, `providerPowerSum`, `drainPerTick`, `tickDrainPerTick`, `tickInjectionPerTick`, `avg*` | `double` | energy accounting, read-modify-write |
| `overlayGrid` | `EnergyOverlayGrid` | lazily built list of energy services connected by overlay connections |
| `passiveGenerators`, `watchers`, `interests`, `overlayGridConnections` | identity set, `HashMap`, `TreeSet`, `Multiset` | node-membership state |

**Scope.** Injection and extraction iterate `getConnectedServices()`, i.e. every `EnergyService`
in the same `EnergyOverlayGrid`. The domain that must be consistent is therefore the **energy
overlay grid**, not a single `Grid`. It is rebuilt lazily (`buildCache`) after `invalidate()`.

## Readers and writers
| Path | Thread in NestWorld | Touches |
|---|---|---|
| `TickHandler.onServerTickEnd` → `Grid.onServerEndTick` → `EnergyService.onServerEndTick` (passive generator `injectPower`, idle drain `extractAEPower`, power-state events) | main, END of tick | all of the above |
| `TickHandler.onServerTickStart` → `onServerStartTick` (passive generators) | main | `passiveGenerators`, overlay |
| **External Forge Energy:** `ForgeEnergyAdapter.receiveEnergy` → `IExternalPowerSink.injectExternalPower` → `funnelPowerIntoStorage` → `EnergyAcceptorBlockEntity` / `EnergyAcceptorPart` / `ControllerBlockEntity` → `grid.getEnergyService().injectPower` → `injectProviderPower` | **the caller's thread**, i.e. whichever block entity calls `receiveEnergy` | `requesters` (iterate + remove), `ongoingInjectOperation`, accounting, and events from storages that change state (`addProvider`) |
| AE2's own generators/consumers (`VibrationChamberBlockEntity`, `ChargerBlockEntity`, `InscriberBlockEntity`) | the AE2 block entity's tick thread (main while `ae2` is pinned) | inject / extract |
| Node join/leave: `EnergyService.addNode` / `removeNode` (`addProvider`, `addRequester`, `removeProvider`, `removeRequester`, overlay invalidate) | wherever a grid node is created or destroyed (block placed or broken, chunk load/unload) | membership sets |
| `TickHandler.addNetwork` / `removeNetwork` / `getGridList` | guarded by `Platform.assertServerThread()` | the grid registry |

## Findings
1. **CONFIRMED (code-level).** The incident exception requires a second thread inside
   `injectProviderPower` on an energy service of the same overlay grid at the same moment.
   - `ongoingInjectOperation` is set only in `injectProviderPower` and reset in its `finally`.
   - The failing main-thread stack is an *extract* path with no `injectProviderPower` frame.
   - It cannot be explained by reentrancy on the main thread: the main thread's own writes to the
     flag are always program-order visible to itself.
   - **Class:** CROSS_THREAD + PHASE_CONSTRAINED, overlay-grid-scoped (GRID_SCOPED, widened to the
     energy overlay).
2. **CONFIRMED (code-level).** An external mod's block entity calling Forge Energy
   `receiveEnergy` on an AE2 energy acceptor or controller mutates AE2 energy-service state
   synchronously, on the caller's thread. This answers MOD-COMPAT-1 §6, question 1: yes.
3. **CONFIRMED (code-level).** AE2 assumes a single server thread for this state.
   - There is no lock, no volatile, and no concurrent collection.
   - AE2's phase guards detect only same-call-stack reentry. Under concurrency they fire only by
     chance: the exception is the visible case.
   - The invisible case is two threads iterating and modifying the same `ObjectRBTreeSet`, or
     doing read-modify-write on the accounting doubles. That can silently corrupt the tree or the
     energy totals.
   - This answers §6, question 3: cross-thread is the cause; AE2's own reentrancy handling is
     intended and not the problem.
4. **CONFIRMED (code-level).** AE2's thread assertion cannot see NestWorld threads.
   - `Platform.assertServerThread()` compares `Thread.getThreadGroup()` with
     `SidedThreadGroups.SERVER`.
   - `RegionThread` is constructed by the server thread with `super(name)`, so it inherits the
     SERVER thread group. AE2's own guard passes on region threads.
   - The same applies to grid creation and removal (`addNetwork` / `removeNetwork`).
5. **Not proven:** which writer caused each incident. See PIPEZ-COMPAT-001 for the strongest
   candidate. Other candidates on the island are any FE producer or pipe adjacent to an AE2
   energy acceptor or controller (Mekanism cables, Flux Networks, Thermal, Powah).

## Expected execution contract (AE2)
- All `EnergyService` / `Grid` mutations and iterations happen on one thread, the server thread.
- Injection or extraction may re-enter and fire grid events, and the phase guards police that.
- External callers of the Forge Energy capability are assumed to be on that same thread.

## NestWorld interaction
| Mode | Overlap |
|---|---|
| free-running regions (island config) | region threads tick block entities at any time, including during the main thread's END-tick handler → main vs region overlap (**the observed incidents**) |
| barrier | region rounds finish before END-tick handlers, so main vs region overlap is removed. **But two region threads in the same block-entity round can still both inject into one overlay grid** (for example, two pipes in two regions). Barrier mode does not make this race impossible |
| `ae2` mod pin | moves AE2's own block entities to main; external injectors keep running on their region threads, so this pin alone cannot prevent the race (observed: 17 recurrences after the pin) |

## Candidate adapter boundary (to evaluate in the design step; not chosen)
**Serialization domain:** the energy overlay grid, the narrowest domain AE2's own algorithm treats
as one unit. It is narrower than the whole mod, wider than a single `Grid`.

Entry points to cover:
- `injectProviderPower`;
- `extractProviderPower`;
- `getProviderEnergyDemand`;
- `addNode` / `removeNode` (energy part);
- `onServerStartTick` / `onServerEndTick`;
- `refreshPower`;
- `EnergyOverlayGrid.buildCache` / `invalidate`.

Candidates:
- **A. Overlay-grid exclusive section.** Every entry above runs under one exclusive section owned
  by the overlay grid.
  - Open questions: lock ordering when one overlay's callback reaches another (P2P energy tunnels,
    overlay connections); the identity of the domain across an `invalidate()` / rebuild; and the
    cost on main's END tick.
- **B. Thread-affine external injection.**
  - On a thread that is not the domain's owner, `ForgeEnergyAdapter.receiveEnergy` /
    `injectExternalPower` accepts into the acceptor's own local buffer (bounded by its existing
    capacity) and returns.
  - The buffer is flushed into the grid on the owning thread.
  - Changes acceptor semantics: a simulate result may not match the later modulate. Needs a
    terminal outcome for buffered energy.
- **C. Route external callers to main.** Pin specific external block-entity types that feed AE2
  (the current mitigation: `pipez:energy_pipe`).
  - Whack-a-mole: every new FE producer next to AE2 needs its own pin. Mitigation only.

**Rejected now:**
- swallowing the `IllegalStateException` (MOD-COMPAT-1 §12: FAIL);
- `synchronized` on `EnergyService` as a final fix (wrong scope: the overlay, not one service);
- pinning all of AE2 (does not stop external writers).

## Required Mod API capability (preliminary)
Candidate A needs an exclusive section keyed by a mod-defined dynamic domain identity (the overlay
grid), with a defined lock-order or deadlock policy. Candidate B needs a way to run the flush on the
domain's owning thread. No such bounded primitive exists in the Mod API today, but neither is
proven necessary yet. **MOD-API-GAP to be written in the design step only if the design proves it**
(§14). No generic executor.

## Reproduction proposal
- Isolated scratch server, same AE2 / Pipez / Mekanism jars (hashes above), free-running on.
- Topology:
  - one AE2 grid with a controller, an energy acceptor and energy cells, with power draw
    (drives, interfaces);
  - **N Pipez energy pipes in different regions**, each extracting from a creative or high-rate FE
    source and pushing into the acceptor.
- Expected without a fix: the `IllegalStateException` on main (free-running), and region-thread
  BE-tick exceptions "Cannot modify energy providers while energy is being extracted" (the mirror
  case).
- Cell and energy accounting should be checked for silent corruption: sum of stored energy against
  the inject/extract totals.
- Control runs:
  - barrier mode with 2+ injectors in different regions, to test finding 5's barrier claim;
  - `pipez:energy_pipe` pinned, the current mitigation.
- Then the same workload with the addon: race = 0, plus the §18 correctness checks.

## Pin state
- **Before (island):** `mod:ae2` (auto, 2026-10-06), `be:pipez:energy_pipe` (auto, 2026-10-12).
- **After:** unchanged until the §20 unpin gate.

## Open items (owner)
1. Island jar hashes (read-only):
   `sha256sum /opt/minecraft/mods/{appliedenergistics2-forge-15.4.10,pipez-forge-1.20.1-1.2.26,Mekanism-1.20.1-10.4.16.80}.jar`.
2. The block-entity tick error behind the `be:pipez:energy_pipe` auto-pin, 2026-10-12 about 23:45,
   from that day's rotated log. If it is AE2's mirror exception ("…providers while energy is being
   extracted") on a Pipez stack, the Pipez writer path becomes **STRONGLY INDICATED** for that
   incident.
3. Island topology: what feeds the AE2 grid with energy (Pipez pipes, Mekanism cables, other).
