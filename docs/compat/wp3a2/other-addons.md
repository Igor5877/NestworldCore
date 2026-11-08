# AE2-STORAGE-DOMAIN-001 WP3-A.2 — other AE2 addons (bytecode audit)

Date: 2026-11-07. Read-only. Method: `javap -p -c -v` on the extracted jars under
`/root/ae2-pack-audit-2026-11-07/x/`, checked against AE2 15.4.10 source
(`/root/ae2-src/forge-v15.4.10`). Raw material is in `wp3a2/other-addons-raw/`:
`mixins-all.txt` (every mixin config → target, injection type, method, `at`, handler), `scan-<mod>.txt`
(per-class flags: MEStorage / IStorageProvider / cells / StorageService / StorageHelper / Crafting /
getMachines / watchers / external-storage strategies / foreign caps / async / tick / static-mutable),
`netops-<mod>.txt` (methods that call network storage, their callers inside the class, and the tick
entry points).

Context assumed: in NestWorld, AE2 grid ticks, `CraftingService.onServerEndTick`, grid-service
`onLevelEndTick`/`onServerStartTick` run on **MAIN**. BlockEntityTicker `tick`/`serverTick`, entity
ticks, `Item.inventoryTick` (player tick) and Botania mana-burst/spark logic run on **REGION**
threads. The planned split: REGION does the bus's neighbour-inventory work, MAIN does the network
insert/extract, and an explicit transit buffer carries items between them.

Confidence labels: **C** = CONFIRMED from bytecode; **S** = STRONGLY INDICATED (structure confirmed,
one link inferred, e.g. obfuscated callee or ticker registration not traced); **H** = HYPOTHESIS.

---

## 0. Cross-cutting facts (AE2 itself; they govern every addon below)

1. **The strategy does both halves of the transfer.** `ImportBusPart.doBusWork` builds
   `StackTransferContextImpl(grid.getStorageService(), energy, …)` and calls
   `importStrategy.transfer(ctx)` (ImportBusPart.java:46-57). `ExportBusPart.doBusWork` calls
   `getExportStrategy().transfer(ctx, what, amount)` (ExportBusPart.java:148,158). Inside
   `transfer`, the strategy calls the foreign capability **and**
   `ctx.getInternalStorage().getInventory().insert/extract` (or `StorageHelper.poweredExtraction`)
   itself. Every addon key-type strategy works the same way (§F-1, §K-1, §B-1). So a transit split
   cannot just move `transfer()` to REGION. It needs a REGION-side `StackTransferContext` whose
   `getInternalStorage()` returns the transit buffer (plus energy accounting), and a MAIN-side drain.
   **C**
2. **Export bus crafting push runs on MAIN.** `ExportBusPart.insertCraftedItems` → `getExportStrategy().push(...)`
   (ExportBusPart.java:193-196) is called by the crafting CPU on MAIN, so it is a foreign
   capability call made from MAIN. The same holds for AdvancedAE's `StockExportBusPart` (§A-2). **C**
3. **Every network insert first enters the crafting service.** `CraftingServiceStorage` mounts its
   `MEStorage` at `Integer.MAX_VALUE` priority, and `insert` → `craftingService.insertIntoCpus(...)`
   (CraftingServiceStorage.java). So every network insert, including any MAIN-side transit drain,
   runs the `insertIntoCpus` overrides of crazyae2addons, AdvancedAE and neoecoae, and AppliedFlux's
   `CraftingServiceStorage$1.insert` HEAD hook. **C**
4. **An AE2 Interface with no config exposes the whole network** as `Capabilities.STORAGE`
   (`InterfaceLogic.getInventory()` returns `networkStorage` when `!hasConfig`; InterfaceLogic.java:245-251,
   601). Any foreign code that looks up `Capabilities.STORAGE` reaches MAIN-owned network storage
   directly. Applied Botanics Corporea (§B-4) does this from REGION. **C**

---

## 1. crazyae2addons 2.6.2 (`net.oktawia.crazyae2addons`)

Mixin config `mixins.crazyae2addons.json`, plugin `mixins.Plugin`. The plugin's `shouldApplyMixin`
enables `MixinAAE`, `MixinAAE2`, `AAEExecutingCraftingJobAccessor` and `AAECraftingServiceMixin` only
when `advanced_ae` is loaded. It enables `MixinCraftingService` only when `advanced_ae` is **not**
loaded. `MixinCraftingCpuLogicAE2` vs `…AE2CL` is chosen by a bytecode probe for `trySubmitJob(...Z)` (ae2cl). **C**

Mixins (storage/crafting-relevant):
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| AAECraftingServiceMixin (prio 1200) | `CraftingService` | **@Overwrite `insertIntoCpus`**; @Redirect `Set.iterator` in `onServerEndTick` and `getCpus` | Inserts into CPUs in priority order: AE2 clusters sorted by prio, then **`grid.getMachines(AdvCraftingBlockEntity.class)` on every insert** → `AdvCraftingCPULogic.insert`. Also re-sorts the CPU tick order. |
| MixinCraftingService (no AAE) | `CraftingService` | @Inject HEAD cancellable `insertIntoCpus`; @Redirect `onServerEndTick` iterator; @Inject HEAD `getCpus` | Same priority insert, AE2-only |
| MixinCraftingServiceCL | `CraftingService` | @Inject TAIL `<clinit>` | Rewrites the static CPU comparators (`FAST_FIRST_COMPARATOR` etc.; static state written once) |
| MixinCraftingCpuLogicAE2 / AE2CL, MixinAAE | `CraftingCpuLogic` / `AdvCraftingCPULogic` | @Redirect `getProviders`/`pushPattern` in `executeCrafting`; @Inject HEAD/RETURN `trySubmitJob`; @ModifyExpressionValue `insert`→`AEKey.matches` | Per-requester pattern filtering, final-output matching |
| CraftingTreeNodeMixin | `CraftingTreeNode` | @Redirect `getCraftingFor` in `buildChildPatterns` | Filters patterns for the requester (runs on the AE2 crafting-calc thread, like AE2) |
| MixinListCraftingInventory | `ListCraftingInventory` | @Redirect `KeyCounter.get` in `extract` | CPU inventory accounting tweak |
| MixinPatternProviderLogic (1100), MixinAAE2 (1200) | `PatternProviderLogic` | @ModifyExpressionValue `List.contains` / `getCraftingLockedReason` in `pushPattern`; NBT TAIL | Pattern push gating |
| MixinCraftingCpuCluster | `CraftingCPUCluster` | NBT TAIL, `getName` HEAD | CPU priority persistence, owner name |
| Abstract/StorageLevelEmitterPartMixin | level emitters | TAIL inject | uuid/NBT |
| MixinEntity | `Entity` | HEAD on `position/blockPosition/distanceTo*/getEyePosition/isInWall` | Wormhole "anchor" (not storage, but it is a global hot path; see risks) |
| MixinServerPlayer | `ServerPlayer.tick` | @Redirect `stillValid` | Remote menu validity |
| ViewCellItemMixin, RestrictedInputSlotMixin | view cells | HEAD/redirect | filter |

Features:
| # | Feature | 1 mount | 2 foreign cap | 3 custom storage | 4 crafting cb | 5 async | 6 static | 7 thread today | Class | Conf |
|---|---|---|---|---|---|---|---|---|---|---|
| C-1 | insertIntoCpus overrides (above) | – | – | – | yes, **getMachines per insert** | – | comparators (clinit) | MAIN (CPU tick, and every network insert via §0.3) | MAIN_NATIVE | C |
| C-2 | `NBTExportBusPart` **extends `IOBusPart`, own `doBusWork`**: `getCachedInventory` scan + AE2 `StackExportStrategy.transfer` with its own `StackTransferContextImplementation` | – | via strategy | – | – | – | – | MAIN (grid tick) | bus path: TRANSIT candidate only if handled per class (bypasses `ExportBusPart.doBusWork`) | C |
| C-3 | `MobExportBus` **extends `ExportBusPart`, overrides `doBusWork`**: `MEStorage.extract` of mob keys, then spawns the mob in the world | – | world entity spawn, not a cap | mob keys | – | – | – | MAIN | bus path, the external side is a **world write** | C (spawn call obfuscated: S) |
| C-4 | `NBTStorageBusPart` (`IStorageProvider`, `IGridTickable`, `StorageBusInventory`) | yes | yes (storage bus) | yes | – | – | – | MAIN | EXTERNAL_MOUNT | C |
| C-5 | `MobFormationPlane` (`IStorageProvider`, `InWorldStorage`: a network insert spawns a mob) | yes | world | yes | – | – | – | MAIN (on any network insert) | EXTERNAL_MOUNT (world sink) | C |
| C-6 | `MobAnnihilationPlane` (`IGridTickable`; reads the mob at its position → `insertIntoGrid`) | – | world read | – | – | – | – | MAIN | MAIN_NATIVE (cross-region world read) | C |
| C-7 | Grid-tick machines: AutoBuilderBE (places/breaks blocks + network reserve), AutoEnchanterBE, ApothAutoEnchanterBE, MobFarmControllerBE, SpawnerExtractorControllerBE, EjectorBE (provider-like push), CraftingSchedulerBE (`ICraftingRequester.insertCraftedItems`), PenroseControllerBE→PenroseInjectionPortBE, DisplayPart | – | some (Ejector push; AutoBuilder world) | – | requester/push | – | – | MAIN | MAIN_NATIVE | C |
| C-8 | `HackedPatternProviderLogic` (extends `PatternProviderLogic`; network ops in `pushPattern`/`returnToStorage`) | – | provider target | – | push | – | – | MAIN (CPU tick) | MAIN_NATIVE | C |
| C-9 | `WirelessNotificationTerminal.inventoryTick` (`m_6883_`) → `sendHudUpdate` reads network storage; `PortableAutobuilder.pasteNow` extracts from network | – | – | – | – | – | – | **REGION (player tick / item use)** | CUSTOM_SHARED_STATE (REGION read/write of MAIN-owned storage) | C (caller m_6883_) |
| C-10 | Static: `Utils.voltagesMap`, `ClipboardPacket.packetAccumulator`, `PortalJadeContext.*`, client renderers | – | – | – | – | – | yes | packet/client | CUSTOM_SHARED_STATE (minor) | C |

Async: only FML `enqueueWork` (CompletableFuture) in the packet classes. No executors on the storage path. **C**

---

## 2. neoecoae 20.4.2 (`cn.dancingsnow.neoecoae`)

Mixins (`neoecoae.mixins.json`, no plugin):
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| CraftingServiceMixin | `CraftingService` | @Inject HEAD `beginCraftingCalculation` (ECO planner); TAIL `addNode/removeNode/updateCPUClusters`; HEAD `onServerEndTick` (**`tickComputationCpus`**); INVOKE `Set.clear` in `onServerEndTick` (merge); HEAD `submitJob` (both overloads); **RETURN `insertIntoCpus`**; RETURN `getRequestedAmount`; HEAD `hasCpu` | ECO computation CPUs as AE2 CPUs |
| CraftingServiceCpuListMixin (900) | `CraftingService.getCpus` RETURN | | add ECO CPUs |
| ae2.CraftingCpuLogicFastPathMixin (2000), aae.AdvCraftingCpuLogicFastPathMixin | `CraftingCpuLogic` / `AdvCraftingCPULogic` | HEAD `tickCraftingLogic` (register output route), HEAD `executeCrafting` (dispatch fast path) | Fast path for external CPU jobs |
| StorageCellsMixin | `StorageCells.getCellInventory` RETURN | | ECO cell inventories in every drive/chest |
| aae.TileExIOPortMixin | ExtendedAE `TileExIOPort.tickingRequest` | @WrapOperation `StorageCells.getCellInventory` | ECO cells in the EAE IO port |
| IOPortMenuMixin, aae.ContainerExIOPortMixin, PatternEncodingTermMenuMixin, CraftingCPUMenuMixin, CraftingStatusMenuMixin | menus | | UI |

Features:
| # | Feature | Findings | Thread today | Class | Conf |
|---|---|---|---|---|---|
| N-1 | Crafting bridge | `insertIntoCpus` RETURN → `NeoECOCraftingServiceBridge.insertIntoCpus(grid,…)` → `getComputationClusters(grid)` = **`grid.getMachines(ECOComputationSystemBlockEntity)` on every insert** → `ECOCraftingCPULogic.insert`. CPU ticking runs from `onServerEndTick` HEAD. | MAIN (and every network insert, §0.3) | MAIN_NATIVE | C |
| N-2 | ECO planner | `ECOPlanningService.PLANNING_POOL` = `Executors.newCachedThreadPool`. The snapshot is captured on the caller thread by `ECOAE2SnapshotFactory.capture` (`copyInventory` reads grid storage); solving runs on the pool. Static `GRAPH_CACHE` keyed by `ICraftingService`. `ECOPlanningHostLease.ACTIVE_JOBS` static, guarded by `monitorenter`. | caller of `beginCraftingCalculation` (menu/requester thread), then the pool | CUSTOM_SHARED_STATE + async | C (pool); S (snapshot taken only on the caller thread) |
| N-3 | ECO storage (Drive / StorageSystem / infinite domain) | `ECODriveBlockEntity`/`ECOStorageSystemBlockEntity` implement `IStorageProvider` and mount `ECOStorageCell`/`ECOInfiniteStorage`. Backends: `FileBackedECOStorageBackend` (all public methods `synchronized`; WAL/checkpoint via `ECOStorageIoWorker` static single-thread `walExecutor`/`checkpointExecutor`, `Future` checkpointWrite, `volatile degraded`); `ECOCellStorageManager` static `synchronized` maps CELLS/OWNERS/OWNER_IDS; `ECOInfiniteStorageDomains` static synchronized ENTRIES; `FileBackedInfiniteStorageEngine` synchronized + submit. | network ops on MAIN; persistence I/O on its own threads | CUSTOM_SHARED_STATE (self-locked, own I/O threads) | C |
| N-4 | **ECOStorageSystemBlockEntity BE tick** (`tick(Level,BlockPos,BlockState)`, NEBlockEntityTicker) | Calls `updateInfiniteStorageMode` → `runInfiniteMigrationStep`/`migrateDriveToDomain`/`restoreInfiniteDomainToNormalStorage`/`pollInfiniteStorageLoad`/`syncInfiniteModeChanges` (moves cell contents, **`IStorageProvider.requestUpdate`** → NetworkStorage remount), plus `flushInfiniteEngineBudgeted`. `tickingRequest` (MAIN) calls the same `updateInfiniteStorageMode`. | **REGION (BE ticker) and MAIN (grid tick)** | CUSTOM_SHARED_STATE: REGION mutates MAIN-mounted storage | S (BE ticker wiring through `NEBlockEntityTicker`/registration not traced to `getTicker`) |
| N-5 | Storage interface | `tickingRequest` → `transferStorageInterfaceContents` → `MEStorage`↔`MEStorage` between the ECO host and the interface's grid (`transferPowered`, `poweredInsert`). No foreign caps. | MAIN | MAIN_NATIVE | C |
| N-6 | Crafting CPU logic shared state | Static `ECOCraftingCPULogic.JOB_OUTPUT_ROUTES`, `SLOW_PATH_NETWORK_BUDGETS` (WeakHashMap keyed by CraftingService), `ECOExternalCpuOutputRoutes.ROUTES` (ConcurrentHashMap), `ECOCraftingFastPathCache.ACTIVE_CACHES` (WeakHashMap + monitorenter), `ECOFastPathDiagnostics` static counters | MAIN | CUSTOM_SHARED_STATE (correct only while everything stays on MAIN) | C |
| N-7 | ECOIntegratedWorkingStation (`IGridTickable`, `pushOutResult` → network + `ExternalStorageStrategy` to neighbours), ECOCraftingSystem/Worker (grid tick, coolant/power) | | MAIN | MAIN_NATIVE (foreign push on MAIN) | C |
| N-8 | ECO energy cell | covered by the earlier energy audit (`../eco-energy-cell.javap`); not re-audited here | – | – | – |

Bus paths: none. neoecoae has no import/export bus parts; `IOPortMenuMixin` changes only a menu slot. **C**

---

## 3. AdvancedAE 1.3.6 (`net.pedroksl.advanced_ae`)

Mixins (`mixins.aae.json`, plugin `AAEMixinPlugin`):
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| cpu.MixinCraftingService | `CraftingService` | @Inject `onServerEndTick` at FIELD `lastProcessedCraftingLogicChangeTick` (**`tickAdvClusters1` → `AdvCraftingCPULogic.tickCraftingLogic`**) and at FIELD `interests` (`getAllWaitingFor`); TAIL `addNode/removeNode/updateCPUClusters` (`getMachines`); **@Overwrite `insertIntoCpus`** (AE2 clusters, then own `advCraftingCPUClusters` set; **no** `getMachines` per insert); INVOKE_ASSIGN `submitJob`; RETURN `getCpus`/`getRequestedAmount`; HEAD `hasCpu` | Quantum Computer CPUs |
| cpu.MixinCraftingCPUMenu | `CraftingCPUMenu` | init/setCPU/cancel/removed/broadcastChanges | UI |
| MixinPatternProviderLogic | `PatternProviderLogic.getActiveSides` TAIL | | directional provider |
| MixinIOBusPartInvoker | `IOBusPart` | @Invoker `callUpdateState()` | used by its own bus parts |
| MixinPatternEncodingTermMenu, appflux.*, eae.MixinContainerPatternModifier (@Overwrite `replace`/`modify`) | menus / AdvPatternProvider | | UI, upgrades |

**Overwrite conflict:** both AdvancedAE (`cpu.MixinCraftingService`, default priority 1000) and crazy
(`AAECraftingServiceMixin`, priority 1200) `@Overwrite CraftingService.insertIntoCpus`. Under Mixin
priority rules the higher-priority overwrite is applied last and wins, so in this pack the effective
body is crazy's (with `getMachines` on every insert). neoecoae's `@Inject RETURN` then wraps
whichever body is in place. **S** (needs a runtime `-Dmixin.debug.export` dump to confirm)

Features:
| # | Feature | Findings | Thread today | Class | Conf |
|---|---|---|---|---|---|
| A-1 | Quantum Computer crafting | above; `AdvCraftingCPULogic.storeItems` ← `finishJob`/`tickCraftingLogic` does network inserts | MAIN | MAIN_NATIVE | C |
| A-2 | **Custom buses**: `ImportExportBusPart` and `StockExportBusPart` **extend `ExportBusPart`**; `AdvancedIOBusPart` extends `StockExportBusPart`. All override `doBusWork`. ImportExport/AdvancedIO run **both** `StackImportStrategy.transfer` and `StackExportStrategy.transfer` in one `doBusWork`. StockExport reads foreign stock through `StorageReader`/`StorageReaderImpl` (foreign caps; AppMek chemical reader), then `attemptCrafting` → `push(SIMULATE)` → `requestCrafting`. Custom `StackTransferContextImpl` and `FilteredImportStackTransferContext` (`canInsert` reads network). | MAIN (grid tick) | bus path. A transit split keyed on `ImportBusPart`/`ExportBusPart.doBusWork` **does not reach these**. A split keyed on `IOBusPart.tickingRequest` **would** reach them, along with foreign stock reads and crafting requests mixed in. | C |
| A-3 | `AdvPatternProviderLogic` (own Ticker, `doWork` network ops; pushes via `AdvPatternProviderTargetCache` = `Capabilities.STORAGE`/`ExternalStorageFacade`) | | MAIN | MAIN_NATIVE (foreign push on MAIN) | C |
| A-4 | `QuantumCrafterEntity` (`IGridTickable`; craft from network, `exportToMe`/`exportSendList`), `ReactionChamberEntity` (`IGridTickable`; auto-export to neighbours via `ExternalStorageStrategy.createWrapper`, FE cap) | | MAIN | MAIN_NATIVE | C |
| A-5 | **Quantum Armor** `UpgradeCards.autoFeed`/`autoStock` via `QuantumHelmet/Chestplate/Leggings/Boots.m_6883_` (Item.inventoryTick) → `IUpgradeableItem.tickUpgrades` → linked grid `MEStorage.extract/insert/getAvailableStacks` | | **REGION (player tick)** | CUSTOM_SHARED_STATE: REGION read/write of MAIN-owned storage | C |
| A-6 | `xmod.appflux.AppliedFluxPlugin.rechargeAeStorageItem/rechargeEnergyStorage` (network FE extraction to recharge armor) | | REGION (armor tick path) | as A-5 | S |
| A-7 | Static: `AdvancedAE.INSTANCE`, `AAEConfig.INSTANCE`, `QuantumCrafterTermMenu.inventorySerial` (static counter, menu thread) | | | minor | C |

---

## 4. AppliedFlux 1.3.7 (`com.glodblock.github.appflux`)

Mixins (`mixins.appflux.json`):
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| MixinCraftingServiceStorage | `CraftingServiceStorage$1.insert` | @Inject HEAD cancellable | **FluxKey → returns 0** (FE never enters CPUs); on the network-insert path (§0.3) |
| MixinCreativeEnergyCellBlockEntity | `CreativeEnergyCellBlockEntity.injectAEPower` | @Overwrite | energy |
| MixinInterfaceLogic / MixinPatternProviderLogic (+ extendedae variants, AdvancedAE's appflux.*) | `<init>` TAIL, `onUpgradesChanged` TAIL, NBT, drops | | induction-card upgrade → registers with `EnergyDistributeService` |
| MixinPatternProviderBlock/Part (+EAE) | `neighborChanged`/`onNeighborChanged` HEAD | | invalidate cached FE target |
| MixinChargerBlockEntity | wirelesschargers `ChargerBlockEntity.update` | @Redirect `getBlockEntity` | blacklist |

Features:
| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| F-1 | **FE key strategies** registered in `AFRegistryHandler`: `FEStackImportStrategy`, `FEStackExportStrategy`, `FEExternalStorageStrategy(+FEStorageWrapper)`, `FEContainerItemStrategy` | `transfer()`: `BlockApiCache<IEnergyStorage>.find` → network `insert(SIMULATE)` → `extractEnergy` → network `insert(MODULATE)` → on shortfall `receiveEnergy` (put back). Export: `poweredExtraction(SIMULATE)` → `receiveEnergy` → `poweredExtraction(MODULATE)` → re-insert into the network on refusal. `push()` is foreign only. | MAIN today (any AE2 import/export bus with an FE key) | **bus path; the strategy mixes foreign and network calls**. TRANSIT_SAFE only after the strategy contract is split (§0.1). | C |
| F-2 | FE cells (`FluxCellInventory`, `FECellHandler`, creative) | | MAIN | MAIN_NATIVE | C |
| F-3 | **Flux Accessor capability** (`TileFluxAccessor.getCapability` → `CapAdaptor` → `NetworkFEPower.receiveEnergy/extractEnergy` = network `MEStorage.insert/extract`); `GTEUPower` (GT `IEnergyContainer`), `FluxNetworkPower` (`IFNEnergyStorage`) adapters | Any neighbour mod can call into network storage | **REGION (foreign machine/cable ticks); Flux Networks on its own tick** | CUSTOM_SHARED_STATE: inbound foreign calls into MAIN-owned storage | C |
| F-4 | `EnergyDistributeService` (grid service) `onLevelEndTick` → `TileFluxAccessor.distribute()` (`level.getBlockEntity(neighbour)` + `EnergyHandler.getHandler` → `receiveEnergy` with network extract) and `charge()` (`chargeNetwork`) | `activeNodes` is an unsynchronized IdentityHashMap set; `wake` comes from `setServiceHost` (addNode) | MAIN | MAIN_NATIVE with foreign calls on MAIN | C |
| F-5 | Static config (`AFConfig.*`), `AFItemAndBlock.*` | | | minor | C |

---

## 5. appliede 0.14.3-fix (`gripe._90.appliede`)

Mixins (`appliede.mixins.json`, plugin): `crafting.CraftingServiceMixin` (`getProviders` RETURN:
intercepts transmutation patterns), `crafting.CraftingPlanMixin` (`<init>` RETURN),
`misc.MEStorageMenuMixin` (@Redirect `StorageHelper.poweredExtraction` in `tryFillContainerItem` →
`emcExtraction`), tooltip/packet mixins (`MEInventoryUpdatePacket`, `GridInventoryEntry`), ae2wtlib terminal registration.

| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| E-1 | **EMC storage**: `EMCModulePart` (`IStorageProvider`, `ICraftingProvider`, `IGridTickable`) mounts `EMCStorage` from the `KnowledgeService` grid service | `EMCStorage.insert/extract/insertItem/extractItem` mutate ProjectE **`IKnowledgeProvider` (player capability)**: `setEmc`, `addKnowledge`, and `KnowledgeService.syncEmc()` (packets). Uses `TransmutationOffline` (accessor) for offline players. | MAIN (any network op routed into EMC storage) | CUSTOM_SHARED_STATE: MAIN mutates player-owned capability state | C |
| E-2 | **`EMCImportBusPart` / `EMCExportBusPart` extend `IOBusPart`, own `doBusWork`** | Direct `BlockEntity.getCapability(EMC_STORAGE_CAPABILITY / ITEM_HANDLER)`, `ExternalStorageFacade.of(IItemHandler)` extract → `EMCStorage.insertItem` (learns + converts); export: `EMCStorage.extractItem` → `ItemHandlerHelper.insertItem`; `IEmcStorage.extractEmc/insertEmc`. **Does not use `StackWorldBehaviors` strategies.** | MAIN (grid tick) | bus path. Not TRANSIT_SAFE: the "network" side is player-capability state, and the bus bypasses the strategies. | C |
| E-3 | `EMCInterfaceLogic` (`IGridTickable`, `WrappedEMCStorage`), `EMCInterfacePart/Block` (foreign item cap) | | MAIN | MAIN_NATIVE | C |
| E-4 | `LearnAllItemsPacket` handler → network storage op inside `enqueueWork` | | server main (`enqueueWork`) | MAIN_NATIVE | S |

---

## 6. mae2 2.1.0 (`stone.mae2`)

Mixins (`mixins.mae2.json`, plugin): `CraftingCPUClusterMixin` (@Redirect
`CraftingBlockEntity.getAcceleratorThreads` in `addBlockEntity`: dense accelerators);
`PatternProviderLogicMixin` / `PatternProviderLogicCLMixin` (prio 2000): HEAD/TAIL `pushPattern`
**write static `PatternP2PTunnelLogic.isBlocking`/`blockingMode`**; TAIL `updatePatterns`,
`readFromNBT`, INVOKE `onMainNodeStateChanged`.

| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| M-1 | Pattern P2P blocking flag | Global static `isBlocking`/`blockingMode`, set at HEAD and cleared at TAIL of every `PatternProviderLogic.pushPattern`. Two concurrent `pushPattern` calls would corrupt it. | MAIN (CPU tick) | CUSTOM_SHARED_STATE (correct only while everything stays on MAIN) | C |
| M-2 | Multi-P2P tunnels (Item/Fluid/FE/EU, `CapabilityMultiP2PTunnel`), `MultiP2PService` | Foreign capability pass-through; no network storage | REGION callers into tunnels | out of storage scope (P2P-001 domain) | C |
| M-3 | Network storage access | **None.** No `MEStorage`/`IStorageService` references in the jar. | – | – | C |

---

## 7. molecularmanipulator (inside omnisequence-transfinite 2.0.9, `com.atir.molecularmanipulator`)

Mixins (`molecularmanipulator.mixins.json`, plugin). Storage/crafting-relevant:
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| CraftingCpuLogicMixin | `CraftingCpuLogic` | WrapOperation `ListCraftingInventory.insert/extract`, `CraftingCpuHelper.extractPatternInputs/reinjectPatternInputs/calculatePatternPower`, `getProviders`, `isBusy`, `pushPattern`, `Map.entrySet`; HEAD/RETURN `trySubmitJob`, `tickCraftingLogic`, `executeCrafting`; WrapMethod `getAllItems`; ModifyExpressionValue `getCoProcessors` | Batch crafting dispatch |
| ExactReturnNotificationMixin (800) | `CraftingCpuLogic` | WrapMethod `insert`, `postChange` | batches return notifications (on the §0.3 insert path) |
| AdvancedAECraftingCpuLogicMixin | `AdvCraftingCPULogic.executeCrafting` | same set | |
| OmniCraftingServiceMixin | `CraftingService` | ModifyVariable/HEAD/RETURN `submitJob`, HEAD `getProviders`, RETURN `findSuitableCraftingCPU` | |
| DataEnergisticsCpuIdentityMixin (1100) | `CraftingService` (another mod's injected method) | HEAD | |
| OmniCraftingCalculationMixin (1100) | `CraftingCalculation` | RETURN `<init>` (**`grid.getMachines`**, on the caller thread); WrapMethod `run` (AE2 calc executor); WrapOperation in `runCraftAttempt` | |
| **GridActiveMachinesMixin** | **`appeng.me.Grid.getActiveMachines` RETURN** | | expands pattern containers. Every caller of `getActiveMachines` gets a modified set. |
| **KeyCounterMixin** | **`appeng.api.stacks.KeyCounter.add/addAll` HEAD** | | saturating overflow. This is global; it affects all storage accounting. |
| PatternProviderLogicMixin | `PatternProviderLogic` | HEAD/RETURN `pushPattern`, WrapOperation `ICraftingMachine.pushPattern`, `pushInputsToExternalInventory`, `PatternProviderTarget.insert` (in `adapterAcceptsAll`, `lambda$pushPattern$2`), NBT, `hasWorkToDo` RETURN, `sendStacksOut` HEAD, `clearContent` | refunds go back to the network via `molecularmanipulator$refundLegacyBatch` |
| Cluster/ClusterAssemblerMatrixMixin, TileAssemblerMatrixPatternMixin, ExtendedAEPlus*, Useless*, LegacyAelis*, NbtTear*, LabeledPattern* | third-party addons | | |
| Gravity*/Singularity*/GhostMatter* | vanilla Entity/Player/Level | | not storage |

| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| O-1 | Crafting overlays (above) | | MAIN (CPU tick); AE2 calc pool for `run` | MAIN_NATIVE | C |
| O-2 | **BE tickers that do network storage ops**: `MolecularManipulatorBlockEntity` (extends `PatternProviderBlockEntity`; `ticker()` → `serverTick` → `flushBufferedOutputs`), `SingularityBlockEntity.serverTick` (`tickDuplication`/`collectResource`/`flushRecovery`), `MatterPatternBuffer.serverTick` → `flushToNetwork`, `MatterFabricationPortBlockEntity.serverTick` → `returnInputsToNetwork`, `MolecularCenterBlockEntity`, `MolecularAutoCrafterBlockEntity`/`MolecularAutoCrafter.tick` → `runPattern`, `OmniComputationCoreBlockEntity` (build/dismantle store), `MatterFabricationBlockEntity`; the blocks register these via `BlockEntityTicker` | | **REGION** | CUSTOM_SHARED_STATE: REGION writes MAIN-owned storage | C (ticker wiring in `*Block` classes + netops) |
| O-3 | Static registries/budgets: `OmniBatchProviderAdapterRegistry.snapshot/revision`, `OmniBigIntegerProviderAdapterRegistry.snapshot`, `OmniPostAccountingOutputAdapterRegistry.snapshot`, `UselessExactOutputReturn.budgetTick/spentThisTick`, `UselessBigIntegerApiBridge.attempted/available`, `MolecularExternalScaledPattern.lookup` | | MAIN and REGION (via O-2) | CUSTOM_SHARED_STATE | C |
| O-4 | `AssemblerMatrixMolecularCoreBlockEntity` (`IGridTickable` → `flushBufferedOutputs`) | | MAIN | MAIN_NATIVE | C |

Bus paths: none. **C**

---

## 8. bmaddon 1.0.4 (`com.edgemq.bmaddon`)

Mixins: `PatternEncodingTermMenuMixin` (encode HEAD/RETURN, `isPattern`, `clearPattern`: Blood
Altar patterns) and `RestrictedInputSlotMixin.mayPlace` RETURN.
| # | Feature | Thread | Class | Conf |
|---|---|---|---|---|
| BM-1 | `BloodAltarAssemblerBlockEntity` (`IGridTickable`; `tickingRequest` → `tryInsertPendingOutput` network insert; drives a Blood Altar) | MAIN | MAIN_NATIVE (how the altar is accessed was not traced: H) | C |
| BM-2 | `BloodGeneratorBlockEntity` (FE/fluid handlers, no AE storage) | REGION | n/a | C |
| BM-3 | Static `SyncedBMAddonConfig.clientSnapshot`, `BMAddonModEvents.registered*`, `BMAddonNetwork.packetId` | startup/client | minor | C |

---

## 9. PackagedAuto 3.4.15.46 (`thelm.packagedauto`)

No mixins. AE integration is `integration.appeng.blockentity.AE*BlockEntity`. Each extends the
base PackagedAuto BE and implements `IInWorldGridNodeHost` and `ICraftingProvider`.
| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| P-1 | **BE `tick()` → network insert**: `PackagerBlockEntity.tick` → `ejectItem` (AE override → `StorageHelper.poweredInsert` into the grid); same for Crafter/Unpackager/Distributor/PackagerExtension `ejectItem(s)`; `AEPackagingProviderBlockEntity.tick` → `sendPackaging`/`sendUnpackaging` (poweredInsert); `chargeMEEnergy` (grid energy) | | **REGION** | CUSTOM_SHARED_STATE: REGION writes MAIN-owned storage and energy | C |
| P-2 | `pushPattern` (from the CPU on MAIN): `AEPackagingProviderBlockEntity.pushPattern` → `getCapability(ITEM_HANDLER)` of the neighbour → `MiscHelper.insertItem` | | MAIN | MAIN_NATIVE with foreign call | C |
| P-3 | Static config fields (`energyCapacity`, `refreshInterval`, `drawMEEnergy`…), `MiscHelper.server`, `PackagedAutoApi.instance` | | | minor | C |

---

## 10. aeallpattern 0.2.6-beta.1 (`io.github.langqi99.aeallpattern`)

Mixins (plugin `AeAllPatternMixinPlugin`): `TianshuCraftingServiceMixin` (prio 2000; INVOKE
`ExecutorService.submit` in `CraftingService.beginCraftingCalculation`: captures router policy),
`TianshuCraftingCalculationMixin` (HEAD/RETURN `run`, HEAD `runCraftAttempt`, on the calc thread),
`CraftingServiceSecondaryOutputMixin` (RETURN `getCraftingFor`/`getCraftables`),
`PatternProviderLogicMixin.updatePatterns` HEAD, `CraftConfirmMenuMixin` (WrapOperation
`beginCraftingCalculation`), and many third-party pattern-holder mixins (AdvancedAE, neoecoae
pattern bus/catalog, ExtendedAE matrix, ae2lt, useless, ae2cs, PackagedAuto).
| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| AP-1 | Pattern expansion / routing | static `AggregatePatternExpander.synchronousMode`; `FastPlanningWatchdog` static `newSingleThreadScheduledExecutor` (watchdog for the planner) | MAIN / calc pool / watchdog thread | CUSTOM_SHARED_STATE + async | C |
| AP-2 | **`PatternLinkerBlockEntity.serverTick`** (`BlockEntityTicker`) → `IncomingBuffer.tick` → `drainBoundMachineOutput` (`MachineAdapter.extractAnyOutput`: foreign machine, bound **by position, possibly in another region**) → `insertIntoNetwork` (network insert); also `VirtualCraftingProvider.refresh/tickRefresh/tickAvailability` (crafting-provider updates) | | **REGION** | CUSTOM_SHARED_STATE: REGION does a foreign extract **and** a network insert (an unofficial "import bus") | C |

---

## 11. megacells 2.4.6 (`gripe._90.megacells`)

Mixins: `ItemEntityMixin` (prio 500; `fireImmune` RETURN, `tick` RETURN: lava transform; entity
tick, not storage) and a client `loadModel` hook.
| # | Feature | Thread | Class | Conf |
|---|---|---|---|---|
| MG-1 | Bulk / MEGA cells (`BulkCellInventory`, `BulkCellItem$Handler`, AppMek radioactive cells, appbot mana cells) mounted by drives/chests | MAIN | MAIN_NATIVE | C |
| MG-2 | `CellDockPart` (`IStorageProvider`, `IChestOrDrive`, `DriveWatcher`) | MAIN | MAIN_NATIVE | C |
| MG-3 | `DecompressionModulePart` (`ICraftingProvider` + `IGridTickable`: `pushPattern` buffers outputs, tick inserts them into the network); `DecompressionService` grid service (`onServerStartTick`) | MAIN | MAIN_NATIVE | C |
| MG-4 | `CompressionService.INSTANCE` (instance sets rebuilt by `loadRecipes` on datapack sync / server start; read by bulk cells) | MAIN | MAIN_NATIVE (read-mostly global) | C |

Bus paths: none. **C**

---

## 12. Applied Mekanistics 1.4.3 (`me.ramidzkh.mekae2`)

No mixins.
| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| K-1 | **Chemical key strategies** (`MekanismStackImportStrategy`, `MekanismStackExportStrategy`, `MekanismExternalStorageStrategy`/`ChemicalHandlerFacade`, `ChemicalContainerItemStrategy`) registered in `AppliedMekanistics` | `BlockApiCache<IChemicalHandler>` × 4 (gas/infusion/pigment/slurry). `transfer()` mixes foreign chemical extract/insert with network `insert` (×2: SIMULATE then MODULATE) and `poweredExtraction`. | MAIN today (AE2 buses with chemical keys) | bus path, strategy mixes foreign and network calls (§0.1) | C |
| K-2 | Chemical cells (`ChemicalStorageCell`) | MAIN | MAIN_NATIVE | C |
| K-3 | **QIO**: `QioSupport.onBlockEntityCapability` attaches an AE `MEStorage` capability to Mekanism `IQIOComponent` BEs; `QioStorageAdapter` reads/writes **`IQIOFrequency`** (a global Mekanism frequency). Static `CACHE` is an unsynchronized `WeakHashMap<IHashedItem,AEItemKey>`. | MAIN (storage bus mount) vs Mekanism's own frequency tick | EXTERNAL_MOUNT (global foreign state) + CUSTOM_SHARED_STATE (static cache) | C |
| K-4 | `ChemicalP2PTunnelPart`, `MultipleCapabilityP2PTunnelPart` | REGION callers | P2P domain | C |

---

## 13. Applied Botanics 1.5.2 (`appbot`)

Mixins (`appbot.mixins.json`): `Pool` (ManaPoolBlockEntity), `TAPBE` (TerrestrialAgglomerationPlate),
`ClampedAboveInsertOnly` (ManaSpreader, PowerGenerator, LifeImbuer…), `FullPassthroughInsertOnly`
(ManaSplitter, ManaVoid). These are interface mixins: they add `appbot.ae2.SafeMana`
(`insert/extract(int, Actionable)`) to Botania BEs so the AE strategies can simulate. Also
`BotaniaBlockEntitiesMixin` (@ModifyArg `<clinit>`: registers `FluixPoolBlockEntity` under
Botania's pool type), `ManaPoolBlockEntityAccessor`, and a client renderer mixin.

| # | Feature | Findings | Thread | Class | Conf |
|---|---|---|---|---|---|
| B-1 | **Mana key strategies** (`ManaStorageImportStrategy`, `ManaStorageExportStrategy`, `ManaExternalStorageStrategy$ManaStorageAdapter`, `ManaContainerItemStrategy`) through `AppliedBotanics.manaReceiver` → `BlockApiCache(MANA_RECEIVER)` | Import: network `insert(SIMULATE)` → `SafeMana.extract(MODULATE)` → network `insert(MODULATE)`; if the network refuses, it puts the mana back, and failing that **voids it with an AELog warning**. Export: `poweredExtraction` SIM/MOD → `SafeMana.insert`; on refusal it re-inserts into the network, then voids (debug log). | MAIN today (AE2 buses with mana key) | bus path, strategy mixes foreign and network calls (§0.1). The void-on-refusal semantics depend on the SIMULATE result. | C |
| B-2 | **`FluixPoolBlockEntity` extends Botania `ManaPoolBlockEntity`** | `getCurrentMana`/`getMaxMana`/`isFull`/`calculateComparatorLevel` → network `extract/insert(SIMULATE)`; `receiveMana` → `poweredInsert`/`poweredExtraction`; `SafeMana.insert/extract` → powered ops; `saveChanges` → `TickHandler.addCallable`. **Every** Botania caller (spreader bursts = entities, sparks, comparators, the pool's own Botania ticker) reaches network storage. | **REGION** | CUSTOM_SHARED_STATE: inbound foreign calls into MAIN-owned storage (this is the "FluixPool hit on every network insert" from the known facts, seen from the other side: it is also hit on every *mana read*) | C |
| B-3 | Mana cells (`ManaCellInventory`, creative), `MEStorageManaItem` (portable cell as a Botania mana item: item-local cell, not grid) | MAIN / player | MAIN_NATIVE / n/a | C |
| B-4 | **`MECorporeaNode`** (Corporea node over `Capabilities.STORAGE` via `BlockApiCache`) → `MEStorage.getAvailableStacks/extract` | Unconfigured AE2 Interface = whole network (§0.4); Corporea requests come from Corporea Index/Funnel/Interceptor | **REGION** | CUSTOM_SHARED_STATE: REGION reads/extracts from network storage | C |
| B-5 | `ManaP2PTunnelPart` | REGION | P2P domain | C |

---

## 14. merequester 1.2.1 (`com.almostreliable.merequester`)

Mixins: registration only (`AEBlockEntities/AEBlocks/AEItems/AEParts/InitMenuTypes` TAIL `init`),
plus client REI/JEI drag `accept`.
| # | Feature | Thread | Class | Conf |
|---|---|---|---|---|
| R-1 | `RequesterBlockEntity` (`IGridTickable`, `ICraftingRequester`, `IStackWatcher` in `StorageManager`); `ExportState.handle` → `poweredInsert` of crafted results; `PlanState`/`RequestState` → crafting service | MAIN | MAIN_NATIVE | C |

No foreign capabilities, no async, no static mutable state on the storage path. **C**

---

## Summary table

| Mod | Feature | Class | Conf | Touches the bus split? |
|---|---|---|---|---|
| crazyae2addons | insertIntoCpus overwrite/inject (getMachines per insert) | MAIN_NATIVE | C | yes, indirectly: every MAIN-side drain insert runs it |
| crazyae2addons | NBTExportBusPart (IOBusPart), MobExportBus (ExportBusPart) | TRANSIT candidate per class | C | **yes, own `doBusWork`** |
| crazyae2addons | NBTStorageBus, MobFormationPlane | EXTERNAL_MOUNT | C | MAIN insert → world/foreign |
| crazyae2addons | Wireless notification terminal (player tick) | CUSTOM_SHARED_STATE (REGION→storage) | C | no (separate violation) |
| neoecoae | CraftingService bridge (getMachines per insert), CPU ticks | MAIN_NATIVE | C | indirectly (§0.3) |
| neoecoae | ECO storage backends (synchronized, WAL executors, static managers) | CUSTOM_SHARED_STATE | C | no |
| neoecoae | ECOStorageSystem BE tick → migration + requestUpdate | CUSTOM_SHARED_STATE (REGION→storage) | S | no (separate violation) |
| neoecoae | Planner pool + snapshot | CUSTOM_SHARED_STATE + async | C/S | no |
| AdvancedAE | Quantum CPU (insertIntoCpus overwrite, onServerEndTick ticks) | MAIN_NATIVE | C | indirectly |
| AdvancedAE | ImportExport / StockExport / AdvancedIO buses (ExportBusPart subclasses) | TRANSIT candidate per class | C | **yes, own `doBusWork`, import+export+foreign stock in one tick** |
| AdvancedAE | Quantum Armor autoFeed/autoStock (player tick) | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| AppliedFlux | FE import/export/external strategies | TRANSIT candidate (strategy split) | C | **yes, via AE2 buses** |
| AppliedFlux | Flux Accessor FE/GT/FluxNetworks caps → network | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| AppliedFlux | EnergyDistributeService | MAIN_NATIVE (foreign on MAIN) | C | no |
| appliede | EMC storage (player knowledge capability) | CUSTOM_SHARED_STATE | C | yes, if a drain insert routes into EMC |
| appliede | EMC import/export buses (IOBusPart, direct caps) | not TRANSIT_SAFE | C | **yes, own `doBusWork`** |
| mae2 | static `isBlocking`/`blockingMode` around pushPattern | CUSTOM_SHARED_STATE | C | no |
| mae2 | storage | (none) | C | no |
| molecularmanipulator | crafting mixins, global `KeyCounter.add` and `Grid.getActiveMachines` mixins | MAIN_NATIVE (global hooks) | C | indirectly (KeyCounter is used everywhere) |
| molecularmanipulator | BE tickers flushing to network | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| bmaddon | Blood Altar assembler (grid tick) | MAIN_NATIVE | C | no |
| PackagedAuto | BE tick → poweredInsert / ME energy | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| aeallpattern | PatternLinker serverTick: foreign extract + network insert | CUSTOM_SHARED_STATE (REGION→storage) | C | no, but it behaves like an import bus |
| aeallpattern | calc/route mixins, watchdog executor | CUSTOM_SHARED_STATE + async | C | no |
| megacells | cells, dock, decompression | MAIN_NATIVE | C | no |
| Applied-Mekanistics | chemical strategies | TRANSIT candidate (strategy split) | C | **yes, via AE2 buses** |
| Applied-Mekanistics | QIO mount + static WeakHashMap cache | EXTERNAL_MOUNT + CUSTOM_SHARED_STATE | C | no |
| Applied Botanics | mana strategies (void on refusal) | TRANSIT candidate (strategy split) | C | **yes, via AE2 buses** |
| Applied Botanics | FluixPool (ManaPool backed by network) | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| Applied Botanics | Corporea node over Capabilities.STORAGE | CUSTOM_SHARED_STATE (REGION→storage) | C | no |
| merequester | requester | MAIN_NATIVE | C | no |

---

## Key risks for the WP3-A transit split

1. **The split has to happen at the strategy contract, not at `doBusWork`** (§0.1). Every non-item
   key type in the pack (FE: AppliedFlux; chemicals: AppMek; mana: AppBot) runs foreign and network
   calls interleaved inside `transfer()`, including SIMULATE → MODULATE → put-back/void sequences.
   - A REGION-side context whose `getInternalStorage()` is a transit buffer must give SIMULATE
     answers that match what MAIN will accept later. Otherwise AppBot **voids mana** ("network
     refused insert"), and FE/chemicals strand in transit.
   - Export strategies need the network extraction done on MAIN **before** REGION pushes
     (pre-staged transit), plus a return path for refusals. Today the strategies re-insert into
     the network inline.
   - Recommendation: an allowlist of strategy classes that are known to be decomposable (AE2 item
     and fluid), with every other `StackImportStrategy`/`StackExportStrategy` implementation kept
     MAIN-whole (fail-closed).
2. **Bus subclasses bypass a `doBusWork`-level hook, or break under an `IOBusPart`-level hook:**
   crazy `NBTExportBusPart` (IOBusPart), `MobExportBus` (ExportBusPart, world entity spawn); AdvancedAE
   `ImportExportBusPart`/`StockExportBusPart`/`AdvancedIOBusPart` (ExportBusPart: import+export in
   one tick, foreign stock reads through `StorageReader`, crafting requests); appliede
   `EMCImportBusPart`/`EMCExportBusPart` (IOBusPart: direct `getCapability`, EMC = player capability).
   The split should apply by **exact class** (`appeng.parts.automation.ImportBusPart` /
   `ExportBusPart`), with all subclasses running MAIN-whole as today.
3. **The MAIN-side insert runs third-party code anyway.** Through `CraftingServiceStorage` (priority
   MAX), every drain insert runs crazy's or AdvancedAE's `insertIntoCpus` overwrite and neoecoae's
   RETURN hook. Both crazy and neoecoae call **`grid.getMachines(...)` on every insert**. The
   overwrite conflict (crazy 1200 vs AdvancedAE 1000) means crazy's body is most likely live (S).
   Mounted storages also run foreign or world code on a MAIN insert: crazy `MobFormationPlane`
   (spawns mobs), appliede `EMCStorage` (mutates player `IKnowledgeProvider` + packets), AppMek
   QIO (global frequency), and storage buses of every strategy. Draining in batches on MAIN keeps
   this cost per batch rather than per bus tick, but it does not remove it. This is consistent with
   "storage MAIN-owned".
4. **Region→storage writers that the transit split does not cover** (they do not go through buses):
   PackagedAuto (BE `tick()` → `poweredInsert` + ME energy), molecularmanipulator (≈8 BE tickers),
   aeallpattern `PatternLinker` (foreign extract + network insert from BE tick: a de-facto import
   bus), neoecoae `ECOStorageSystem` BE tick (migration + `IStorageProvider.requestUpdate`; S),
   Applied Botanics `FluixPool` (any Botania caller) and `MECorporeaNode` (through
   `Capabilities.STORAGE` of an unconfigured AE2 Interface), AppliedFlux Flux Accessor FE/GT/Flux
   Networks capabilities, AdvancedAE Quantum Armor (player tick), crazy wireless notification
   terminal (player tick). Each needs either a MAIN-hop facade (an inbound transit or mailbox at the
   capability or ticker boundary) or a pin to MAIN. They are not "transit-safe by default".
5. **Global hooks that make "MAIN only" an invariant rather than a nice-to-have:**
   molecularmanipulator `KeyCounterMixin` (all `KeyCounter.add/addAll`) and `GridActiveMachinesMixin`
   (`Grid.getActiveMachines`); mae2 static `PatternP2PTunnelLogic.isBlocking/blockingMode` toggled
   around every `pushPattern`; neoecoae static WeakHashMaps (`SLOW_PATH_NETWORK_BUDGETS`,
   `ECOCraftingFastPathCache.ACTIVE_CACHES`); AppMek `QioStorageAdapter.CACHE` (unsynchronized
   WeakHashMap); AppliedFlux `EnergyDistributeService.activeNodes`. None of these tolerate a second
   thread. A REGION-side transit **must not** call `KeyCounter`-based network snapshot code, or any
   provider/pattern code.
6. **Own threads that already exist** (they must not be handed network objects): neoecoae
   `ECOPlanningService.PLANNING_POOL` (cached pool, works on a snapshot) and `ECOStorageIoWorker`
   WAL/checkpoint executors (backend-internal, synchronized); aeallpattern `FastPlanningWatchdog`
   scheduled executor; AE2's own crafting-calc pool, which crazy, molecularmanipulator and
   aeallpattern mixin into (`CraftingTreeNode.buildChildPatterns`, `CraftingCalculation.run`).
7. **Gaps in confidence:**
   - neoecoae BE-ticker registration (N-4) is S: the `NEBlockEntityTicker` wiring was not followed
     to `getTicker`.
   - The `insertIntoCpus` overwrite winner is S and needs a mixin export dump.
   - How bmaddon's assembler accesses the altar is H.
   - AdvancedAE's AppliedFlux recharge path (A-6) is S.

---

## Dangerous-entry register (owner format)

Format: source / class / method → current thread/domain → state touched → AE2-owned | external-owned | mixed → can invoke foreign code → same-tick requirement → routing candidate → evidence.

"Same-tick" means the caller's semantics need the result inside the same call or tick: for example a synchronous return value used by the caller, a SIMULATE→MODULATE pair, or a capability getter.

| # | source / class / method | current thread/domain | state touched | ownership | foreign code? | same-tick? | routing candidate | evidence |
|---|---|---|---|---|---|---|---|---|
| D1 | AppliedFlux / `FEStackImportStrategy.transfer`, `FEStackExportStrategy.transfer/push` | MAIN (AE2 bus grid tick) | network MEStorage (insert/poweredExtraction) + neighbour `IEnergyStorage` | mixed | YES | YES (SIM→MOD→put-back inside one call) | TRANSIT only after strategy decomposition; until then FAIL-CLOSED (keep MAIN-whole) | C: javap call sequence BlockApiCache.find → MEStorage.insert SIM → extractEnergy → insert MOD → receiveEnergy |
| D2 | Applied-Mekanistics / `MekanismStackImportStrategy.transfer`, `MekanismStackExportStrategy.transfer` | MAIN (bus) | network + neighbour `IChemicalHandler` (4 types) | mixed | YES | YES | FAIL-CLOSED → later TRANSIT | C: 2× MEStorage.insert, poweredExtraction, BlockApiCache×4 |
| D3 | Applied Botanics / `ManaStorageImportStrategy.transfer`, `ManaStorageExportStrategy.transfer` | MAIN (bus) | network + neighbour `ManaReceiver` (SafeMana) | mixed | YES | YES (and **voids mana** on refusal) | FAIL-CLOSED (void semantics make an approximate SIMULATE unsafe) | C: AELog "voided it because network refused insert" / LOGGER.debug "voided {} Mana" |
| D4 | AdvancedAE / `ImportExportBusPart.doBusWork`, `StockExportBusPart.doBusWork`, `AdvancedIOBusPart.doBusWork` | MAIN (grid tick) | network (import+export), foreign stock via `StorageReader`, crafting requests | mixed | YES | YES (stock read → export decision in one tick) | MAIN (exact-class exclusion from the split) | C: subclasses of ExportBusPart overriding doBusWork; calls StackImportStrategy.transfer + StackExportStrategy.transfer + attemptCrafting |
| D5 | crazyae2addons / `NBTExportBusPart.doBusWork` | MAIN | network cached inventory scan + export strategy | mixed | YES | YES | MAIN (exclude) | C: extends IOBusPart; getStorageService + StackExportStrategy.transfer |
| D6 | crazyae2addons / `MobExportBus.doBusWork` | MAIN | network extract + world entity spawn | mixed | YES (world) | YES | MAIN (exclude); possible later TRANSIT with REGION spawn | C: extends ExportBusPart; MEStorage.extract (spawn: S) |
| D7 | appliede / `EMCImportBusPart.doBusWork`, `EMCExportBusPart.doBusWork` | MAIN | neighbour ITEM_HANDLER / IEmcStorage + `EMCStorage` → ProjectE `IKnowledgeProvider` (player cap) | mixed (external = player-owned) | YES | YES | MAIN (exclude; FAIL-CLOSED for any transit) | C: direct BlockEntity.getCapability, ExternalStorageFacade.of, EMCStorage.insertItem/extractItem |
| D8 | appliede / `EMCStorage.insert/extract/insertItem/extractItem` (mounted by `EMCModulePart`) | MAIN (any network op routed to EMC) | ProjectE player knowledge/EMC + `syncEmc` packets | external-owned (player) | YES | YES | CALLBACK (player-owner hop) or MAIN with player-ownership guard | C: IKnowledgeProvider.setEmc / addKnowledge / KnowledgeService.syncEmc |
| D9 | crazyae2addons / `AAECraftingServiceMixin.insertIntoCpus` (@Overwrite, prio 1200) and `MixinCraftingService` (no AAE) | MAIN (every network insert via CraftingServiceStorage, plus CPU) | CPU inventories + `grid.getMachines(AdvCraftingBlockEntity)` | AE2-owned | NO | YES (return value = amount accepted) | MAIN | C: bytecode getMachines inside overwrite; Plugin gating on advanced_ae |
| D10 | neoecoae / `CraftingServiceMixin.neoecoae$insertIntoCpus` → `NeoECOCraftingServiceBridge.insertIntoCpus` | MAIN (every network insert) | ECO CPU logic + `grid.getMachines(ECOComputationSystemBlockEntity)` | AE2-owned (+ addon static maps) | NO | YES | MAIN | C |
| D11 | AdvancedAE / `cpu.MixinCraftingService.insertIntoCpus` (@Overwrite), `tickAdvClusters1/2` | MAIN | Quantum CPU logic | AE2-owned | NO (CPU push → providers → foreign, as in AE2) | YES | MAIN | C (overwrite winner vs crazy: S) |
| D12 | neoecoae / `ECOStorageSystemBlockEntity.tick` → `updateInfiniteStorageMode`, `runInfiniteMigrationStep`, `syncInfiniteModeChanges`, `pollInfiniteStorageLoad`, `flushInfiniteEngineBudgeted` | **REGION** (BE ticker) **and** MAIN (`tickingRequest`) | mounted ECO cells / infinite engine + `IStorageProvider.requestUpdate` (NetworkStorage remount) | mixed | NO | NO | MAIN (move to grid tick only; the BE-tick path should be FAIL-CLOSED) | S: bytecode of tick/tickingRequest call graph; ticker wiring not traced |
| D13 | neoecoae / `ECOPlanningService.submit` (PLANNING_POOL), `ECOAE2SnapshotFactory.capture` | caller thread (menu/requester), then cached pool | snapshot of grid inventory/patterns; static GRAPH_CACHE | AE2-owned (snapshot) + addon static | NO | NO (Future) | MAIN for capture; pool OK only on snapshot | C pool / S capture-only-on-caller |
| D14 | neoecoae / `FileBackedECOStorageBackend`, `ECOStorageIoWorker` (walExecutor/checkpointExecutor), `ECOCellStorageManager` static | MAIN (ops) + own I/O threads | addon cell storage, files | external-owned (addon, self-locked) | NO | NO | MAIN (no change; leave self-locked) | C: synchronized methods, newSingleThreadExecutor ×3 |
| D15 | PackagedAuto / `PackagerBlockEntity.tick` → `AEPackagerBlockEntity.ejectItem`; `AECrafter/AEUnpackager/AEDistributor/AEPackagerExtension.ejectItem(s)`; `AEPackagingProviderBlockEntity.tick` → `sendPackaging/sendUnpackaging`; `chargeMEEnergy` | **REGION** (BE tick) | network storage (`poweredInsert`) + grid energy | AE2-owned | YES (`sendUnpackaging` → neighbour ITEM_HANDLER) | NO (eject can be deferred) | TRANSIT (inbound buffer → MAIN drain) or MAIN pin | C: tick → ejectItem → StorageHelper.poweredInsert |
| D16 | PackagedAuto / `AEPackagingProviderBlockEntity.pushPattern` | MAIN (CPU) | neighbour ITEM_HANDLER | external-owned | YES | YES (push bool) | MAIN (as AE2 pattern providers today) | C |
| D17 | molecularmanipulator / `MolecularManipulatorBlockEntity.serverTick` → `flushBufferedOutputs`; `SingularityBlockEntity.serverTick`; `MatterPatternBuffer.serverTick` → `flushToNetwork`; `MatterFabricationPortBlockEntity.serverTick` → `returnInputsToNetwork`; `MolecularCenterBlockEntity`, `MolecularAutoCrafterBlockEntity`/`MolecularAutoCrafter.tick` → `runPattern`, `OmniComputationCoreBlockEntity`, `MatterFabricationBlockEntity` serverTick | **REGION** (BlockEntityTicker) | network storage insert/extract | AE2-owned | YES (some: ForeignCap flagged in Center/AutoCrafter/Fabrication/Singularity) | UNKNOWN (runPattern/takeBuildMaterial extract-and-use within one tick) | MAIN pin (FAIL-CLOSED) until per-method review; flush paths → TRANSIT | C: `*Block` ticker wiring + netops callers |
| D18 | molecularmanipulator / `KeyCounterMixin` (`KeyCounter.add/addAll` HEAD), `GridActiveMachinesMixin` (`Grid.getActiveMachines` RETURN) | any thread that uses KeyCounter / getActiveMachines | global AE2 accounting / machine sets | AE2-owned | NO | YES | MAIN (invariant: no REGION-side KeyCounter or network snapshot code) | C |
| D19 | aeallpattern / `PatternLinkerBlockEntity.serverTick` → `IncomingBuffer.tick` → `drainBoundMachineOutput` → `MachineAdapter.extractAnyOutput` + `insertIntoNetwork`; `VirtualCraftingProvider.refresh/tickRefresh/tickAvailability` | **REGION** (BlockEntityTicker) | foreign machine (bound by position, possibly another region) + network insert + crafting-provider state | mixed | YES | NO (buffered) | TRANSIT (REGION extract → buffer → MAIN insert); provider refresh → MAIN; cross-region machine binding → FAIL-CLOSED | C |
| D20 | Applied Botanics / `FluixPoolBlockEntity.getCurrentMana/getMaxMana/isFull/receiveMana/calculateComparatorLevel/insert/extract` | **REGION** (Botania bursts, sparks, pool ticker, comparators) | network storage (mana key) SIM/MOD + poweredInsert/Extraction | AE2-owned | NO (called by foreign code) | YES (synchronous getters/receivers) | FAIL-CLOSED (cannot be satisfied asynchronously; options: MAIN-published cached snapshot for reads + TRANSIT for receiveMana, or disable) | C |
| D21 | Applied Botanics / `MECorporeaNode.work` (countItems/extractItems) via `Capabilities.STORAGE` | **REGION** (Corporea index/funnel/interceptor) | network storage (unconfigured AE2 Interface = whole grid) | AE2-owned | NO | YES (Corporea returns stacks synchronously) | FAIL-CLOSED or CALLBACK (deferred Corporea response) | C: getAvailableStacks + extract; InterfaceLogic.getInventory returns networkStorage |
| D22 | AppliedFlux / `TileFluxAccessor.getCapability` → `CapAdaptor` → `NetworkFEPower.receiveEnergy/extractEnergy/getEnergyStored`; `GTEUPower`, `FluxNetworkPower` | **REGION** (foreign cables/machines), Flux Networks tick | network storage (FE key) | AE2-owned | NO (called by foreign code) | YES (capability return values) | FAIL-CLOSED or TRANSIT with bounded MAIN-published budget | C |
| D23 | AppliedFlux / `EnergyDistributeService.onLevelEndTick` → `TileFluxAccessor.distribute/charge` | MAIN (grid service) | neighbour BE lookup + `IEnergyStorage.receiveEnergy` + network extract; unsynchronized activeNodes set | mixed | YES | YES | MAIN today; REGION part could become TRANSIT (like an export bus) | C |
| D24 | AdvancedAE / `QuantumHelmet/Chestplate/Leggings/Boots.m_6883_` → `IUpgradeableItem.tickUpgrades` → `UpgradeCards.autoFeed/autoStock` (+ `AppliedFluxPlugin.recharge*`) | **REGION** (player tick) | linked grid storage extract/insert/getAvailableStacks + player inventory | mixed | NO | NO (feeding/stocking can lag) | TRANSIT (player request → MAIN fulfil → player-owner apply) or CALLBACK | C (recharge: S) |
| D25 | crazyae2addons / `WirelessNotificationTerminal.m_6883_` → `sendHudUpdate`; `PortableAutobuilder.pasteNow` → `extractAvailable` | **REGION** (player tick / item use) | network storage read / extract | AE2-owned | NO | read: NO; paste extract: YES | read → MAIN-published snapshot; extract → CALLBACK | C |
| D26 | mae2 / `PatternProviderLogicMixin.onPushStart/onPushEnd` (static `PatternP2PTunnelLogic.isBlocking/blockingMode`) | MAIN (CPU pushPattern) | global static flags | external-owned (addon static) | NO | YES | MAIN (invariant: pushPattern single-threaded) | C: putstatic in HEAD/TAIL handlers |
| D27 | Applied-Mekanistics / `QioStorageAdapter` (static `CACHE` WeakHashMap) via `QioSupport` capability | MAIN (storage-bus mount) | Mekanism global `IQIOFrequency` + unsynchronized static cache | external-owned | YES | YES | MAIN (EXTERNAL_MOUNT stays MAIN) | C |
| D28 | crazyae2addons / `MobFormationPlane$InWorldStorage.insert` (and similar mounted world sinks) | MAIN (on network insert) | world (mob spawn at the plane's position, any region) | external-owned (world) | YES | YES | MAIN (accepted cost of MAIN-owned storage; flag cross-region world write) | C structure / S spawn call |
| D29 | AppliedFlux / `MixinCraftingServiceStorage.skip` (CraftingServiceStorage$1.insert HEAD) | MAIN (every insert) | none (returns 0 for FluxKey) | AE2-owned | NO | YES | MAIN | C (benign, listed because it is on the drain path) |
