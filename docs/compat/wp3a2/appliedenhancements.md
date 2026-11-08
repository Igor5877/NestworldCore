# WP3-A.2 audit — appliedenhancements 1.1.2-forge

Scope: AE2-STORAGE-DOMAIN-001 WP3-A.2. Planned transit split for import/export buses: the REGION thread does only the external neighbour-inventory interaction, MAIN does the network insert/extract, and an explicit transit buffer carries items between them. Storage domain = MAIN, no new lock.

Inputs:
- Jar: `/root/ae2-pack-audit-2026-11-07/x/appliedenhancements-1.1.2-forge/`
- Mixin config: `appliedenhancements.mixins.json`, `required=true`, `injectors.defaultRequire=1`
- Reference sources: AE2 15.4.10 source, AE2 15.4.11 classes
- Full `javap -p -c -v` dumps of every class: `/root/ae2-pack-audit-2026-11-07/wp3a2/javap-appliedenhancements/`

Method: bytecode only. I read the annotations of all 40 server mixins and the bodies of every storage/IO mixin and its helper classes. I grepped all non-client classes for concurrency APIs, capability APIs, storage APIs and static state, and checked the injection points against the AE2 15.4.11 bytecode. Nothing was run or modified.

Confidence tags: **[C]** CONFIRMED from bytecode, **[S]** STRONGLY INDICATED, **[H]** HYPOTHESIS.

## 0. Pack config that actually applies

Project Infinity and pi-smoke use identical `appliedenhancements-common.toml` files.

| Key | Field | Mod default | Pack value | Effect |
|---|---|---|---|---|
| `performance.io_bus.enable_slot_routing` | `ENABLE_IO_BUS_OPTIMIZATION` | true | **true** | All four IO-bus mixins are active |
| `performance.storage_bus.enable_slot_index` | `ENABLE_STORAGE_BUS_SLOT_INDEX` | true | **true** | Storage-bus slot index is active |
| `storage.infinite.enable_listing_limit_bypass` | `ENABLE_INFINITE_STORAGE_LIMIT_BYPASS` | false | **true** | NetworkStorage infinite-cell paths are active. The mod's tag marks `ae2:creative_storage_cell` and `expatternprovider:infinity_cell`, and ExtendedAE is in the pack. |
| `crafting.aelis.enable_automatic_planner` | `ENABLE_AUTOMATIC_AELIS_PLANNER` | false | **false** | `ManualCraftingInventoryLock.enabled()` returns false, so the **reservation clamp on NetworkStorage.extract is dormant** [C] |
| `performance.pattern_cache.enabled` | `ENABLE_PATTERN_CACHING` | true | true | Pattern-input memo caches are active |

Every flag is read on every call through `ForgeConfigSpec.get()`, so a config reload can flip behaviour at runtime.

## 1. Per-mixin table (server mixins; the 23 client mixins are UI-only and out of scope)

Column key:
- **Mount**: does it mount or provide storage?
- **Foreign cap**: does it call foreign capabilities?
- **Custom state**: custom MEStorage, monitor or cache?
- **Thread**: which thread enters it today.

"CPU tick" means CraftingService end-tick → CraftingCpuLogic, on the server thread.

| # | Mixin → target | Injection(s) | What it does | Mount | Foreign cap | Custom state | Thread | Class |
|---|---|---|---|---|---|---|---|---|
| 1 | **NetworkStorageMixin** → `appeng.me.storage.NetworkStorage` | `@Inject mount RETURN`, `@Inject unmount RETURN`, `@WrapOperation extract→MEStorage.extract`, `@ModifyVariable extract HEAD (amount)`, `@WrapOperation getAvailableStacks→MEStorage.getAvailableStacks` | Tracks mounted "infinite" cells. A MODULATE extract from an infinite cell becomes a SIMULATE of 1 and returns the full amount without depleting. Clamps every extract by crafting reservations. Merges per-mount listings with saturation. | No (wraps the network aggregate) | No | Per-NetworkStorage `volatile MountedInfiniteStorage` (lazy, double-checked locking on `this`); `Collections.synchronizedSet(IdentityHashMap)`; classifier `synchronizedMap(IdentityHashMap)` | MAIN (grid storage ops) | MAIN_NATIVE [C] |
| 2 | **StorageImportStrategyMixin** → `appeng.parts.automation.StorageImportStrategy` (both item and fluid instances) | `@WrapOperation transfer→HandlerStrategy.getFacade(Object)`, `@WrapOperation transfer→ExternalStorageFacade.extract(...)` | (a) Caches the `ExternalStorageFacade` across ticks while the adjacent handler is the same object. (b) If the requested amount is ≤0 (that is, network SIMULATE-insert returned 0), it returns 0 without calling external extract. | No | Indirect: external extract | Per-strategy plain fields `cachedHandler`, `cachedFacade` | MAIN (bus tick) | CUSTOM_SHARED_STATE (per-bus) / TRANSIT_SAFE with conditions [C] |
| 3 | **StackImportFacadeMixin** → `appeng.parts.automation.StackImportFacade` | `@WrapOperation transfer→StackImportStrategy.transfer` | When `!context.hasOperationsLeft()`, it returns true and skips the remaining strategies (for example, the fluid strategy after items). | No | No | None | MAIN (bus tick) | TRANSIT_SAFE with conditions [C] |
| 4 | **ExternalStorageFacadeItemHandlerMixin** → `ExternalStorageFacade$ItemHandlerFacade` | `@WrapOperation extractFromHandler→wrapHandlerExtract`, `@Inject extractExternal RETURN`, `@Inject insertExternal RETURN`, `@Inject getStackInSlot RETURN`, `@Inject getAvailableStacks HEAD/RETURN`, `@WrapOperation getAvailableStacks→IItemHandler.getStackInSlot`, `@WrapMethod extractExternal` | (a) Rechecks that the slot still matches the key before every extract. (b) Remembers the last slot and key seen by `getStackInSlot` and extracts from that slot first. (c) Builds a key→slots index during `getAvailableStacks` and extracts from the indexed slots first. (d) Invalidates the index after any MODULATE insert/extract with a result >0. Any shortfall falls back to AE2's full scan; a SIMULATE shortfall delegates to native. | Instance is the storage-bus mount, the import-bus facade and the export target | Yes: `IItemHandler.getStackInSlot/getSlots/extractItem` on the facade's handler | Per-facade plain fields `slotIndex (StorageBusSlotIndex)`, `lastObservedSlot`, `lastObservedKey`; **no synchronisation** | Storage-bus facade: MAIN (network ops). Import-bus facade: MAIN (bus tick) | EXTERNAL_MOUNT (storage bus) + CUSTOM_SHARED_STATE (per facade) [C] |
| 5 | **ItemHandlerStrategyMixin** → `appeng.parts.automation.HandlerStrategy$1` (= the `HandlerStrategy.ITEMS` **static singleton**) | `@WrapOperation insert(IItemHandler,AEKey,long,Actionable)→ItemHandlerHelper.insertItem` | Replaces Forge's slot order 0..n with: remembered slots for (handler, key) first, then 0..n. Records the accepting slots after every call, **SIMULATE and MODULATE alike**. | No | Yes: `IItemHandler.insertItem/getSlots` on the export target | **Server-global** `WeakSlotHintCache` (WeakHashMap<IItemHandler, LRU(128) key→slots>). Methods are `synchronized`; the lazy init of the field is **unsynchronised**. | MAIN (export-bus tick, `StorageExportStrategy.transfer/push`) | CUSTOM_SHARED_STATE (global) / TRANSIT_SAFE [C] |
| 6 | **StorageCellsMixin** → `appeng.api.storage.StorageCells` | `@Inject getCellInventory RETURN` | Records the StorageCell→ItemStack mapping for infinite-tag classification. | No | No | Static `synchronizedMap(WeakHashMap) CELL_ITEMS` | Whoever creates a cell inventory: MAIN (drive/chest), client (tooltips) | CUSTOM_SHARED_STATE, thread-safe [C] |
| 7 | **DelegatingMEInventoryAccessor** → `DelegatingMEInventory` | `@Accessor delegate` | Lets the code unwrap a mount to find its cell. | — | — | — | MAIN | MAIN_NATIVE [C] |
| 8 | **NetworkCraftingSimulationStateInventoryLockMixin** → `NetworkCraftingSimulationState` | `@Inject <init> RETURN` | Subtracts active manual reservations from the planning snapshot (only while the AELIS automatic planner is enabled). | No | No | Reads `ManualCraftingInventoryLock.LEDGERS` (static synchronized WeakHashMap<MEStorage, Ledger>) | Server thread: `CraftingCalculation` is constructed in `beginCraftingCalculation` | MAIN_NATIVE [C] |
| 9 | **MEStorageMenuContextMixin** → `MEStorageMenu` (interface `NetworkItemExtractionMenuBridge`) | no injector, adds a method | Context-menu "extract to player": `poweredExtraction` from network storage into the player inventory, with re-insert and drop of the remainder. | No | No | No | MAIN (packet `consumerMainThread`) | MAIN_NATIVE [C] |
| 10 | **CraftConfirmMenuMixin** → `CraftConfirmMenu` | `@Inject planJob HEAD`, `@WrapOperation planJob→ICraftingService.beginCraftingCalculation`, `@Inject replan/goBack HEAD`, `@Inject broadcastChanges INVOKE sendPacketToClient + RETURN (×4)`, `@WrapMethod startJob()/startJob(Z)`, `@WrapOperation startJob→ICraftingSubmitResult.successful`, `@Inject removed HEAD` | Progress sync and long/BigInteger amounts. **Acquires an inventory reservation when a plan completes** (in `broadcastChanges`), holds it until `startJob` or menu close, and replans on conflict. | No | No | Per-menu `Reservation`; static `AtomicLong` | Menu broadcast thread (player container tick) and packet → MAIN | MAIN_NATIVE; dormant in pack [C]. The thread is UNKNOWN under NestWorld player-region execution [H]. |
| 11 | **CraftAmountMenuMixin** → `CraftAmountMenu` (bridges) | no injector | Long/exact amount entry; reads `IStorageService.getCachedInventory()` | No | No | No | MAIN (packet) | MAIN_NATIVE [C] |
| 12 | **AelisCraftingCalculationMixin** → `appeng.crafting.CraftingCalculation` | `@WrapOperation <init> NEW CraftingTreeNode`, `@Inject <init> RETURN`, `@WrapMethod run`, `@WrapMethod runCraftAttempt`, `@WrapOperation runCraftAttempt→CraftingTreeNode.request / buildCraftingPlan / getNodeCount`, `@Inject runCraftAttempt HEAD/RETURN` | Swaps in the AELIS planner, tracks progress, and limits concurrent calculations with static Semaphores. | No | No | Static `Semaphore CALCULATION_SLOTS(max(1,min(8,cpus/2)), fair)` and `INTERACTIVE_SLOT(1)` | AE2 `CRAFTING_POOL` ("AE Crafting Calculator") or the mod's `AelisExactCraftingService.EXECUTOR` | MAIN_NATIVE plus the calc thread in lockstep [C] |
| 13 | **CraftingSimulationStateCyclicCraftingMixin / ...BytesMixin / InfiniteCraftingSimulationMixin / ...LongSafetyAccessor / AelisChildSimulationStateAccessor** → `CraftingSimulationState` family | `@Inject ignore/addCrafting/addBytes/extract/insert`, `@WrapMethod applyDiff`, `@WrapOperation applyDiff→addCrafting/addBytes`, `@Inject buildCraftingPlan RETURN`, accessors | Exact/BigInteger and cyclic plan bookkeeping. Infinite keys are served from the simulation, not from storage. | No | No | Per-simulation fields only | Calc thread | MAIN_NATIVE (calc-confined) [C] |
| 14 | **CraftingPlanMixin / CraftingPlanSummaryMixin / CraftingPlanSummaryAccessor** | `@Inject fromJob HEAD/RETURN` and fields | Carries plan metadata; summary projection | No | No | Per-plan | Calc thread / menu | MAIN_NATIVE [C] |
| 15 | **CraftingCpuLogicBatchMixin** → `CraftingCpuLogic` (prio 900) | `@ModifyVariable/@Inject trySubmitJob`, `@WrapMethod executeCrafting/insert/finishJob`, `@WrapOperation executeCrafting→pushPattern / extractPatternInputs` (require=0), `@Inject getPendingOutputs/getAllItems HEAD`, `@Inject read/writeToNBT` | Cycle runtime, balanced provider batches, retention of the final output. Touches the CPU inventory, not network storage. | No | Via `pushPattern` (native) | Static `ThreadLocal<ArrayDeque<MolecularBalancedBatchScope>>`; per-CPU fields | MAIN (CPU tick) | MAIN_NATIVE [C] |
| 16 | **CraftingServiceCycleSafetyMixin** → `CraftingService` (prio 1100) | `@ModifyReturnValue getProviders RETURN` | Wraps the provider iterable while a cycle is active | No | No | No | MAIN / calc | MAIN_NATIVE [C] |
| 17 | **CraftingCPUClusterCycleAwareMixin, AdvancedAeCycleAwareCpuMixin** | interface only | Marks the CPU as cycle-aware | — | — | — | — | MAIN_NATIVE [C] |
| 18 | **AdvancedAeCraftingCycleMixin, AdvancedAeOrderCompletionMixin** (`@Pseudo` AdvCraftingCPULogic) | same shape as #15 plus `CraftingLink.insert` and `ListCraftingInventory.insert` wraps | AdvancedAE CPU parity | No | Via `pushPattern` | Per-CPU fields | MAIN (CPU tick) | MAIN_NATIVE [C] |
| 19 | **ExecutingCraftingJobLongSafetyMixin** (+ accessors ExecutingCraftingJobCycle/TaskProgress/ElapsedTimeTracker) | `@Inject <init> RETURN` | Rebuilds saturated progress | No | No | No | MAIN | MAIN_NATIVE [C] |
| 20 | **AECraftingPatternInputMixin** → `AECraftingPattern$Input` | `@Inject isValid HEAD/RETURN`, `@Inject getRemainingKey HEAD/RETURN` | Memoises `isValid(key, level)` and `getRemainingKey(key)` per pattern input | No | No | `volatile` single-entry caches plus `volatile ConcurrentHashMap` caches, bounded by `max_entries_per_pattern`; double-checked lazy init under `monitorenter this` | Server thread (CPU/provider) **and** calc threads | CUSTOM_SHARED_STATE, thread-safe [C] |
| 21 | **CraftingStatusEntryExactMixin, CraftingStatusExactMixin** | `@Inject isDeleted/compareTo/create/write/read`, `@WrapOperation create→isDeleted` | Exact status display and serialisation | No | No | No | Menu / network | MAIN_NATIVE [C] |
| 22 | **Ae2CraftingTreeHelperMixin, UselessSmartDoublingPlanMixin, AE2WTLibCraftingTerminalHandlerMixin** (`@Pseudo`) | `@Inject build HEAD`, `@WrapMethod rewriteForSubmission` (require=0), `@Inject getAccessibleAmount RETURN` | Addon UI/plan compatibility; saturates a long | No | No | No | Menu / MAIN | MAIN_NATIVE [C] |
| 23 | **PatternAccessTermMenuBatchMoveMixin, PatternContainerTrackerAccessor** | interface and accessors | Batch-moves patterns between pattern-provider internal inventories | No | No (`InternalInventory`) | No | MAIN (packet) | MAIN_NATIVE [C] |
| 24 | **AelisCraftingTreeNodeAccessor, AelisCraftingTreeProcessAccessor** | `@Accessor/@Invoker` (including `request`, `buildChildPatterns`) | Planner access to the AE2 tree | No | No | No | Calc thread | MAIN_NATIVE [C] |

Non-mixin classes on storage/crafting paths:
- `storage.InfiniteStorageSupport`: static `ThreadLocal<Boolean> PHYSICAL_EXTRACT` and `ThreadLocal<IdentityHashMap<MEStorage,Boolean>> AVAILABILITY_QUERIES`. It makes nested SIMULATE `MEStorage.extract` calls on the NetworkStorage itself.
- `storage.MountedInfiniteStorage`, `storage.InfiniteStorageCellRegistry(+MountedClassifier)`: synchronized collections.
- `storage.StorageBusSlotIndex`: unsynchronised.
- `storage.WeakSlotHintCache`: all methods `synchronized`.
- `runtime.ManualCraftingInventoryLock`: static synchronized `WeakHashMap LEDGERS` and `ThreadLocal ACTIVE_SUBMISSION`.
- `runtime.InventoryReservationLedger`: `synchronized` methods.
- `runtime.ExactCraftingTree`: static `synchronizedMap(WeakHashMap)` TOTALS and NODES.
- `crafting.MolecularReusableInputAdapters`: static `synchronizedMap(IdentityHashMap) IDENTITY_PROBES`. This uses strong keys.
- `api.AelisExactCraftingService`: static fixed-pool `EXECUTOR`, daemon threads.
- `runtime.AelisCycleDispatchScope`, `ExactRequestScope`, `CraftingPlannerIntervention`, `aelis.AelisRecursionGuard/AelisIgnoredSeedScope/AelisFirstCandidatePrefixProbeScope`: static ThreadLocals.
- `api.*Api`: registration `ConcurrentHashMap` / `CopyOnWriteArrayList`.
- `network.ServerConfigSyncState`: static volatile.

## 2. Answers to the nine questions

**Q1. Targets and injections.** See the table. Every AE2 target uses `remap=false`. All storage/IO injection points exist with the same shape in AE2 15.4.11:
- `StorageImportStrategy.transfer` still calls `HandlerStrategy.getFacade` and then `ExternalStorageFacade.extract`.
- `StackImportFacade.transfer` still calls `StackImportStrategy.transfer`.
- `HandlerStrategy$1.insert` still calls `ItemHandlerHelper.insertItem`.
- `ItemHandlerFacade` still has `extractFromHandler`, `wrapHandlerExtract`, `extractExternal`, `insertExternal`, `getStackInSlot`, and `getAvailableStacks` calling `IItemHandler.getStackInSlot`.
- `NetworkStorage.extract/mount/unmount/getAvailableStacks` are unchanged. [C]

**Q2. Mount/provide storage?** No new MEStorage, IStorageProvider or cell type. [C]
- `ExternalStorageFacadeItemHandlerMixin` changes the internals of the AE2 facade that storage buses mount (EXTERNAL_MOUNT, already MAIN under WP3-A).
- NetworkStorageMixin changes how existing mounts are aggregated: infinite cells and reservations.

**Q3. Foreign capabilities.** No `getCapability` or `LazyOptional` anywhere in the jar. [C] `IItemHandler` methods are called only in:
- **ItemHandlerFacade (#4):** `getStackInSlot`, `getSlots` and `extractItem` (through native `extractFromHandler`). Paths: storage-bus `extractExternal`/`getAvailableStacks` (MAIN network ops), and the import-bus `getStackInSlot`/`extract` loop (bus tick).
- **HandlerStrategy.ITEMS (#5):** `insertItem` and `getSlots`. Path: export bus `StorageExportStrategy.transfer/push`.

The handler comes from AE2's own `BlockApiCache`. No `IFluidHandler` calls are added; fluid buses are affected only by the facade cache in #2 and the budget short-circuit in #3.

**Q4. Custom MEStorage or monitors.**
- No custom MEStorage. No IStorageWatcher, IStackWatcher or listener registration. [C]
- KeyCounter use: temporary per-call counters in `collectAvailableStacks` and in the reservation snapshot only.
- Custom caches: the storage-bus slot index (per facade), the observed-slot hint (per facade), the facade cache (per import strategy), the global export slot-hint cache, and the pattern-input memos.
- `InfinitePlanningInventory` is an interface mixed into `CraftingSimulationState`; it is not storage.

**Q5. Crafting and storage callbacks added or changed.**
- Network extract: reservation clamp and infinite non-depletion.
- Network listing: saturating merge.
- Mount/unmount: infinite tracking.
- Crafting: CraftingCalculation path, plan metadata, CPU `executeCrafting/insert/finishJob/trySubmitJob` wrapping, `CraftingService.getProviders` wrapping, and the menu plan/start lifecycle (reservation acquire/release). [C]

**Q6. Async, threads and locks.**
- **NetworkStorageMixin `monitorenter`:** double-checked lazy init of the per-NetworkStorage `volatile MountedInfiniteStorage`, synchronised on the NetworkStorage instance (`this`). Nothing else is held under it. It exists so the infinite-state lookup is safe from any caller thread. The real callers are planning-snapshot code (`InfiniteStorageSupport.snapshot`, called from `ManualCraftingInventoryLock.tryAcquire`) and normal network ops. [C]
- **AECraftingPatternInputMixin `monitorenter`:** double-checked lazy init of the `ConcurrentHashMap` cache fields, synchronised on the `AECraftingPattern$Input` instance. Pattern objects are shared between the server thread (CPU/provider `isValid`) and AE crafting-calculator threads, which explains the volatile, CHM and monitor design. [C]
- **AelisCraftingCalculationMixin:** static fair Semaphores gate `CraftingCalculation.run()` on the calculator thread, using `acquire()` (blocking) and, for interactive requests, `tryAcquire()` with a fallback to the interactive slot. The planner's `PauseCheckpoint` is a method reference to **`CraftingCalculation.handlePausing`**, so AELIS keeps AE2's monitor lockstep: the calc thread only runs while the thread in `simulateFor` waits. [C] The slot is acquired **before** the original `run()` registers with `TickHandler`, so the server thread never waits on the semaphore. [C]
- `AelisExactCraftingService.EXECUTOR`: a fixed daemon pool that runs `CraftingCalculation.run()`. The calculation is constructed on the caller thread, which builds the NetworkCraftingSimulationState there. [C]
- `ManualCraftingInventoryLock` / `InventoryReservationLedger`: monitor on the static `LEDGERS` plus synchronized ledger methods. These guard the server thread (menu/extract) against the server thread used at calculation construction, and are defensive against calc threads. [C]
- `WeakSlotHintCache`: synchronized; currently single-threaded (MAIN). [C]
- `TerminalAwareFuture`: synchronized future wrapper around menu plan futures. [C]

**Q7. Static and shared mutable state.** See the non-mixin list above. The storage-relevant ones are:
1. `ManualCraftingInventoryLock.LEDGERS`: global, keyed by NetworkStorage, dormant in the pack.
2. `InfiniteStorageCellRegistry.CELL_ITEMS`: global, keyed by cell.
3. The `HandlerStrategy.ITEMS` slot-hint cache: an instance field on a JVM-wide singleton, so effectively global.
4. ThreadLocals `PHYSICAL_EXTRACT` and `AVAILABILITY_QUERIES`: per-thread, scoped in try/finally.
5. `MolecularBalancedBatchScope`: a ThreadLocal stack in the CPU tick.

The per-instance fields on NetworkStorage, ItemHandlerFacade and StorageImportStrategy are not static, but they live on long-lived AE2 objects.

**Q8. Thread today.**
- Bus, storage, CPU and network ops run on the MAIN grid tick (the NestWorld decision).
- Planning runs on the AE crafting-calculator or AELIS exact executor, in lockstep.
- Packets run on MAIN (`consumerMainThread`).
- Menu `broadcastChanges` runs on whatever thread runs the player's container tick. Under NestWorld player-region execution this may not be MAIN [H]. It matters only if `enable_automatic_planner=true`, because then reservation acquisition calls NetworkStorage SIMULATE extracts from that thread. That is the same class of issue as native AE2 terminal menus reading storage.

**Q9. Effect on the transit split (StorageImportStrategy / StorageExportStrategy / HandlerStrategy / ExternalStorageFacade / StackImportFacade).**

The mod does **not** move any network call into external code, and it does **not** add new network-then-external interleaving inside one call. AE2's own import interleave is unchanged in the 15.4.11 bytecode:
1. `getStackInSlot` (external)
2. network `insert SIMULATE`
3. external `extract MODULATE`
4. network `insert MODULATE`
5. external `insert` of any overflow

AE2's export interleave is:
1. network `extract SIMULATE`
2. external `insert SIMULATE`
3. network `extract MODULATE`
4. external `insert MODULATE`
5. network re-insert of any overflow

The mod only changes the internals of the external steps and adds two short-circuits. The specific couplings a split must respect:

1. **skipZeroAmountExtraction couples external extract to the network-side amount [C].** The amount passed to `ExternalStorageFacade.extract` in `StorageImportStrategy.transfer` is AE2's `amountForThisResource` (the result of the network SIMULATE). If it is ≤0, the mod skips the external extract entirely. If the REGION phase passes 0 or −1 as an "unknown, let MAIN decide" placeholder at that call site, **nothing will ever be imported**. The REGION phase must pass a positive transit budget (for example `min(remainingTransferAmount, transitFree)`). This matches native semantics: native would call extract(0) and get 0.
2. **Op-budget short-circuit [C].** `StackImportFacade` stops after the first strategy once `hasOperationsLeft()` is false. AE2 decrements operations by **network-inserted** amount. If the REGION phase decrements by extracted amount instead, item imports will consume the budget and starve the fluid strategy differently from today. The ops accounting must stay on one side and be applied before `StackImportFacade` checks the next strategy.
3. **Pairing between `getStackInSlot` and `extractExternal` on the same facade [C].** The observed-slot hint is written in `getStackInSlot` RETURN and consumed (then cleared) in the next `extractExternal` for the same key, using plain non-volatile fields. If the slot scan and the extract run on the same thread (both REGION, which is the natural split), behaviour is preserved. If they are split across threads (MAIN scans, REGION extracts), the code stays correct because the key is rechecked and a full-scan fallback follows, but it is a data race on plain fields and the fast path is lost.
4. **The import facade is cached across ticks per StorageImportStrategy [C].** It uses plain fields `cachedHandler` and `cachedFacade`. Each bus must always run its external phase on one thread; region reassignment between ticks needs a happens-before edge. The cached facade also carries the per-facade hint state from item 3.
5. **The export slot-hint cache is server-global [C].** It is the instance field of the `HandlerStrategy.ITEMS` singleton, so every export bus in the world shares one `WeakSlotHintCache`, used by both the SIMULATE and MODULATE external inserts.
   - Thread safety: all methods are `synchronized`, so REGION-parallel use is safe, but it becomes a **global contention monitor** across all region threads.
   - Lazy init: the init in `getSlotHints()` is unsynchronised. A race can create two caches, one of which is lost. That only loses hints; the object's `final` map field is safely published.
   - No correctness impact: hints only reorder slot attempts, and `IItemHandler.insertItem` does the real work.
   - Note that SIMULATE also records hints, so a SIMULATE on REGION at tick T followed by a MODULATE on REGION at T+k uses whatever the hints say then. That is harmless.
6. **Storage-bus slot index (EXTERNAL_MOUNT) [C].** The index is built during the facade's own `getAvailableStacks` and invalidated only by that facade's own MODULATE operations. Changes to the same chest made through another path (an import bus on REGION, or hoppers) leave it stale. Staleness is handled by the key recheck before every extract plus the fallback, so it is safe while everything stays on one thread. It is unsynchronised and assumes the storage-bus facade is MAIN-only, which WP3-A already guarantees. The fundamental "MAIN storage bus and REGION import bus on the same foreign inventory" race is **not introduced by this mod**, and the index does not make it worse.
7. **The reservation clamp (dormant in pack) lives on the MAIN side [C].** With `enable_automatic_planner=true`, every `NetworkStorage.extract` (including an export bus's MODULATE extract) is clamped to `physicalAvailable − reservedForOthers`. `physicalAvailable` is computed with a **re-entrant SIMULATE extract on the same NetworkStorage**. The MAIN phase of the split must therefore do a real `NetworkStorage.extract`. It must not commit against a cached-inventory snapshot taken earlier, or reservations held by an open CraftConfirmMenu will be bypassed.
8. **Infinite cells (active in pack) [C].** A MODULATE extract from a marked cell does not deplete it and returns the full amount, once a SIMULATE of 1 succeeds. This is neutral for the split as long as extraction happens on MAIN through NetworkStorage.
9. **Bytecode-shape dependency, the highest practical risk [C].** The mixin config is `required=true` with `defaultRequire=1`. Mixins #2, #3, #4 and #5 inject at specific INVOKEs inside `StorageImportStrategy.transfer`, `StackImportFacade.transfer`, `HandlerStrategy$1.insert` and the `ItemHandlerFacade` methods. If NestWorld implements the split by `@Overwrite`, by rewriting those method bodies, or by redirecting the same INVOKEs:
   - with a removed call site, AE+ fails to apply and **the server crashes at mixin apply**;
   - with a redirect on the same INVOKE, the two mixins conflict;
   - if NestWorld instead substitutes its own strategy objects, AE+ silently stops affecting them (no crash, behaviour change).

   The split should keep the original AE2 methods intact and either wrap them from outside (call the external half and the network half as separate entry points) or use `@WrapOperation` on the same targets, which chains with MixinExtras.

## 3. Classification

| Feature | Class | Confidence |
|---|---|---|
| **Mod overall** | **MAIN_NATIVE** for the storage and crafting domain; **CUSTOM_SHARED_STATE** in the IO-bus routing layer; compatible with the transit split under conditions C1–C5 | CONFIRMED |
| NetworkStorage infinite-cell handling (pack ON) | MAIN_NATIVE | CONFIRMED |
| Manual crafting reservation clamp and ledger (pack OFF) | MAIN_NATIVE + CUSTOM_SHARED_STATE (static ledger) | CONFIRMED |
| Storage-bus slot index (pack ON) | EXTERNAL_MOUNT (MAIN-confined, unsynchronised) | CONFIRMED |
| Import facade cache, zero-skip, op-budget short-circuit (pack ON) | TRANSIT_SAFE under conditions (C1, C2, C4) | CONFIRMED |
| Observed-slot hint in the import loop (pack ON) | TRANSIT_SAFE if scan and extract stay on the same thread (C3) | CONFIRMED |
| Export slot-hint cache on the `HandlerStrategy.ITEMS` singleton (pack ON) | CUSTOM_SHARED_STATE (global, synchronized) — TRANSIT_SAFE, with a contention caveat | CONFIRMED |
| AELIS planner and crafting calculation | MAIN_NATIVE plus the calc thread in AE2 lockstep (`handlePausing`) | CONFIRMED |
| Pattern-input memo caches | CUSTOM_SHARED_STATE, thread-safe | CONFIRMED |
| CPU logic, cycle and batching | MAIN_NATIVE | CONFIRMED |
| CraftConfirmMenu reservation acquisition thread under NestWorld player-region execution | UNKNOWN | HYPOTHESIS |

Conditions for the transit split:
- **C1** — The REGION phase passes a positive amount to `ExternalStorageFacade.extract` in `StorageImportStrategy.transfer`. Never pass a 0 or −1 placeholder: the zero-skip will drop the extract.
- **C2** — Operations are decremented on one side, by the same rule as today, before `StackImportFacade` evaluates `hasOperationsLeft()`.
- **C3** — Each bus's external half (slot scan, extract/insert, overflow return) runs entirely on one thread per tick, with a happens-before edge if the bus migrates between regions.
- **C4** — The original AE2 method bodies and INVOKE call sites stay intact (no `@Overwrite` or redirect of the same INVOKEs). AE+ is `required=true` and `defaultRequire=1`.
- **C5** — The MAIN half commits through a real `NetworkStorage.extract`/`insert`, not a precomputed snapshot, so the reservation clamp and infinite-cell semantics still apply.

## 4. Key risks for the transit split (ranked)

1. **Hard crash or silent bypass if the split rewrites AE2 IO methods** (C4). Four required mixins inject at INVOKEs inside `transfer`/`insert`.
2. **Zero-amount short-circuit drops imports** if the REGION side does not know the network capacity and passes 0 (C1).
3. **Server-global synchronized slot-hint cache** on `HandlerStrategy.ITEMS`. It becomes one monitor contended by every export bus on every region thread; the lazy init race is benign. This is a performance risk, not a correctness risk.
4. **Plain-field per-facade and per-strategy hint state** requires single-thread confinement per bus (C3).
5. **Ops-budget semantics:** the short-circuit makes the import order between item and fluid strategies depend on ops accounting (C2).
6. **(Dormant) reservation clamp** requires real MAIN-side `NetworkStorage.extract`. It would become live if an operator enables `crafting.aelis.enable_automatic_planner` (C5). The menu-thread question is open [H].
7. Not mod-specific, but widened by a multi-tick transit: external inventory changes between the SIMULATE and MODULATE halves. Both AE2 and AE+ handle this via the overflow return and fallback paths. The overflow-return external insert must also run on REGION.
