# AE2-COMPAT-CLOSURE-001 — Applied Energistics 2: from KNOWN RISK to SUPPORTED / VERIFIED

Owner TZ received 2026-10-28, sections 0–69 complete. Normative rules from §61–69 used throughout:
- status scale (§63): DISCOVERED → CONFIRMED → CORRELATED → IMPLEMENTED → VERIFIED → FROZEN; forbidden without
  evidence: DISCOVERED→VERIFIED, CORRELATED→CLOSED, IMPLEMENTED→FROZEN;
- final closure matrix (§61) may contain no UNKNOWN / CORRELATED / STRONGLY INDICATED;
- N/A only for features absent/disabled in the production pack and declared unsupported (§62);
- two levels (§67): AE2 ENGINEERING VERIFIED (copies) and AE2 PRODUCTION VERIFIED (canary 24 h one island,
  48–72 h several, exact stack, no test drivers or timing-changing detectors, §66); SUPPORTED needs both;
- commit discipline (§64): detector/fixture → fix → evidence → docs; Core only for generic mechanisms (§65).

Status: **OPEN** — phase P0 (provenance + static-state audit) in progress.

## Status board (umbrella)

| domain | status | evidence |
|---|---|---|
| ENERGY | CLOSED / VERIFIED (prior) | addon ENERGY domain; regression in P6 |
| GRID-PHASE / staging | CLOSED / VERIFIED (prior) | a34eff745 |
| TICK MANAGER (queue, T2, lost wake) | **CLOSED / VERIFIED** (owner, TZ §5) | AE2-TICKMANAGER-RACE-001, `AIRIS-REPRO-2026-10-26.md` §6 |
| P2P safety H1/H2/H3, cross-owner fail-closed | CLOSED / VERIFIED (prior) | P2P-001 |
| P2P cross-owner **function** (P2P-FUNC-001) | OPEN — owner design gate (TZ §23) | — |
| static state (AE2-AUDIT-STATIC-001) | IN PROGRESS (first pass below) | this doc |
| grid lifecycle / topology invariant | OPEN | — |
| event bus | OPEN (one existing Core-side protection, see static table) | — |
| pathing, storage, crafting, spatial, statistics, overlay, QNB | OPEN | — |
| pins (AE2-PINS-001) | OPEN | — |

## P0.1 Source provenance (TZ §2)

| item | value |
|---|---|
| AE2 version | 15.4.10 |
| AE2 jar SHA-256 | `fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc` |
| upstream tag | `forge/v15.4.10` → commit `b4b08d9941e3faecb520d76be617629bb56661e1`, tree `cf5bfe1542ccc2665218db38c2ae45e1b9d26db1` |
| local source tree | `/root/ae2-src/forge-v15.4.10` (shallow clone of the tag) |
| jar ↔ tag correspondence | all 1455 top-level `appeng/` classes of the jar have a source file in the tag (5 source files absent from the jar are Fabric-only / `package-info`); for the 112 classes in `appeng/me/**`, `appeng/parts/p2p/**`, `appeng/hooks/ticking/**`, **0 of 3453** LineNumberTable entries point at a blank or comment line of the tag source; e.g. `TickManagerService:111-112` = `if (queue.poll() != tt) throw new IllegalStateException()` exactly as observed at runtime |
| confidence | STRONGLY INDICATED (no bit-for-bit rebuild of the jar) |
| jar manifest | no commit metadata (`Manifest-Version`, `MixinConfigs` only); class timestamps 2025-10-25 |
| Forge (copies) | 47.4.307-canary-core-306-ownership (Core 1b179775) |
| addon | `nestworld-compat-ae2` 0.1.0, jar `71a5d781e3308cf926123db43ba940fb51c5131ddf1e90184b6c6777beb233be` |
| Mixin target class SHA | to be added with gate vNext (TZ §57) |

The upstream `forge/1.20.1` HEAD is not used as evidence; all reads below are from the tag tree.

## P0.2 Static mutable state — first pass (TZ §7)

Method: javap over all 1873 `appeng/` classes of the pinned jar → static non-final fields (523, of which 52 are not
`static {}`/registry handles) and static final fields of mutable types (251); client-only packages excluded; each
remaining runtime candidate traced to its writers in the tag source. Type alone is not used as a verdict.

| field | use (tag source) | classification | addon / Core protection | next |
|---|---|---|---|---|
| `AEItemKey$InternedTag.INTERNED` (WeakHashMap) | get/put inside `synchronized (AEItemKey.class)` | **THREAD_SAFE** | — | none |
| `EncodedPatternItem.SIMPLE_CACHE` | only after `getClientLevel()` | **CLIENT_ONLY** | — | none |
| `Grid.nextSerial` (int ++) | debug `toString` / crash report only | **benign race** (debug label) | grids created under T0; addon orders locks by its own atomic `DomainLock` serial | none |
| `Grid.ITERATION_BUFFER` (one ArrayList for **all** grids) | `notifyAllNodes`: throws ISE if non-empty, then addAll / iterate / clear | **MAIN_ONLY** (source + runtime) | both callers run only from server-tick handlers: `EnergyService.onServerEndTick → publicPowerState` (lines 263/265, the only call sites) and `PathingService.onServerEndTick → postBootingStatusChange`; detector: 32 calls, 0 off-main, 0 concurrent. (First-pass suspicion withdrawn: `KERNEL.defer` keeps the thread, but the caller is already main.) | keep the addon main-affinity assertion |
| `MBCalculator.modificationInProgress` (static WeakReference) | set/cleared in `calculateMultiblock` and the clusters' `destroy()`; a second thread seeing a foreign flag silently skips its own recalculation, or both set it → ISE "A modification is already in-progress" | **DISCOVERED** (two-thread entry observed, overlap not yet) | none | detector (G, 5 min, period 10): 4509 entries — 1800 on `NestWorld-Region-0`, 2709 on Server thread (chunk 2 has no running region, its BEs tick on main) — 0 concurrent, 0 ISE; a tighter driver is blocked by AE2-BE-READY-001 |
| `FakePlayer.FAKE_PLAYERS` (WeakHashMap, unsynchronized) | `Platform.getFakePlayer` → planes / placement | **DISCOVERED** (no runtime call on Airis copies: 0 calls) | none | fixture with formation/annihilation planes in two regions |
| `TransformLogic.fluidCache / explosionCache / anyFluidCache` | `ItemEntityMixin` (item entity tick → region threads); IdentityHashMap `computeIfAbsent`, lazy init; caches cleared on recipe reload | **DISCOVERED** | none | detector: 40 607 calls, all `NestWorld-Region-0` via `ItemEntity.tick → canTransformInAnyFluid`; 0 concurrent (single active region). Needs a two-region item-entity fixture + `/reload` (writes happen only on (re)fill) |
| `GridEventBus.EVENTS` (HashMap) | `postEvent → getSubscriptions → computeIfAbsent` at runtime (first post of an unseen event class inserts) | **NEEDS_FIX (protected)** | Core legacy `static_collection_race_transformer` swaps it to a concurrent map (registered in production `coremods.json`) — AE2-specific protection living in Core, against the addon > Core rule | decide: keep as legacy exception or move into the addon |
| `QuantumBridgeBlockEntity.singularitySeed` (int ++) | QNB frequency = `time*100 + seed++ % 100` | low risk (collision needs same ms and lost increment) | — | document |
| `TickManagerService.MONITORING_ENABLED`, `AELog.*Enabled`, `PhaseSorting.ENABLE_CYCLE_WARNING` | config/debug flags | IMMUTABLE_AFTER_BOOT (by use) | — | confirm writers |
| registries: `GridServices.registry`, `Upgrades.*`, `StorageCells.*`, `PatternDetailsHelper.DECODERS`, `P2PTunnelAttunement.*`, `BlockEntityMoveStrategies.*`, `GridLinkables.registry`, `Settings.SETTINGS`, `AEBaseBlockEntity.REPRESENTATIVE_ITEMS`, `HotkeyActions.REGISTRY`, `EventFactoryImpl.ARRAY_BACKED_EVENTS` | written during mod init | IMMUTABLE_AFTER_BOOT (to confirm: no runtime writer) | — | writer scan |
| `VoxelShapeCache.CACHE`, `CompassService` cache | Guava `LoadingCache` | THREAD_SAFE | — | none |
| `CraftingService.CRAFTING_POOL` | crafting calculation executor (AE2's own async design) | by design — audit in AE2-CRAFTING-001 | — | P5 |

## P1 findings

### AE2-BE-READY-001 — CONFIRMED (new, addon ON)

Workload (copy G307AE2ON, AE2 addon NORMAL): a test driver on a region thread places and, one tick later, removes
`ae2:1k_crafting_storage` next to a crafting unit, in region #0 and region #1. Within 60 s: 6 tick-skips, then the
Core gave up catch-and-continue → full crash shutdown. Every report:

```
ClassCastException: AirBlock cannot be cast to AbstractCraftingUnitBlock
  CraftingBlockEntity.getUnitBlock(92) ← getItemFromBlockEntity ← onReady(106)
  ← ServerBlockEntityRepo$FirstTickInfo.callInit (addon nw$callInit wrapper) ← TickHandler.readyBlockEntities(408)
  ← onServerLevelTickEnd ← Server thread
```

Mechanism (source, tag b4b08d99 + addon): AE2 readies newly placed AE2 block entities on the **server thread**
(`TickHandler.readyBlockEntities`). The addon's `FirstTickInfoMixin` serialises `callInit` with removal through the
per-BE lifecycle lock and revalidates inside it (removed? same BE at pos? same block?). But vanilla
`LevelChunk.setBlockState(air)` on the owner region thread **first writes the new state into the section, then**
calls `removeBlockEntity → setRemoved` (where the addon takes the lock). So main passes the revalidation, the owner
thread flips the state, and `onReady` reads air. More generally, `onReady` (e.g. `calculateMultiblock`, neighbour
scans) reads world state of a region-owned chunk on main while its owner mutates it — an ownership violation by
AE2's design, not a narrow window only.

Fix options (owner decision — touches the Core boundary, §65):
- **R1 owner-thread readiness (preferred):** run `callInit` for a BE on the thread that owns its chunk. Needs a
  *generic* Core primitive "run on the owner of (level, pos) at the next owner tick" (allowed under §65: generic
  handoff primitive; no AE2 knowledge in Core). The addon then defers `FirstTickInfo.callInit` for region-owned
  BEs to their owner; main-owned BEs keep the current path.
- **R2 addon-only, narrower:** take the lifecycle lock *before* the block-state write when an AE2 BE is replaced
  (mixin at the head of `LevelChunk.setBlockState`), so state write + removal are atomic w.r.t. `callInit`. Closes
  this crash shape but not the general main-reads-owner-chunk problem (neighbour scans in `onReady`).
- not acceptable: holding the region chunk lock from main during `callInit` (§4: no waiting for another region
  under a world/chunk lock).

Owner decision 2026-10-28: **R1 APPROVED** (internal/privileged Core owner-dispatch primitive, not public Mod API;
non-blocking; executes only at the owner's safe point; re-resolves the owner after split/merge with bounded
reroute; Core knows nothing about AE2 and does not promise the BE still exists; addon re-resolves and revalidates
identity/generation and keeps a lifecycle state NEW → READY_PENDING → OWNER_ENQUEUED → INITIALIZING → READY,
removal → CANCELLED; ≤ 1 onReady per lifecycle generation). **R2 REJECTED** as final. Pre-fix evidence pushed
(99a2df54b, 3909f4ff1, 84024fc7e).

#### STOP before implementing R1 — cross-region neighbour discovery (pre-implementation audit)

`onReady` mixes two parts with different owners:
1. **BE-local**: own block state, orientation, multiblock membership (`CraftingBlockEntity.onReady` →
   `getUnitBlock`, `AEBaseBlockEntity.onReady` orientation write). Owner = the BE's region. This is where the
   confirmed CCE happens.
2. **Topology**: `AENetworkBlockEntity.onReady → getMainNode().create(level, pos)` → in-world connection discovery
   reads **neighbour** block entities (`level.getBlockEntity(neighbourPos)`), which may lie in another region.

Core fact (`Level.getBlockEntity` patch → `RegionChunkView.getBlockEntity`): on a RegionThread, a foreign position
within ghost depth of the border returns a **ghost snapshot (detached copy)** of the BE
(`BoundaryManager.getGhostChunk(...).getBlockEntity`); deeper foreign positions use a bounded `tryReadLock` and, on
timeout, an unsynchronised read. On the Server thread the read is raw and live (the confirmed race).

Consequence: running the whole `onReady` on the owner thread (R1 as stated) makes a node at a region border discover
its foreign neighbour through a detached copy → no connection, or a connection to a copy's node → AE2 grids
silently split at region borders (functional regression; same defect class H3 found for P2P). Today cross-border
connections form only because `onReady` runs on main with live (unprotected) reads.

Design options for the owner (R1 stays the frame; the question is the topology part):
- **R1-a split:** BE-local part on the owner thread (R1 primitive); node creation / connection discovery as a
  separate topology step that never reads foreign live state from the wrong thread: neighbours in the same region
  are connected on the owner; a neighbour in another region is connected by a two-sided handshake (each side
  publishes its node to its own owner; the connection is created under the addon's T0 when both sides have
  confirmed live, same-generation endpoints) — generic Core primitive unchanged, AE2 knowledge stays in the addon.
- **R1-b border-aware:** whole `onReady` on the owner when all six neighbours are owned by the same region (the
  common case, no border); for border BEs keep a main-thread topology step executed only at a Core safe point where
  both owning regions are not mutating those chunks (needs a generic Core "multi-owner safe point" primitive —
  larger Core change, not designed yet).
- not acceptable: R1 for everything, letting border nodes connect to ghost copies.

Owner decision 2026-10-28 (2): **R1-a APPROVED**; whole-onReady R1 REJECTED after the audit; R2 REJECTED as final;
R1-b REJECTED for now (multi-owner safe point = distributed barrier in the scheduler). Core addition: generic
owner-mailbox primitive only, no AE2 knowledge in Core. New gate **AE2-CROSS-REGION-TOPOLOGY-001** (below).

### AE2-CROSS-REGION-TOPOLOGY-001 — OPEN (pre-implementation audit)

Contract (owner):
- the protocol carries identity, never foreign live objects: dimension, BlockPos, Direction, lifecycle
  generation, expected endpoint kind; only the owner resolves its live BE / node;
- a connection A↔B exists only for two confirmed current generations; a stale handshake (generation moved, e.g.
  B removed and replaced at the same pos — ABA) must not create a connection;
- T0 is not a giant transaction: before code, audit what `GridConnection` creation / grid merge triggers
  (listeners, events, repath, node callbacks) and prove which part may run under T0;
- scope: initial onReady, neighbour reconnect, connection removal, node destruction, grid split/merge, chunk
  load/unload, region split/merge — every foreign-neighbour path uses the same ownership-safe model;
- acceptance: BE-READY (foreignOwnerWorldRead during local init 0, CCE 0, doubleInit/staleInit/initAfterRemove 0);
  topology (same/cross-region connect, disconnect, reconnect PASS; ghostEndpointUsed 0, staleGenerationAccepted 0,
  phantom/duplicate/lost connection 0; reference == NestWorld topology after drain; region split/merge, chunk
  unload/reload, simultaneous A↔B, A-B-C PASS; deadlock/livelock 0), plus the boundary fixture
  `cable A | REGION BOUNDARY | cable B` place/remove/replace/split/merge with the validator after every drain.

## Phase plan (proposal, ordered by evidence value)

- **P0** provenance ✓, static audit (finish writers scan; detector counters for the four NEEDS_EVIDENCE fields).
- **P1** fixtures + negative controls for confirmed static races (`ITERATION_BUFFER`, `MBCalculator`, `TransformLogic`,
  `FakePlayer`) → addon fixes; decide `GridEventBus` location.
- **P2** umbrella field inventory of `Grid`, `GridNode`, `GridConnection`, `GridEventBus`, `TickHandler` and the seven
  services (TZ §6); topology validator `/nwcompatae2 verify` (TZ §9, §48, no silent repair §49).
- **P3** grid lifecycle / pathing stress with reference comparison (TZ §8, §11–12), region split/merge (§53).
- **P4** storage + watcher accounting (TZ §13–15); **P5** crafting conservation (§16–17); energy/overlay regression
  (§18–19); statistics (§33); spatial (§31–32); QNB (§36, NOT EXERCISED if absent from the pack).
- **P6** lock audit table, wait graph, callback-under-lock and reentrancy gates (TZ §40–43).
- **P7** P2P-FUNC-001: design options A/B/C for owner decision **before any implementation** (TZ §23).
- **P8** pins inventory and one-by-one removal (TZ §44–46); gate vNext (§57–58); performance (§55–56).

#### Audit A — what a foreign neighbour looks like from a region thread (tag b4b08d99 + Core)

`InWorldGridNode.findInWorldConnections` (called from `GridNode.updateState`, i.e. `create/markReady`, colour
change, `CablePart/CableBusContainer.updateConnections` on neighbour change) resolves each exposed side with
`GridHelper.getExposedNode(level, neighbourPos, side)` → `level.getBlockEntity`. On a RegionThread a foreign
position within ghost depth returns `BoundaryManager.ChunkSnapshot.getBlockEntity` = `BlockEntity.loadStatic(NBT)`:
a fresh detached AE2 BE whose `ManagedGridNode` was never created → `getGridNode(side) == null`. Therefore today,
from a region thread: a cross-border neighbour is **invisible** (no new connection), and an existing cross-border
connection is **not destroyed** (`adjacentNode == null → continue`; `cleanupConnections` only drops sides that are
no longer exposed). Cross-border connections are created only by `onReady` on main (raw live read = the race).
Status of the owner's hypothesis: STRONGLY INDICATED by source; runtime confirmation = boundary fixture (P-T1).

#### Audit B — what `GridConnection.create` does, and what may run under T0

`GridConnection.create(a, b, dir)` (addon: `GridPhaseGate.topology` + `KERNEL.guardedStrict(Specs.topology)` = T0 +
both grids' domain locks):
1. `new GridConnection(a, b, dir)` — grid-domain object;
2. `mergeGrids(a, b)` → `Grid.create(a)` or `setGrid` on one node, or `GridPropagator` → `setGrid` on **every node of
   the losing grid**; each `setGrid` = `oldGrid.saveNodeData + remove` → `newGrid.add` → every service's
   `removeNode/addNode` (storage, energy, pathing, ticking, crafting, P2P, statistics, spatial) — grid-domain state;
   then `callListener(onGridChanged)` and `notifyStatusChange` (**deferred by the addon to the end of the section,
   same thread**);
3. `pathing.repath()` — sets flags only (work happens on the next server-tick end, main);
4. `sideA/sideB.addConnection(c)` → `callListener(onInWorldConnectionChanged)` on **both** sides — **not deferred**,
   runs under T0.

Listener callbacks are host (BE/part) code: `saveChanges` → `setChanged`, `markForUpdate` → `sendBlockUpdated`,
part state/model updates — world mutations of the host's chunk. So (1)–(3) are grid-domain and may run under T0 on
any thread (existing, verified T0/domain protocol); (4) and the deferred (2) listeners touch the hosts' world
state and must run on each host's owner, outside T0.

New finding **AE2-NODE-LISTENER-OWNER-001 — DISCOVERED:** node listener callbacks run on whichever thread mutates
the topology (A's region for B's host; main for every host today), i.e. cross-owner world mutation. Exists today
independently of R1-a (every grid merge/split). Impact to be measured (Core already hardened
`ChunkHolder.blockChanged`; `setChanged` is a flag write). Folded into this gate because R1-a's commit step must
not create more of it.

#### Design R1-a (for owner review — no code yet)

Core (generic, no AE2 knowledge):
- **OwnerDispatch** (internal, `net.nestworld.region`, not Mod API): `enqueueForOwner(ServerLevel, BlockPos, Runnable
  op, Consumer<Terminal> onTerminal)`; non-blocking; new mailbox type `OWNER_TASK(REGION_THREAD)` drained in
  `RegionThread.runOneTick` with the other mailbox types (under the region write lock = the owner's safe point);
  at drain the owner is re-checked (`grid.getRegionFor(pos) == this region`), otherwise the task becomes an orphan
  and is re-resolved by position (`MailboxRetargeting`, bounded by `MAILBOX_MAX_REDELIVERIES`); a position owned by
  no region runs on main at the dimension's main safe point; terminal kinds (`REDELIVERY_LIMIT_DROPPED`, level
  unloaded) call `onTerminal` on main — never silent. Accounting via `MailboxAudit` (accepted = applied + terminal).

Addon (all AE2 knowledge):
- **lifecycle generation** per AE2 BE (`NwAeBlockEntity`): bumped on load/placement and on removal; state NEW →
  READY_PENDING → OWNER_ENQUEUED → INITIALIZING → READY, removal → CANCELLED; `onReady` ≤ 1 per generation.
- **local init on the owner:** `FirstTickInfo.callInit` on main: owner == main → current path; else
  `enqueueForOwner(pos, ownerInit(gen))`; `ownerInit` re-resolves the live BE at pos, checks generation/state/block,
  then runs AE2's `onReady` on the owner thread.
- **neighbour resolution:** wrap `GridHelper.getExposedNode` inside `findInWorldConnections`: neighbour owned by
  the current thread → AE2's path; foreign owner → return `null` to AE2 and post a handshake instead (no ghost read).
- **endpoint registry** (grid-domain, guarded by T0): `(dim, pos, side, generation) → GridNode`, written only by the
  endpoint's owner when its node is live, removed on removal/generation bump.
- **handshake:** A's owner posts `Offer(dimA, posA, sideA, genA, posB)` to owner(posB). B's owner resolves its live
  BE, checks the reciprocal side, colour compatibility and its own generation, publishes its endpoint, then commits:
  under T0, re-read both registry entries — both present with the offered generations → `GridConnection.create`
  (grid-domain part only); otherwise STALE (counted, nothing created). Duplicate offers from both sides are
  harmless: commit is idempotent (`hasConnection` check under T0).
- **listener owner-dispatch:** `onInWorldConnectionChanged`, `onGridChanged`, `notifyStatusChange` collected during
  the T0 section and, after it, delivered per host through `enqueueForOwner(host pos)` (own host, same owner → run
  inline after the section).
- **removal / reconnect / split:** node destroy runs on its owner (setRemoved); `GridConnection.destroy` is
  grid-domain under T0; the other side's listener goes through owner-dispatch. Reconnect on neighbour change uses
  the same `getExposedNode` wrapper → same handshake.

Fixtures planned (before closure): P-T1 boundary fixture `cable A | BOUNDARY | cable B` (place/remove/replace/split/
merge, validator after each drain; first run on the current addon = runtime confirmation of Audit A), ABA
(B removed + replaced during a handshake), simultaneous A↔B, A-B-C chain, region split/merge, chunk unload/reload,
BE-READY negative control (existing driver) and positive control.

Open questions for review: (Q1) listener owner-dispatch changes the timing of AE2 listener callbacks from
"synchronous inside the topology change" to "next owner safe point" — acceptable, or must some listener stay
synchronous? (Q2) endpoint registry as grid-domain state under T0 — acceptable placement? (Q3) main-owned
positions (no region) keep AE2's direct path?
