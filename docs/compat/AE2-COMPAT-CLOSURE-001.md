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
| grid lifecycle / topology invariant | OPEN — R1-a scope 1 (Core primitive) VERIFIED on copy; scopes 2–3 pending | c0400dd87, f040ed31c |
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

#### Owner decisions 2026-10-28 (3) — R1-a design review

```
AE2-CROSS-REGION-TOPOLOGY-001
boundary ghost lookup:              STRONGLY INDICATED → targeted live fixture REQUIRED (before R1-a code)
cross-owner GridNode listener exec: CONFIRMED BY SOURCE; runtime damage DISCOVERED (AE2-NODE-LISTENER-OWNER-001)
callback model:                     DEFER TO HOST OWNER — APPROVED; callbacks synchronous under T0 — REJECTED
endpoint registry:                  APPROVED — scope = per-server AE2 compatibility topology domain, protection = T0
same-owner topology:                direct path allowed
cross-owner topology:               handshake required
MAIN:                               an owner domain; direct path only when BOTH endpoints are MAIN-owned
CREATE / DESTROY / SPLIT:           all in gate scope
```

- **Q1 callbacks:** no host callback that can reach world / BE / capability runs under T0 (Interface
  `onGridChanged → InterfaceLogic.gridChanged → notifyNeighbors`, cable `onInWorldConnectionChanged →
  markForUpdate`). Model: T0 mutates AE2 topology/domain state and records `TopologyCallbackIntent {nodeIdentity,
  lifecycleGeneration, topologyEpoch, sequence, type}`; after unlock each intent is delivered at the host owner's
  safe point after revalidating the host generation. Causal order per node within one topology transaction is
  preserved (`onGridChanged` → power-state → `onInWorldConnectionChanged`). If AE2 cannot be called without
  synchronous listeners, the addon intercepts/defers listener **delivery**; it does not abandon T0.
- **Q2 registry:** not grid state (before commit there is no single grid owner). Server-scoped topology domain
  under T0: endpoint registry, pending handshakes, topology epochs. Key `(dimension, BlockPos, Direction,
  lifecycleGeneration)`; `EndpointRecord {position identity, generation, live GridNode published by its owner,
  publication epoch, LIVE/REMOVED}` — never a ghost BE or a foreign capability. Commit under T0: both records
  current, directions reciprocal, both nodes live, connection absent → mutate → record intents → unlock.
  Cleared on BE/node removal, chunk unload, level/server shutdown, generation replacement. Invariant: no
  `EndpointRecord` outlives its lifecycle generation.
- **Q3 main:** the Core primitive treats MAIN as an owner. Topology rule: `owner(source) == owner(target)` → local
  direct path (MAIN–MAIN or Region A–Region A); otherwise (MAIN↔Region, Region↔Region) → handshake.
- **Allowed under T0:** AE2 topology state (Grid/GridNode/GridConnection structure), service add/remove/staging
  already in the T0 contract, endpoint registry, generation/epoch accounting, callback-intent recording.
  **Forbidden under T0:** host listener execution, world reads/writes, foreign `getBlockEntity`, capability access,
  `notifyNeighbors`, `sendBlockUpdated`, arbitrary mod callbacks.
- `GridConnection.destroy` (repath, `removeConnection` on both sides → `onInWorldConnectionChanged`, `validateGrid`
  → split) uses the same protocol as create.

#### Boundary fixture P-T0 (observation only) — Audit A CONFIRMED; Audit C opened

Copy G307AE2ON (addon NORMAL). A = `ae2:interface` (31,120,100) [chunk 1, grid region #0], B = `ae2:energy_acceptor`
(32,120,100) [chunk 2, grid region #1 — no running thread]. Test-only read-only probe block (`nestworld_mekchurn:probe`)
resolves `IInWorldGridNodeHost.getGridNode(WEST)` of B each tick on the thread that ticks the probe; `probemain`
does the same on the Server thread and checks A↔B.

| lookup of B | thread | BE | node |
|---|---|---|---|
| main command | Server thread | `EnergyAcceptorBlockEntity@4399a301` (live) | `InWorldGridNode@4311e45e`; A↔B connected = **true** (created by main `onReady`) |
| probe at (−8,105,114) [region #0 interior] | `NestWorld-Region-0` | a **different identity every tick** (`@41d6887a`, `@3136d9ee`, …) — ghost `loadStatic` copies | **null** in 402/402 lookups |

→ Audit A (region-thread view of a foreign-region BE = detached copy without a node) **CONFIRMED** at runtime.

Side observation that changes the owner model (**Audit C — BE execution owner**): the probe placed first at
(30,120,100) [chunk 1] and the earlier drivers at x=17/18 [chunk 1] ticked on the **Server thread**, although
chunk 1 belongs to grid region #0. Core routes BE ticks by `NestworldRegionSystem.runBlockEntityPhase`: interior of
a region (> `BORDER_BAND_CHUNKS` = 2 chunks from its edge) → region thread; border band or no region → main (regions
parked then); `cascadeSafeBeIds` types → region thread even in the band. `NestworldOwnership.isCurrentThreadOwner`
answers by grid region only and does not reflect the band. Consequences:
1. the R1-a Core primitive must resolve the **BE execution domain** (the same routing as the BE phase: interior →
   region, band/outside → main, cascade-safe → region), not the grid region — otherwise a band BE would be
   initialised on a region thread while it ticks on main;
2. adjacent AE2 endpoints across a **grid-region** border are both in border bands → both MAIN under the default
   band (no ghost reads for adjacent pairs); ghost reads require a cascade-safe AE2 type or band 0;
3. the realistic cross-owner adjacent pair is **interior (region thread) ↔ band (main) inside one grid region**;
   there `getBlockEntity` from the region thread is a raw live read of a main-executed BE (same grid region), so
   the hazard is concurrency with main, not a ghost copy. Whether main mutates band chunks while the region runs
   (free-running) decides if that pair is racy → next fixture (P-T1: interior/band pair).

#### Owner decisions 2026-10-28 (4)

```
AE2-BOUNDARY-GHOST-001                      CONFIRMED
  scope: a foreign-region getBlockEntity from a region thread may return a detached snapshot without an AE2
  ManagedGridNode. NOT: "adjacent AE2 nodes normally see ghost neighbours" (refuted for the default band).
AE2-BE-READY-001 / R1-a
  generic Core primitive:              APPROVED — name/semantic: BLOCK ENTITY EXECUTION DOMAIN
                                       (enqueueForBlockEntityDomain(level, pos, op, onTerminal);
                                        resolveBlockEntityExecutionDomain(level, pos, type)), not region ownership
  source of truth:                     the SAME resolver runBlockEntityPhase uses (no duplicated band arithmetic):
                                       interior → REGION, band → MAIN, outside region → MAIN, cascade-safe in band → REGION
  re-resolution before execution:      REQUIRED (D0 at enqueue, D1 before run; D0 != D1 → bounded reroute)
  AE2 lifecycle generation validation: REQUIRED (routing and lifecycle validation are separate layers)
  NestworldOwnership:                  unchanged ("which logical region owns pos?"); never used for BE execution
  callbacks:                           delivered to the current BE execution domain (band cable → Server thread)
  full cross-owner topology handshake: DESIGN RETAINED, IMPLEMENTATION BLOCKED ON P-T1
P-T1:                                  REQUIRED NEXT — timeline of region BE work / region entity mutation /
                                       main band BE phase / AE2 readyBlockEntities with overlap counters
```

#### P-T1 result — execution windows DO overlap (decision input)

Copy G307AE2ON, 5 min: region-interior crafting driver (−21,114,130, period 10 → AE2 readiness on main), region item
drivers and item entities, band machines on main. Observation-only timeline (`/nwphase`): a window = Core
`tickBlockEntityList` on main (MAIN_BAND_BE) / on a region thread (REGION_BE), `RegionThread.tickEntities`
(REGION_ENTITY), AE2 `TickHandler.readyBlockEntities` (AE2_READY); overlap = a window starts while another kind is
active on another thread.

| pair | overlap events / 5 min | windows |
|---|---|---|
| MAIN_BAND_BE ~ REGION_BE | **13** | 6 154 / 30 765 |
| MAIN_BAND_BE ~ REGION_ENTITY | **47** | 6 154 / 6 153 |
| REGION_BE ~ AE2_READY | **20 990** | 30 765 / 24 616 |
| REGION_ENTITY ~ AE2_READY | **154** | 6 153 / 24 616 |

Conclusions:
1. With free-running regions the main border-band BE phase is **not** exclusive with region execution (rare but
   real overlap). The compact hypothesis "Core already serialises band ↔ region" is refuted; interior(region) ↔
   band(main) adjacent pairs can mutate concurrently.
2. AE2 readiness on main overlaps region BE work almost continuously.

Additional failures caught by the driver on `NestWorld-Region-0` (same root: AE2 `onReady` on main concurrent with
the BE's owner):
- `IllegalStateException: The node has already been initialized` — region neighbour update → `MBCalculator.
  calculateMultiblock → CraftingCPUCluster.updateStatus → CraftingBlockEntity.updateSubType →
  AENetworkBlockEntity.onGridConnectableSidesChanged → ManagedGridNode.setExposedOnSides → getInitData` took the
  "not yet created" branch while main had created the node in `onReady` (stack:
  `evidence-ae2-topology/duplicate-connection-stack.txt`);
- `IllegalStateException: Connection between node [...] already exists` — two threads passed
  `findInWorldConnections`' "no connection yet" check (outside T0) and both called `GridConnection.create`; the
  second fails inside T0 (TOCTOU between discovery and commit). Stack not captured in the first run (driver stored
  only the message); recorded as CONFIRMED-by-message, stack capture pending.

Implication for R1-a scope:
- moving AE2 readiness (`onReady`) into the **BE execution domain** removes the dominant main↔owner class
  (CCE, already-initialized, duplicate create from onReady) for interior BEs: readiness and neighbour updates then
  run on the same thread;
- adjacent **interior(region) ↔ band(main)** pairs still have two executing threads → topology commit must be safe
  across owners: at minimum discovery-check and create must be atomic w.r.t. both endpoints (idempotent commit under
  T0: "already connected" = keep existing, as AE2's own discovery does), and listener callbacks go to the host's
  execution domain;
- ghost copies (Audit A) only matter for cascade-safe AE2 types or band 0 — the handshake stays the design for that
  case and for interior↔band if the idempotent commit proves insufficient.

#### Owner decisions 2026-10-28 (5) — after P-T1

```
AE2-BE-READY-001                          CONFIRMED (3 symptoms: AirBlock CCE, "node has already been initialized",
                                          onReady ↔ owner topology race) — not closed until negative/positive
                                          fixtures exist for all three
AE2-TOPOLOGY-TOCTOU-001                   CONFIRMED
main-band ↔ region BE overlap             CONFIRMED
readyBlockEntities ↔ region BE overlap    CONFIRMED / HIGH FREQUENCY (20 990 / 5 min)
Core implicit serialization hypothesis    REJECTED BY P-T1
R1-a scope 1 (canonical resolver + primitive)   APPROVED
R1-a scope 2 (onReady in the BE execution domain) APPROVED
R1-a scope 3 (cross-owner commit)               APPROVED with atomic ensureConnection semantics under T0
full generation handshake                 DEFERRED, not rejected (ghost endpoint, cascade-safe across an owner
                                          boundary, band 0, future routing, async publication, ABA)
callback delivery                         REQUIRED: record under T0, deliver after unlock in the host's CURRENT BE
                                          execution domain, re-resolved before delivery
```

- **Canonical resolver:** `resolveBlockEntityExecutionDomain(...)` is the single source of truth and
  `runBlockEntityPhase` itself uses it (interior → REGION, band → MAIN, outside → MAIN, cascade-safe → REGION,
  pinned → MAIN / pinned domain, and any other existing BE-phase exception). `enqueueForBlockEntityDomain`
  re-resolves before execution with bounded reroute.
- **`ensureConnection(A, B, direction)` under T0:** revalidate A live, B live, A ≠ B; exact existing A↔B →
  `EXISTING`; conflicting/inconsistent connection → invariant failure (never masked); else create exactly once →
  `CREATED`. A duplicate-connection `IllegalStateException` is never converted to success. Minimal lifecycle
  validation now: a node destroyed/replaced between discovery and T0 is not accepted.

#### R1-a scope 1 — Core primitive implemented (2026-10-28)

Commits (§64): `c0400dd87` feat(core) resolver + primitive · `f040ed31c` test(core) fixtures · this docs/evidence
commit. Generic Core mechanism, no AE2 knowledge (§65).

- **Canonical resolver:** `blockEntityTypePolicy(pins, typeId)` + `blockEntityRegionFor(policy, cx, cz)` in
  `NestworldDimensionRegion`; `runBlockEntityPhase` buckets with exactly this pair (behavior unchanged), and
  `BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain(level, pos, type)` composes the same pair. Policy
  covered: pinned → MAIN (incl. mod-namespace pins), cascade-safe → grid region (band included), interior → REGION,
  band → MAIN, no region → MAIN, unmanaged level → MAIN. The only other BE-phase exception — a region bucket dropped
  for one tick on backlog — skips, it does not move work: the domain stays that region.
- **`enqueueForBlockEntityDomain(level, pos, type, op, onTerminal)`:** non-blocking, never inline. REGION: a
  `BE_DOMAIN_TASK` mailbox leg drained in `RegionThread.runOneTick` under the region write lock (same lock and
  thread as that region's BE work rounds; split/merge excluded). MAIN: per-dimension queue drained by `tick()` after
  both retargeting passes. Unmanaged level: server `TickTask`. Re-resolved before execution in every domain; bounded
  reroute (`Task.redeliveries` across all legs, cap `MAILBOX_MAX_REDELIVERIES`) → `REDELIVERY_LIMIT_DROPPED` on the
  server thread, op not run; `LEVEL_GONE` for main-queued tasks at dimension shutdown; op exceptions contained.
- **Mailbox transport:** a leg whose destination retired is closed with the new explicit audit outcome `handedOff`
  and re-resolved by the domain (no new `MailboxLifecycle.Terminal`). C11 balance now
  `accepted − applied − terminal − handedOff − inFlight`.

Evidence — isolated copy `H401BEDOM` (pristine Airis copy + Core `47.4.401` built from the tree committed as
`f040ed31c`, installer sha256 `318a2ddf…a0eb6`, AE2 addon 0.1.0; netns, taskset 0-2, `-Dnestworld.mailboxAudit=true`,
free-running on, band 2). Log: `/root/airis-2026-10-25/evidence-be-domain/fixtures-401-20261028-1022.log`.

```
/nestworld testmutate bedomain all   run 1: pass=37 fail=0 skip=1   run 2 (after mailbox suite churn): pass=37 fail=0 skip=1
  interior          op on NestWorld-Region-0, domainAtRun=REGION#0
  band              grid owner r0, op on Server thread (MAIN)
  pinned            MAIN; untyped probe in the same chunk stays REGION#0
  cascade           band position, op on NestWorld-Region-0
  unmanaged         Nether → Server thread via server task, runsInsideEnqueue=0
  never-inline      runsInsideEnqueue=0, then once on MAIN
  pin-change        REGION#0 → pinned → MAIN, rerouted+1 (consumer-side reroute, no retirement)
  split             r0 → [r2,r3], now REGION#2, op on NestWorld-Region-2, rerouted+1, C11 handedOff=1
  merge             MAIN → merged r6, op on NestWorld-Region-6, rerouted+1
  limit             REDELIVERY_LIMIT_DROPPED on main, runs=0
  failure           throwing op contained (opFailures+1), next task runs
  outside           SKIP — the grid tiles the managed dimension; no-region MAIN reachable only via unmanaged level
  every scenario    beDomain pending back to 0; C11 unaccounted=0 balance=0 duplicates=0 misrouted=0
/nestworld testmutate mailbox all    (CORE-MAILBOX-MERGE-001 regression) pass=69 fail=0 skip=0
```

Fixture notes (honest scope): the first split attempts on a freshly started copy were skipped by the documented
split/merge lock guard (region 0 busy during startup tick spikes); the fixture now retries such passes and requires
every abandoned attempt's probe to have run exactly once. Split/merge use deterministic midpoint cuts — the
entity-median cut on the restored layout put the merge probe on the merged region's own edge (band), which proved
nothing.

Status: R1-a scope 1 **IMPLEMENTED / VERIFIED (engineering, copy)**. AE2-BE-READY-001 stays CONFIRMED (not closed):
closure needs scope 2 (onReady in the BE execution domain) with negative/positive fixtures for all three symptoms.
Next: scope 2 in the AE2 addon (readiness via the primitive, lifecycle states, generation validation), then scope 3
(`ensureConnection` under T0, callback delivery in the host's current BE execution domain, destroy on the same path).

#### Owner decisions 2026-10-28 (6) — scope 1 accepted; leg vs task accounting

Pushed: `041d1fa16`, `c0400dd87`, `f040ed31c`, `651ba6891`. Approved: Core primitive design, `handedOff` mailbox
outcome, C11 balance including `handedOff` — **with the explicit leg-vs-task distinction below**.

Two separate invariants (normative):

```
MAILBOX LEG INVARIANT (MailboxAudit, per audit id = per leg)
  postedLegs = applied + handedOff + terminalLegOutcomes + inFlight      (quiescence: inFlight = 0, unaccounted = 0)
  each posted leg has exactly one outcome: APPLIED | HANDED_OFF | existing terminal (TARGET_NOT_FOUND, ...)

BE DOMAIN TASK INVARIANT (BlockEntityExecutionDomain, per logical task)
  createdTasks = executedTasks + terminalDroppedTasks + outstandingTasks (quiescence: outstandingTasks = 0)
  a task ends ONLY by: executed exactly once | explicit terminal drop (REDELIVERY_LIMIT_DROPPED, LEVEL_GONE)
  handedOff does NOT appear here; rerouted counts moves, not outcomes
```

`HANDED_OFF ≠ task terminal.` A logical task is a chain of legs (`leg#1 HANDED_OFF → leg#2 HANDED_OFF → leg#3
APPLIED + executed`). A clean mailbox `balance=0 unaccounted=0` alone cannot exclude "old leg handedOff → re-route
lost"; the task invariant does (`outstanding > 0` at quiescence). The fixtures assert both per scenario
(`beDomainAccounting`: `pending` back to baseline; `C11`). Recorded also at `MailboxAudit.recordHandedOff` and in the
`BlockEntityExecutionDomain` accounting comment.

Also confirmed by owner: MAIN is not modelled as a fake region (audit/topology stay truthful); unmanaged level →
`server.tell(TickTask)`, never inline (the caller thread is not a hidden fourth domain); one canonical policy for
`runBlockEntityPhase` and the primitive, pins/pinmod included.

```
R1-a / Scope 1 — BLOCK ENTITY EXECUTION DOMAIN
Implementation           IMPLEMENTED
Deterministic fixture    VERIFIED        Split retarget   VERIFIED     Merge retarget   VERIFIED
Pin retarget             VERIFIED        MAIN routing     VERIFIED     Unmanaged level  VERIFIED
Redelivery limit         VERIFIED        Failure isol.    VERIFIED     Never-inline     VERIFIED
Mailbox regression       69/69 PASS
BE-domain fixture        37 PASS / 0 FAIL / 1 explicit SKIP (outside: unreachable, covered by unmanaged)
Mailbox accounting       balance=0 unaccounted=0 misrouted=0 duplicates=0
Status                   ENGINEERING VERIFIED
```

Next: **AE2 R1-a scope 2 only** (scope 3 not touched in parallel). Commit chain: detector/fixture AE2-BE-READY →
addon lifecycle state/generation → route `onReady` through `enqueueForBlockEntityDomain` → evidence → docs closure.
Acceptance, each symptom separately: AirBlock `ClassCastException` → 0; "The node has already been initialized" →
0; duplicate `GridConnection` from the onReady race → 0 via scope 2, **or** explicitly left to scope 3 if it still
reproduces through interior ↔ MAIN-band topology activity (not to be closed silently under scope 2).

#### Scope 2 STOP (2026-10-28) — AE2 is pinned in the production configuration

Detector `430fd1331` (`/nwbeready`). Facts:

- **AE2 is mod-pinned on the real island.** `world/data/nestworld-pinned-mods.txt` = `ae2, edexpandere, exdeorum,
  mekanism` in the pristine Airis copy and in G307AE2ON (file unchanged since 2026-10-14; auto-pin of 2026-10-06,
  `TickHandler.onServerTickEnd`, pre-addon). With the canonical policy (pins included, owner requirement) **every
  AE2 block entity's execution domain is MAIN**. CONFIRMED (file + resolver on H: all `ae2:crafting_storage`
  positions → MAIN).
- **The BE-READY repro on G ran under that pin.** Readiness was already on its BE execution domain (MAIN). The
  race is MAIN (pinned AE2 domain) vs the **region thread that owns the chunk** (driver placement/removal).
  Routing `onReady` through `enqueueForBlockEntityDomain` is a no-op for this configuration. CONFIRMED.
- **Pin leak:** a pin covers ticking only. AE2 block callbacks still run on region threads (`neighborChanged →
  MBCalculator → updateSubType → setExposedOnSides`, S2 stack below). CONFIRMED.

Negative baseline, production pins (H401BEDOM, Core 401, addon 0.1.0, detector 430fd1331; storage at
(-20,115,130) and (200,115,200): storage domain MAIN (pinned), driver domain REGION#0 / REGION#1; period 1, 242 s;
evidence `/root/airis-2026-10-25/evidence-be-ready/neg-prodpins-addon010/`):

```
ready calls=4426 onMain=4426 offMain=0 offDomain=3 (solarflux:solar_panel, not pinned, readied on main)
S1 CCE                    5   ownInit 5        Server thread   (onReady reads AIR placed by the region thread)
S2 already initialized    2   foreignInit 2    NestWorld-Region-#  (region-thread neighborChanged while main readies)
S3 duplicate connection   0   (in 242 s; seen on G in P-T1)
alive=1 crashNew=4 (tick-skipped) toggles=9701
```

Invalid runs (quarantined in `evidence-be-ready/invalid/`, not evidence): first detector build broke
`ManagedGridNode` loading (8 crash reports, 10 spurious BE auto-pins on H — pin files restored from the pristine
copy); one run picked positions with the storage type for the driver (all MAIN, no race).

**Decision needed (owner)** — scope 2 as approved assumes unpinned AE2:

- **(A) Supported config = `ae2` unpinned** (AE2-PINS-001): the addon is the region-safety layer; scope 2 applies
  as designed. Requires a separate pin-policy closure with its own evidence (the 2026-10-06 pin reason is the
  tick-end grid crash class that TICKMANAGER now covers — HYPOTHESIS until shown).
- **(B) Supported config = `ae2` pinned:** scope 2 does not address BE-READY; the fix becomes a **generic Core pin
  completeness** gap (a pinned type's block callbacks / BE create-remove triggered by region-thread writes run off
  its domain) — new Core work, design first.
- **(C) both:** scope 2 implemented domain-generically (correct for either config) + a pin-leak item tracked
  separately; BE-READY closure scoped per config.

#### Owner decisions 2026-10-28 (7) — option C

```
R1-a Scope 2                     APPROVED — domain-generic (no "if AE2 pinned"; canonical Core policy only)
Production AE2 pin               KEEP FOR NOW
AE2 pin removal                  BLOCKED ON AE2-PINS-001
Pin completeness                 NEW CORE GATE REQUIRED (CORE-PIN-DOMAIN-COMPLETENESS-001)
Pinned production BE-READY       NOT CLOSED BY SCOPE 2 ALONE
Unpinned / integrated readiness  SCOPE 2 IS THE CORRECT FIX
Pushed                           63888f47c, 430fd1331, 545e09fb4
```

Two distinct problems (separated by the detector):
1. **readiness outside its BE execution domain** (unpinned AE2, integrated hosts — e.g. `solarflux:solar_panel`
   offDomain=3 under production pins) → scope 2;
2. **correct pinned readiness domain (MAIN) + a foreign callback entering the same AE2 lifecycle concurrently**
   (region-thread `neighborChanged → MBCalculator`, S2) → not a readiness-domain problem.

Scope 2 flow (every host alike — unpinned AE2 → REGION or MAIN, pinned AE2 → MAIN, SolarFlux / other AE2 hosts →
their actual domain): `FirstTickInfo.callInit → resolve current BE → enqueueForBlockEntityDomain → re-resolve →
validate lifecycle/generation → call init exactly once`.

Status split after scope 2 (no false CLOSED): `R1-a scope 2 primitive semantics: VERIFIED`, `AE2 readiness
off-domain execution: VERIFIED FIXED`, **`AE2-BE-READY-001 (production pinned AE2): STILL OPEN`** — remaining path
REGION callback → pinned AE2 state on MAIN.

```
AE2-PIN-LEAK-001
  Observed                                  CONFIRMED
  Pinned AE2 BE tick                        MAIN
  AE2 block callback execution              REGION possible
  Safety consequence                        CONFIRMED (S2 occurs through this path)
  Violates the current documented pin contract?   TO AUDIT
```

**CORE-PIN-DOMAIN-COMPLETENESS-001** (Core gate, no code before the contract): first establish what a pin means —
"all execution of this mod is MAIN-only" (then tick→MAIN + neighborChanged→REGION is a Core contract violation) vs
the historical "BE/entity tick of this mod is not region-parallel" (then it is a limited contract that proved
insufficient for AE2). Only after that, audit entry paths: `Block.onPlace`, `onRemove`, `neighborChanged`,
`updateShape`, BE lifecycle, capability callbacks, scheduled ticks, events, entity interaction, redstone/comparator
updates. Pin scope is not extended before the formal contract exists.

**AE2-PINS-001** (pin-policy gate): pin ON + addon vs pin OFF + addon over the existing fixtures (TickManager,
Energy, BE-READY, topology churn, storage/interface workload, P-T1). Pin removal only on pin OFF + addon → 0 known
invariant violations and 0 historical race reproductions. Target production state: AE2 addon + AE2-specific pin =
0 — a goal, not a current decision. The 2026-10-06 auto-pin reason ≈ TickManager race is not sufficient.

#### Scope 2 clean A/B matrix (2026-10-28)

Rig: H401BEDOM (pristine Airis copy, Core 47.4.401, detector 430fd1331, churn driver period 1, 240 s). Every run
starts from a **fresh copy of the pristine world** (pins, layout; no leftover drivers). Storage at (-20,115,130) and
(200,115,200), drivers at x+1 → REGION#0 / REGION#1. Test-only config "ae2 unpinned": `ae2` removed from
pinned-mods **and** its carried auto-pin count (`mod:ae2=2` is in the pristine counts file and re-pins `ae2` at every
boot), `-Dnestworld.autoPin=false` (no mid-run re-pin). Production config: pristine pins, autoPin on. Pins unchanged
in every run. Addon 0.2.0 = `1140d6a36` + `2659098dd` (jar sha256 `0eb2da64…5a31`). Evidence:
`/root/airis-2026-10-25/evidence-be-ready/<run>/`; superseded/invalid runs with reasons in `invalid/`.

```
run                     config      addon   alive   crash  readiness (calls / onMain / offDomain)   S1   S2           S3
neg-prodpins-addon010   prod pins   0.1.0   242 s   5      4739 / 4739 / 11 (solarflux)              4    2 (1 fI,1 nI) 0
neg-unpinned-addon010   ae2 unpin   0.1.0   DIED~150s 6    2345 / 2345 / 2242   (at t+106 s)         6    0            0
pos-unpinned-addon020   ae2 unpin   0.2.0   242 s   0      489 / 103 / 0  (386 on region threads)    0    0            0
pos-prodpins-addon020   prod pins   0.2.0   241 s   6      4544 / 4533 / 0  (11 solarflux deferred)  4    2 (2 fI)     0
fI = foreignInit (main inside readiness), nI = noInit (no readiness running anywhere)
```

Addon accounting pos-unpinned: readyInDomain 103 + readyDeferred 4972 (readyRanInDomain 4971, one in flight at the
snapshot) → 4585 `initCancelled` at revalidation (host already removed by the driver — the AirBlock-CCE situation,
now cancelled instead of crashing) + 489 executed `onReady` (= detector 489). readyStale / Dropped / EnqueueFailed /
InitFailed / RequeuedNotTicking = 0. pos-prodpins: 4542 in domain (MAIN, pinned), 11 deferred → 11 ran in domain.

Crash classes outside readiness-domain: `MBCalculator` "modification already in-progress" ISE — neg-prodpins and
pos-prodpins (one each, readying (201,115,200)); `ConcurrentModificationException` in `PathingService.
calculateAdHocChannels → CraftingBlockEntity.getMultiblockNodes` ("Ticking grid on end of server tick", main) —
pos-prodpins. `nwae2static` MB_MODIFICATION concurrent entries: 25 / 2 / 23 (neg-prodpins / pos-unpinned /
pos-prodpins).

Statuses:

```
R1-a Scope 2 (domain-generic readiness)            VERIFIED (engineering, copy) for unpinned / integrated hosts
  readiness off-domain execution                   VERIFIED FIXED   (2242 → 0 unpinned; 11 → 0 solarflux under pins)
  readiness-origin S1/S2/S3 (unpinned)             6/0/0 → 0/0/0
production pinned configuration                    PARTIALLY EFFECTIVE (integrated hosts only; AE2 itself already MAIN)
AE2-BE-READY-001 (production, ae2 pinned)          STILL OPEN — S1 4, S2 2 with 0.2.0: REGION callback / region-thread
                                                   placement vs pinned AE2 lifecycle on MAIN (AE2-PIN-LEAK-001)
S2 attribution                                     readiness-in-wrong-domain: 0 after 0.2.0; remaining S2 = main
                                                   readiness ↔ foreign region callback (fI); nI case = access after
                                                   destroy (HYPOTHESIS — detector does not yet record host state)

AE2-MB-MODIFICATION-001 (MBCalculator.modificationInProgress, global static)
  global static state                              CONFIRMED
  concurrent entry                                 CONFIRMED (nwae2static MB_MODIFICATION concurrent 2–25 per run)
  runtime failure                                  CONFIRMED (ISE crash reports)
  production relevance with AE2 pin                CONFIRMED (ISE in both prod-pins runs, via the pin leak)
  relevance to AE2-PINS-001                        BLOCKER FOR PIN REMOVAL

PathingService CME (getMultiblockNodes, main)      CORRELATED with region-thread MBCalculator via the pin leak; same
                                                   crash class as the 2026-10-06 ae2 auto-pin reason

AE2-PINS-001 verdict                               AE2 pin removal NOT APPROVED (TICKMANAGER: possibly covered;
                                                   readiness: scope 2; MBCalculator static: NEW CONFIRMED blocker)
```

#### Owner decisions 2026-10-28 (8) — scope 2 VERIFIED; lifecycle regression; scope 2 CLOSED

Pushed: `2980ea112`, `1140d6a36`, `2659098dd`, `7c9fe6682`. Remaining production cause narrowed: readiness
scheduling itself is **not** it; remaining = MAIN pinned AE2 lifecycle ↕ concurrent entry from a REGION block
callback (pin-domain incompleteness / callback ingress). AE2-MB-MODIFICATION-001 statuses as in the matrix
section (all CONFIRMED, blocks pin removal). PathingService CME stays **CORRELATED** — root cause NOT CONFIRMED
until a detector shows the causal overlap.

Lifecycle regression (QUEUE-LIFE fixtures) — H401BEDOM, fresh pristine world, **production pins**, Core 401,
addon 0.2.0, `-Dnestworld.compatAe2.fixtures=true` (test-only), inscriber targets at (-20,120,130) and
(-17,120,133) (inscriber domain MAIN — pinned; position grid/air domain REGION#0, region-thread remover via an
armor-stand host). Evidence `/root/airis-2026-10-25/evidence-be-ready/life-prodpins-addon020/`:

```
LIFE DURING: PASS  remover=NestWorld-Region-0 blocked=true  readyInvoked=1 ghost=false
LIFE BEFORE: PASS  remover=NestWorld-Region-0 blocked=false cancelled=init-cancelled:removed readyInvoked=0 ghost=false
no stale execution          readyStale=0
no double init              DURING readyInvoked=1
no init-after-remove        BEFORE readyInvoked=0, cancelled by revalidation
no lost lifecycle transition initCancelled +1 exactly; readyInDomain +2 (the two hosts)
generation accounting       readyDeferred 11 = readyRanInDomain 11; coalesced/stale/dropped/enqueueFailed/
                            initFailed/requeuedNotTicking = 0
detector                    ready offDomain=0; S1/S2/S3 = 0; lifeOrderViolation=0; crashNew=0
```

```
R1-a SCOPE 2 — AE2 readiness in the BE execution domain        CLOSED / VERIFIED
  readiness routing                 VERIFIED
  execution-domain correctness      VERIFIED
  identity revalidation             VERIFIED
  removed-host cancellation         VERIFIED
  accounting                        VERIFIED
  lifecycle regression (prod pins)  VERIFIED (life_during, life_before PASS)
  unpinned / integrated hosts       ENGINEERING VERIFIED
  explicit limitation               does NOT close production pinned AE2 callback races

Scope 1 — Core BE execution domain   VERIFIED (ENGINEERING)
Production AE2-BE-READY-001          OPEN — remaining cause PIN/CALLBACK DOMAIN LEAK (AE2-PIN-LEAK-001)
AE2-MB-MODIFICATION-001              CONFIRMED (blocks AE2 pin removal)
PathingService CME                   CORRELATED
AE2 pin removal                      NOT APPROVED
Next                                 CORE-PIN-DOMAIN-COMPLETENESS-001 (contract audit first) and R1-a scope 3
                                     (topology commit / callback routing)
```

#### Owner decisions 2026-10-28 (9) and R1-a scope 3 — first evidence (2026-10-29)

Decisions (9): refined D for pins — `pin` = tick pin, `pinmod` = mod-wide tick pin (unchanged), `pinzone` = future
spatial MAIN zone (CORE-PINZONE-001, separate gate); overclaiming texts corrected (`be2044fdd`); scope 3 approved
to start now (explicit routing of its own callbacks; does not rely on pin).

Scope 3 commits: `bc5b844c6` detector (listener domain), `907dffa4a` ensureConnection under T0, `2e66fef83`
listener intents → host BE domain (addon 0.3.0, sha256 `4daa983c…50cf`), `0e9896992` ensure fixture, `13017bc18`
detector (unrouted listener call sites).

Deterministic `nwcompatae2 topology ensure` (H401BEDOM, production pins, MAIN-domain energy cells; evidence
`evidence-be-ready/smoke-addon030/`): **PASS** — EXISTING (same object, no ISE, counts unchanged), INVARIANT (one-
sided state → addon invariant failure, restored), CREATED (exactly once after destroy), REJECTED_STALE (destroyed
node, nothing connected); each counter +1. Mixin apply errors 0.

Churn matrix (same rig as scope 2; 240 s; fresh world; pins unchanged in every run):

```
run                     listeners calls  off-domain        routed  routedOffDomain  unroutedOffDomain  S1 S2 S3  crash
s3-prodpins-addon020    70836            13829 (19.5%)     n/a     -                13829              6  0  0   7 (alive)
s3-prodpins-addon030    11863 (77 s)     57    (0.5%)      3968    0                57                 3  0  0   6 (died)
s3-unpinned-addon020    12222            5899  (48%)       n/a     -                5899               0  0  0   0
s3-unpinned-addon030    7078             2995  (42%)       2971    0                2995               0  0  0   0
```

Unrouted off-domain listener call sites (`/nwbeready listeners`, prod pins, 90 s): **0.2.0** ≈1618 each at
`GridConnection.destroy` / `create` listener calls **on region threads** (T0 listeners of pinned hosts — the
AE2-NODE-LISTENER-OWNER-001 volume) + 57 on main; **0.3.0** only the 57 on main: `Grid.notifyAllNodes` (36) and
channel finalization `ChannelFinalizer.visitNode` / `GridConnection.finalizeChannels` (21) — AE2 end-of-tick paths,
**outside any kernel section**, delivering to hosts whose domain is a region (e.g. unpinned `expatternprovider`).

Production-pin survival A/B (failures in the first 60 s; Core breaker ≈ 6 tick failures / 60 s → crash shutdown;
evidence `ab-prodpins-0{2,3}0-{1,2,3}` 90 s + the two 240 s runs each; one 0.3.0 run interrupted by a host restart →
`invalid/`):

```
0.2.0   first-60 s failures 3, 5, 3, 6, 2   mean 3.8   died 1/5
0.3.0   first-60 s failures 6, 5, 6, 5, 3   mean 5.0   died 4/5
crash classes identical (AirBlock CCE in readiness, MBCalculator ISE; single NPE / CME reports in both versions)
MB_MODIFICATION calls 14404 / 14206 (0.2.0) vs 14309 (0.3.0); offMain ≈12600 both; concurrent 11 / 49 vs 22
```

Statuses:

```
ensureConnection (EXISTING / INVARIANT / CREATED / REJECTED_STALE)   VERIFIED (deterministic fixture)
TOCTOU duplicate in churn                                             NOT REPRODUCED (S3 0, topoExisting 0 in all runs)
in-section listener intents → host BE domain                          VERIFIED — routedOffDomain 0 in every run
AE2-NODE-LISTENER-OWNER-001 (T0 listeners on foreign threads)         CONFIRMED (≈1618/site/90 s in 0.2.0) → FIXED by 0.3.0
remaining listener off-domain: AE2 main end-of-tick paths             CONFIRMED (Grid.notifyAllNodes, channel
  (outside sections; not in scope 3's T0 mandate)                     finalization; 57/run) — decision needed
prod-pins survival worse with 0.3.0                                   CORRELATED (n=5 each, distributions overlap, not
                                                                      significant); mechanism check (MB work on main)
                                                                      does NOT support it — NOT CONFIRMED
scope 3 fixtures still missing                                        interior↔band pair, region split/merge, chunk unload
AE2-BE-READY-001 (production, pinned)                                 OPEN (unchanged)
```

#### Owner decisions 2026-10-29 (10)

```
R1-a Scope 3                         IMPLEMENTED / PARTIALLY VERIFIED
  ensureConnection                   VERIFIED BY DETERMINISTIC FIXTURE (EXISTING idempotent; one-sided state →
                                     invariant failure; destroyed node rejected; re-create exactly once)
  topology listener ownership        VERIFIED — routed ≈20k calls, offDomain 0
  remaining scope-3 fixtures         REQUIRED: interior ↔ border band, split/merge, chunk unload
                                     (S3 not reproduced in live churn after the fix)
end-of-tick listeners                OUT OF SCOPE → NEW GATE AE2-NODE-LISTENER-ENDTICK-001
0.3.0 production survival regression CORRELATED, NOT CONFIRMED (possible regression signal: yes)
additional statistical A/B           DEFERRED (more valuable after the pin leak is closed, on an environment without
                                     the known S1/S2/MB failures)
next for the survival question       per-tick-failure ATTRIBUTION (timestamp, thread, call site, host, host domain,
                                     pin state, readiness in flight, MBCalculator active, topology listener delivery
                                     active)
0.3.0 production promotion           NOT APPROVED / BLOCKED
pushed                               be2044fdd .. d742b9700 (push ≠ production approval)
```

**AE2-NODE-LISTENER-ENDTICK-001** (new gate): the 57/run listener calls from AE2 server-end-tick paths
(`Grid.notifyAllNodes`, `ChannelFinalizer.visitNode` / `GridConnection.finalizeChannels`) — not part of a topology
transaction under T0. Candidate contract: host domain resolution → deferred delivery. Before implementation verify:
same-tick delivery requirement; coalescing of identical state notifications; ordering POWER / CHANNEL / GRID_BOOT;
lifecycle generation; host removed before delivery; callback re-entering AE2; pathing completion before callback.

#### R1-a scope 3 — lifecycle fixtures (2026-10-29)

`nwcompatae2 topology lifecycle` (H401BEDOM, fresh pristine world, TEST CONFIG ae2 unpinned + autoPin off, fixtures
flag, Core 401, addon 0.3.0 + fixture commit — jar sha256 `ae84f12e…7e4a`). A = (64,120,2000) → REGION#1 (r1's first
interior chunk column), B = (63,120,2000) → MAIN (r1's border band). Evidence
`evidence-be-ready/topo-lifecycle-run{1,2}/`.

```
run1  10 cycles  55 steps  PASS   topoInvariant 0  topoStale 0  cbDeferred 194 / ranInDomain 193 / stale 1 / dropped 0 / failed 0
run2  20 cycles  95 steps  PASS   topoInvariant 0  topoStale 0  cbDeferred 234 / ranInDomain 233 / stale 1 / dropped 0 / failed 0
both  routed listeners routedOffDomain 0; S1/S2/S3 0; crashNew 0; mixin errors 0
split Region 1 (2..1875000 x, all z) split on Z at chunk 4/5 → A (chunk z 125) moved to the child; domain change
      asserted by the fixture; post-split interior↔band cycle valid
unload A's chunk unloaded (asserted); after reload A and B reconnected exactly once
cbStale 1 = a callback whose host was removed before delivery, cancelled by revalidation (correct)
unrouted off-domain listeners (not scope 3): Grid.notifyAllNodes 1164, finalizeChannels 265/260, ChannelFinalizer 260
      — all AE2 end-of-tick paths on main → AE2-NODE-LISTENER-ENDTICK-001
```

run3 (merge step added, jar sha256 `4ab7e74f…97bb5`): 10 cycles, 61 steps, PASS — split `#1 -> #2 + #3` (A → #3),
post-split cycle, merge `#3 + #2 -> #4` (A → #4, asserted), post-merge cycle, unload/reload, cleanup; topoInvariant 0,
routedOffDomain 0, crashNew 0. Correction: runs 1–2 covered split only; merge is covered from run3.

```
R1-a Scope 3 required fixtures      interior ↔ border band  PASS (×40 cycles over three runs)
                                    split (domain change)   PASS
                                    merge (domain change)   PASS (run3)
                                    chunk unload / reload   PASS
                                    ensureConnection        PASS (deterministic, earlier)
live-churn S3 reproduction          still NOT REPRODUCED (pre- and post-fix)
0.3.0 production promotion          NOT APPROVED (survival signal CORRELATED; attribution pending)
```

#### Owner decisions 2026-10-29 (11) — R1-a scope 3 VERIFIED (engineering)

```
R1-a SCOPE 3                       atomic ensureConnection VERIFIED · topology callback routing VERIFIED ·
                                   interior↔MAIN-band VERIFIED · region split VERIFIED · region merge VERIFIED ·
                                   chunk unload/reload VERIFIED · stale callback rejection VERIFIED (cbStale = positive
                                   test: host removed before delivery → revalidation → cancelled) · topology invariants
                                   VERIFIED · routed callback offDomain 0 · S1/S2/S3 in lifecycle fixtures 0
  engineering status               VERIFIED
  production status                NOT APPROVED
live-churn S3 not reproduced       does not block engineering verification (deterministic fixture covers
                                   ABSENT→CREATED, EXACT→EXISTING, BROKEN→invariant failure, DEAD→rejected,
                                   destroy+recreate→exactly one)
R1-a (scopes 1–3)                  engineering implementation complete and verified
addon 0.3.0                        PRODUCTION NOT APPROVED
next                               causal tick-failure attribution (candidates: A pin leak, B MB-MODIFICATION,
                                   C PathingService CME, D scope-3 listener delivery timing, E none) →
                                   decide scope-3 regression YES / NO / UNKNOWN → then AE2-NODE-LISTENER-ENDTICK-001 audit
pushed                             686a5d7c2 .. f6e65e3f7
```

#### Tick-failure attribution — 0.2.0 vs 0.3.0 under production pins (2026-10-29)

Detector `046fdcd17` (`/nwbeready failures`, `[AE2-FAIL-ATTR]` records: exception, crash title, top AE2 frames, failing
readiness host with domain/pin state, and at the THROW the other threads' activity — readiness, MBCalculator,
topology commit, routed/unrouted listener delivery — with last-end ages). H401BEDOM, production pins, churn period 1,
90 s × 3 per version, alternating, fresh world each; evidence `evidence-be-ready/attr-prodpins-0{2,3}0-{1,2,3}/`.

```
                                           0.2.0 (17 records)            0.3.0 (18 records)
deaths (breaker)                           1/3                            3/3        (all A/B runs so far: 2/8 vs 7/8)
exception classes                          15 CCE (S1), 2 MB ISE          16 CCE (S1), 2 MB ISE
failing host                               crafting_storage@(-19,115,130) 15,  same host 18
                                           @(201,115,200) 2
crash path                                 readiness on main (pinned AE2 → MAIN) in every record, both versions
region thread inside GridConnection        4/17                           8/18
  destroy/create at the throw
routed listener for the SAME host <=50 ms  n/a (nothing routed)           2/18
failure inside a listener delivery         0                              0
MBCalculator active on another thread      0                              0
```

Candidate classification (owner list A–E):

```
A  pin leak (region-thread placement/removal ↔ pinned readiness on MAIN)   ALL S1 records, both versions
B  AE2-MB-MODIFICATION-001                                                  the ISE records, both versions
C  PathingService CME                                                       not seen in these runs
D  scope-3 listener delivery timing as the trigger                          NOT SUPPORTED — no same-host pattern (2/18),
                                                                            no failure inside delivery
E  none of the above                                                        0
```

Verdict proposal: **scope-3 regression as a new failure mechanism: NO** (no new class, host or path; D not supported).
**0.3.0 surfaces the existing pin-leak S1 faster: CORRELATED** (deaths 7/8 vs 2/8; region topology commit active at
the throw 8/18 vs 4/17). Mechanism **HYPOTHESIS**: 0.2.0 ran T0 listeners under T0 on the region thread, so the
region held T0 longer and main's readiness (whose node creation needs T0) was accidentally serialized behind the
removal; 0.3.0 releases T0 sooner, removing that accidental serialization — i.e. it exposes A, it does not create a
new defect. Not measured yet: T0 hold time / readiness blocking (kernel has `contended` only).

#### Owner decisions 2026-10-29 (12)

```
T0 serialization hypothesis          stays HYPOTHESIS — not measured now (not a closure blocker)
Scope 3 regression                   NOT CONFIRMED
New failure mechanism in 0.3.0       NOT OBSERVED
Increased failure frequency          CORRELATED
Proposed explanation                 reduced accidental T0 serialization — HYPOTHESIS
Production blocker                   AE2-PIN-LEAK-001 + AE2-MB-MODIFICATION-001 (decision holds even if the T0
                                     hypothesis is wrong: confirmed concurrent ingress into pinned AE2)
R1-a Scope 3                         CLOSED / VERIFIED (engineering, copy)
  production promotion               BLOCKED BY EXTERNAL KNOWN COMPATIBILITY ISSUES
Addon 0.3.0                          engineering VERIFIED; production NOT APPROVED (known AE2 pin-domain
                                     incompleteness + confirmed MB static race); scope-3-caused regression NOT CONFIRMED
pushed                               8ca8d6943, 046fdcd17, 17a5e7b8e
next                                 AE2-NODE-LISTENER-ENDTICK-001 source audit → docs/compat/AE2-NODE-LISTENER-ENDTICK-001-audit.md
```

#### Owner decisions 2026-10-29 (13) and AE2-NODE-LISTENER-ENDTICK-001 evidence

Decisions (13): scope = only the three audited end-of-tick sources (GRID_BOOT, POWER, CHANNEL); `onOwnerChanged`,
`onSaveChanges` and other callbacks OUT OF SCOPE (no automatic routing of every listener); generic transport
(`HostDomainCallbackDelivery`), narrow audited callers; coalescing DEFERRED (exact FIFO; a later
AE2-LISTENER-COALESCE-001 performance gate may prove equivalence); lifecycle revalidation REQUIRED; fixture with
POWER and GRID_BOOT/CHANNEL, stale case and negative control APPROVED; `0e24f8f50` pushed.

Commits: transport refactor, ENDTICK routing (addon 0.4.0), fixture (this batch). Fixture `nwcompatae2 topology
endtick` on H401BEDOM (fresh pristine world, TEST CONFIG ae2 unpinned + autoPin off, fixtures flag, Core 401, addon
0.4.0 sha256 `2359688e…e4ce`); A = interface (64,120,2000) REGION#1, B = interface (63,120,2000) MAIN, C = creative
energy cell (62,120,2000) MAIN. Evidence `evidence-be-ready/endtick-run{2,3}/` (run1 invalid: setup placed blocks
in not-yet-loaded chunks → `invalid/`).

```
run2, run3 (identical)   ENDTICK: PASS (21 steps)
POWER OFF/ON ×2          per host emitted == delivered (exact, FIFO); off-domain deliveries 0; final powered=true
pathing reboot           GRID_BOOT emitted and delivered per host; CHANNEL/GRID_BOOT FIFO = emission; channel met;
                         not booting at the end
stale                    A's state callback CANCELLED_STALE; deliveries to removed A = 0;
                         emitted = delivered + stale
negative control         routing OFF → A's listener observed on Server thread (the 6 unroutedOffDomain events)
accounting (st*)         emitted 3111 = inDomain 1202 + deferred 1909; deferred 1909 = ranInDomain 1902 + stale 7;
                         dropped 0, failed 0, enqueueFailed 0, noHost 0
island-wide listeners    routed 5149 / 5160, routedOffDomain 0, unroutedOffDomain 6 (= negative-control phase only)
ITERATION_BUFFER recursion errors 0; crashNew 0; S1/S2/S3 0
```

```
AE2-NODE-LISTENER-ENDTICK-001        IMPLEMENTED / VERIFIED (engineering, copy) for GRID_BOOT / POWER / CHANNEL
known off-domain AE2 node-listener paths (topology T0 + end-of-tick state)   → 0 outside the negative control
production (ae2 pinned)              AE2 hosts' domain is MAIN → end-tick delivery inline (unchanged); affects
                                     unpinned AE2-integrated hosts in regions only
addon 0.4.0 production               NOT APPROVED (blockers unchanged: AE2-PIN-LEAK-001, AE2-MB-MODIFICATION-001)
```

Scope-3 regression on addon 0.4.0 (transport refactor): first run FAIL only at "A's domain did not change after the
split" — Core log `Split/merge lock guard: timed out locking region 0 — skipping this pass`; the fixture issued the
split once and the merge step then merged A's region with its real sibling (region 0). Topology invariants held
(topoInvariant 0, routed off-domain 0) → fixture weakness, run moved to `invalid/`. Fixture now retries split/merge
(≤10 × 20 ticks, domain change asserted). Re-run `evidence-be-ready/topo-lifecycle-addon040/` (jar sha256
`51bed4cd…c097`): **PASS**, 99 steps, split #1 → #3, merge #3 → #4, routed and unrouted listener off-domain 0 island-
wide, crashNew 0. Cosmetic: the status repeats the success line for the remaining attempt steps.
