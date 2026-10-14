# AE2-COMPAT-DESIGN-001 — energy-overlay-scoped serialization for AE2 15.4.10

**Status: DESIGN-1 (2026-10-14) — for owner review. No code.** Direction A (owner decision
2026-10-14).
- B (buffered external energy) is PARKED.
- C (pinning feeders) is an operational mitigation only.
- No new Mod API primitive.

Inputs: AE2-COMPAT-001 (Audit-1), PIPEZ-COMPAT-001, the installed AE2 15.4.10 bytecode
(`fbfee05c…`) decompiled in `/root/mod-compat-audit/src/`.

## 1. Invariant
> For one AE2 energy overlay domain, at most one conflicting mutation or iteration phase executes
> at a time, regardless of which region, mod, block entity or thread entered AE2.
> Different domains run concurrently.

**Domain:** the set of `EnergyService`s that `getConnectedServices()` returns, i.e. one
`EnergyOverlayGrid`. Its membership is the transitive closure over
`IEnergyOverlayGridConnection`, and the only implementor in AE2 is `QuartzFiberPart`.
In practice almost every overlay holds a single `EnergyService` (one grid, no quartz fibers).

## 2. What the guard covers (energy domain)
Each entry below runs with the full domain held (§3). The column "reads/writes" refers to the
fields in AE2-COMPAT-001 → Shared state.

| # | AE2 entry (`appeng.me.service.EnergyService` unless noted) | Why it must be inside |
|---|---|---|
| E1 | `injectPower` (→ `injectProviderPower` on every member) | iterates and removes from `requesters`; sets `ongoingInjectOperation`; accounting |
| E2 | `extractAEPower` (→ `extractProviderPower`) | iterates and removes from `providers`; sets `ongoingExtractOperation`; accounting |
| E3 | `getEnergyDemand` (→ `getProviderEnergyDemand`) | iterates `requesters`; used by every `receiveEnergy(…, simulate=true)` |
| E4 | `getStoredPower`, `refreshPower`, `getMaxStoredPower` | read and refresh `globalAvailablePower` / `providerPowerSum` (`refreshPower` iterates `providers`) |
| E5 | `storagePowerChangeHandler`, `nodeIdlePowerChangeHandler` (grid-event handlers, `GridEventBus`) | `addProvider` / `addRequester`, `drainPerTick`; posted from any thread that changes a storage's or node's state |
| E6 | `addNode`, `removeNode` | membership sets, accounting, passive generators, watchers, `overlayGridConnections` → `invalidateOverlayEnergyGrid` |
| E7 | `onServerStartTick` | passive-generator election, overlay field |
| E8 | `onServerEndTick` | passive injection (E1), threshold interests, averages, idle drain (E2), has-power state |
| E9 | `registerEnergyInterest`, `unregisterEnergyInterest` | the `interests` `TreeSet` iterated by E8 |
| E10 | `invalidateOverlayEnergyGrid`, `getOverlayGrid` → `EnergyOverlayGrid.buildCache` / `invalidate` | rewrite `overlayGrid` on every member |
| E11 | `saveNodeData` | reads `localStorage` share; part of a node move (§4) |

**Outside the energy domain**, recorded as residual risks; this addon does not cover them:
- Other AE2 grid services touched from region threads: pathing, storage, P2P tunnel lists,
  crafting. The P2P energy tunnel (`FEP2PTunnelPart.receiveEnergy`) forwards energy to its
  outputs' neighbours without touching `EnergyService`, but reads the P2P service's output list.
- AE2-global state: `TickHandler` grid registry (`addNetwork` / `removeNetwork`) and
  `Grid.nextSerial`, a non-atomic static. These are covered only by the topology guard (§4).
- Foreign `IAEPowerStorage` participants. In this pack the only one is the SolarFlux solar
  panel, via `SolarPanelTileMixin`. The AE2 side calls into its `energy` field from inside the
  domain, while the panel's own tick, on its region thread, changes the same field outside the
  domain. That is a race on the participant's own state that a domain guard cannot close.
  Mitigation: the panel's block-entity type follows its domain, or is pinned when it is an AE2
  node. Decided in implementation, measured first.

## 3. Domain identity and lifecycle
**No lock lives on `EnergyOverlayGrid`.** That object is rebuilt lazily after every `invalidate()`,
so two threads could lock two different overlay objects that cover the same services. That is the
ABA trap the owner flagged.

Instead:
- **One lock per `EnergyService`.** A `@Unique` `ReentrantLock` and a `@Unique final long
  domainSerial` are added to `EnergyService` by Mixin. The serial comes from the addon's own
  `AtomicLong` at construction, not from `Grid.serialNumber`, whose generator is a non-atomic
  static.
- **A domain is held when every member service's lock is held**, acquired in ascending
  `domainSerial` order.
- **Membership is resolved optimistically, then validated under the locks:**
  1. Read the candidate members: the current overlay list if built, else a traversal of
     `getOverlayGridConnections()`. This is a racy read, treated as a hint only.
  2. Lock all candidates in ascending serial order.
  3. Recompute membership under the locks: build the overlay if it is null, which writes only
     the fields of members now locked.
  4. Equal → proceed. Different (a quartz fiber was connected or removed meanwhile) → unlock
     all and retry.
  5. The retry is bounded, counted by a metric, and backs off.
- **Entry validation (stale service).** External energy enters through
  `funnelPowerIntoStorage`, which reads `getMainNode().getGrid()` first. That grid can be
  merged away, leaving a dead `EnergyService` (§4).
  - After acquiring the domain, the entry checks that its node's current grid is still the
    service's grid, and that the grid is alive (`pivot != null`).
  - If not, it releases and retries with a fresh lookup.
  - Energy is never injected into a dead service's `localStorage`. That would be silent loss.

## 4. Merge, split and node moves
AE2 has no atomic merge or split; both are sequences of single-node moves.
- **Merge.** `GridConnection.create` → `mergeGrids` → `GridPropagator`, which calls
  `GridNode.setGrid(winner)` on each node of the losing grid.
- **Split.** `GridSplitDetector` on connection destroy → `Grid.create`, then propagation.
- **One move.** `setGrid`:
  1. `old.saveNodeData`, then `old.remove` (`removeNode` on every service);
  2. `new.add` (`addNode` on every service, carrying the saved energy share);
  3. the node listeners.
- **Death of the losing grid.** It dies when its last node leaves: pivot `null`, then
  `TickHandler.removeNetwork`.

**Protocol:**
- **T0 — topology guard.** One addon-global `ReentrantLock`. It wraps the outermost
  topology-changing AE2 entry:
  - `GridConnection.create` and `GridConnection.destroy`;
  - `GridNode.destroy`;
  - `Grid.create`;
  - the split detector.

  It also guards `TickHandler.addNetwork` / `removeNetwork` and therefore `Grid.nextSerial`.
  Topology changes are rare (blocks placed or broken, chunk load or unload), so one global
  lock costs little.
- **T1 — a node move** (`GridNode.setGrid`). It holds the union of the old and the new
  domains, locked in ascending order with the §3 validation, for steps 1–2. Release happens
  before the listeners (§6).
- **Lock order (global, deadlock-free):** `T0` → domain service locks in ascending
  `domainSerial`. A thread that holds domain locks never acquires `T0`; that is checked and
  counted (§6).
- **ABA, explicitly.** Thread X resolves grid G and blocks on G's domain. Meanwhile G is merged
  into H (under `T0` + G ∪ H). When X acquires G's lock, entry validation sees that the node's
  grid is now H, so X retries on H. A dead service never receives a modification, and every
  membership change happens with all old and new members locked.

## 5. Operations that span domains
| Case | Domains | Handling |
|---|---|---|
| E1/E2 over an overlay with several members (quartz fiber) | one domain, several locks | ascending order |
| Node move during merge or split | two domains | ascending order over the union, under `T0` |
| External caller feeding two acceptors of different domains in one tick (a Pipez network with several AE2 endpoints) | two domains, **sequentially** | each `receiveEnergy` acquires and releases its own domain; nothing is held between calls |
| A domain-held thread reaching another domain through a callback | would be nesting | removed by §6; any remaining case is detected |

## 6. Reentrancy and callbacks
**Reentrant lock.** AE2 re-enters its own energy paths on the same thread by design: an inject
changes a storage, which fires `GridPowerStorageStateChanged`, which calls `addProvider` (E5)
inside E1. A per-thread held-set records which domain locks the thread owns.
- Re-acquiring a held lock: allowed.
- Acquiring a new lock with a serial higher than every held one: allowed (ordered).
- Acquiring a new lock with a lower serial: **not allowed to block**. It is tried once
  (`tryLock`), counted, and logged once with its stack.
  - If the try fails, the call is a deadlock candidate. In diagnostic mode it throws
    (fail-loud in tests). Production behaviour is an owner decision; see Open decisions, item 3.

**Callbacks that can leave the domain**, which AE2 runs while it would hold the guard:
- `EnergyService.onServerEndTick` → `EnergyWatcher.post` → energy-level-emitter parts → redstone
  neighbour updates, which can reach arbitrary blocks;
- `publicPowerState` → `grid.notifyAllNodes(POWER)` and the `GridPowerStatusChange` event → every
  node listener;
- `GridNode.setGrid` → `callListener(onGridChanged)` and `notifyStatusChange`.

**Design: snapshot under the guard, callbacks after release.**
- **E8, `onServerEndTick`:** the energy computations run under the domain lock. That covers the
  passive inject, interest-range selection (copied out of `interests`), averages, idle drain
  and the has-power decision. The interest watcher posts and the power-state broadcast run
  after release, in the same order AE2 uses.
- **T1, node move:** listener callbacks run after release.
- **Ordering effect:**
  - On a single thread the observable order does not change. AE2 already runs these callbacks
    after the state they report.
  - A concurrent reader can see the new energy state before the watcher fires. That is no
    stronger than vanilla tick-boundary semantics.

**Inner calls that stay inside the domain:**
- `IAEPowerStorage.injectAEPower` / `extractAEPower` on AE2's own cells and powered block
  entities (`StoredEnergyAmount`). These only fire grid events back into the same grid.
- The SolarFlux participant (§2): reads and writes its own field, with no outcalls.

## 7. Nested Forge capability graph (installed code)
```
[region R1] Pipez PipeLogicTileEntity.tick ─ receiveEnergy ─▶ AE2 ForgeEnergyAdapter (acceptor/controller)
     └─ funnelPowerIntoStorage ─▶ acquire DOMAIN(grid of acceptor)  ─▶ E1 inject ─▶ AE2 storages ─▶ E5 events (same domain, reentrant)
                                 ◀─ release ◀──────────────────────────────────────────────────────────────┘
[region R2] Mekanism cable / Flux / Powah … ─ receiveEnergy ─▶ same path (other caller, same domain → serialized)
[region R1] Pipez ─▶ AE2 FE P2P input ─▶ P2P output neighbour ─▶ (maybe) AE2 acceptor of domain B ─▶ DOMAIN(B)
     (no energy-domain lock is held while the tunnel forwards → no nesting)
[main] TickHandler END ─▶ Grid.onServerEndTick ─▶ E8 under DOMAIN ─▶ release ─▶ watchers / notifyAllNodes (outside)
```
- AE2 never pushes energy out to foreign Forge Energy storages from inside its energy service.
- Charger and Inscriber inject into their own internal buffers, not into a grid.
- Hence no AE2 energy path calls a foreign mod while holding a domain lock, once §6 is applied.

## 8. Performance model
| Cost | Estimate | Note |
|---|---|---|
| Uncontended acquire + release, single-member overlay | ≈ 20–40 ns per entry | ReentrantLock CAS pair plus the held-set update |
| Validation (§3) | one field read plus one identity compare | no allocation on the fast path |
| Main END tick | one domain acquisition per grid per tick | only around the energy computations |
| Contention | only when two threads touch the same domain at once | e.g. N pipes in N regions feeding one AE2 network |
| Contended throughput of one domain | that of one thread, which is AE2's own assumption | different networks stay parallel |

**Measured, not assumed (§19).** Pinned baseline against the addon on the same topology:
- TPS and MSPT;
- region and main CPU;
- domain acquisitions, contended acquisitions and wait-time histogram;
- retries (§3) and out-of-order attempts (§6);
- topology-lock hold time.

## 9. Injection points (Mixin, AE2 15.4.10 exact)
- **Wrap whole methods** in try/finally with MixinExtras `@WrapMethod`. MixinExtras ≥ 0.4 is
  needed; the pack already carries up to 0.5.0 through jar-in-jar, and the addon jar-in-jars its
  own.
  - **On `EnergyService`:** E1–E4, E5, E6, E7, E9, E10, E11.
  - **E8 is restructured:** a wrap holds the domain for the computations, then the deferred
    callbacks run (§6). This is the only semantic edit; it changes order only relative to other
    threads.
  - **On the topology side:** `GridConnection.create` / `destroy`, `GridNode.destroy`,
    `GridNode.setGrid` (T1), `Grid.create`, `TickHandler.addNetwork` / `removeNetwork` (T0).
  - **Entry validation:** at `EnergyAcceptorBlockEntity`, `EnergyAcceptorPart` and
    `ControllerBlockEntity` `funnelPowerIntoStorage` (§3, stale service).
- **Side fields:** `@Unique` lock and serial on `EnergyService`. No `WeakHashMap` side table on
  the hot path.
- **Version gate (fail-closed, §21).**
  - A Mixin config plugin `shouldApplyMixin` checks the AE2 mod version (`15.4.10`) and the jar's
    sha256 (`fbfee05c…`).
  - On mismatch: no mixin applies, one clear warning is logged, and the existing auto-pins stay
    active.
- **Detector stays on.** Auto-pin and the NestWorld race detector are untouched. The addon
  publishes its counters (acquisitions, contention, retries, out-of-order attempts) through a
  read-only command.

## 10. Rejected in this design
- **A lock on the `EnergyOverlayGrid` object.** It is replaced on rebuild: the ABA trap of §3.
- **`synchronized(EnergyService)` per service without overlay-wide acquisition.** Injection
  iterates every member's `requesters`, so one service lock is not enough.
- **Holding the domain across watcher and node-listener callbacks.** That nests foreign code
  under the lock and makes deadlock possible.
- **A Mod API primitive now** (owner rule). Recorded as a pattern candidate only (§11).

## 11. Mod API
None needed for this addon: Mixin plus side fields inside `nestworld-compat-ae2`.

Pattern log for after Mekanism and one or two more mods: shared dynamic network + entry from
several regions + per-network serialization + topology changes + validate-then-retry identity.
If it repeats, a Core / Mod API construct can be designed from at least three real instances.

## 12. Open decisions for the owner
1. **Scope of T0.**
   - One addon-global topology lock: simple, and topology changes are rare.
   - Or a per-dimension lock.
   - Proposed: global until measured.
2. **SolarFlux participant (§2).** Measure first, then follow the domain or pin the panel's
   block-entity type when it is an AE2 node.
3. **Production policy for an out-of-order nested acquisition that fails `tryLock` (§6).** None
   is known in the installed code. Choose between:
   - fail-loud with a crash report, so the bug is visible;
   - or block anyway, logging and counting it, which risks a deadlock.
   - Proposed: fail-loud plus auto-pin of the offending block-entity type.
4. **Residual AE2 races outside the energy domain (§2)** — storage, pathing and P2P services —
   are not in this gate. Record them for MOD-COMPAT-1 follow-ups.

## 13. Next after approval
1. Implement `nestworld-compat-ae2`, as a separate Forge jar.
2. Run a reproduction harness that shows the race before (AE2-COMPAT-001 → Reproduction
   proposal).
3. Run the same workload with the addon: race = 0, plus the §18 correctness checks.
4. Run stress and soak, then the pinned-vs-addon A/B.
5. Pass the unpin gate (§20).
