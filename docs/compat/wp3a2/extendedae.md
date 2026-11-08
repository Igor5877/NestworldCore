# AE2-STORAGE-DOMAIN-001 WP3-A.2 — ExtendedAE 1.4.21 + ExtendedAE Plus 1.6.3 audit

Read-only bytecode/source audit, 2026-11-07.

Inputs:
- ExtendedAE (`expatternprovider`) 1.20-1.4.21-forge, extracted at `/root/ae2-pack-audit-2026-11-07/x/ExtendedAE-1.20-1.4.21-forge/`
- ExtendedAE Plus (`extendedae_plus`) 1.6.3, extracted from `/root/project-infinity/mods/extendedae_plus-1.6.3.jar` to `/root/ae2-pack-audit-2026-11-07/x/extendedae_plus-1.6.3/`
- AE2 15.4.10 source at `/root/ae2-src/forge-v15.4.10`. The 15.4.11 diff (`/root/ae2-15.4.11-audit/changed.lst`) touches only `CraftingCPUCalculator`, `OverlayManager`, `Tooltips`, `FillCraftingGridFromRecipePacket` and `TestPlots`. Every AE2 class cited below is therefore identical in 15.4.11.

Working files (javap dumps, helper scripts) are in `/root/ae2-pack-audit-2026-11-07/wp3a2/javap/` and `/root/ae2-pack-audit-2026-11-07/wp3a2/*.sh`.

Confidence tags:
- **[C]** CONFIRMED from bytecode or AE2 source.
- **[S]** STRONGLY INDICATED.
- **[H]** HYPOTHESIS.

Thread notes. "MAIN" for grid-device callbacks (`IGridTickable.tickingRequest`) rests on AE2 `TickManagerService.tickQueue`, which the NestWorld T2 suspension wraps (`compat/nestworld-compat-ae2/.../TickManagerServiceMixin.java`, comment "main runs only that") **[S]**. "REGION" for vanilla `BlockEntityTicker`, neighbour updates and player ticks follows NestWorld's region-execution model **[S]**.

---

## 0. Executive summary

1. **Ex import and export buses add no logic.** `PartExImportBus`/`PartExExportBus` override only `getOperationsPerTick()` (`super × EPPConfig.busSpeed`; default 8, range 2..128), `getUpgradeSlots()` (8), `getMenuType()` and models **[C]**. All behaviour, including `doBusWork`, strategies, crafting and scheduling, is AE2's `ImportBusPart`/`ExportBusPart`. The split question is therefore the AE2 question, scaled by up to **768 operations per tick per bus** (96 × 8): 768 items, or 768 buckets for fluids.
2. **AE2's own bus algorithm interleaves the two sides inside one call.** Export runs network SIMULATE extract → external SIMULATE insert → network MODULATE extract → external MODULATE insert → reinsert leftover into the network, or void it. Import runs network SIMULATE insert → external MODULATE extract → network MODULATE insert → reinsert leftover into the external inventory, or void it **[C]**. A transit split replaces each synchronous simulate on the other side with a probe or a speculative move, which changes semantics (§3).
3. **The export bus touches the external side from crafting callbacks too.** `insertCraftedItems()` is called by the crafting CPU (MAIN) and pushes straight into the neighbour, returning the accepted amount synchronously. `attemptCrafting` also SIMULATE-pushes into the neighbour **[C]**. A transit design must cover these paths.
4. **extendedae_plus mixes into `IOBusPart` (the channel card), so Ex buses inherit it.** `IOBusPartChannelCardMixin` injects at `tickingRequest` TAIL and runs `ChannelCardConnectionController.tick()`, which can call `GridHelper.createConnection` / `IGridConnection.destroy` during the bus tick, i.e. grid topology mutation **[C]**. The channel card is registered as an upgrade for AE2 import/export/storage buses and for `EX_IMPORT_BUS`/`EX_EXPORT_BUS` **[C]**. This network-side tail must stay on MAIN.
5. **extendedae_plus has several confirmed off-MAIN paths into MAIN-owned storage, crafting or topology.** None of them involve the buses:
   - `JeiSyncManager.onPlayerTick` (player tick, REGION) runs `grid.getStorageService().getInventory().getAvailableStacks()` and `CraftingService.getCraftables()` every 20 ticks per player holding a linked wireless terminal. It is registered unconditionally **[C]**.
   - `TagInventoryMEInterfaceBlockEntity` exposes a Forge `IItemHandler` whose `getSlots`/`getStackInSlot`/`extractItem` scan `getCachedInventory()` and call `MEStorage.extract` on network storage. Any foreign pipe, which runs on REGION, enters MAIN-owned storage **[C]**.
   - `WirelessTransceiverBlockEntity.serverTick` (vanilla BE ticker, REGION) calls `WirelessSlaveLink.updateStatus()`, which creates and destroys grid connections **[C]**.
   - `MirrorPatternProviderBlockEntity.serverTick` (vanilla BE ticker, REGION) resolves a master pattern provider at an arbitrary position, possibly in **another dimension** (`server.getLevel(dim)`, `isLoaded`, `getBlockEntity`), copies its patterns, then calls `PatternProviderLogic.updatePatterns()` → `ICraftingProvider.requestUpdate` (CraftingService) **[C]**.
   - `EntitySpeedTickerPart` (grid tick, MAIN) calls the **facing foreign block entity's own ticker N extra times** **[C]**. Foreign code then runs on MAIN while that BE's owning region also ticks it.
6. **Unsynchronized static shared state.**
   - **[C]**: ExtendedAE `CraftingMatrixThread.LRU` (`SingleThreadLRU`; even `get()` mutates the access order), shared by every assembler-matrix crafter.
   - **[C]**: extendedae_plus `SuperAssemblerMatrixCalculator.PENDING_RECALCULATIONS` / `DELAYED_RECALCULATIONS` (static `IdentityHashMap`). Written from block load/neighbour paths, drained in `LevelTickEvent` END.
   - **[C]**: `JeiSyncManager.playerStates` (static `HashMap`).
   - **[C]**: `TagPriorityList.INVALIDATOR` (static `WeakHashMap`).
   - **[C]**: Infinity BigInteger cells keep their contents in one global overworld `SavedData` (`InfinityStorageManager`, static `ExtendedAEPlus.STORAGE_INSTANCE`).
7. **Concurrency.** ExtendedAE uses no threads, executors, locks or `synchronized` (only FML `enqueueWork` and datagen) **[C]**. extendedae_plus has `synchronized` registries (`WirelessMasterRegistry`, `LabelNetworkRegistry`), a `ScheduledExecutorService` in `ModConfig` that only handles config debounce, and runs mixin code on AE2's **"AE Crafting Calculator"** thread. That thread includes `CraftingSimulationStateMixin.onBuildCraftingPlan`, which iterates `CraftingService.getProviders(...)` **[C]**.

---

## 1. Mixins

### 1.1 ExtendedAE (`mixins.epp.json`; common section; client-only mixins listed for completeness)

| Mixin | Target | Injection | Purpose / relevance |
|---|---|---|---|
| MixinAbstractLevelEmitterPart | `appeng.parts.automation.AbstractLevelEmitterPart` | @Redirect `animateTick` FIELD `DustParticleOptions.REDSTONE` | Client particle colour. Irrelevant. |
| MixinAEBaseMenu | `appeng.menu.AEBaseMenu` | @Inject HEAD `isPlayerSideSlot` | Menu slot semantics. Irrelevant. |
| **MixinAEBasePart** | `appeng.parts.AEBasePart` | @Redirect `readFromNBT` INVOKE `IManagedGridNode.loadFromNBT` (skipped when tag `BYPASS_EXTENDEDAE` is present); **@Overwrite `addToWorld`** = `mainNode.create(level,pos)` wrapped in `catch IllegalStateException` (swallowed) **[C]** | Applies to **every AE2 part**, the IO buses included. It hides double-create ISEs during part lifecycle, which matters for NestWorld lifecycle/teardown audits: a duplicate `create` on a racing path becomes silent. |
| MixinAECraftingPattern | `appeng.crafting.pattern.AECraftingPattern` | Accessor-style (`getOutput`, `getRecipeHolder`) | Read-only. |
| MixinAppEngInternalInventory | `appeng.util.inv.AppEngInternalInventory` | Adds `getStacks`/`setStacks`/`silentClear` (IntruderInventory) | Direct array access, used by the ME packing tape and similar. |
| MixinGlobalWireNetwork | `blusunrize...GlobalWireNetwork` (IE) | @Redirect `getNetwork` `Level.getCapability` | IE compat for the display level. Irrelevant. |
| MixinPatternAccessTermMenu | `appeng.menu.implementations.PatternAccessTermMenu` | @Inject TAIL `sendFullUpdate` | Terminal sync. Menu path (player/menu thread). |
| MixinRecipeManager | `net.minecraft...RecipeManager` | Accessor `byType` | Read-only. |
| MixinSlot | `net.minecraft...inventory.Slot` | @Mutable x/y | Client layout. |
| client: MixinAbstractContainerScreen, MixinClearPatternAccessTerminalPacket, MixinPatternAccessTerminalPacket, MixinRenderBlockOutlineHook, MixinScreen | client | — | Not server relevant. |

**No ExtendedAE mixin targets `IOBusPart`, `ImportBusPart`, `ExportBusPart`, `StorageBusPart`, `StorageService`, `NetworkStorage` or `CraftingService`** **[C]**.

### 1.2 extendedae_plus (`extendedae_plus.mixins.json`, `defaultRequire=1`, plugin `MixinConditions`; ~93 common and ~40 client mixins)

Full target list: `javap/plus_mixin_targets.txt`. Per-injection list: `javap/plus_mixin_summary.txt`. The mixins relevant to storage, crafting, topology or threading:

| Mixin | Target | Injection(s) | Purpose / relevance |
|---|---|---|---|
| **ae2.parts.automation.IOBusPartChannelCardMixin** | `appeng.parts.automation.IOBusPart` (therefore also `PartExImportBus`/`PartExExportBus`, Precise/Threshold export) | @Inject TAIL `upgradesChanged`, TAIL `readFromNBT`, RETURN `getTickingRequest` (forces `isSleeping=false` while a channel card is present), TAIL `tickingRequest` (runs `controller.tick()`; can override the return to SLOWER/SLEEP) **[C]** | Channel card = wireless grid link. `controller.tick()` → `WirelessSlaveLink.updateStatus()` → `GridHelper.createConnection` / `IGridConnection.destroy` **[C]**. Also adds a static registry entry `ChannelCardConnectionController.REGISTRY` (synchronizedMap(WeakHashMap<BlockEntity, IdentityHashSet>); the inner set is not synchronized). |
| ae2.parts.storagebus.StorageBusPartChannelCardMixin | `appeng.parts.storagebus.StorageBusPart` | Same pattern (+`nodeChanged`) | Same topology mutation inside storage bus ticks (MAIN). |
| ae2.helpers.InterfaceLogicChannelCardMixin / InterfaceLogicTickerMixin / InterfaceLogicUpgradesMixin | `appeng.helpers.InterfaceLogic`, `InterfaceLogic$Ticker` | HEAD/TAIL `tickingRequest` (delayed init, keep-awake); ctor upgrade slots | Channel card for interfaces (incl. Ex/Oversize interface). Topology change in grid tick (MAIN). The static reflective `outerField` cache is benign. |
| ae2.helpers.patternprovider.* (Ticker/Advanced/Doubling/SyncVersion), ae2.compat.PatternProviderLogicCompatMixin, ae2.autopattern.PatternProviderLogicContainsModifyMixin, advancedae.* | `PatternProviderLogic`, `PatternProviderLogic$Ticker`, AdvancedAE logic | NBT, settings, `pushPattern`, blocking `contains` WrapOperation, channel-card tick | Crafting-provider behaviour (blocking mode, smart doubling, channel card). Runs in the grid tick / crafting CPU push (MAIN). |
| ae2.CraftingCPUClusterMixin | `appeng.me.cluster.implementations.CraftingCPUCluster` | @Inject (cancellable) `addBlockEntity` | Removes the accelerator-thread limit. Cluster formation (MBCalculator path). |
| ae2.crafting.CraftingCpuLogicManualWaitingMixin, ae2.autopattern.CraftingCpuLogicPatternPowerMixin | `appeng.crafting.execution.CraftingCpuLogic` | Several @Inject; @ModifyExpressionValue on power | Manual-wait state, power cost. CPU tick (MAIN). |
| **ae2.autopattern.CraftingSimulationStateMixin** | `appeng.crafting.inv.CraftingSimulationState` | @Inject `buildCraftingPlan` | Rewrites the crafts map (pattern scaling). Calls `grid.getCraftingService()` and iterates **`CraftingService.getProviders(pattern)`** **[C]** on the **"AE Crafting Calculator"** thread (AE2 `CraftingService.CRAFTING_POOL`, a cached thread pool) **[C]**. |
| ae2.autopattern.CraftingCalculationMixin, ae2.CraftingCalculationMixin | `appeng.crafting.CraftingCalculation` | ctor capture grid; ModifyExpressionValue time threshold | Calculator thread. |
| ae2.autopattern.CraftingServiceGetProvidersMixin | `appeng.me.service.CraftingService` | @ModifyArg `getProviders` (unwrap scaled pattern) | Pure. |
| ae2.menu.MEStorageMenuMixin, CraftConfirmMenuForceStartMixin, CraftingCPUMenu*, PatternEncodingTerm*, ContainerPatternEncodingTermMenuMixin | menus | various | Menu/packet paths. Encode → upload pattern into a provider (`ProviderUploadUtil`). |
| ae2.ChannelCardNetworkBlockEntityLifecycleMixin, ae2.parts.ChannelCardPartLifecycleMixin, AEBasePartClientSyncMixin | `AENetworkBlockEntity`, `AEBasePart` | unload/remove/stream | Disconnects channel links on unload/remove (chunk unload thread). |
| extendedae.common.matrix.* , TileAssemblerMatrixGlassLifecycleMixin | ExtendedAE assembler matrix classes, `AENetworkBlockEntity` | neighbour change, onLoad, unload, remove | Schedules super-matrix recalculation into static maps (§2.13). |
| extendedae.common.PartExPatternProviderMixin / TileExPatternProviderMixin | ExtendedAE Ex pattern provider | @ModifyArg ctor capacity | Slot count. |
| extendedae.common.TileCircuitCutterParallelMixin | `TileCircuitCutter` | capacity, parallel recipes | Machine (grid tick). |
| appflux.AppfluxPatternProviderLogicMixin | `PatternProviderLogic` | upgrade slots | — |
| minecraft.accessor.BlockEntityAccessor | `BlockEntity` | `setType` accessor | Used by PatternCorePlus to retype. |

---

## 2. Per-class findings

Column key: (1) mounts/provides storage, (2) foreign capability calls, (3) custom MEStorage/monitor/cache, (4) crafting/storage callbacks, (5) async/locks, (6) static/shared mutable state, (7) thread today.

### 2.1 `PartExImportBus` extends `appeng.parts.automation.ImportBusPart` **[C]**
- Overrides only: `getOperationsPerTick()` = `ImportBusPart.getOperationsPerTick() * EPPConfig.busSpeed`, `getUpgradeSlots()` = 8, `getMenuType()` = `ContainerExIOBus.IMPORT_TYPE`, `getStaticModels()`.
- Upgrade limits (`EAERegistryHandler`): capacity 5, redstone 1, speed 4, inverter 1, fuzzy 1 **[C]**. extendedae_plus adds the channel card **[C]**.
- (1) No storage mount. (2) Inherited: `StorageImportStrategy` → `BlockApiCache.find` = `level.getBlockEntity(pos.relative(side))` + `be.getCapability(ITEM_HANDLER|FLUID_HANDLER|addon caps, side.opposite())`. **No caching**: every call does `getBlockEntity`. (3) None. (4) None. (5) None. (6) Reads `EPPConfig.busSpeed` (static, config load only). (7) MAIN (grid tick).

### 2.2 `PartExExportBus` extends `appeng.parts.automation.ExportBusPart` **[C]**
- Same overrides (EXPORT_TYPE). Upgrade limits: capacity 5, redstone 1, speed 4, fuzzy 1, crafting 1 **[C]**; plus the channel card.
- (2) `StorageExportStrategy` (same `BlockApiCache`); `push()` on crafting paths. (4) **`ICraftingRequester`**: `MultiCraftingTracker` → `CraftingService` job requests; `insertCraftedItems()` called by the CPU → `getExportStrategy().push(...)` straight into the neighbour **[C]**. (7) MAIN (grid tick and crafting CPU tick).

### 2.3 Other ExtendedAE export buses
- `PartSpecialExportBus` (base of `PartModExportBus`, `PartTagExportBus`) extends `IOBusPart` **[C]**. Its `doBusWork` iterates **`storageService.getCachedInventory().keySet()`** (the whole network key set, every tick), filters with `ModPriorityList`/`TagPriorityList`, then calls `getExportStrategy().transfer(...)` per key until ops run out. Context: `ModStackTransferContext`/`TagStackTransferContext` (plain value holders) **[C]**. It does not inherit the `ExportBusPart` crafting logic.
- `PartPreciseExportBus` extends `ExportBusPart`. Exact-batch mode: `simulateExtract` on the network (`StorageHelper.poweredExtraction` SIMULATE) plus a `push(SIMULATE)` on the neighbour before `transfer`, i.e. all-or-nothing batches; crafting card support **[C]**.
- `PartThresholdExportBus` extends `ExportBusPart`. Exports only when `getCachedInventory().get(key)` is GREATER/LOWER than the threshold **[C]**.
- All use `Ae2Reflect.getExportContext` (reflective call into `ExportBusPart.createTransferContext`) and `EPPConfig.busSpeed` scaling (Precise) **[C]**.

### 2.4 Storage buses: `PartSpecialStorageBus` (+ `PartModStorageBus`, `PartPreciseStorageBus`, `PartTagStorageBus`) **[C]**
- A reimplementation of AE2 `StorageBusPart`: `IStorageProvider` + `IGridTickable`. It builds `CompositeStorage` from `StackWorldBehaviors.createExternalStorageStrategies(level, pos.relative(side), side.opposite)` → `ForgeExternalStorageStrategy.createWrapper` (a live `ExternalStorageFacade` over the foreign `IItemHandler`/`IFluidHandler`). It also uses `PartAdjacentApi<MEStorage>(Capabilities.STORAGE)`, which mounts an adjacent ME interface's storage, i.e. **another grid's NetworkStorage** when that interface is unconfigured (AE2 `InterfaceLogic.getInventory()`) **[C]**. It sets `ITickingMonitor monitor` and calls `monitor.onTick()` in `tickingRequest`.
- `StorageBusInventory extends MEInventoryHandler` (access, partition, void, extract filtering). `PreciseInventory` overrides `insert`/`extract`/`getAvailableStacks` against a `KeyCounter` amount filter (`StorageMode.test`) **[C]**.
- `onNeighborChanged` (world neighbour update → REGION) → `updateTarget`/`scheduleUpdate` → `grid.getTickManager().alertDevice` **[C]**. NestWorld already guards the tick-manager entry points.
- Classification: **EXTERNAL_MOUNT** (as decided in WP3-A, storage buses stay MAIN-owned). `TagPriorityList.memory` (`Reference2BooleanOpenHashMap`, `computeIfAbsent` inside `isListed`) is per-filter mutable state hit on every insert/extract, and is safe only single-threaded.

### 2.5 `PartExInterface` / `TileExInterface` / `PartOversizeInterface` / `TileOversizeInterface` **[C]**
- Plain AE2 `InterfaceLogic` (more slots, paging). Oversize replaces `InterfaceLogic.config`/`storage` through reflection with `OversizeConfigInv` (capacity × `EPPConfig.getOversizeMultiplier`, which reads a static config map). Capability passthrough = `InterfaceLogic.getCapability`: `GENERIC_INTERNAL_INV` (local buffer) and `Capabilities.STORAGE`. **When unconfigured, STORAGE = the whole grid's `NetworkStorage`** (AE2 source) **[C]**.
- The interface tick (restock from/to network) is MAIN. Foreign pipes on REGION touch only the local `GenericStackInv` buffer, whose change callback is `onStorageChanged` → wake device (tick manager).
- Classification: interface logic **MAIN_NATIVE**; the exposed local buffer is a candidate natural transit buffer (§3.4); the STORAGE cap is **EXTERNAL_MOUNT** (exposes network storage to storage buses).

### 2.6 `TileExDrive` extends `DriveBlockEntity` **[C]**
- Only `getCellCount()`, client stream state and menu (reflection on client-side fields). Cells and mounts are AE2-native. **MAIN_NATIVE**.

### 2.7 `InfinityCellInventory` (ExtendedAE) **[C]**
- `StorageCell`: infinite record key (`getAvailableStacks` adds `getAsIntMax`). insert/extract are trivial, with no state beyond the ItemStack; idle drain comes from static config. **MAIN_NATIVE**.

### 2.8 Assembler matrix (ExtendedAE): `TileAssemblerMatrixPattern`, `ClusterAssemblerMatrix`, `TileAssemblerMatrixCrafter`, `CraftingMatrixThread` **[C]**
- `TileAssemblerMatrixPattern`: `ICraftingProvider` + `PatternContainer`. Its `pushPattern` → `ClusterAssemblerMatrix.pushCraftingJob` → `TileAssemblerMatrixCrafter.pushJob` → `CraftingThread.acceptJob`. `updatePatterns` → `ICraftingProvider.requestUpdate`. These are crafting callbacks (MAIN, crafting CPU).
- `TileAssemblerMatrixCrafter`: `IGridTickable`, up to 8 `CraftingMatrixThread`s (logic objects, **not Java threads**). Output goes into a 128-slot `OutputBuffer`, flushed in `tickingRequest` via `IStorageService.getInventory().insert(...)` (MAIN).
- `ClusterAssemblerMatrix`: `IAECluster`, uses AE2's static `MBCalculator.setModificationInProgress` (AE2-native global static).
- **`CraftingMatrixThread.LRU`**: `private static final SingleThreadLRU<AEItemKey, SearchResult>`. It is unsynchronized; `get()` calls `touch()`, which mutates `accessOrder` (`ObjectLinkedOpenHashSet`) **[C]**. It is shared by every crafter in every dimension. Safe only while all crafter ticks are single-threaded (today MAIN). **CUSTOM_SHARED_STATE**.

### 2.9 extendedae_plus `MirrorPatternProviderBlockEntity` extends AE2 `PatternProviderBlockEntity` **[C]**
- A **vanilla `BlockEntityTicker`** is registered (`ExtendedAEPlus.commonSetup` → `AEBaseEntityBlock.setBlockEntity(..., null, serverTick)`) → **REGION** thread.
- `serverTick` → `syncBoundMaster` (every 2 ticks while unstable, then 20; retry 40 for an unloaded master) → `resolveBoundMaster`: `server.getLevel(masterDimension)`, `isLoaded(masterPos)`, `getBlockEntity(masterPos)` (master can be any position, **cross-dimension**) → copies the master's pattern inventory and settings into its own `PatternProviderLogic` → `updatePatterns()` → `ICraftingProvider.requestUpdate(mainNode)` → CraftingService provider re-index.
- Classification: **CUSTOM_SHARED_STATE / UNKNOWN for ownership** (cross-region and cross-dimension BE read from REGION, plus a CraftingService mutation trigger from REGION). Crafting and pushing patterns otherwise follow AE2 PatternProviderLogic (MAIN).

### 2.10 extendedae_plus `PatternCorePlusBlockEntity` extends `TileAssemblerMatrixPattern` **[C]**
- 72-slot pattern inventory (swapped in through an accessor mixin). `onChangeInventory` → `SuperAssemblerMatrixCluster.invalidatePatternInputHandler()` / `refreshCraftingProvider()`. `eap$updateSuperMatrixStatus` performs `level.setBlock` (FORMED/POWERED) on itself.
- Thread: inventory change (menu/automation) plus cluster calls. Crafting-provider updates are MAIN-intended. **MAIN_NATIVE**, with the super-matrix static scheduler caveat (2.13).

### 2.11 extendedae_plus `SuperAssemblerMatrixBlockEntity` / `SuperAssemblerMatrixCluster` / `SuperAssemblerMatrixPatternInputHandler` **[C]**
- `SuperAssemblerMatrixBlockEntity`: `ICraftingProvider` + `IGridTickable` + `PatternContainer`. The cluster keeps `batchQueue`, `fallbackThreads` (`VirtualThreadTask`, a logical task and not a Java thread **[S]**), `outputBuffer` (`Object2LongLinkedOpenHashMap`) and `insertToNetwork` (MAIN tick). `PatternInputHandler` is a Forge `IItemHandler` over pattern inventories (pattern insertion by automation can come from foreign REGION code into local inventories, not network storage).
- **MAIN_NATIVE** for crafting/output. The static scheduler is in 2.13.

### 2.12 extendedae_plus `InfinityBigIntegerCellInventory` / `InfinityStorageManager` **[C]**
- A custom `StorageCell` with BigInteger counts. Contents live in **one global `SavedData`** in the overworld `DimensionDataStorage` (`InfinityStorageManager`, `Object2ObjectOpenHashMap<UUID, InfinityDataStorage>`, `storageRevision`), reached through static `ExtendedAEPlus.STORAGE_INSTANCE` (initialised in `LevelTickEvent` START). The per-cell cache is revision-based.
- Every grid in every dimension using these cells shares one unsynchronized map. Copied cells (same UUID) share contents. **CUSTOM_SHARED_STATE** (safe only while all cell access is MAIN, which matches the storage-MAIN decision; any off-MAIN reader such as JeiSyncManager breaks it).

### 2.13 extendedae_plus `SuperAssemblerMatrixCalculator` **[C]**
- `PENDING_RECALCULATIONS`, `DELAYED_RECALCULATIONS`: `private static final java.util.IdentityHashMap<ServerLevel, …>`, unsynchronized.
- Written by `scheduleRecalculate` / `scheduleAfterNeighborChange` / `scheduleUltimateLoadRecalculate`, called from `SuperAssemblerMatrixBlock`, `SuperAssemblerMatrixBlockEntity`, `HybridCoreBlock`, `UploadCoreBlockEntity`, `BlockAssemblerMatrixBaseMixin` (neighbour change) and `TileAssemblerMatrixBaseMixin` (onLoad). These are block-update/load paths, i.e. REGION or chunk load.
- Drained by `processScheduledRecalculations(level)` from `ExtendedAEPlus.worldTick` (`LevelTickEvent` END), which then forms clusters through `level.getBlockEntity`.
- **CUSTOM_SHARED_STATE**: cross-thread static map writes are possible as soon as two regions update matrix blocks. Which thread NestWorld fires `LevelTickEvent` on is **[H]** (believed main-level wrapper).

### 2.14 extendedae_plus `JeiSyncManager` **[C]**
- Registered unconditionally on `MinecraftForge.EVENT_BUS` (mod constructor). `@SubscribeEvent onPlayerTick` (phase END), every 20 ticks per player: `WirelessTerminalLocator.find(player)` → `getConnectedGrid` → **`grid.getStorageService().getInventory().getAvailableStacks()`**. That is a full NetworkStorage enumeration, which also walks every mounted storage bus → foreign `IItemHandler`s, plus **`grid.getCraftingService().getCraftables(filter)`**. The result is diffed and sent in S2C packets. `playerStates` = static `HashMap`.
- Thread: the player tick (**REGION** under NestWorld player-region execution **[S]**). It reads MAIN-owned storage and crafting and transitively calls foreign inventories in other regions. **Violates the storage MAIN-ownership decision**. Classification: **UNKNOWN → must be rerouted** (snapshot published by MAIN, or the handler moved to MAIN).

### 2.15 extendedae_plus `TagInventoryMEInterfaceBlockEntity` **[C]**
- `IInWorldGridNodeHost`; its `getCapability(ITEM_HANDLER)` returns `TagFilteredItemHandler`. `getSlots()` → `collectMatchingItems()` → `grid.getStorageService().getCachedInventory()` scan + tag filter. `extractItem` → `grid.getStorageService().getInventory().extract(...)`.
- Whoever calls the capability (hoppers, pipes, other mods' BE ticks: **REGION**) enters MAIN-owned NetworkStorage. **EXTERNAL_MOUNT (inverted: network exposed as a foreign inventory)**. It needs the same transit/proxy treatment as a bus, or MAIN-only gating.

### 2.16 extendedae_plus wireless: `WirelessTransceiverBlockEntity`, `LabeledWirelessTransceiverBlockEntity`, `WirelessMasterRegistry`, `LabelNetworkRegistry`, `WirelessSlaveLink`, `ChannelCardConnectionController` **[C]**
- Transceiver blocks register **vanilla server tickers** → `serverTick` → `slaveLink.updateStatus()` → `WirelessMasterRegistry.get(level, freq, owner)` (static `HashMap<Key, WeakReference>`, `static synchronized`) → distance check → **`GridHelper.createConnection(a,b)` / `IGridConnection.destroy()`** on the **REGION** thread.
- The `Key` includes the dimension unless `useGlobal()` (config) is on, in which case links are **cross-dimension** **[S]**.
- `LabelNetworkRegistry` is a `SavedData` with `synchronized` methods.
- Classification: **CUSTOM_SHARED_STATE** + topology mutation off-MAIN. The channel-card variant (2.17) does the same from the grid tick (MAIN).

### 2.17 extendedae_plus `EntitySpeedTickerPart` **[C]**
- `IGridTickable` part. In `tickingRequest` it resolves `level.getBlockEntity(pos.relative(side))`, obtains `blockState.getTicker(level, type)`, and `performTicks(be, ticker, n)` **runs the foreign BE's ticker n extra times** on the AE grid tick thread (MAIN). Energy comes from `IEnergyService.extractAEPower` or `AppliedFluxCompat.tryExtractFE` over network storage (MAIN).
- Foreign BE code runs on MAIN concurrently with its owning REGION. **UNKNOWN (outside the taxonomy: foreign tick execution); high risk**. Also has a channel-card controller.

### 2.18 extendedae_plus `NetworkPatternControllerBlockEntity` **[C]**
- Grid node host and menu only. Pattern-provider list and toggles go through packets (`enqueueWork` → server thread). **MAIN_NATIVE** **[S]**.

### 2.19 Not audited in depth (noted)
ExtendedAE `PartActiveFormationPlane` (in-world placement/pickup, i.e. world entity access), `TileExIOPort` (cell transfer, MAIN), `TileIngredientBuffer` (plain inventory), `TileWirelessConnector`/`WirelessConnect` (same-level grid connections created in the BE lifecycle, distance-limited by config, through `Locatables`), `TileCaner`, `TileExMolecularAssembler`/`TileExInscriber`/`TileCircuitCutter` (machines with `RecipeExecutor`, grid tick). None of them is in the measured hot set.

---

## 3. Ex import/export buses vs the REGION/MAIN transit split

### 3.1 What the bus tick touches today (all on MAIN, AE2 code) **[C]**
| Step | Side | Code |
|---|---|---|
| `isSleeping`, redstone/pulse | local part state | `IOBusPart.tickingRequest` |
| `canDoBusWork`: `mainNode.isActive()` + `Platform.areBlockEntitiesTicking(level, targetPos)` | network + **neighbour chunk state** | IOBusPart |
| `getFilter()` (config keys, fuzzy) | local | IOBusPart |
| Import: `apiCache.find` → foreign handler; iterate `getSlots`/`getStackInSlot`; per resource: network `insert SIMULATE` → **external `extract MODULATE`** → network `insert MODULATE` → leftover **external `insert MODULATE`** (else voided + warn) | both, interleaved per slot | `StorageImportStrategy.transfer` |
| Export: per config slot (round-robin/random via `level.getRandom()`): network `poweredExtraction SIMULATE` → **external `insert SIMULATE`** → network `poweredExtraction MODULATE` → **external `insert MODULATE`** → leftover network `insert` (else voided + error) | both, interleaved per key | `StorageExportStrategy.transfer` |
| Fuzzy export: `getCachedInventory().findFuzzy(...)` | network | ExportBusPart |
| Crafting card: `attemptCrafting` → **external `push SIMULATE`** → `MultiCraftingTracker.handleCrafting` → CraftingService | both | ExportBusPart |
| `insertCraftedItems` (called by the crafting CPU, not the bus tick) → **external `push MODULATE`**, synchronous accepted count | external from crafting CPU | ExportBusPart |
| Return FASTER/SLOWER/SLEEP | tick manager | IOBusPart |
| extendedae_plus TAIL: channel-card `controller.tick()` → grid connection create/destroy | **topology** | IOBusPartChannelCardMixin |

Tick rate: AE2 `TickRates.ImportBus` / `ExportBus` (min/max from AE2 config) with FASTER/SLOWER modulation. ExtendedAE does not change it **[C]**. Ops per tick: `{0:1, 1:8, 2:32, 3:64, 4:96}[speedCards] × busSpeed(8)` → up to 768 **[C]**. Slots exported: `min(18 + 9 × capacityCards, 63)` **[C]**.

### 3.2 Extra world access besides the facing neighbour
- **None** in ExtendedAE's Ex import/export bus code **[C]**. There is no multi-target, wireless target or entity access: only `level.getBlockEntity(hostPos.relative(side))` + `getCapability(side.opposite)` per strategy (item, fluid, plus any addon `StackWorldBehaviors` strategies registered in the pack, e.g. AppMek chemicals or AppliedFlux FE **[S]**), and `areBlockEntitiesTicking(targetPos)`.
- Caveats:
  - (a) The neighbour position may lie in a **different region** from the bus host when the bus faces across a region boundary, so the "external side" must be owned by the region of `targetPos`, not of the host.
  - (b) The channel card can attach the bus's node to a **remote grid** (other region or, in global mode, other dimension). The network side then belongs to that grid. MAIN ownership of storage keeps this consistent.
  - (c) `RANDOM` scheduling uses `level.getRandom()` (level RNG) **[C]**.

### 3.3 Would "external on REGION / network on MAIN via transit buffer" preserve behaviour?
Partially. Exact equivalence is impossible because of the synchronous cross-side simulates. Per feature:

| Feature | Preserved? | Notes |
|---|---|---|
| Import, plain/filtered/inverted/fuzzy | **Mostly**, with a semantics change | REGION extracts up to the budget into transit **without** a network-capacity simulate. MAIN inserts; the remainder must be **returned to the neighbour** by REGION next tick or held in transit (no voiding). Requires: transit holds ≤1 batch, REGION skips extraction while transit is non-empty or the network-full flag is set, and the filter is snapshotted from config (pure data). Extra latency of 1 handoff. Items in transit are briefly in neither inventory (accounting invariant needed). |
| Export, plain | **Mostly**, with a semantics change | MAIN must decide the amount without an external SIMULATE. Options: (i) REGION publishes a per-key acceptance probe (SIMULATE insert) last tick, MAIN extracts `min(probe, available)`, REGION inserts, leftover returns to the network through transit; (ii) speculative extract with return. Both delay by ≥1 handoff and let the network see items temporarily missing (terminal/crafting visibility). |
| Export round-robin `nextSlot` advance | Needs care | Advance only when work was done; with async completion, advance on the MAIN reconcile. |
| Export fuzzy | Same as plain | `findFuzzy` on MAIN; per-key probes needed. |
| **Precise export (exact batch)** | **Not without a probe** | The all-or-nothing batch needs an exact external SIMULATE; it needs REGION-side atomic accept-or-reject of the whole batch with full return. |
| **Threshold export** | Yes (network side only) | The threshold check is pure MAIN (`getCachedInventory().get`). |
| **Mod/Tag export** (`PartSpecialExportBus`) | Poorly | It iterates the entire network key set and relies on per-key `transfer` returning 0 to move on. Without a synchronous external answer this means per-key probes over the whole key set, or a speculative bulk extract. Probes must be produced by REGION from the neighbour's handler contents and capacity. |
| **Crafting card** (`attemptCrafting`, `insertCraftedItems`) | **No, as is** | `insertCraftedItems` is called by the crafting CPU on MAIN and returns a synchronous accepted count. Under the split MAIN cannot touch the neighbour, so crafted output must go into a bus-side transit that **accepts on behalf of the neighbour**. The CPU then counts it delivered before the neighbour accepts, and rejection needs a path back to the network (or to the CPU, which is impossible after the link completes). `attemptCrafting`'s external SIMULATE also needs the probe. |
| Redstone / pulse mode | Yes | The pulse is latched in local part state. `onNeighborChanged` → `alertDevice` is already guarded by NestWorld compat. |
| `canDoBusWork` (`areBlockEntitiesTicking(targetPos)`) | Needs a REGION-published flag | It reads neighbour chunk state from MAIN. |
| Channel card (extendedae_plus TAIL) | Must stay on MAIN | Topology mutation. Keep it in the MAIN half. |
| Sleep/wake modulation | Yes | FASTER/SLOWER computed on MAIN from the reconcile result. |
| Operations budget (768/tick) | Yes | Budget carried in the transit request. Note fluids: 768 ops = 768 buckets. |

### 3.4 Observations for the WP3-A.2 design
- Because Ex buses add only an ops multiplier, a split implemented for AE2 `ImportBusPart`/`ExportBusPart` (strategy-level or `doBusWork`-level) automatically covers them. `PartSpecialExportBus` (Mod/Tag) and `PartPreciseExportBus`/`PartThresholdExportBus` have their own `doBusWork` and need explicit handling or exclusion **[C]**.
- The strategies are the natural seam: `StackImportStrategy.transfer(context)` and `StackExportStrategy.transfer/push`. Every network access goes through `context.getInternalStorage()` / `getEnergySource()`, and every external access goes through `BlockApiCache.find`. A REGION-side "external facade" plus a MAIN-side "network facade" can be cut there, but the per-call interleaving forces probe/return semantics.
- The ME interface pattern (local buffer + MAIN restock) already achieves "foreign side touches only a local buffer" for interfaces. An import/export bus could be emulated as "interface-like buffer + REGION pump" at the cost of the semantics above.

---

## 4. Classification table

| Feature / class | Class | Confidence |
|---|---|---|
| PartExImportBus (AE2 ImportBusPart logic, ×busSpeed) network side | MAIN_NATIVE | C |
| PartExImportBus external neighbour interaction | TRANSIT_SAFE *with* return-to-source + network-full gating + filter snapshot (semantics change: no pre-simulate, +1 tick) | S |
| PartExExportBus plain/fuzzy/round-robin export | TRANSIT_SAFE *only with* REGION acceptance probes and leftover return (+1 tick, items transiently out of network) | S |
| PartExExportBus crafting card (`insertCraftedItems`, `attemptCrafting`) | UNKNOWN (synchronous external push from crafting CPU; not transit-preserving as is) | C (mechanism) |
| IOBusPartChannelCardMixin tail on all IO buses | MAIN_NATIVE (topology mutation; must stay on MAIN) | C |
| PartPreciseExportBus exact batch | UNKNOWN (needs atomic external accept/reject) | C (mechanism) |
| PartThresholdExportBus | network side MAIN_NATIVE; external as plain export | C |
| PartModExportBus / PartTagExportBus (`PartSpecialExportBus`) | MAIN_NATIVE scan; external side TRANSIT-unfriendly (full key-set scan × per-key probe) | C (mechanism) / S (cost) |
| PartSpecialStorageBus + Mod/Precise/Tag storage buses | EXTERNAL_MOUNT | C |
| TagPriorityList (`memory`, static `INVALIDATOR` WeakHashMap) | CUSTOM_SHARED_STATE (single-thread only) | C |
| PartExInterface / TileExInterface / Oversize interface logic | MAIN_NATIVE; `Capabilities.STORAGE` exposure = EXTERNAL_MOUNT (whole grid when unconfigured) | C |
| TileExDrive | MAIN_NATIVE | C |
| InfinityCellInventory (EAE) | MAIN_NATIVE | C |
| TileAssemblerMatrixPattern / ClusterAssemblerMatrix / Crafter | MAIN_NATIVE | C |
| CraftingMatrixThread static `SingleThreadLRU` | CUSTOM_SHARED_STATE | C |
| MixinAEBasePart @Overwrite `addToWorld` (swallows ISE) | MAIN_NATIVE (lifecycle masking risk) | C |
| MirrorPatternProviderBlockEntity ticker (REGION; cross-dim master read; triggers CraftingService update) | CUSTOM_SHARED_STATE / UNKNOWN | C |
| PatternCorePlusBlockEntity | MAIN_NATIVE | S |
| SuperAssemblerMatrix BE/Cluster (crafting, output insert) | MAIN_NATIVE | S |
| SuperAssemblerMatrixCalculator static IdentityHashMaps | CUSTOM_SHARED_STATE | C |
| InfinityBigIntegerCell / InfinityStorageManager (global SavedData) | CUSTOM_SHARED_STATE (MAIN-only safe) | C |
| JeiSyncManager (player tick → storage + crafting enumeration) | UNKNOWN (off-MAIN reader of MAIN storage; reroute required) | C |
| TagInventoryMEInterfaceBlockEntity IItemHandler over network storage | EXTERNAL_MOUNT (inverted) | C |
| WirelessTransceiver BE tickers (REGION grid connect/destroy; static registry) | CUSTOM_SHARED_STATE + off-MAIN topology | C |
| EntitySpeedTickerPart (foreign BE ticker run from grid tick) | UNKNOWN (foreign tick on MAIN) | C |
| CraftingSimulationStateMixin (`CraftingService.getProviders` on AE Crafting Calculator thread) | UNKNOWN (AE2-native async calc pattern, widened) | C |
| NetworkPatternControllerBlockEntity | MAIN_NATIVE | S |
| ModConfig ScheduledExecutor | n/a (config debounce only) | S |

---

## 5. Key risks (ordered)
1. **JeiSyncManager**: REGION player tick enumerates `NetworkStorage.getAvailableStacks()` (including storage-bus foreign inventories in other regions) and `CraftingService.getCraftables`. This already contradicts storage MAIN ownership, independent of WP3-A.2.
2. **TagInventoryMEInterface**: a foreign `IItemHandler` straight into network storage (`extract`), callable from any REGION pipe.
3. **Topology mutation off-MAIN**: wireless transceiver BE tickers (`GridHelper.createConnection` / `destroy` on REGION). The channel-card variant runs inside every IO/storage bus and interface tick (MAIN), so a bus split must keep the TAIL on MAIN.
4. **Export-bus crafting path**: `insertCraftedItems` performs a synchronous external push from the crafting CPU, so the transit split cannot preserve the accepted-count contract.
5. **Precise / Mod / Tag export** cannot be reproduced without synchronous external simulates; they need probes or exclusion. Mod/Tag also scan the whole network key set every tick on MAIN (a likely contributor to the measured cost).
6. **Mirror pattern provider**: REGION ticker reads arbitrary-position, cross-dimension BEs and triggers CraftingService provider updates.
7. **EntitySpeedTickerPart**: foreign BE tickers executed on MAIN while their region also ticks them.
8. **Unsynchronized statics**: `CraftingMatrixThread.LRU`, `SuperAssemblerMatrixCalculator` maps, `JeiSyncManager.playerStates`, `TagPriorityList.INVALIDATOR`, Infinity BigInteger SavedData, and the inner sets of `ChannelCardConnectionController.REGISTRY`.
9. **`MixinAEBasePart` swallowing `IllegalStateException`** in `addToWorld` for every AE2 part: it can mask lifecycle races that NestWorld fixtures rely on detecting.
10. **Cross-region neighbours**: a bus at a region edge has its host and target in different regions; the external half must run on the target's owner.
