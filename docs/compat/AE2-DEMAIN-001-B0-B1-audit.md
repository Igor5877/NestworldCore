# AE2-DEMAIN-001 B0 + B1 — source audit and design proposal (no code, no runs)

Status: **AUDIT + DESIGN — STOP for owner review** (plan: `AE2-DEMAIN-001.md`). Nothing was built, run or committed.
Sources: AE2 15.4.10 (`A/` = `/root/ae2-src/forge-v15.4.10/src/main/java/appeng/`; production 15.4.11 differs only in
`CraftingCPUCalculator.verifyInternalStructure`, AE2-RELEASE-EQUIV-001), addon 0.7.7 (`N/` =
`compat/nestworld-compat-ae2/src/main/java/net/nestworld/compat/ae2/`), Core (`C/` = `src/main/java/net/nestworld/`),
race detector (`R/` = `compat/nestworld-ae2-race-detector/src/main/java/net/nestworld/ae2detect/`), pack jars
`/root/project-infinity/mods` (javap, read-only), evidence `E1/` =
`/root/airis-2026-10-25/evidence-be-ready/invalid-h-soak-run1-pismoke-pins-not-production/`.
Confidence: CONFIRMED / STRONGLY INDICATED / CORRELATED / HYPOTHESIS / UNKNOWN.

## 0. Key findings (summary)

| # | finding | confidence |
|---|---|---|
| K1 | `MBCalculator.modificationInProgress` is ONE JVM-global, non-volatile `static WeakReference<IAECluster>`; its purpose is a **re-entrancy guard for one causal chain** (suppress recursive recalcs while a cluster is being (re)built or destroyed). Cross-cluster exclusion is incidental (AE2 assumes one server thread). | CONFIRMED (`A/me/cluster/MBCalculator.java:32-36,44-57`) |
| K2 | Run1 failure is **not** off-domain execution: every routed op ran in its own BE domain (`ready offDomain=0`, `d2 … offDomain=0`, `tdOffDomain=0`, `tmOffDomain=0`). The conflict is between **independent** crafting CPUs (−200,−200 / +200,+200) in different legitimate domains (Region-0/1/2/10 + MAIN) sharing the one static. | CONFIRMED (`E1/final.txt`, `E1/log-lines.txt`) |
| K3 | Besides the ISE there are two **silent** failure modes nobody counts: (a) a thread seeing a foreign flag returns early from `calculateMultiblock` → recalc lost, structure stays stale/unformed; (b) a foreign `finally set(null)` clears another thread's guard mid-operation. | CONFIRMED by source (`MBCalculator.java:84-87,146-148`); occurrence count UNKNOWN (no counter) |
| K4 | Three pack addons have their own multiblocks on the **same static**: AdvancedAE quantum computer (`AdvCraftingCPUCalculator/Cluster`), ExtendedAE assembler matrix (`CalculatorAssemblerMatrix/ClusterAssemblerMatrix`), neoecoae (`NEClusterCalculator` + `NECluster`). None is covered by D2 / D4a / the teardown claim (those mixins target AE2 classes only). They are safe today only because their mods are pinned. | CONFIRMED (javap: `invokestatic MBCalculator.setModificationInProgress/isModificationInProgress` in `AdvCraftingCPUCluster.destroy`, `ClusterAssemblerMatrix`, `NECluster.destroy`; `N/mixin/ClusterTeardownClaimMixin.java:302` targets 3 AE2 classes) |
| K5 | **megacells crafting units are AE2's `CraftingBlockEntity` / `CraftingMonitorBlockEntity` registered under `megacells:`** (`mega_crafting_unit`, `mega_crafting_storage`, `mega_crafting_monitor`), and `megacells` is **not** in live `nestworld-pinned-mods.txt` → in production their readiness / neighbour recalcs / teardown run on REGION threads while pinned AE2 CPUs run on MAIN — a latent instance of the run1 race in the live config. Not observed (no MEGA CPU in H/I churn; live MB ISE 0 in 5 min smoke). | STRONGLY INDICATED (javap `MEGABlockEntities`; `C/region/NestworldPins.java:218-226`; live pin file) — production presence of MEGA CPUs UNKNOWN |
| K6 | All MB structural entry points are **void** (onReady, neighborChanged → updateMultiBlock, onRemove/setRemoved/unload teardown, QNB serverTick / onChangeInventory) → every one is **ASYNC_SIDE_EFFECT**; no SYNC_RESULT / SYNC_MUTATING_RESULT caller found. | CONFIRMED for AE2; addon multiblocks STRONGLY INDICATED (same void overrides, javap signatures) |
| K7 | A dedicated lane thread cannot run AE2's recalc as-is: `calculateMultiblock` interleaves world reads (6-direction scans, face checks, full-bounds verify), decisions and world/BE mutations (`setBlock`, `saveChanges`, exposed sides, grid events, `Locatables`, Forge event bus). Proposed lane = **logical serialization domain (token + FIFO tickets), bodies execute in the requester's BE execution domain** (GMEIL `SERIAL_COMPAT` semantics). | CONFIRMED (source §3) — design |
| K8 | Core needs **nothing new** for the first safe form (BlockEntityExecutionDomain enqueue/isCurrentThreadIn, CORE-PINZONE-001 position pins exist). GMEIL `SERIAL_COMPAT` exists only as an enum value, no executor. B8 would benefit from a small generic Core addition (mod-pin exclusions). | CONFIRMED (`C/gmeil/ExecutionPolicy.java:6`, `C/gmeil/ExecutionGateway.java:7-10`, `C/region/BlockEntityExecutionDomain.java`) |
| K9 | B0 trap: `h-soak.sh:30` calls `gridcensus report` instead of `start` → `E1/census-end.txt` is all `0.000` ("14357 ticks (53413.6 s)" = startNanos 0). Every earlier soak has no AE2 grid cost data. | CONFIRMED |

## 1. Evidence base — invalid H run1 (AE2 not pinned; production-pack copy)

| item | value (`E1/final.txt`) | reading |
|---|---|---|
| MB_MODIFICATION | calls 71 912, offMain 55 160, **concurrent 1 259** | detector `R/StaticDetect.java:37-53`: two different threads inside calc/destroy at once |
| mbCalc / clusterDestroy | 43 696 (offMain 32 992) / 28 216 (offMain 22 168) | both flag writers off MAIN |
| ISE sites | churn first error `MBCalculator.setModificationInProgress:50 ← calculateMultiblock:134 ← updateMultiblockAfterNeighborUpdate:80 ← CraftingBlockEntity.updateMultiBlock (D2 route, in domain) ← AbstractCraftingUnitBlock.neighborChanged ← … MekChurn driver setBlock` on Region-2; readiness `onReady → calculateMultiblock` ISE on Region-0/1/10 (`E1/log-lines.txt`) | ISE at the **new-cluster** set (line 134) |
| readyInitFailed | 19 (= `[BE-DOMAIN] task … failed` lines) | onReady aborted after node creation, before the structure formed → CPU unformed until the next neighbour update |
| churn errors | 149 of 28 177 toggles | |
| routing | ready offDomain 0, listeners routedOffDomain 0, d2 offDomain 0 (crafting/qnb/cablebus), teardown offDomain 0, members offDomain 0 | the addon's domain routing is correct; the static is shared across correct domains (K2) |
| S1/S2/S3 | 0/0/0 | |
| run2 (AE2 pinned) | all of the above 0 | single writer thread (MAIN) |

Correction to the plan wording: crafting CPU **jobs** never ticked on region threads — `CraftingService.onServerEndTick`
(`A/me/service/CraftingService.java:141-153`) is a grid service tick (MAIN). What ran on region threads in run1 was
cluster **formation / recalculation / teardown** (BE readiness, neighbour callbacks, removal). CONFIRMED.

## 2. B1-Q1 — the static state, its users and its invariant

| site | file:line | access | behaviour |
|---|---|---|---|
| field | `MBCalculator.java:36` | `private static WeakReference<IAECluster>` (non-volatile, no lock) | one value per JVM |
| `setModificationInProgress(c)` | `:44-53` | read-compare-write | same cluster → no-op; **another non-null in progress and c≠null → ISE "A modification is already in-progress for: <inProgress>" (`:50`)**; c=null clears unconditionally |
| `isModificationInProgress()` | `:55-57` | read | |
| `calculateMultiblock` entry | `:84-87` | read | **flag set → silent return** (K3a) |
| new-cluster branch | `:131-137` | `createCluster` → **set(new) (`:134`)** → `updateBlockEntities` (breaks clusters in bounds) | ISE site 1 |
| same-bounds branch | `:138-140` | **set(existing) (`:140`)** → `cluster.updateStatus(false)` | ISE site 2 |
| `finally` | `:146-148` | **set(null)** — also on early-return/disconnect paths | clears whoever's flag is set (K3b) |
| `CraftingCPUCluster.destroy` | `A/me/cluster/implementations/CraftingCPUCluster.java:106-133` | `ownsModification = !is…` (`:112`), set(this) (`:114`), clear (`:130`) | check-then-set race → ISE site 3, or runs unguarded when it saw a foreign flag |
| `QuantumCluster.destroy` | `…/QuantumCluster.java:192-221` | set(this) **unconditionally** (`:198`), clear (`:219`) | ISE site 4 when anything else is in progress |
| `SpatialPylonCluster.destroy` | `…/SpatialPylonCluster.java:72-87` | set(this) unconditionally (`:79`), clear (`:85`) | ISE site 5 |
| AdvancedAE `AdvCraftingCPUCluster.destroy` | javap | is / set / set(null) (crafting pattern) | same as site 3 |
| ExtendedAE `ClusterAssemblerMatrix` | javap | references set/is | same class of site (exact shape UNKNOWN, not decompiled) |
| neoecoae `NECluster.destroy` | javap | is / set / set(null) | same as site 3 |
| addon calculators | `AdvCraftingCPUCalculator`, `CalculatorAssemblerMatrix`, `NEClusterCalculator` extend `MBCalculator`; `NEClusterCalculator.calculateMultiblock` overrides but only calls `super` (javap) | inherit `:84-148` | base-method interception covers them |

**Invariant protected** (CONFIRMED by reading every writer): while one cluster is being created/re-formed
(`updateBlockEntities` + `updateStatus`) or destroyed (member `updateStatus(null)`), calculations triggered *by those
mutations on the same call chain* are suppressed, and the re-formation path's nested `oldCluster.breakCluster() →
destroy()` (crafting) must not clear the outer guard (`ownsModification`). It is a re-entrancy guard of a single
synchronous causal chain. It is *not* a semantic cross-cluster lock — but because it is global, AE2 is correct only if
at most one thread ever touches it.

**Why concurrency (not re-entrancy) throws**: re-entrant calls on one thread either see their own cluster (`:46` no-op)
or are suppressed at `:84`; the crafting destroy checks ownership first. Only a *second thread* can be between `:84`
and `:134/140` while the first holds the flag, or enter an unconditional QNB/pylon destroy during another thread's
modification. Failure set under concurrency: F1 ISE (sites 1-5); F2 silent recalc loss (`:84`); F3 foreign clear
(`:147`) removes the other chain's re-entrancy protection; F4 stale visibility (non-volatile). Also observed:
PathingService CME on `getMultiblockNodes` (CORRELATED, `AE2-COMPAT-CLOSURE-001.md:655`) — MAIN pathing iterates a
cluster's member list (`A/blockentity/crafting/CraftingBlockEntity.java:328-343`) while another thread populates it
(`CraftingCPUCluster.addBlockEntity :140-162`).

## 3. B1-Q2/Q3 — shared multiblock structural operations, entry points, threads, what they touch

### 3.1 Entry points × thread today (production pins: `ae2`, `advanced_ae`, `expatternprovider`, `extendedae_plus`, `neoecoae`, … pinned; `megacells` NOT)

| family (BE types) | recalc entry | teardown entry | other shared op | addon routing | thread today |
|---|---|---|---|---|---|
| AE2 crafting CPU (`ae2:crafting_unit`, `ae2:crafting_storage`, `ae2:crafting_monitor`; `extendedae_plus:*x_crafting_accelerator` = same BE class) | `onReady → calculateMultiblock` (`CraftingBlockEntity.java:104-110`, via `FirstTickInfo.callInit` → `BeDomainReady.ready`); `neighborChanged → updateMultiBlock → updateMultiblockAfterNeighborUpdate` (`AbstractCraftingUnitBlock`:73-79 → `CraftingBlockEntity.java:112-116`) | `onRemove → breakCluster` (`AbstractCraftingUnitBlock`:82-92, `CraftingBlockEntity.java:220-258`); `disconnect` (`:193-201`, from MBCalculator `:150`); re-formation `updateStatus(c) → old.breakCluster()` (`:118-125`) | grid event `GridCraftingCpuChange` (`CraftingCPUCalculator` updateBlockEntities; `CraftingCPUCluster.destroy`) → `CraftingService.updateList=true` (`CraftingService.java:110-113`) | readiness: scope 2 (`N/glue/BeDomainReady.java:111-151`); neighbour: D2 (`N/mixin/CraftingBlockEntityD2Mixin.java:13`); teardown: D4a (`N/mixin/CraftingTeardownD4aMixin.java:20,34`, `CraftingCPUClusterD4aMixin.java:25,37`) | MAIN (pinned). Region-thread *callers* are routed to MAIN |
| same, megacells (`megacells:mega_crafting_unit/_storage/_monitor`) | same AE2 code | same | same | same mixins apply (class-based) | **REGION** (domain resolver: type not pinned) — K5 |
| QNB (`ae2:quantum_ring`, ring + link) | `onReady → updateStatus=true` then **BE serverTick** → `cluster.updateStatus(true)` + `neighborUpdate(self) → calc` (`QuantumBridgeBlockEntity.java:94-106,168-178`); `neighborChanged → neighborUpdate` (`QuantumBaseBlock`:83-89) | `onRemove → breakClusterOnRemove` (`:287-295`); `setRemoved`/`onChunkUnloaded → disconnect(false)` (`:162-184`); **`QuantumCluster.onUnload(LevelEvent.Unload)` → destroy** (`QuantumCluster.java:63-69`, NOT routed) | `QuantumCluster.updateStatus` (`:71-133`): **`Locatables.quantumNetworkBridges()` static `HashMap`** register/unregister (`A/api/features/Locatables.java:41-44`), **other side's cluster** `connection` field, `GridHelper.createConnection` between the two bridges' grids (possibly another dimension); also from `onChangeInventory` (singularity slot, `:138-142`) — callable by any inserting thread | D2 `QuantumBridgeD2Mixin.java:13`; D4a `QuantumBridgeD4aMixin.java:14,37`, `QuantumClusterD4aMixin` | MAIN (pinned); `onChangeInventory` from a foreign inserter: HYPOTHESIS (not observed) |
| spatial pylon (`ae2:spatial_pylon`) | `onReady → calc` (`SpatialPylonBlockEntity.java:72-77`); `neighborChanged` (`:87-91`) | `setRemoved`/`onChunkUnloaded → disconnect` (`:66-99`) | grid `SpatialPylonCache` reads clusters on MAIN | D2 `SpatialPylonD2Mixin.java:13`; D4a `SpatialPylonD4aMixin.java:20` | MAIN (pinned) |
| AdvancedAE quantum computer (`advanced_ae:quantum_core` + member types; exact ids UNKNOWN) | `AdvCraftingBlockEntity.onReady/updateMultiBlock` → `AdvCraftingCPUCalculator` (inherited calc) | `breakCluster`, `disconnect`, `updateStatus` (javap) | crafting-service CPU list | **none** (own classes) | MAIN (mod pin) |
| ExtendedAE assembler matrix (`expatternprovider:assembler_matrix_*`; `extendedae_plus:*assembler_matrix*`) | `TileAssemblerMatrixBase.onReady/updateMultiBlock` → `CalculatorAssemblerMatrix` | `breakCluster`, `disconnect`, `updateStatus` | matrix crafting state | none | MAIN (mod pins) |
| neoecoae storage / computation / crafting clusters | `NEBlockEntity.onReady/updateMultiBlock` → `NE*ClusterCalculator` | `disconnect`, `breakCluster`, `NECluster.destroy` | own storage system | none | MAIN (mod pin) |

### 3.2 What one structural recalc touches (AE2; addon calculators same skeleton, internals UNKNOWN)

| phase | operations | kind | cross-domain? |
|---|---|---|---|
| R1 bounds scan | `isValidBlockEntityAt` along −x/−y/−z/+x/+y/+z (`MBCalculator.java:100-117,161-163`) → `level.getBlockEntity` | world READ | yes, if the structure straddles region interior / border band (crafting up to 17³ `CraftingCPUCalculator.checkMultiblockScale`; pylon line has **no length limit**) |
| R2 face check | `verifyUnownedRegion` — every BE on the 6 faces just outside bounds (`:174-182,223-262`) | world READ | yes (faces are outside the structure) |
| R3 internal verify | `verifyInternalStructure` — every position: BE present, `isValid`, block identity / storage bytes from block state (`CraftingCPUCalculator`, `QuantumCalculator`, `SpatialPylonCalculator`) | world READ | as R1 |
| D decision | `{DISCONNECT, KEEP(same bounds), NEW(min,max)}` (`:119-150`) | pure | — |
| M1 cluster object | `createCluster` (new object; QNB `setCenter` → **Forge EVENT_BUS.register** `QuantumCluster.java:242-245`) | compat state (+ global bus) | — |
| M2 members | `updateBlockEntities`: per member `updateStatus(c)` → `old.breakCluster()` (D4a), member `cluster` field, `updateSubType(true)` → **`level.setBlock(pos, POWERED/FORMED, UPDATE_CLIENTS)`** + `onGridConnectableSidesChanged` (exposed sides → node topology) (`CraftingBlockEntity.java:118-153`); `addBlockEntity` → `te.setCoreBlock`, **`te.saveChanges()`** (`CraftingCPUCluster.java:140-162`); `done()` → core `previousState` NBT → crafting logic restore (`:266-276`); QNB `updateStatus(c, flags, true)` → `markForUpdate` + exposed sides; pylon `updateStatus` → exposed sides + `recalculateDisplay → markForUpdate` | **world WRITE + BE mutation of every member** | yes when straddling |
| M3 grid | `GridCraftingCpuChange` post (`CraftingCPUCalculator` updateBlockEntities); QNB `createConnection` / `connection.destroy` (kernel-guarded under T0 by `GridConnectionMixin`); `Locatables` | grid / global state | QNB pair: other grid, possibly other dimension |
| M4 status | `cluster.updateStatus(updateGrid)` — crafting: every member `updateSubType(true)` again (`CraftingCPUCluster.java:99-104`) | world WRITE | as M2 |
| T teardown | D4a: claim → transition in anchor domain (`cancelJob`, inventory capture, `isDestroyed`, member list, grid event) → member intents (CLUSTER channel) → drop intents (`N/glue/ClusterTeardownCoordinator.java:65-192`) | already decomposed | handled (D4a) |

Re-entrancy sources that the flag suppresses (CONFIRMED by reading): M2 `old.breakCluster()` (crafting re-formation,
same thread); calcs triggered while members' block states change. `setBlock(…, UPDATE_CLIENTS)` sends no neighbour
updates (flag 1 absent) — shape updates only (`AbstractCraftingUnitBlock.updateShape` = model data). Member
`updateStatus(null)` (teardown) never calls a calc (crafting `:118-153`, QNB `:213-224`, pylon `:111-122`) — so D4a's
deferred member intents do **not** need the flag window. CONFIRMED.

Same-structure hazard without pins (HYPOTHESIS, not observed): a CPU straddling band (MAIN) and region interior has
members whose readiness runs on two threads → two `NEW` decisions for identical bounds → two cluster objects; the
second `updateBlockEntities` breaks the first (`cancelJob`, inventory drop) → job loss / spurious drops. The static
flag *partially* prevents this today only by throwing.

## 4. B1-Q4 — AE2_SHARED / MULTIBLOCK lane design (first safe form)

### 4.1 Rejected

| option | why rejected |
|---|---|
| ThreadLocal (or per-thread) `modificationInProgress` | hides the race: two threads could (re)build or tear down **the same or overlapping** structure concurrently (double formation, double breakCluster → duplicate drops / job cancel), and the detector would go blind. Explicitly forbidden by B1. |
| dedicated lane thread executing AE2's `calculateMultiblock` | the body does world reads of chunks it does not own (no Core read protocol for a thread that is neither MAIN nor a region) and world/BE writes of members (violates B2). |
| full port: lane computes R1-R3 + D from a snapshot, emits M as intents | requires re-implementing 6+ calculators incl. closed-source addon ones (neoecoae, AdvancedAE, ExtendedAE), version-fragile; contradicts "do not rewrite verified primitives". Kept as the B2/B3 direction for AE2's own three calculators only, if ever needed. |

### 4.2 Proposed: the MB lane is a logical serialization domain; bodies run in BE domains

```
MbLane (addon, plain Java, next to Ae2Kernel)
  token     : AtomicReference<Owner>      Owner = {thread, ticket}  — never waited on
  queue     : FIFO of Ticket               Ticket = {id, kind, level, pos, beType, host (identity), cluster (identity, TEARDOWN/QNB), enqTick}
  reserved  : Ticket in flight to its domain (handoff), or null
kinds: CALC (onReady / neighbour / QNB tick recalc) · TEARDOWN (D4a transition) · QNB_LINK (QuantumCluster.updateStatus)
```

| step | rule |
|---|---|
| entry (any thread, already in the host's BE domain — guaranteed by scope 2 / D2 / D4a / BE tick) | `tryAcquire`: if `token` free **and** queue empty and nothing reserved → CAS owner = (this thread, op) → run the AE2 body **inline, synchronously** → `release` in `finally`. If this thread already owns the token → run inline (AE2's own re-entrancy; nested destroy inside a calc). Otherwise → append Ticket, return immediately (void caller, ASYNC_SIDE_EFFECT). Never blocks, never spins. |
| release | owner := null; if queue non-empty → pop head, `reserved := head`, `BeDomainReady.enqueueTo(level, pos, beType, run(head), onTerminal)` (Core `enqueueForBlockEntityDomain`, re-resolved, revalidated). |
| run(ticket) in its domain | revalidate (host not removed, `level.getBlockEntity(pos) == host`, `!nw$removed`; TEARDOWN: cluster claimed by this request and not yet transitioned; QNB_LINK: cluster not destroyed) → CAS token from `reserved` → body → release. Stale → count, release, hand off next. |
| terminal (Core drop / level gone) | CALC: count `mbCalcDropped`, release reservation (a lost calc is re-triggered by the next neighbour update / reload — but counted, never silent). TEARDOWN: D4a's existing server-thread fallback (`ClusterTeardownCoordinator.java:194-208`) under token. |
| exceptions | body exception → release in `finally` (as AE2's own `finally`), counted and logged with stack; next ticket handed off. |

Result: exactly one writer of AE2's static flag at any time (restores AE2's single-chain assumption, no change to the
flag itself), MAIN is not the mutex, no thread ever waits for the lane, and all world mutation stays in the BE
execution domain of the host that owns the op.

### 4.3 What runs where

| element | lane (token/queue) | BE domain of the op's host (inline under token) | intents (other domains, revalidated) |
|---|---|---|---|
| MBCalculator flag set/clear | ownership (only token holder writes it) | the actual writes (AE2 code) | — |
| R1-R3 reads, decision D | — | yes | — |
| M1-M4 for a **single-domain** structure | — | yes (members are local) | — |
| members in another domain | — | **fenced** (§4.6) | B2 later: formation member intents (CLUSTER channel, like D4a) |
| teardown member updates / drops | — | — | D4a (unchanged) |
| neighbour callbacks emitted by mutations | — | routed by D2 as today (deferred → later separate lane ops) | yes |
| `Locatables`, QNB pair connection, Forge bus | — | yes, under token (global → serialized by the token) | — |
| grid events / connections | — | yes; kernel T0/P as today | — |

### 4.4 Call-contract classification (MAIN-hop facade classes)

| entry | class | justification |
|---|---|---|
| `onReady → calculateMultiblock` | ASYNC_SIDE_EFFECT | void; AE2 itself forms on first tick; ≤1 safe-point delay = TIMING_TOLERANCE |
| `neighborChanged → updateMultiBlock` (and QNB/pylon) | ASYNC_SIDE_EFFECT | void; already deferred by D2 |
| teardown (`breakCluster`, `breakClusterOnRemove`, `disconnect`) | ASYNC_SIDE_EFFECT | void; already deferred by D4a with captured identity |
| QNB `serverTick` / `onChangeInventory → cluster.updateStatus` | ASYNC_SIDE_EFFECT | void |
| readers of the result (`isFormed`, `getCluster`, crafting-service CPU list, `SpatialPylonCache`, `use()` GUI open) | not callers of the op — they read eventual state | lag ≤1 safe point, same as AE2's own first-tick formation |
| SYNC_VOID / SYNC_RESULT / SYNC_MUTATING_RESULT | **none found** | — |

### 4.5 Lock order, progress, foreign code

- Order stays `nw$life → P(G) → T0 → domains` (`N/glue/LifeAdmission.java:20-34`, `N/glue/GridPhaseGate.java:9-22`). The
  token is **try-only**: no thread ever blocks on it → it adds no wait edge → no new cycle. A token holder may block on
  T0/P/`nw$life` exactly as today's AE2 code does on MAIN; none of those holders waits for the token (they enqueue).
  CONFIRMED by construction (to be written as a proof in the implementation commit, like D1).
- The token is never held across a safe point, a mailbox wait, `Ae2Kernel.suspended`, or a Core enqueue *wait* (there
  is none — enqueue is non-blocking).
- Kernel M1 deferrals delivered at section exit run inside the token window if the section is inside the body (same as
  today's synchronous AE2 order). Foreign node-listener callbacks are already routed by TopologyCallbacks; if one
  reaches MB code on the same thread it is re-entrant inline (AE2 semantics), on another thread it enqueues.
- Progress: the head ticket is reserved during handoff → at most one safe point of the target domain per contended op.
  Run1 rate: 1 259 concurrent entries / 1 800 s ≈ 0.7 contentions/s → ≤ ~35 ms/s of lane stall (HYPOTHESIS until
  measured; `mbLaneHandoffNanos` histogram).

### 4.6 Single-domain fence for straddling structures (first safe form)

One generic mixin on `MBCalculator.calculateMultiblock` (covers AE2 + all three addon calculators, K4) wraps the two
mutation transitions **at their call sites**, before anything is mutated:
`@WrapOperation createCluster(...)` (NEW) and `@WrapOperation IAECluster.updateStatus(...)` (KEEP). Check: every
position in `[min,max]` resolves (`BeDomainReady.currentThreadIn`) to the current thread's domain. If not → skip the
mutation (no side effect, flag cleared by AE2's `finally`), count `mbStraddle`, **escalate**: pin `[min,max]` to MAIN
via CORE-PINZONE-001 position pins (`C/region/BlockEntityExecutionDomain.java` `pinPositionToMain`, ref-counted; the
mechanism NeighborAffinity already uses), re-enqueue a CALC ticket (it now runs on MAIN with all members local). Pins are
released by the D4a teardown completion of that structure. Reads (R1-R3) of foreign positions remain best-effort
(Core `RegionChunkView` ghost snapshot / read stamp for region threads, STRONGLY INDICATED by `C/region/RegionChunkView.java:5-27`;
MAIN reading region-owned chunks: UNKNOWN) and are counted (`mbForeignReads`) but not fenced — a stale read can only
cause a wrong *decision*, which the fence or the next neighbour update corrects; it cannot mutate a foreign member.
Split/merge during one op: the op runs inside one domain safe point; whether a domain boundary can move during a region
tick under free-running regions is UNKNOWN → re-check after M4 and count `mbMemberOffDomain` (must stay 0).

### 4.7 Identity, exactly-once, D4a

- Identity = existing primitives: host BE identity + `nw$removed` (lifecycle), BeDomainReady request generations for
  readiness, D4a per-cluster-instance CAS claim (`N/mixin/ClusterTeardownClaimMixin.java`), captured cluster in the
  ports (`N/glue/ClusterTeardownPorts.java`). No new generation scheme.
- CALC tickets are idempotent (recompute from current world); in B1 **no coalescing** (D2's exact-FIFO rule) — owner
  decision D3.
- D4a folds in: `runAt` (`ClusterTeardownCoordinator.java:91-112`) acquires the token around `transition.run()`; if busy
  → TEARDOWN ticket. The claim stays first (exactly-once is unchanged); only *when* the transition runs changes. Member
  and drop intents stay outside the token (they never reach a calc, §3.2).
- Unknown users (addon clusters, future mods): a second mixin on `MBCalculator.setModificationInProgress` (static, one
  target for every caller) **adopts** the token when free (held until the matching `set(null)`) and counts
  `mbFlagWithoutToken` when it is not — a generic verifier/safety net, never a ThreadLocal.
- `QuantumCluster.onUnload` (level unload, MAIN, not routed): runs under token adoption; if the token is busy at that
  moment it is counted (`mbUnloadContended`) — owner decision D7.

### 4.8 Core: exists vs needed

| need | Core today | B1 |
|---|---|---|
| run op in a BE's domain, non-blocking, re-resolved, terminal callback | `BlockEntityExecutionDomain.enqueueForBlockEntityDomain / isCurrentThreadIn` | reuse (via BeDomainReady) |
| pin positions to MAIN (straddle escalation) | CORE-PINZONE-001 `pinPositionToMain / unpinPositionFromMain` | reuse |
| named serialized executor that is neither MAIN nor a region | **none**; GMEIL `SERIAL_COMPAT` = enum + policy text only (`docs/gmeil/POLICY_MODEL.md:10`) | not needed (token domain lives in the addon) |
| "pin mod X except types Y" | **none** (`NestworldPins.isBePinned` = id set OR namespace set, `:218-226`) | needed only for B8 (§6) |
| co-location groups (positions that must resolve to one domain) | none | optional, B3+ (replaces §4.6 escalation) |

Stays in the addon: lane, tickets, fence, verifier, counters, fixtures, per-type adapters.

### 4.9 B3 note (not now)

Keyed lanes (per structure / per grid) would let two token holders run at once → both write AE2's static again. B3
therefore needs the static's accessors redirected to **per-key modification state owned by the lane ticket** (explicit
key, disjointness proven: non-intersecting envelopes, no QNB link between them) — not a ThreadLocal. Out of B1 scope.

## 5. B1-Q5 — fixtures and acceptance

Harness pattern: `N/glue/TeardownFixtures.java` / `TeardownClosureFixtures.java` (fixtures flag, step lists, `Hold`
hooks, claim observer, item accounting against world ItemEntities), `N/NestworldCompatAe2.java:137-170` command tree;
rig scripts `/root/airis-2026-10-25/teardown-run.sh`, `d4a-chain.sh`, `h-soak.sh` (fix K9 first).

Configs: **P** = production pins (`ae2` pinned); **U** = TEST-ONLY narrow unpin: `ae2` removed from pinned mods,
all `ae2:*` BE ids except `ae2:crafting_unit`, `ae2:crafting_storage`, `ae2:crafting_monitor`, `ae2:quantum_ring`,
`ae2:spatial_pylon` listed in `nestworld-be-pins.txt` (§6); `megacells:mega_crafting_*` unpinned as today. Lane switch
`-Dnestworld.compatAe2.mbLaneOff=true` = negative control.

| id | setup | lane OFF (neg. control) expectation | lane ON acceptance |
|---|---|---|---|
| MB-1 two-domain formation | U; CPU A in region R0 interior, CPU B in R1 interior; `Hold` hook inside A's `updateBlockEntities`; trigger B's readiness/neighbour update | ISE ≥1 (site 1/2) or silent skip counted by fixture probe | ISE 0; B's ticket queued then ran once; both CPUs formed (bounds, member count, FORMED states) |
| MB-2 calc vs foreign destroy | U; hold A's calc; tear down B (QNB or pylon destroy = unconditional set) on another domain | ISE (site 4/5) | 0; teardown claimed 1, transitioned once after A released |
| MB-3 straddle | U; CPU across R0 interior / border band | double formation or ISE possible (HYPOTHESIS) | `mbStraddle` ≥1, members position-pinned, formed once on MAIN, `mbMemberOffDomain` 0; pins released after teardown |
| MB-4 re-entrancy preserved | P and U; CPU re-formation (place a block joining two CPUs) | — | old clusters torn down exactly once, inventory drops exact (captured = delivered), job cancelled once, new cluster formed |
| MB-5 addon multiblock vs AE2 | U + AdvancedAE/neoecoae structure (mod-pinned, MAIN) while AE2 CPU recalcs on a region | ISE / `mbFlagWithoutToken` > 0 | `mbFlagWithoutToken` 0 (adopted), ISE 0 |
| MB-6 MEGA (production shape) | **P** (megacells unpinned); MEGA CPU in region interior + AE2 CPU on MAIN, churn both | reproduces K5 (ISE / concurrent > 0) — decides D6 | 0 |
| MB-7 lifecycle | P and U; unload/reload chunk, split/merge region under a held ticket | — | stale tickets counted, no off-domain member mutation, readyInitFailed 0 |
| MB-8 soak | U, h-soak shape (period-1 churn of crafting storage + network toggles), 30 min, both lane OFF/ON | OFF ≈ run1 (concurrent ≫0, ISE, readyInitFailed >0) | ON: MB concurrent 0, ISE 0, readyInitFailed 0, S1/S2/S3 0, teardown/members/drops accounting exact, off-domain 0 in every channel, crash 0, watchdog 0 |

Global acceptance (both P and U): `MB_MODIFICATION concurrent` 0; ISE 0; `mbFlagWithoutToken` 0; `mbMemberOffDomain`
0; `mbCalcDropped` accounted (emitted = inline + ran + stale + dropped); drops captured = delivered + retained(0); P
config results identical to 0.7.7 baseline (no regression; lane is a no-op cost when everything is on MAIN — measure).

## 6. B1-Q6 / B8 hook — pin decomposition

| Core capability | status |
|---|---|
| per-BE-type pins (`pinnedBeIds`, `nestworld-be-pins.txt`) | CONFIRMED (`NestworldPins.java:218-219`, `loadBe` `:722`) |
| per-mod pins (`pinnedMods`, namespace of the BE type id) | CONFIRMED (`:220-224`) |
| per-position pins (runtime, ref-counted) | CONFIRMED (`:228-270`) |
| `BE_POLICY_PINNED` resolution (type → position) | CONFIRMED (`C/region/NestworldDimensionRegion.java:311-345`) |
| **exclusion** ("ae2 except …") | **missing** — narrow unpin today = drop `ae2` from mods and enumerate every other `ae2:*` BE id in be-pins (works, but brittle across AE2 releases) |

First narrow-pin set after B1 ("unresolved shared types", stay MAIN): everything AE2-ecosystem **except** the MB
types that B1 makes safe. Concretely the B1 unpin candidates are only: `ae2:crafting_unit`, `ae2:crafting_storage`,
`ae2:crafting_monitor`, `ae2:quantum_ring` (QNB_LINK also touches `Locatables` + the remote cluster → keep pinned until
MB-2/MB-7 pass), `ae2:spatial_pylon`; plus `megacells:mega_crafting_unit/_storage/_monitor` (already unpinned — see D6).
Addon multiblocks (`advanced_ae:*`, `expatternprovider:assembler_matrix_*`, `extendedae_plus:*`, `neoecoae:*`) stay
mod-pinned (no addon audit; K4). Every other AE2 type (cables/parts host `ae2:cable_bus`, controller, drive, interface,
pattern provider, chest, energy cells, …) stays pinned until B4/B5 (storage domain + transit). Exact addon BE ids:
UNKNOWN — take them from a block-entity-type registry dump on pi-smoke (`/nestworld dumpregistries`), not from
`registry-baselines/` (different pack).

## 7. B0 — baseline design (do not run yet)

### 7.1 Metric → existing measurement → gap

| metric | existing source | covers | missing → cheapest addition (diagnostic-only, test-side) |
|---|---|---|---|
| AE2 MAIN time (total) | spark profiler on `Server thread`, package `appeng.*` + addon packages (memory: spark, not jstack) | sampled, complete (grid ticks, readiness, callables, deferred callbacks) | exact: detector `@WrapMethod` timing of `TickHandler.onServerTick` / `onLevelTick` (`A/hooks/ticking/TickHandler.java:236,295`) + `readyBlockEntities` (`:380`) |
| AE2 BE tick time | Core `TickAttribution.recordBlockEntityTick` per mod namespace (`/nestworld modload`, `C/region/NestworldCommand.java:3787-3791`) | exact ns, but cumulative since start, not split MAIN/REGION, megacells separate namespace | take two snapshots (delta); per-thread split = detector |
| MAIN BE phase | `SplitGuardTelemetry.mainBePhase` EWMA (`/nestworld splitguard`) | all pinned BEs per dimension (not AE2-only) | none (context metric) |
| grid tick cost | addon `gridcensus start/report` per grid levelEnd/serverEnd ns (`N/mixin/GridMixin.java:80-94`, `N/glue/GridCensus.java`) | exact per grid | per-service split of serverEnd (Crafting / Storage / Energy / Pathing / Spatial / TickManager): detector wraps of each service's `onServerEndTick` / `onLevelEndTick`, census-gated |
| import/export/interface activity | `gridcensus devices` (device class ns, calls, domain) (`TickManagerServiceMixin.java:186`) | exact | none |
| storage ops/tick | — | — | detector counters on `NetworkStorage.insert/extract` (calls, MODULATE/SIMULATE, ns, thread MAIN/REGION, caller class top-N) |
| crafting CPU time | inside serverEnd only | — | detector wrap of `CraftingCpuLogic.tickCraftingLogic` (ns, CPUs/tick) + `CraftingService.onServerEndTick` |
| MBCalculator time | detector counts only (`R/mixin/MBCalculatorDetectMixin.java`, `ClusterDestroyDetectMixin.java`, `R/D2Detect.java:62-66`) | calls, offMain, concurrent | add ns + per-family + `skippedForeignFlag` (K3a) to the same wrappers |
| callbacks | `nwcompatae2 stats` cb/st/bk/cl emitted/inline/deferred/stale/dropped | counts | ns of deferred delivery on MAIN (BE_DOMAIN main pass) — detector wrap of `HostDomainCallbackDelivery.runInDomain` by name |
| transit ops (B5 does not exist) | NeighborAffinity pins (`neighborAffinity{pinned/live}`) as proxy | positions pulled to MAIN | detector: count off-MAIN callers into AE2 capability handlers / `MEStorage` (what B5 would turn into transit) |
| MAIN MSPT contribution | `/nestworld mspt` percentiles; spark AE2 share | — | ratio = AE2 MAIN ns / MAIN tick ns per window (from the above) |
| BE-domain backlog | `/nestworld bedomain` (`BlockEntityExecutionDomain.stats`) | accepted/executed/pending | none |

Placement: all new counters in the **test-only race-detector mod** (already used on pi-smoke; production addon
untouched). Run B0 twice: **B0-perf** without the detector (spark + Core + addon census only — overhead-free numbers) and
**B0-count** with the detector (counts and ns).

### 7.2 Procedure (pi-smoke, production-like) — for later execution

1. Preconditions (abort on any failure, record to evidence):
   - no other JVM in `/root/pi-smoke` (`pgrep -x java` + `readlink /proc/<pid>/cwd`; never `pgrep -f`). **At audit time
     one pi-smoke JVM was running (affinity 0-7)** — coordinate with Track A.
   - live has 0 players — determined **without** sending commands to live (Server List Ping status query or the live
     `logs/latest.log` join/leave balance).
   - pins byte-identical: `cmp` of `nestworld-pinned-mods.txt`, `nestworld-be-pins.txt`, `nestworld-pins.txt`,
     `nestworld-pin-reasons.txt` (+ `nestworld-cascade-safe-be.txt` absent on both) vs `/root/project-infinity/world/data/`
     (identical at audit time); repeat after the run (autoPin is on → runtime pin changes = invalid run).
   - mod jars sha256 identical to live for the AE2 ecosystem + Core + addon.
2. Fix `h-soak.sh` K9 (call `gridcensus start` at t0, `report` + `devices` at end).
3. `taskset -c <fixed 8-CPU set disjoint from nothing in particular, e.g. 28-35>` for the pi-smoke JVM; record the
   JVM's `availableProcessors`, region-thread and Netty counts (Core's CPU planner auto-tunes from the visible CPU count —
   keep the set identical for every later B-step comparison).
4. Workload: the production world copy, **no artificial churn** (period-1 churn is not a baseline — CLOSURE-001 "H TPS ≈
   7.7"); keep the AE2 bases loaded by forceloading the chunks of the top-N grids by node count (from a census at t0),
   recorded as the workload descriptor (grid count, node count, CPU count, devices).
5. Warm-up 5 min → snapshot A (modload, nwcompatae2 stats, nwae2static/d2, bedomain, mspt) → `gridcensus start` + spark
   `profiler start --thread "Server thread"` → 20 min window → snapshot B → `gridcensus report/devices`, spark stop →
   normal stop. Metrics = B − A.
6. Repeat ×2 (variance), then B0-count with the detector.

## 8. Open questions

1. Can a domain boundary move during one region tick under free-running regions (affects §4.6 re-check)? UNKNOWN.
2. MAIN-thread reads of region-owned chunks during region ticks (R1-R3 from MAIN): protected? UNKNOWN.
3. ExtendedAE `ClusterAssemblerMatrix` flag usage shape (not decompiled); addon calculators' internal world access. UNKNOWN.
4. Do MEGA crafting CPUs exist in the live world (K5 exposure)? UNKNOWN — answer with a read-only census on pi-smoke
   (world copy), not on live.
5. QNB `onChangeInventory` from foreign inserting threads (pipes into the link's singularity slot) — reachable off-domain?
   HYPOTHESIS.

## Decisions requested from owner

- **D1 lane form**: accept "MB lane = logical serialization domain (try-only token + FIFO tickets), AE2 bodies execute in
  the host's BE execution domain" as the first safe form of "one serialized lane off MAIN" (dedicated thread and full port
  rejected, §4.1)?
- **D2 straddling structures**: fence + escalate to MAIN position pins (recommended, §4.6) vs formation member intents now
  (B2-complete, larger) vs keep those types pinned.
- **D3 calc coalescing**: none in B1 (exact FIFO, recommended) or allow per-host coalescing of idempotent CALC tickets.
- **D4 handoff**: reserve the token for the in-flight head ticket (FIFO, ≤1 safe-point stall, recommended) vs opportunistic.
- **D5 addon multiblocks** (AdvancedAE / ExtendedAE / neoecoae): generic coverage only (base `calculateMultiblock` wrap +
  setter adoption/verifier) in B1, or also per-mod entry adapters (StartupGate SHA-gated)?
- **D6 megacells (production, K5)**: add `megacells:mega_crafting_unit/_storage/_monitor` to live be-pins now as a separate
  production fix, first confirm with MB-6 / world census, or leave until B1 ships?
- **D7** `QuantumCluster.onUnload` under a busy token: count-only, or force MAIN inline at level unload?
- **D8 B8 Core hook**: add a generic "pinned-mod exclusions" list to `NestworldPins`, or enumerate `ae2:*` ids in be-pins?
- **D9 B0**: instrumentation in the test-only detector (recommended); CPU set; forceload of the top-N production grids;
  approve fixing `h-soak.sh` census call (K9).

STOP — no implementation before review.
