# AE2-COMPAT-CLOSURE-001 — Applied Energistics 2: from KNOWN RISK to SUPPORTED / VERIFIED

Owner TZ received 2026-10-28 (sections 0–60 complete; the message was cut inside section 61 "closure matrix" —
the rest of 61 and anything after it is still to be received).

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
| `Grid.ITERATION_BUFFER` (one ArrayList for **all** grids) | `notifyAllNodes`: throws ISE if non-empty, then addAll / iterate / clear | **NEEDS_EVIDENCE → likely NEEDS_FIX** | `EnergyService.publicPowerState → notifyAllNodes(POWER)` is only deferred to the end of the grid section **on the same thread** (`KERNEL.defer`), so it can run on a region thread; `PathingService.postBootingStatusChange → notifyAllNodes(GRID_BOOT)` runs from `onServerEndTick` (main). Two grids on two threads share the buffer. | detector + fixture (two grids, power flips on a region thread vs boot on main) |
| `MBCalculator.modificationInProgress` (static WeakReference) | set/cleared around multiblock (re)formation of `QuantumCluster`, `CraftingCPUCluster`, `SpatialPylonCluster`; throws ISE "A modification is already in-progress" if another cluster holds it | **NEEDS_EVIDENCE → likely NEEDS_FIX** | none found | detector + fixture (two clusters re-forming in two regions) |
| `FakePlayer.FAKE_PLAYERS` (WeakHashMap, unsynchronized) | `Platform.getFakePlayer` → planes / placement | **NEEDS_EVIDENCE** | none found | find thread of callers (planes tick on owner region) |
| `TransformLogic.fluidCache / explosionCache / anyFluidCache` | `ItemEntityMixin` (item entity tick → region threads); IdentityHashMap `computeIfAbsent`, lazy init | **NEEDS_EVIDENCE → likely NEEDS_FIX** | none found | detector + fixture (item entities in fluid in two regions after a reload) |
| `GridEventBus.EVENTS` (HashMap) | `postEvent → getSubscriptions → computeIfAbsent` at runtime (first post of an unseen event class inserts) | **NEEDS_FIX (protected)** | Core legacy `static_collection_race_transformer` swaps it to a concurrent map (registered in production `coremods.json`) — AE2-specific protection living in Core, against the addon > Core rule | decide: keep as legacy exception or move into the addon |
| `QuantumBridgeBlockEntity.singularitySeed` (int ++) | QNB frequency = `time*100 + seed++ % 100` | low risk (collision needs same ms and lost increment) | — | document |
| `TickManagerService.MONITORING_ENABLED`, `AELog.*Enabled`, `PhaseSorting.ENABLE_CYCLE_WARNING` | config/debug flags | IMMUTABLE_AFTER_BOOT (by use) | — | confirm writers |
| registries: `GridServices.registry`, `Upgrades.*`, `StorageCells.*`, `PatternDetailsHelper.DECODERS`, `P2PTunnelAttunement.*`, `BlockEntityMoveStrategies.*`, `GridLinkables.registry`, `Settings.SETTINGS`, `AEBaseBlockEntity.REPRESENTATIVE_ITEMS`, `HotkeyActions.REGISTRY`, `EventFactoryImpl.ARRAY_BACKED_EVENTS` | written during mod init | IMMUTABLE_AFTER_BOOT (to confirm: no runtime writer) | — | writer scan |
| `VoxelShapeCache.CACHE`, `CompassService` cache | Guava `LoadingCache` | THREAD_SAFE | — | none |
| `CraftingService.CRAFTING_POOL` | crafting calculation executor (AE2's own async design) | by design — audit in AE2-CRAFTING-001 | — | P5 |

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
