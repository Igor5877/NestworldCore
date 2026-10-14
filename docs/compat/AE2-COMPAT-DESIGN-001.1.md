# AE2-COMPAT-DESIGN-001.1 — lock model under external participant callbacks

**Status: DESIGN-1.1 (2026-10-14) — for owner review. No code.** It supersedes the lock-model
parts of AE2-COMPAT-DESIGN-001 (§§2, 5, 6, 8, 9). The rest of 001 stands.

| Item | State |
|---|---|
| Audit-1 | PASS |
| Direction A | APPROVED |
| Design-1 architecture | PROMISING |
| Implementation | BLOCKED until this document is accepted |
| B (buffering) | PARKED |
| C (pins) | PRODUCTION MITIGATION |
| New Mod API primitive | NOT YET |

**Owner decisions recorded (2026-10-14):**
1. T0 is **GLOBAL** in v1; hold and wait time are measured.
2. SolarFlux: correctness audit first; panels stay pinned/conservative until then
   (SOLARFLUX-COMPAT-001).
3. Fail-loud is acceptable for development and tests only. Production needs proof or a safe
   fallback (this document).
4. Storage, pathing and P2P are outside this gate. **A whole-AE2 unpin, or declaring AE2
   concurrency-safe, after this addon is forbidden.** Only the Energy subsystem closes here.

## 1. The blocker, made concrete
Design-1 said that no AE2 energy path calls foreign code under the domain lock. That is false.
`injectProviderPower` and `extractProviderPower` call `IAEPowerStorage.injectAEPower` /
`extractAEPower` on every member of `requesters` / `providers`. Those members include
third-party implementations and AE2 classes whose side effects reach vanilla neighbour
notification.

**Concrete inversion path found in the installed pack**:
```
[thread holding DOMAIN(D_a)]  E2 extractProviderPower
  → SolarPanelTile.extractEnergy   (SolarFlux 20.1.11, participant of D_a)  — writes panel.energy  (IRREVERSIBLE)
  → BlockEntity.setChanged()       (m_6596_)
  → Level.updateNeighbourForOutputSignal(pos, block)
  → CableBusBlock.onNeighborChange (AE2 cable of ANY network next to the panel)
  → CableBusContainer.onNeighborChanged → ToggleBusPart.onNeighborChanged
  → if redstone changed: GridHelper.createConnection / connection.destroy()   ⇒ TOPOLOGY MUTATION (needs T0)
  (or IOBusPart.onNeighborChanged → grid.getTickManager().alertDevice(node) on a DIFFERENT grid)
```
A lower-order or T0 acquisition can therefore be requested **after** an irreversible mutation.
The "prove it impossible" route fails as Design-1 stated it. Safe mechanisms are required (§5).

## 2. Participants: every `IAEPowerStorage` implementation in the pack
Full bytecode scan of the SkyBlock island pack, including jar-in-jar, 2026-10-14.

| Implementation | Source | Side effects of `injectAEPower` / `extractAEPower` (audited) |
|---|---|---|
| `GridEnergyStorage` | AE2 | `StoredEnergyAmount` → on threshold crossing `grid.postEvent(GridPowerStorageStateChanged)` → **same grid's** `EnergyService` (E5, reentrant) |
| `EnergyCellBlockEntity` (+ megacells variants, same class) | AE2 | `StoredEnergyAmount` events (same grid); `setChangedNoTicketUpdate` → `getChunk(x,z,FULL,false).setUnsaved(true)`; `grid.getTickManager().alertDevice(node)` (**same grid**, TickManagerService). The blockstate change is deferred to the cell's own grid tick, so there is no neighbour update under the lock |
| `CreativeEnergyCellBlockEntity` | AE2 | none (returns constants) |
| `AEBasePoweredBlockEntity` family: `ControllerBlockEntity`, `EnergyAcceptorBlockEntity`, `ChestBlockEntity`, `InscriberBlockEntity`, `ChargerBlockEntity`; ExtendedAE `TileExCharger`, `TileExInscriber`, `Crankable` (inherit AE2's `final` methods) | AE2 code | `StoredEnergyAmount` → `emitPowerStateEvent` (no-op in the base class; the controller posts to its **same grid**) |
| **`SolarPanelTile` via `SolarPanelTileMixin`** | **SolarFlux 20.1.11, third party** | attribute recomputation (`SimpleAttributeProperty.setBaseValue`), writes `energy`, **`setChanged()` → vanilla neighbour output-signal notification → arbitrary neighbour blocks' code** |

**Entry callers** of `IEnergyService` (they enter E1–E4 from their own threads; `mod:ae2` pins do
not cover them):
- AdvancedAE, ExtendedAE, extendedae_plus;
- PackagedAuto, PackagedAvaritia, PackagedDraconic;
- aeinfinity, applied-botanics, applied_kjs, merequester, ExtendedTerminal (from packets).

The guard sits on the `EnergyService` methods, so it covers every caller regardless of who calls.

## 3. Classification of E1–E11 (+ TickManager T-group)
Classes:
- **DOMAIN_LOCAL:** the guard may cover the whole method.
- **CALLS_EXTERNAL_CODE:** the guard is held across a call into a participant or a foreign
  interface.
- **TOPOLOGY_MUTATION:** T0, then the ordered domains.
- **CALLBACK_AFTER_COMMIT:** state is computed under the guard; the callback runs after unlock.

| # | Entry | Class | Notes |
|---|---|---|---|
| E1 | `injectPower` → `injectProviderPower` | CALLS_EXTERNAL_CODE | participant `injectAEPower`; reentrant same-grid events |
| E2 | `extractAEPower` → `extractProviderPower` | CALLS_EXTERNAL_CODE | participant `extractAEPower` (the SolarFlux path of §1) |
| E3 | `getEnergyDemand` → `getProviderEnergyDemand` | CALLS_EXTERNAL_CODE (read-only) | participant `getPowerFlow`, `getAEMaxPower`, `getAECurrentPower`: getters, no side effects in any §2 implementation |
| E4 | `getStoredPower`, `refreshPower`, `getMaxStoredPower` | CALLS_EXTERNAL_CODE (read-only) | `refreshPower` calls participant `getAECurrentPower` |
| E5 | `storagePowerChangeHandler`, `nodeIdlePowerChangeHandler` | DOMAIN_LOCAL | set and accounting mutation only (`printStackTrace` on a non-public store) |
| E6 | `addNode`, `removeNode` | TOPOLOGY_MUTATION (part of a node move) | always called from `Grid.add` / `remove`, i.e. under T0 + domains (§4); calls participant `getPowerFlow` / `getAEMaxPower` / `getAECurrentPower` (getters); `EnergyWatcher` creation calls `ews.updateWatcher` (AE2 parts: field store) |
| E7 | `onServerStartTick` | DOMAIN_LOCAL | `IPassiveEnergyGenerator.setSuppressed` (AE2 vibration chamber: field) |
| E8 | `onServerEndTick` | **split:** CALLS_EXTERNAL_CODE (passive inject E1, idle drain E2) + CALLBACK_AFTER_COMMIT (`EnergyWatcher.post`, `publicPowerState` → `postEvent(GridPowerStatusChange)` + `notifyAllNodes(POWER)`) | §6 lists the precise injection points |
| E9 | `registerEnergyInterest`, `unregisterEnergyInterest` | DOMAIN_LOCAL | `TreeSet` mutation |
| E10 | `invalidateOverlayEnergyGrid`, `getOverlayGrid` / `buildCache` | DOMAIN_LOCAL (multi-member) | writes `overlayGrid` on every member; reads other members' `overlayGridConnections` |
| E11 | `saveNodeData` | DOMAIN_LOCAL | part of a node move (§4) |
| T1 | `TickManagerService.alertDevice`, `sleepDevice`, `wakeDevice`, `addNode`, `removeNode` | DOMAIN_LOCAL (**same per-grid lock**) | `IdentityHashMap`s + per-level `PriorityQueue`; `alertDevice` is reached from E1 / E2 via energy cells |
| T2 | `TickManagerService.onServerEndTick` / `onLevelEndTick` → `tickQueue` | **split:** queue operations (`peek`, `poll`, re-insert, `currentlyTicking`) under the lock; `unsafeTickingRequest` → `IGridTickable.tickingRequest` (device work, arbitrary AE2/addon code, item export to foreign inventories) **after unlock** | the lock is never held across a device tick |
| G1 | `GridConnection.create` / `destroy`, `GridNode.destroy`, `Grid.create`, `GridNode.setGrid`, split detector, `TickHandler.addNetwork` / `removeNetwork` | TOPOLOGY_MUTATION | T0, then the union of the involved domains in ascending order |
| G2 | `GridNode.setGrid` listener calls (`onGridChanged`, `notifyStatusChange`) | CALLBACK_AFTER_COMMIT | |

**Per-grid lock scope:** one lock per `Grid`, keyed on its `EnergyService` and serial. It covers
that grid's `EnergyService` **and** `TickManagerService` state, because E1/E2 reach T1 through
energy cells. The domain is still the energy overlay: the locks of all member grids, ascending.

## 4. Lock / callback graph (after this design)
```
                 ┌──────────────────────────── T0 (global, topology) ───────────────────────────┐
                 │ G1: connect/destroy/setGrid/create/registry  → acquire domains(old ∪ new) asc │
                 └───────────────────────────────────────────────────────────────────────────────┘
                                   (T0 is acquired only while NO domain lock is held)

 entry (any thread) ─▶ resolve grid ─▶ acquire DOMAIN = {member grids} asc ─▶ validate (§3 of 001) ─▶
     E1/E2/E3/E4 ─▶ participant calls (§2 audited set) ─┬▶ same-grid events → E5 (reentrant, held)
                                                         ├▶ same-grid TickManager T1 (held)
                                                         ├▶ chunk.setUnsaved(true)            (vanilla flag)
                                                         └▶ setChanged → neighbour notification ──▶ DEFERRED (M1)
     E8 ─▶ computations under DOMAIN; watcher posts, power-status event, notifyAllNodes ──▶ AFTER COMMIT
 release DOMAIN ─▶ run post-commit queue (M1 deferred notifications, E8/G2 callbacks), FIFO, same thread
```

## 5. Safe mechanisms (no abort, no replay)
- **M1 — notification deferral inside a domain section.**
  - While the current thread holds any AE2 domain lock, every vanilla neighbour notification it
    issues is appended to a thread-local post-commit queue instead of being delivered inline:
    `Level.updateNeighbourForOutputSignal`, `Level.neighborChanged` / `updateNeighborsAt`, and
    `BlockState.onNeighborChange` dispatch.
  - The queue is delivered in FIFO order right after the outermost release, on the same thread,
    before control returns to the original caller of the AE2 entry. A Pipez `receiveEnergy` call
    returns only after the notifications have run.
  - These calls return no value, so deferring them changes no synchronous contract. The only
    observable difference is that the receivers run after the AE2 energy section instead of in
    the middle of it.
  - Effect: the §1 inversion path is cut at its first foreign step.
- **M2 — refusal fallback for nested synchronous energy calls (defence in depth).**
  - Applies when E1–E4 are entered while the thread already holds domain locks and the target
    domain needs a lock with a lower serial that is not held.
  - It is tried once (`tryLock`). On failure the nested call returns a **contract-valid
    "nothing moved" result** and has no side effects:
    - inject returns the full amount as leftover;
    - extract returns 0;
    - demand returns 0;
    - E4 reads return the last value published at the previous release (a volatile snapshot).
  - Nothing has been applied, so nothing needs to be undone or replayed. Counted and logged once
    per site.
- **M3 — deferred TickManager hints.**
  - Applies to T1 calls (`alertDevice`, `sleepDevice`, `wakeDevice`) on a grid whose lock is not
    held, with a lower serial.
  - The call is queued post-commit and the immediate return value is `true`. In-pack callers
    ignore the return value (IOBusPart, energy cell); every use is listed at implementation.
- **M4 — T0 while domains are held** (topology from inside an energy section).
  - **No reachable path remains after M1 and the CALLBACK_AFTER_COMMIT treatment of E8 and G2.**
    Topology call sites are:
    - BE `onLoad` / init via `TickHandler` (main, no locks held);
    - `setRemoved` / `onChunkUnloaded` (not reachable from §2 participant code);
    - part add/remove by players (no locks held);
    - part neighbour reactions (ToggleBus, IOBus, …): reached only through notifications, which M1
      defers;
    - node listeners: after commit.
  - **Residual:** an unknown future code path. It cannot be refused (it returns a connection)
    and must not block (inversion). Development and tests fail loud. Production applies M5 so
    that unknown code never runs under a domain lock.
- **M5 — participant admission (fail-closed bound on foreign code under lock).**
  - Only audited `IAEPowerStorage` classes (§2, exact mod versions) enter a guarded domain's
    `requesters` / `providers`.
  - An unknown participant at `addNode` is not added to the power sets: it does not exchange
    power through the grid. A warning names it, and its block-entity type is reported for a pin.
  - With M5, the code that runs under a domain lock is exactly the audited set of §2, plus AE2.
    Its outcalls are fully enumerated in §4. Hence **no lower-order or T0 acquisition can occur
    under a held lock, before or after any mutation.** M2 and M3 remain only as detectors and
    safety nets.
  - **Cost:** a visible behaviour change for unknown power-storage mods, owner decision 1 below.
    The alternative is "unknown participant ⇒ that domain stays unguarded", which is today's
    behaviour.

**Proof sketch for owner decision 3.**
- Under M5, every frame executed while a domain lock is held belongs to AE2 or to an audited
  participant.
- §2 and §3 enumerate their outcalls. Each one is in exactly one of four groups:
  - inside the held set (same-grid events, same-grid T1);
  - lock-free (chunk flag, field writes);
  - deferred (M1);
  - after commit (E8, G2, T2 device ticks).
- Hence no lock outside the held set is requested inline.
- Lock acquisition happens only at outermost entries: either T0 then ascending domains, or
  ascending domains alone.
- The waits-for graph therefore follows a strict total order and cannot cycle. The inversion that
  would force abort/replay cannot arise.

## 6. Injection points, refined (where one `@WrapMethod` is not enough)
| Site | Mechanism |
|---|---|
| E1, E3, E4, E5, E7, E9, E10, E11, T1 | `@WrapMethod` (whole method; reentrant acquire; release in `finally`; run the post-commit queue at the outermost release) |
| E2 | `@WrapMethod`; participant calls stay inside (CALLS_EXTERNAL_CODE, made safe by M1 + M5) |
| **E8 `onServerEndTick`** | `@WrapMethod` holds the domain for the whole method; **`@WrapOperation` on the three callback calls** (`EnergyWatcher.post`, `IGrid.postEvent(GridPowerStatusChange)`, `Grid.notifyAllNodes(POWER)`) turns each into a post-commit queue entry; AE2's code order is preserved, and only delivery moves after unlock |
| **T2 `TickManagerService.tickQueue`** | `@WrapOperation` on `unsafeTickingRequest`: release the grid lock before the device tick and re-acquire it after (the lock is never held across a device tick). Re-acquiring may change the domain: re-validate as in 001 §3 before touching the queue |
| G1 | `@WrapMethod` at the outermost topology entries with T0, then ascending domains over old ∪ new |
| G2 | `@WrapOperation` on `GridNode.setGrid`'s `callListener` and `notifyStatusChange` calls → post-commit |
| Entry validation | `funnelPowerIntoStorage` in `EnergyAcceptorBlockEntity`, `EnergyAcceptorPart` and `ControllerBlockEntity` (stale service; 001 §3) |
| M1 | `@WrapMethod` on `Level.updateNeighbourForOutputSignal`, `Level.neighborChanged(BlockPos, Block, BlockPos)` and `Level.updateNeighborsAt`: when the thread holds an AE2 domain, enqueue; otherwise pass through. **This is the only vanilla-class mixin in the addon**; it is active only inside AE2 domain sections |
| M5 | at `EnergyService.addNode` before `addProvider` / `addRequester`: admission by class plus mod version |

The version gate (001 §9) stays: exact AE2 15.4.10 and SolarFlux 20.1.11 versions and hashes for
the audited participant set. Unknown versions mean no mixins and the pins stay.

## 7. Performance model (HYPOTHESIS only; no numbers yet)
The cost of an entry depends on:
- overlay cardinality (number of locks);
- the validation path;
- contention;
- nesting depth;
- merge/split frequency;
- post-commit queue length.

The question the benchmark answers is whether **different overlay domains scale independently,
with contention confined to one domain.**

| Dimension | Values |
|---|---|
| overlay size (grids joined by quartz fibre) | 1 / 2 / 4 / 8 / 16 |
| threads entering | 1 / 2 / 4 / 8 |
| layout | all threads on one domain (contention) vs one domain per thread (independence) |
| topology churn | none vs periodic connect/disconnect (T0 hold and wait) |
| baseline | pinned (`mod:ae2` + feeders) vs addon, same topology and duration |

Metrics:
- TPS and MSPT;
- region and main CPU;
- acquisitions, contended acquisitions and the wait-time histogram per domain;
- retries;
- M2 refusals and M3 deferrals (expected 0);
- post-commit queue depth;
- T0 hold and wait time.

## 8. Findings split out
- **SOLARFLUX-COMPAT-001:** the panel's `energy` is written both by the panel tick and by the AE2
  callback. OPEN / SHARED-STATE CONFIRMED / RACE NOT YET REPRODUCED. Production: pinned until
  audited.
- **COMPAT-THREAD-IDENTITY-001:** recorded separately.
- **Residual AE2 races outside Energy:** storage, pathing, P2P. Recorded in AE2-COMPAT-001; they
  keep `mod:ae2` pinned.

## 9. Owner decisions needed before implementation
1. **M5 policy for unknown participants.**
   - (a) fail-closed exclusion: not admitted to power sets, behaviour change, provable;
   - (b) the domain stays unguarded when an unknown participant joins: today's behaviour,
     unprovable.
   - Proposed: (a).
2. **M1 scope.** Defer vanilla neighbour notifications issued inside AE2 domain sections. It is a
   vanilla-class mixin in a compat addon, narrowly gated to inside AE2 sections. Accept, or
   require another boundary.
3. **M3 return value** for deferred TickManager hints (`true`), subject to the caller audit at
   implementation.
