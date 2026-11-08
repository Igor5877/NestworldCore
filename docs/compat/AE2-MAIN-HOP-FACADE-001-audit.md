# AE2-MAIN-HOP-FACADE-001 — audit + design (2026-11-09)

Status: **AUDIT / DESIGN — NO CODE. STOP FOR REVIEW** (end of document).
Scope (owner decisions 2026-11-09, `AE2-STORAGE-DOMAIN-001-WP3A2.md` §4): every region→AE2-network-state ingress that
tick pins cannot cover, classified by CALL CONTRACT and decided under the binding rules
(ASYNC → MAIN_CALLBACK; SYNC read with a proven read model → SNAPSHOT; SYNC mutation needing an immediate result →
never deferred → FAIL_CLOSED or explicit ownership design; unknown → FAIL_CLOSED; forbidden: "enqueue MAIN + return a
fake value"). Storage domain = MAIN, no new lock.

Inputs (read-only): Core source `src/main/java/net/nestworld/**` and the generated Forge tree
`projects/forge/src/main/java/net/minecraft/**` (cross-checked: no committed patch under `patches/` touches the lines
cited for `ServerPlayer.doTick`, `ServerGamePacketListenerImpl.tick` or `MinecraftServer.tickChildren`); AE2 15.4.10
source (the 15.4.11 diff does not touch any class cited here, see `wp3a2/extendedae.md` inputs); extracted addon jars
`/root/ae2-pack-audit-2026-11-07/x/*` (`javap -p -c -v`); `ae2wtlib-15.3.3-forge.jar` from the owner pack, extracted to
the session scratchpad; owner pack `/root/project-infinity` (`user_jvm_args.txt`, `world/data/nestworld-*.txt`,
`mods/`) read but not modified. No server was touched and no build was run.

Confidence: **[C]** CONFIRMED from bytecode or source, **[S]** STRONGLY INDICATED (one link inferred),
**[H]** HYPOTHESIS.

---

## 0. Headline findings

**F1. The premise "player tick runs on a region thread" is wrong for the three priority player entries. [C]**
In the owner pack all three run on MAIN today, for two reasons:
- The heavy half of the player tick runs on MAIN unconditionally. `ServerPlayer.doTick()` calls `super.tick()`, which
  fires the Forge `PlayerTickEvent` START/END (`Player.java:214`, `:285`) and runs `LivingEntity.aiStep` → `Player.aiStep`
  → `inventory.tick()` (`Player.java:499`) → `ItemStack.onInventoryTick` → `Item.inventoryTick` / armor tick
  (`Inventory.java:225-235`). It also runs `touch` → `ItemEntity.playerTouch` → `EntityItemPickupEvent` (`Player.java:527-560`).
  `doTick()` is invoked only from `ServerGamePacketListenerImpl.tick()` (`:271`). That method is called by
  `Connection.tick()` (`Connection.java:291-296`), which is called by `ServerConnectionListener.tick()` (`:140-149`),
  which is called by `MinecraftServer.tickChildren()` → `this.getConnection().tick()` (`MinecraftServer.java:1094`). That
  call sits on the server thread, after the per-level loop.
- The Phase-11 #31.3 player-tick experiment (`NestworldDimensionRegion.java:1049-1067`) moves only
  `level.tickNonPassenger(player)`, i.e. `ServerPlayer.tick()` (`ServerPlayer.java:427-462`). That covers the light half:
  gameMode, `containerMenu.broadcastChanges()`, `stillValid`, camera and advancements. It does not move `doTick`. It is
  also double-gated: `-Dnestworld.playerTickRegionExecution` (default false, `NestworldTuning.java:1021`) plus a per-player
  opt-in set that is empty by default (`PlayerTickExperiment`).
- The owner pack sets none of the `nestworld.player*RegionExecution` flags (`user_jvm_args.txt`: only freeRunningRegions,
  autoFreeRunning, autoPin, shardedDimensions).

Therefore `JeiSyncManager.onPlayerTick` (PlayerTickEvent), the AdvancedAE quantum armor (`inventoryTick`) and the
crazyae2addons wireless notification terminal (`inventoryTick`) are **MAIN_NATIVE today**. The `WP3A2.md` §2
"player tick on a region thread — not mitigated by pins" bullet must be corrected. The real player→AE2 region ingress
exists **only when the opt-in experiments are switched on**. It then goes through `containerMenu.broadcastChanges()`
(AE2 terminals, wireless hosts, ae2wtlib, crafting-confirm menus) and through the `PLAYER_USE_ITEM*` /
`PLAYER_INTERACTION` dispatchers (item use and use-on-block: wireless terminal open, crazy PortableAutobuilder,
ae2wtlib restock on `RightClickBlock`). §3.A covers this.

**F2. Foreign-capability ingress is real, but its concurrency shape is narrower than "races MAIN". [C]**
- In the BE phase, MAIN ticks its own bucket first (`runBlockEntityPhase` → `tickBlockEntityList(pins, mainBes)`,
  `NestworldDimensionRegion.java:588`). It then dispatches region buckets and **waits** on the latch
  (`runWorkRound` → `awaitLatch`, `RegionThreadPool.java:381-400`), servicing only `pollTask()` chunk work meanwhile.
- So a region pipe that calls an AE2 capability does not run concurrently with AE2's MAIN grid tick, which runs at
  server END tick. It does run **concurrently with other regions' pipes calling into the same grid**, and it violates
  the ownership decision.
- The real data races are therefore region↔region on one `NetworkStorage` / cell / `KeyCounter`. They also include
  cross-region writes through storage-bus mounts: region A → network insert → storage bus → a foreign BE owned by region B.
- Two paths escape the barrier: (a) the `TICK_WAIT_LIMIT_NANOS` abandon path (`RegionThreadPool.java:394-398`: MAIN
  resumes while region threads still run), and (b) free-running entity rounds and scheduled-tick drains
  (`RegionThread.runFreeRunningTick`, `drainOneScheduledTickBatch`), which are not latch-awaited. Entity or
  scheduled-tick callers into AE2 capabilities race MAIN for real.

**F3. `IStorageService.getCachedInventory()` is not a read. [C]**
`StorageService.getCachedInventory()` (AE2 `StorageService.java:182-186`) runs `updateCachedStacks()` when
`cachedStacksNeedUpdate`. That flag is set at every server END tick while no watcher interest exists (`:87-95`). The
rebuild:
- clears and refills the single, in-place `KeyCounter` (`:97-104`), so there is no double buffer;
- enumerates every mount, including storage-bus foreign handlers in any region;
- posts `onStackChange` to watchers (level emitters, interfaces and others → foreign code) (`:107-125`).

Consequences:
- Any "cheap" region-side read of the cached inventory is a **mutating, foreign-code-invoking** call. This applies to
  TagInventoryME `getSlots`/`getStackInSlot`, AppliedFlux `getEnergyStored`, and `MEStorageMenu` under the experiment.
- No SNAPSHOT may alias the live `KeyCounter`. A snapshot must be a MAIN-built immutable copy.

**F4. Core has no synchronous per-call MAIN hop. [C]**
- GMEIL `ExecutionGateway` is "Phase 1 observation … deliberately does not enforce MAIN_COMPAT until MainExecutionBridge
  can preserve the full temporal contract" (`gmeil/ExecutionGateway.java:7-10`). `MainExecutionBridge` exists nowhere
  in the tree.
- A region thread that blocked on MAIN inside the BE phase would deadlock, because MAIN is in `awaitLatch`. The one
  exception is work that `awaitLatch` itself services, and today that is only `pollTask()`.
- The existing legitimate hops are all **asynchronous**: `BlockEntityExecutionDomain.enqueueForBlockEntityDomain`
  (MAIN queue per dimension, re-resolved, terminal-reported), `MinecraftServer.execute`/`tell`, and
  `HostDomainCallbackDelivery`.
- The player dispatchers (`PlayerUseItemDispatcher` etc.) go the other way, MAIN→region, with a bounded wait. Their
  "fallback = caller runs it on MAIN" is a **legitimate synchronous MAIN execution**: the real call, the real result.
  It is the only existing synchronous main path relevant here.

**F5. AE2-NEIGHBOR-AFFINITY-001 covers only some of the capability hosts, and only adjacent callers. [C]**
- `HostNeighborAffinityMixin` pins the 6 neighbours of `AENetworkBlockEntity` subclasses that are `InterfaceLogicHost`
  or `PatternProviderLogicHost`.
- `PartNeighborAffinityMixin` pins the faced block of every non-cable `AEBasePart`.
- Not covered:
  - `TagInventoryMEInterfaceBlockEntity`: extends `AEBaseBlockEntity`, not `AENetworkBlockEntity`, and is not an
    interface host.
  - `TileFluxAccessor`: an `AENetworkBlockEntity`, but neither an interface nor a provider host.
  - `ChestBlockEntity`: an `AENetworkPowerBlockEntity`, which is not an `AENetworkBlockEntity` subclass.
- Covered: `PartFluxAccessor` (an `AEBasePart`), AE2/ExtendedAE/megacells interface and provider blocks and parts.
- Coverage is only for callers whose ticking BE is the **adjacent** block. Pipe networks that call remote endpoints
  are a different class: Pipez (CONFIRMED, `PIPEZ-COMPAT-001.md` finding 2), and [H] XNet controllers, LaserIO nodes,
  SFM managers and Modular Routers sender modules, all present in the pack. Pinning the AE2 host's neighbours does not
  move those callers.

**F6. Owner-pack pins already put several D-entries on MAIN. [C pins file; S per path]**
`world/data/nestworld-pinned-mods.txt` lists advanced_ae, ae2, aeallpattern, appflux, botania, botanicalextramachinery,
crazyae2addons, expatternprovider, extendedae_plus, molecularmanipulator, neoecoae and packagedauto.
- Botania pinned means mana bursts, spreaders and Corporea sparks/index/funnel tick on MAIN. So Applied Botanics
  FluixPool (D20) and MECorporeaNode (D21) are entered from MAIN [S: FluixPool is registered under Botania's pool BE
  type (`BotaniaBlockEntitiesMixin`, `wp3a2/other-addons.md` §13), so the botania namespace pin applies].
- Pipe and logistics mods (pipez, mekanism, thermal, xnet, laserio, sfm, modularrouters, enderio, integrated*) are
  **not** pinned.

---

## 1. Execution-context facts used by every row

| # | Fact | Evidence | Conf |
|---|---|---|---|
| E1 | `PlayerTickEvent`, `Item.inventoryTick`, armor tick, Curios `curioTick` (LivingTickEvent), `EntityItemPickupEvent`, `LivingEntityUseItemEvent.Finish` all run inside `ServerPlayer.doTick()` on MAIN | `ServerGamePacketListenerImpl.java:271`; `MinecraftServer.java:1094`; `Player.java:214,285,499,527-560`; `Inventory.java:225-235` | C |
| E2 | Player-tick experiment moves only `ServerPlayer.tick()` (incl. `containerMenu.broadcastChanges()` at `ServerPlayer.java:435`) | `NestworldDimensionRegion.java:1049-1067`, `:1091-1092` | C |
| E3 | All player region flags default false; none set in owner pack | `NestworldTuning.java:987,1021,1032,1044,1118,1129,1143`; `/root/project-infinity/user_jvm_args.txt` | C |
| E4 | Item use / use-on-block / block interaction run on a region thread only under their own experiment flags; MAIN blocks on a bounded wait and falls back to running the call itself | `PlayerUseItemDispatcher.java` header + `dispatch`; `PlayerUseItemOnBlockDispatcher.java:130`; `PlayerInteractionDispatcher.java:101` | C |
| E5 | BE phase: MAIN bucket first, then region round awaited by MAIN (latch, `pollTask` only) | `NestworldDimensionRegion.java:552-603`; `RegionThreadPool.java:362-400` | C |
| E6 | Free-running entity rounds and scheduled-tick drains are not latch-awaited | `RegionThread.java:255-262,303-392`; `glue/RegionTasks.java` javadoc | C |
| E7 | Level/server tick events (`onPostLevelTick`, ServerTickEvent) fire on MAIN | `MinecraftServer.java:1052-1088` | C |
| E8 | Position pins (CORE-PINZONE-001) make whatever BE sits at a position tick on MAIN from the next BE phase | `BlockEntityExecutionDomain.pinPositionToMain` javadoc | C |
| E9 | `getCachedInventory()` may rebuild in place and post watcher callbacks | AE2 `StorageService.java:87-125,182-186` | C |
| E10 | No synchronous region→MAIN hop exists in Core | F4 | C |
| E11 | `InterfaceLogic.getCapability`: `STORAGE` → `getInventory()` = **whole `NetworkStorage` when unconfigured** (`hasConfig == false`); `GENERIC_INTERNAL_INV` → local `ConfigInventory`. Forge `ITEM_HANDLER`/`FLUID_HANDLER` on any AE2 BE are adapters over `GENERIC_INTERNAL_INV` only (local buffer, regardless of config) | AE2 `InterfaceLogic.java:219-260,598-606`; `InitCapabilities.java:54-70` | C |

Mods named as callers that tick from level/server events rather than BE tickers, and are therefore MAIN by E7:
Mekanism transmitter networks, Flux Networks, Thermal Dynamics grids, EnderIO conduits, Integrated Dynamics networks.
**[H]**: not verified in their bytecode for this audit; it affects only the "who reaches this from REGION" column,
not the decision.

---

## 2. Register — owner format

Columns: **source / class / method** → **thread/domain today** → **state touched** → **owner** (AE2-owned |
external-owned | mixed) → **foreign code?** → **same-tick?** → **CALL CONTRACT** → **decision** → **evidence** (conf).

### 2.A Player ingress (priority entries first)

| # | source / class / method | thread today | state touched | owner | foreign? | same-tick? | CONTRACT | decision | evidence |
|---|---|---|---|---|---|---|---|---|---|
| P1 | extendedae_plus `server.JeiSyncManager.onPlayerTick(TickEvent.PlayerTickEvent)` (`@SubscribeEvent`, registered in the `ExtendedAEPlus` ctor) | **MAIN** (E1) | `MEStorage.getAvailableStacks()` (all mounts incl. storage-bus foreign handlers), `ICraftingService.getCraftables(filter)`, static `HashMap playerStates`, S2C `SyncNetworkInventoryS2CPacket` via `PacketDistributor.PLAYER` | mixed | YES (storage-bus mounts; appliedenhancements listing wrap; molecularmanipulator KeyCounter mixin) | NO (every `SYNC_INTERVAL_TICKS`=20, result is a packet diff) | **ASYNC_SIDE_EFFECT** | **MAIN_NATIVE today (no facade)**. If `doTick` ever leaves MAIN: **MAIN_CALLBACK** of the whole per-player body (coalesced per player, revalidate online + same level + terminal still linked); ≤1 tick on a 20-tick period = PASS. The callback also keeps `playerStates` single-threaded. | javap: `onPlayerTick` RuntimeVisibleAnnotations `SubscribeEvent`; Methodrefs `MEStorage.getAvailableStacks`, `ICraftingService.getCraftables`, `SimpleChannel.send`; E1 (C) |
| P2 | AdvancedAE `QuantumHelmet/Chestplate/Leggings/Boots.m_6883_` (inventoryTick) → `IUpgradeableItem.tickUpgrades` → `UpgradeCards.autoFeed`, `autoStock`, `recharging` (+ `AppliedFluxPlugin.recharge*`), `magnet` | **MAIN** (E1) | autoFeed: `MEStorage.extract` ×2 + `insert`; autoStock: `getAvailableStacks` + `extract` + `insert` ×2; player inventory; `IEnergyService` (recharge); magnet: moves world `ItemEntity`/`ExperienceOrb` | mixed (network + player-owned inventory + world entities) | YES (mounts; recharge → foreign `IEnergyStorage` items via Curios) | NO at the vanilla boundary (`inventoryTick` is void). Inside `tickUpgrades` each upgrade returns a boolean that is consumed in the same call (internal SYNC_RESULT). | **ASYNC_SIDE_EFFECT** (unit = one `tickUpgrades` pass), SYNC_RESULT internally | **MAIN_NATIVE today**. Future (player tick off MAIN): MAIN_CALLBACK of the storage-touching upgrades as a unit (autoFeed/autoStock/recharging), never per call. Movement and magnet upgrades stay with the player's thread (magnet touches entities of the player's own region). | javap `UpgradeCards`: `autoFeed` @133/205 `MEStorage.extract`, @253 `insert`; `autoStock` @113 `getAvailableStacks`, @309 `extract`, @358/417 `insert` (C); E1 (C); A-6 recharge (S) |
| P3a | crazyae2addons `items.WirelessNotificationTerminal.m_6883_` → `sendHudUpdate` | **MAIN** (E1) | network storage read → HUD packet | AE2-owned | YES (mounts on read) | NO | **ASYNC_SIDE_EFFECT** | **MAIN_NATIVE today**; future MAIN_CALLBACK | `wp3a2/other-addons.md` C-9 (C); E1 |
| P3b | crazyae2addons `items.PortableAutobuilder.m_7203_` (use) / `m_6225_` (useOn) → `pasteNow` → `extractAvailable` (`MEStorage.extract` @320) + block placement lambdas (`lambda$pasteNow$1`) | **MAIN** (packet handler). REGION only under `PLAYER_USE_ITEM` / `PLAYER_USE_ITEM_ON_BLOCK` experiments (E4) | network extract MODULATE + world block writes at arbitrary positions | mixed (network + world) | YES (world, mounts) | **UNKNOWN**: the `InteractionResult` return was not traced to be independent of the extract | **UNKNOWN → treated as SYNC_MUTATING_RESULT** | **MAIN_NATIVE today**. Under the experiments: **explicit design = dispatcher veto**. The item-use dispatcher returns `Optional.empty()` for AE2-ecosystem items, so MAIN runs the real call synchronously (the existing fallback path). Never FAIL_CLOSED (that would cancel a player action, a gameplay change). | javap `PortableAutobuilder`: `m_7203_`@169 and `m_6225_`@77 → `pasteNow`; `extractAvailable`@224-320 (C) |
| P4 | **ae2wtlib** 15.3.3 `wct.magnet_card.MagnetHandler.handle` (from `CraftingTerminalItemMixin.m_6883_` / `curioTick`) → `sendRestockAble` (network read → packet), `handleMagnet` (entity moves) | **MAIN** (E1) | network read; world item entities | mixed | YES | NO | **ASYNC_SIDE_EFFECT** | MAIN_NATIVE today; future MAIN_CALLBACK (read part) | javap `CraftingTerminalItemMixin.m_6883_`@39 → `MagnetHandler.handle` (C) |
| P5 | ae2wtlib `AE2wtlibForge.handle(EntityItemPickupEvent)` → `AE2wtlibEvents.insertStackInME` (`MEStorage.insert`) → `event.setCanceled(result)` | **MAIN** (E1, `Player.touch`) | network insert | AE2-owned | YES | **YES** (boolean result decides whether the vanilla pickup is cancelled) | **SYNC_MUTATING_RESULT** | MAIN_NATIVE today. Under any future player-tick move: **FAIL_CLOSED is wrong** (the item would go to the player inventory instead of ME: a gameplay change) → **explicit design: keep `aiStep` touch on MAIN** (it already is, E1). | javap `AE2wtlibForge.handle(EntityItemPickupEvent)` @20 `insertStackInME` @23 `setCanceled`; `insertStackInME`@145 `MEStorage.insert` (C) |
| P6 | ae2wtlib restock: `handle(LivingEntityUseItemEvent$Finish)` (MAIN, doTick) and `handle(PlayerInteractEvent$RightClickBlock)` → `AE2wtlibEvents.restock` (`extract`@124, `insert`@169) | Finish: **MAIN**. RightClickBlock: MAIN; **REGION under `PLAYER_USE_ITEM_ON_BLOCK`** (event fires inside `ServerPlayerGameMode.useItemOn`) | network extract/insert + held stack (consumer sets it) | mixed | YES | YES (the restocked stack is written back into the hand in the same call) | **SYNC_MUTATING_RESULT** | MAIN_NATIVE today; under the experiment: **dispatcher veto** (as P3b) | javap `AE2wtlibForge` handle(RightClickBlock)@43, handle(Finish)@44 → `restock` (C); event location (S) |
| P7 | AE2 `MEStorageMenu.broadcastChanges` (all ME terminals incl. wireless / ae2wtlib `WTMenuHost`, ExtendedAE/extendedae_plus terminals): `toolboxMenu.tick()`, `updateActiveCraftingJobs`, `getCraftablesFromGrid`, `storage.getAvailableStacks()`, `updatePowerStatus`; `ItemMenuHost` range/power tick | **MAIN** today. **REGION** for opted-in players under `PLAYER_TICK_REGION_EXECUTION` (E2) | full network enumeration, crafting service, energy service, item power | mixed | YES | NO (output = incremental S2C packets; the next broadcast reconciles from `previousAvailableStacks`) | **ASYNC_SIDE_EFFECT** | MAIN_NATIVE today. Under the experiment: **MAIN_CALLBACK** = coalesced "broadcast this menu on MAIN" (revalidate `player.containerMenu == menu && containerId`), ≤1 tick UI latency = TIMING_TOLERANCE. Simpler equivalent alternative: Core keeps a player whose open menu is an AE2-ecosystem menu on MAIN (whole-player fallback, the existing "fall through to main" pattern at `NestworldDimensionRegion.java:1065-1066`). | AE2 `MEStorageMenu.java:244-300` (C); E2 (C) |
| P8 | appliedenhancements `CraftConfirmMenuMixin` in `broadcastChanges` (acquire inventory reservation when a plan completes) | MAIN today; REGION only under the experiment | `ManualCraftingInventoryLock` ledgers (static synchronized) + network snapshot | AE2-owned + addon static | NO | NO (void broadcast) | **ASYNC_SIDE_EFFECT** | dormant in pack (planner off). Under the experiment, same as P7 (MAIN_CALLBACK / keep player on MAIN) | `wp3a2/appliedenhancements.md` #10 (C), config `ENABLE_AUTOMATIC_AELIS_PLANNER=false` (C) |
| P9 | AE2 `WirelessTerminalItem.use` / ae2wtlib `IUniversalWirelessTerminalItem.open/tryOpen` → menu construction (reads linked grid, WAP range) | MAIN; REGION under `PLAYER_USE_ITEM` | grid lookup, energy | AE2-owned | YES | YES (InteractionResult; menu opened in-call) | **SYNC_MUTATING_RESULT** | **dispatcher veto** (as P3b) | javap `IUniversalWirelessTerminalItem` (C for presence); call chain (S) |

### 2.B Foreign capability ingress (priority entries first)

| # | source / class / method | thread today | state touched | owner | foreign? | same-tick? | CONTRACT | decision | evidence |
|---|---|---|---|---|---|---|---|---|---|
| C1 | extendedae_plus `TagInventoryMEInterfaceBlockEntity$TagFilteredItemHandler.getSlots` / `getStackInSlot` / `getSlotLimit` / `isItemValid` → `collectMatchingItems()` → `IStorageService.getCachedInventory()` iterate + tag filter | callers' thread: **REGION** for BE-ticker pipes and machines (Pipez, XNet, LaserIO, SFM, Modular Routers, hoppers, machine auto-output); MAIN for level-event networks (E7) and pinned callers | cached inventory (may **rebuild + post watchers**, F3) | AE2-owned (+ watchers → foreign) | YES (F3) | YES (pipe uses the value in the same call chain) | **SYNC_RESULT** (but F3 makes it mutating underneath) | off-MAIN: **FAIL_CLOSED** (`getSlots`=0, `getStackInSlot`=EMPTY). SNAPSHOT **rejected** (§4.2). Plus neighbour-affinity extension (§3). Remote callers need the §5 ownership design. | `tagh.txt`: `getSlots`→`collectMatchingItems`; `collectMatchingItems` → `getCachedInventory`, `KeyCounter.iterator`, `IPartitionList.isListed` (C) |
| C2 | same handler `.extractItem(slot, amount, simulate)` → `getNetworkItem(slot)` (re-runs `collectMatchingItems`) → `IGridNode.isActive` → `getStorageService().getInventory().extract(key, n, SIMULATE|MODULATE, IActionSource.ofMachine(be))` | as C1 | NetworkStorage extract (mounts, cells, appliedenhancements reservation/infinite wraps, storage buses → foreign BEs anywhere) | mixed | YES | YES | **SYNC_MUTATING_RESULT** | off-MAIN: **FAIL_CLOSED** (return `ItemStack.EMPTY`, no side effect, nothing enqueued) + affinity + §5 | `tagh.txt` `extractItem` @10 `getNetworkItem`, @41 `isActive`, @70-112 `getGrid→getStorageService→getInventory→MEStorage.extract` (C) |
| C3 | same handler `.insertItem` | any | none (returns the input stack) | — | NO | — | SYNC_RESULT (pure) | **no gate** (AE2 state not touched) | `tagh.txt` `insertItem`: `aload_2; areturn` (C) |
| C4 | AppliedFlux `caps.NetworkFEPower.receiveEnergy(int, boolean)` → `MEStorage.insert(FluxKey.of(FE), n, ofSimulate(sim), src)`; `.extractEnergy` → `MEStorage.extract(...)` (via `TileFluxAccessor.getCapability` / `PartFluxAccessor.getCapability` → `CapAdaptor.find(cap)` factory) | callers' thread: REGION for BE-ticker FE pushers/pullers (Pipez energy pipes = CONFIRMED remote-endpoint caller; XNet; LaserIO; generators auto-outputting to adjacent); MAIN for Mekanism / Flux Networks / Thermal / EnderIO [H, E7] | NetworkStorage FE key: FE cells, **FE storage buses → foreign `IEnergyStorage` in any region** (`FEExternalStorageStrategy`), `CraftingServiceStorage` (AppliedFlux skip mixin returns 0 for FluxKey) | mixed | YES | YES (accepted/extracted amount returned) | **SYNC_MUTATING_RESULT** | off-MAIN: **FAIL_CLOSED** (`0`). Affinity extension for `TileFluxAccessor` (the part is already covered). Then either an explicit ownership design (§5.3 bounded FE transit buffer) or the §5 caller affinity for remote callers. | `NetworkFEPower.txt`: receive @25 `MEStorage.insert`, extract @25 `MEStorage.extract` (C); `TileFluxAccessor.getCapability` @1 `CapAdaptor.find` (C) |
| C5 | `NetworkFEPower.getEnergyStored()` → `getCachedInventory().get(FluxKey FE)`; `.getMaxEnergyStored()` → `MEStorage.insert(..., SIMULATE)` + `getEnergyStored()` | as C4 | cached inventory (F3: may rebuild); SIMULATE insert walks every FE mount incl. foreign storage-bus handlers | mixed | YES | YES (display, comparator, some cables size their offer by it) | **SYNC_RESULT** | off-MAIN: **SNAPSHOT permitted after proof §4.1** (MAIN-published per-grid FE amount + capacity, published at the AE2 storage END tick). Until it exists: FAIL_CLOSED (`0`). | `NetworkFEPower.txt` getEnergyStored @4 `getCachedInventory`, @15 `KeyCounter.get`; getMaxEnergyStored @25 `insert SIMULATE` (C) |
| C6 | AppliedFlux `xmod.fluxnetwork.FluxNetworkPower.receiveEnergyL/extractEnergyL` (insert/extract @24), `getEnergyStoredL` (`getCachedInventory`), `getMaxEnergyStoredL` (SIMULATE insert) | Flux Networks tick (ServerTickEvent → MAIN) [H] | as C4/C5 | mixed | YES | YES | receive/extract **SYNC_MUTATING_RESULT**; getters **SYNC_RESULT** | same as C4/C5. Expected traffic: MAIN only. The off-MAIN counter is a detector (expected 0). | `FluxNetworkPower.txt` (C); Flux Networks thread (H) |
| C7 | AppliedFlux `xmod.gtceu.GTEUPower.acceptEnergyFromNetwork/changeEnergy/getEnergyStored/getEnergyCapacity` | **dormant**: GTCEu not in the owner pack (`mods/` listing) | — | — | — | — | SYNC_MUTATING_RESULT / SYNC_RESULT | same rule as C4/C5 if GTCEu is ever added; registry row present, gated on mod presence | `GTEUPower.txt` (C); mods listing (C) |
| C8 | AE2 `InterfaceLogic.getCapability(Capabilities.STORAGE)` → `getInventory()` = **`NetworkStorage` of the whole grid when unconfigured** (InterfaceBlockEntity/InterfacePart; ExtendedAE Ex/Oversize interfaces; megacells MEGA interface inherit) | callers: AE2 storage buses of **other** grids (MAIN, grid ops); AdvancedAE `AdvPatternProviderTargetCache` (MAIN); Applied Botanics `MECorporeaNode` (MAIN via botania pin, F6) | whole NetworkStorage (insert/extract/getAvailableStacks) | AE2-owned (+ mounts) | YES | YES | **SYNC_MUTATING_RESULT** (insert/extract), **SYNC_RESULT** (getAvailableStacks) | **MAIN_NATIVE** (every in-pack caller is on MAIN). Guard: off-MAIN → **FAIL_CLOSED** (insert→0, extract→0, listing→empty) + counter = detector (expected 0). No in-pack non-MAIN `Capabilities.STORAGE` caller found [C for the 19 extracted addons; H for the rest of the pack]. | E11; `Capabilities.STORAGE` users in AE2 = StorageBusPart, Condenser, Chest, InterfaceLogic, PatternProviderTarget(Cache) (C) |
| C9 | AE2 interface / pattern provider **`ITEM_HANDLER` / `FLUID_HANDLER`** (adapters over `GENERIC_INTERNAL_INV` / return inventory) | REGION pipes (adjacent: covered by affinity; remote: not) | **host-local** `ConfigInventory` / return inventory → `onStorageChanged` → `updatePlan` → `alertDevice` (guarded M3) | AE2-owned **host** state, not network storage | NO | YES | SYNC_MUTATING_RESULT on host-local state | **outside this facade** (not network storage; AE2-PIN-LEAK class). Adjacent callers already covered by AE2-NEIGHBOR-AFFINITY-001; remote callers → §5 caller affinity, same design. | E11; `InterfaceLogic.java:110-111,342-352`; `PatternProviderLogic.java:763-765` (C) |
| C10 | AE2 **ME Chest** `ChestBlockEntity.getCapability`: `ITEM_HANDLER` (input slot; `InputInventoryFilter.allowInsert` → `cellHandler.insert(SIMULATE)`; `onChangeInventory` → `tryToStoreContents` → `StorageHelper.poweredInsert` into the chest's cell, which is **mounted network storage**), `FLUID_HANDLER` (`FluidHandler.fill` → poweredInsert), `Capabilities.STORAGE` (cell monitor) | REGION pipes (not affinity-covered: `AENetworkPowerBlockEntity`) | chest cell (= a network mount) + energy (chest buffer / grid energy service) | AE2-owned | YES (cell implementations; energy) | YES | `allowInsert` **SYNC_RESULT**; `insertItem` / `fill` **SYNC_MUTATING_RESULT** | off-MAIN: **FAIL_CLOSED** (allowInsert=false → insertItem returns the stack; fill → 0) + affinity extension to the chest's neighbours. Option recorded (owner decision): "input-buffer deferral". The item enters the 1-slot input; `tryToStoreContents` is skipped off-MAIN; AE2's own `serverTick` retry (`ChestBlockEntity.java:314-316`, MAIN, ae2 pinned) stores it ≤1 tick later. This is AE2's native retry path, but `allowInsert`'s SIMULATE gate cannot be answered off-MAIN without a snapshot, so a stack may sit in the input slot when vanilla would have refused it (item location differs; conservation holds). | AE2 `ChestBlockEntity.java:438-488,689-709,740-750`, `FluidHandler` (C) |
| C11 | AE2 energy acceptor / controller / energy cell FE capability → `ForgeEnergyAdapter` → `funnelPowerIntoStorage` → `EnergyService` | REGION / MAIN | energy domain | AE2-owned | NO | YES | SYNC_MUTATING_RESULT | **already designed** (AE2-COMPAT-001 kernel energy domain lock, M2 refusal; `EnergyAcceptor*Mixin`). Not storage domain; listed for completeness. | addon `EnergyAcceptorBlockEntityMixin`/`EnergyAcceptorPartMixin` (C) |
| C12 | Applied Botanics `FluixPoolBlockEntity` (`getCurrentMana`, `receiveMana`, `isFull`, comparator, `SafeMana`) — D20 | **MAIN** via botania pin (bursts, spreaders, sparks are botania types) | network mana key SIM/MOD | AE2-owned | NO | YES | SYNC_RESULT / SYNC_MUTATING_RESULT | **MAIN_NATIVE via pins**. Guard: off-MAIN → FAIL_CLOSED (`receiveMana` no-op is **not** contract-valid: Botania bursts void the mana → see §6, would be a gameplay change) → for this entry the guard must be a **detector only** (count + log, run live) until a non-Botania mana caller is found. | F6 (S); `wp3a2/other-addons.md` B-2 (C) |
| C13 | Applied Botanics `MECorporeaNode.work` (`countItems`/`extractItems` via `Capabilities.STORAGE`) — D21 | **MAIN** via botania pin | as C8 | AE2-owned | NO | YES | SYNC_MUTATING_RESULT | MAIN_NATIVE via pins; covered by the C8 guard | F6 (S); B-4 (C) |
| C14 | molecularmanipulator `MatterFabricationPortBlockEntity` / `MolecularCenterBlockEntity` / `MolecularAutoCrafterBlockEntity` caps; extendedae_plus `SuperAssemblerMatrixBlockEntity` `ITEM_HANDLER`; crazyae2addons `AutoBuilderBE` / `AutoEnchanterBE` `ITEM_HANDLER` | REGION pipes | **local** buffers (`ItemStackHandler`, tanks, pattern handler) | host-local | NO | YES | SYNC_MUTATING_RESULT on host-local state | outside this facade (as C9) | javap getCapability bodies → `itemCapability`/`fluidCapability`/`externalPatternCapability`/lambda over local inventory (S: handler bodies scanned for storage-service references, none found) |

### 2.C Call-contract classification of the WP3-A.2 register (D1–D29) and the ExtendedAE findings

| D | entry (short) | thread today (owner pack) | CONTRACT | decision |
|---|---|---|---|---|
| D1 | AppliedFlux FE import/export strategies | MAIN (bus) | SYNC_MUTATING_RESULT (SIM→MOD→put-back) | MAIN-whole (WP3-B binding §3.1) |
| D2 | AppMek chemical strategies | MAIN | SYNC_MUTATING_RESULT | MAIN-whole |
| D3 | AppBot mana strategies (void on refusal) | MAIN | SYNC_MUTATING_RESULT | MAIN-whole |
| D4 | AdvancedAE IO / stock / advanced IO buses | MAIN | SYNC_MUTATING_RESULT | MAIN-whole |
| D5/D6 | crazy NBT / mob export bus | MAIN | SYNC_MUTATING_RESULT | MAIN-whole |
| D7 | appliede EMC buses | MAIN | SYNC_MUTATING_RESULT | MAIN-whole |
| D8 | appliede `EMCStorage` (player knowledge) | MAIN (players tick MAIN, E1) | SYNC_MUTATING_RESULT | MAIN. Player-owner hop needed only if `doTick` leaves MAIN. |
| D9–D11 | `insertIntoCpus` overwrites (crazy / AdvancedAE / neoecoae) | MAIN | SYNC_MUTATING_RESULT | MAIN |
| D12 | neoecoae ECOStorageSystem BE tick | MAIN (neoecoae pinned) | SYNC_VOID | MAIN via pin |
| D13 | neoecoae planning snapshot capture | MAIN | SYNC_RESULT (Future) | MAIN capture |
| D14 | neoecoae WAL / IO workers | own threads | internal (self-locked) | unchanged |
| D15 | PackagedAuto BE tick → poweredInsert | MAIN (pinned) | ASYNC_SIDE_EFFECT | MAIN via pin (MAIN_CALLBACK if ever unpinned) |
| D16 | PackagedAuto `pushPattern` | MAIN (CPU) | SYNC_RESULT | MAIN |
| D17 | molecularmanipulator BE tickers | MAIN (pinned) | UNKNOWN | MAIN via pin (fail-closed by pin) |
| D18 | molecularmanipulator global `KeyCounter` / `getActiveMachines` mixins | any | SYNC_RESULT (global) | invariant: MAIN-only callers |
| D19 | aeallpattern PatternLinker | MAIN (pinned) | ASYNC_SIDE_EFFECT | MAIN via pin |
| D20 | AppBot FluixPool | MAIN (botania pin) | SYNC_RESULT / SYNC_MUTATING_RESULT | = C12 |
| D21 | AppBot Corporea | MAIN (botania pin) | SYNC_MUTATING_RESULT | = C13 |
| D22 | AppliedFlux Flux Accessor caps | **REGION callers** | SYNC_MUTATING_RESULT / SYNC_RESULT | = C4–C7 |
| D23 | AppliedFlux EnergyDistributeService | MAIN | SYNC_MUTATING_RESULT | MAIN |
| D24 | AdvancedAE quantum armor | **MAIN** (E1, corrects WP3-A.2) | ASYNC_SIDE_EFFECT | = P2 |
| D25 | crazy notification terminal / autobuilder | **MAIN** (E1/E4, corrects WP3-A.2) | ASYNC_SIDE_EFFECT / UNKNOWN→SYNC_MUTATING_RESULT | = P3a / P3b |
| D26 | mae2 static P2P flags | MAIN | SYNC_RESULT | invariant |
| D27 | AppMek QIO adapter | MAIN | SYNC_MUTATING_RESULT | MAIN |
| D28 | crazy MobFormationPlane | MAIN | SYNC_MUTATING_RESULT | MAIN |
| D29 | AppliedFlux CraftingServiceStorage skip | MAIN | SYNC_RESULT | MAIN |
| EAE+ | JeiSyncManager | **MAIN** (E1) | ASYNC_SIDE_EFFECT | = P1 |
| EAE+ | TagInventoryME item handler | **REGION callers** | SYNC_RESULT / SYNC_MUTATING_RESULT | = C1–C3 |
| EAE+ | Wireless transceiver, MirrorPatternProvider | MAIN (extendedae_plus pinned) | SYNC_VOID (topology) | MAIN via pin |
| EAE+ | EntitySpeedTickerPart | MAIN + affinity | SYNC_VOID | AE2-NEIGHBOR-AFFINITY-001 |

Net result: under the owner pack's current flags, the only **live** region ingress into MAIN-owned network storage
is the capability group **C1, C2, C4, C5 and C10**, entered from region-ticked foreign BEs. C9/C14 are the same
mechanism into host-local state. All player entries are MAIN today.

---

## 3. AE2-NEIGHBOR-AFFINITY-001 coverage and extension

| host | class / hierarchy | affinity today | extension |
|---|---|---|---|
| AE2 / EAE / megacells interface **block**, pattern provider **block** | `AENetworkBlockEntity` + `InterfaceLogicHost` / `PatternProviderLogicHost` | 6 neighbours (`HostNeighborAffinityMixin.onReady`) | — |
| all interface / provider / storage-bus / IO **parts**, `PartFluxAccessor` | `AEBasePart` (non-cable) | faced block (`PartNeighborAffinityMixin.addToWorld`) | — |
| `TileFluxAccessor` | `AENetworkBlockEntity` + `IEnergyDistributor` | **none** | add to the host predicate: the existing `onReady` / `setRemoved` / `onChunkUnloaded` hooks already fit |
| `ChestBlockEntity` (ME Chest) | `AENetworkPowerBlockEntity` (separate hierarchy; has `onReady`, `setRemoved`, `onChunkUnloaded`) | **none** | new mixin on `AENetworkPowerBlockEntity` with the same three hooks, predicate `instanceof ChestBlockEntity`; pin the 5 non-front sides (STORAGE/FLUID are refused on the front) or simply all 6 |
| `TagInventoryMEInterfaceBlockEntity` | `AEBaseBlockEntity` + `IInWorldGridNodeHost` (own `onLoad` → `GridHelper.onFirstTick` lambda, `m_7651_` setRemoved, `onChunkUnloaded`) | **none** | `@Pseudo` mixin on this class (exact version 1.6.3, registry-gated): pin 6 neighbours at `onLoad` TAIL (or in the first-tick lambda), release at `setRemoved` / `onChunkUnloaded` HEAD |

Better shape (recommended): replace the class predicate with a **registry row** "host exposes a network-storage-
bridging capability" (WP1 `Ae2CompatibilityRegistry`, exact classes, no wildcard). That keeps one source of truth for
the gate's hook list and the affinity list.

What extension buys: every caller whose ticking BE is **adjacent** to the host (hoppers, machine auto-output, the
extracting Pipez pipe next to a TagInventoryME, an FE cable segment that pushes to its neighbour, Modular Routers
without range modules) then ticks on MAIN, and its capability call becomes MAIN_NATIVE. This is a real result with
no latency.

Cost: the AE2-NEIGHBOR-AFFINITY-001 trade-off. The pinned neighbour now does its own work with **its** other
neighbours from MAIN. That is safe in the BE phase (MAIN bucket ticks before the region round, E5), but it adds MAIN
time. Measure it like the 2026-11-09 pins (+1.0 ms idle for 5 mods).

What it cannot buy: **remote callers.** Pipez inserting/extracting at the far end of a pipe network
(PIPEZ-COMPAT-001 finding 2, C), XNet controllers, LaserIO nodes, SFM managers and Modular Routers sender modules
[H]. Their ticking BE is not next to the AE2 host. → §5.

---

## 4. Snapshot / read-model proofs

### 4.1 `getEnergyStored` / `getMaxEnergyStored` (C5, C6) — SNAPSHOT permitted (S)

Read model: on MAIN, at the end of `StorageService.onServerEndTick` (inside the existing GRID-PHASE `P(G)` phase), for
grids that have at least one registered off-MAIN FE reader, publish into a per-grid volatile record
`{tick, feAmount = cachedInventory.get(FluxKey FE), feFreeCapacity = insert(FE, Long.MAX, SIMULATE)}`. Readers on a
region thread return `clampLong(feAmount)` / `clampLong(feAmount + feFreeCapacity)`.

Proof sketch:
1. **The value is AE2's own cache class.** In vanilla, `getEnergyStored` already returns
   `getCachedInventory().get(FE)`. With watchers present, that cache was rebuilt at the previous `onServerEndTick`
   (E9), the same instant as the publication. **The value is identical.** With no watchers, AE2 rebuilds lazily at the
   first read in the tick. The published value can then differ only by mutations made between the previous END tick and
   this read, i.e. within one tick (≤1-tick staleness, TIMING_TOLERANCE).
2. **No mutation depends on it inside AE2.** The getter does not feed any AE2 decision. Callers that size a transfer by
   it still go through `receiveEnergy`/`extractEnergy`, which is the authority (C4). A stale getter can only make a
   caller offer more or less, and the authority clamps the result.
3. **It removes F3.** The region reader never triggers `updateCachedStacks`, so there is no watcher post and no
   foreign code off MAIN.
4. **Capacity** is a SIMULATE that walks mounts, i.e. foreign code. It is computed only on MAIN and only for grids
   with registered demand, so it adds no foreign calls off MAIN. Its value is the same "previous END tick" class as
   point 1.
5. **Demand registration:** the first off-MAIN read of a grid with no snapshot returns FAIL_CLOSED `0`, counted, and
   registers demand. The snapshot then exists from the next END tick. It is a one-tick cold start per grid, which is
   non-accumulating and display-only.

Residual (owner review): a comparator reading the accessor sees ≤1-tick-old energy. That falls under TIMING_TOLERANCE
item 7 provided redstone timing tests (WP5) confirm no practical difference.

### 4.2 TagInventoryME `getSlots` / `getStackInSlot` (C1) — SNAPSHOT rejected (C)

Reasons:
1. The slot ↔ key mapping is the index into a list rebuilt on **every** call (`getNetworkItem(slot)` →
   `collectMatchingItems()`, `tagh.txt`). A pipe's transaction is `getSlots` → `getStackInSlot(i)` →
   `extractItem(i, n, sim)` → `extractItem(i, n, false)`. If the reads come from a snapshot and the extract runs live
   (on MAIN or later), the live list order can differ and slot `i` can name another key. The result is a different
   item, so semantics are not equivalent.
2. Serving `extractItem` against the snapshot does not help, because it still must mutate live storage. It is not a
   read model, and "extract later + return the snapshot amount now" is exactly the forbidden fake-value pattern.
3. A snapshot `getStackInSlot` followed by a FAIL_CLOSED extract is a contract-valid lie that pipes poll forever. It
   is worse than an empty inventory: it adds wasted work and can mislead filters.

Decision: C1 and C2 are gated together. Off-MAIN both are FAIL_CLOSED (empty inventory), and the fix for remote
callers is ownership (§5), not a read model.

### 4.3 `MEStorageMenu` (P7) — no snapshot needed

Its contract is ASYNC (packets only), so MAIN_CALLBACK applies and no read model is required.

---

## 5. Ownership designs for SYNC_MUTATING_RESULT capability ingress (C2, C4, C10; also C9/C14)

The binding rule forbids deferral. FAIL_CLOSED alone changes gameplay for remote callers (§6). Four candidates,
listed in recommended order:

**5.1 Static neighbour affinity (§3), required in every option.** It covers adjacent callers with zero latency and
no semantic change.

**5.2 Dynamic caller affinity ("the caller moves to MAIN"), recommended for remote callers. Needs Core support.**
- On a refused off-MAIN call, the facade asks Core to pin the **calling BE's position** to MAIN with a lease. The same
  CORE-PINZONE-001 position pin is used, reference-counted, released after N ticks without AE2 ingress from that
  caller, or when the caller BE is removed.
- From the next BE phase the caller ticks on MAIN, and its calls are MAIN_NATIVE with real results.
- Missing piece: Core does not expose "which BE is ticking on this thread". Today the only per-tick context is the
  GMEIL observation key (type and class, no position, only with `GMEIL_OBSERVE`; `NestworldDimensionRegion.java:516-527`),
  and there is no `ThreadLocal` current-ticker in `net.nestworld.region`. It needs a tiny Core API, e.g.
  `BlockEntityExecutionDomain.currentTicker(): (level, pos, typeId) | null`, set in `tickBlockEntityList` around
  `ticker.tick()`. This fits the already-approved **CORE-AUTOPIN-DIAGNOSTICS-001** (attribution).
- Gameplay: the refused call happens **once per caller per lease**. The caller loses one operation; most pipes retry
  next tick, which is a one-time non-accumulating delay of 1 tick. Per GAMEPLAY_EQUIVALENCE item 7 this is "bounded,
  does not accumulate". Owner must confirm that "one lost operation per lease start" is acceptable. If not, combine
  with a **warm-up**: pre-pin known remote callers.
- Caveat (as for every pin): the caller now does all of its work from MAIN. For a Pipez network that means MAIN also
  writes the network's other endpoints, which are in regions. This is safe in the BE phase (E5, MAIN bucket first),
  but it moves MAIN load. Count pinned callers, and keep a cap plus a "lease refused, cap reached" counter, which then
  falls back to FAIL_CLOSED.
- Entity / scheduled-tick callers (E6) are not BEs. They cannot be moved this way and stay FAIL_CLOSED (detector
  first: expect ~0 in pack).

**5.3 Bounded transit buffer per host (FE only; D22 "TRANSIT with MAIN-published budget").**
- `receiveEnergy` off-MAIN accepts into an accessor-local FE buffer, up to `min(bufferCap, publishedFreeCapacity −
  alreadyBuffered)`. The budget comes from the §4.1 snapshot. MAIN drains the buffer into the network at the
  accessor's grid tick.
- `extractEnergy` off-MAIN serves from a pre-extracted buffer that MAIN refilled to last tick's demand.
- The returned amounts are real, because the buffer genuinely holds the energy. Conservation holds: a non-drainable
  remainder stays in the buffer and is never voided.
- Semantic deltas: energy is briefly in neither the network nor the cable (1 tick). Pre-extracted energy is invisible
  to the network for 1 tick. Throughput is equal unless the network sits at the capacity edge.
- This is WP3-B-class transit (same accounting invariant, same WP5 proof obligations). It should be designed together
  with WP3-B, not inside this facade.
- Not applicable to C2 (items with slot semantics) or C10 (the chest's own input slot already is such a buffer, see
  the C10 option).

**5.4 MAIN-serviced synchronous hop. Rejected for now.**
- Core would add a "main ingress queue" that `awaitLatch` services next to `pollTask()`. The region thread posts the
  call and blocks on a bounded future, and MAIN runs the real call while the round is open. The result is real, so the
  pattern is not forbidden.
- Rejected because:
  - It is per-call: pipes call `getSlots` / `getStackInSlot` / `extractItem` many times per tick, so each costs a
    cross-thread round trip.
  - It is a new Core primitive (the absent `MainExecutionBridge`).
  - A timeout must fail closed.
  - It serialises all regions through MAIN mid-round.
- Recorded as the fallback if 5.2 is refused.

---

## 6. FAIL_CLOSED decisions — player-visible effect and gameplay verdict

| site | fail-closed result | player-visible effect | gameplay change? | alternative |
|---|---|---|---|---|
| C1/C2 TagInventoryME (remote caller) | `getSlots`=0, `extractItem`=EMPTY | the XNet/LaserIO/SFM/remote-Pipez extractor sees an empty inventory, moves nothing | **YES** (throughput → 0 for that setup) | §3 affinity (adjacent), §5.2 caller affinity (remote) |
| C4 Flux Accessor receive/extract (remote / unpinned caller) | 0 | FE cable "accessor full / empty": energy does not enter or leave the ME network | **YES** | §3 (TileFluxAccessor), §5.2, or §5.3 transit |
| C5 getters before the snapshot exists | 0 | display / comparator 0 for the first tick per grid | no (one-time, display-only, TIMING_TOLERANCE) | §4.1 snapshot |
| C10 ME Chest (remote caller) | insert refused / fill 0 | pipe keeps its item / fluid ("chest full") | **YES** | §3 (chest affinity), §5.2; option "input-buffer deferral" (owner) |
| C8 interface STORAGE (no in-pack off-MAIN caller) | insert 0 / extract 0 / empty listing | none expected (detector) | no while the counter is 0; YES if a caller appears → becomes a finding, not a silent refusal | §5.2 |
| C12 FluixPool | (would be: no mana accepted) | Botania burst **voids** mana on refusal | **YES (loss)** → **must not enforce**: detector only | Botania pin (current) |
| P3b/P6/P9 player actions under experiment | — (FAIL_CLOSED not used) | — | — | dispatcher veto (MAIN runs the real call) |

Conclusion: FAIL_CLOSED is acceptable as **the default safety state of the gate** (the forbidden pattern is never
used and nothing is enqueued), but **not as the production end state** for C1/C2/C4/C10. Production requires §3 +
§5.2 (or §5.3 for FE) so that no in-pack setup sees a refusal after the warm-up. That is why the gate starts in
**OBSERVE** (§7).

---

## 7. Proposed facade contract (design only)

### 7.1 Types (addon `glue`, no Core compile dependency)

```java
enum IngressContract { ASYNC_SIDE_EFFECT, SYNC_VOID, SYNC_RESULT, SYNC_MUTATING_RESULT }
enum IngressDecision { MAIN_NATIVE, MAIN_CALLBACK, SNAPSHOT, FAIL_CLOSED, OWNERSHIP }   // OWNERSHIP = affinity / lease / transit
record IngressSite(String id, String modId, String modVersion, String targetClass, String method,
                   IngressContract contract, IngressDecision offMainDecision, String failClosedResult) {}
// exact rows only, registered in Ae2CompatibilityRegistry (WP1), version-pinned like the gate tables; no wildcard
```

```java
final class Ae2IngressGate {
  enum Mode { OFF, OBSERVE, ENFORCE }      // -Dnestworld.compatAe2.ingressGate=observe (default observe)
  static boolean storageOwner(ServerLevel l) { return l.getServer().isSameThread(); } // storage domain = MAIN

  // SYNC_RESULT / SYNC_MUTATING_RESULT. Never enqueues. OBSERVE: counts and runs live (today's behaviour);
  // ENFORCE: counts, requests caller affinity (if available), returns the site's contract-valid refusal.
  static <T> T sync(IngressSite s, ServerLevel l, Supplier<T> live, Supplier<T> refusal);

  // SNAPSHOT sites only (C5/C6 after review): off-MAIN read served from the MAIN-published per-grid record.
  static long snapshot(IngressSite s, Grid g, SnapshotField f);

  // ASYNC_SIDE_EFFECT only (P1/P2/P3a/P4/P7/P8 if the player tick ever leaves MAIN): inline on MAIN; else coalesced
  // MAIN callback (key = site + owner identity), revalidated (player online, same level, same containerId / host not removed),
  // accounted emitted = inline + deferred; deferred = ranOnMain + stale + dropped (+ in flight).
  static void async(IngressSite s, Object owner, BooleanSupplier stillValid, Runnable body);
}
```

Invariants (fixtures, as for the existing gates):
- **I1 no fake value.** A refused SYNC call posts no task. The `enqueued` counter is unchanged across a refusal
  (fixture asserts this).
- **I2 no double execution.** An ASYNC body runs exactly once (inline XOR deferred). A deferred body is dropped only
  through `stale` / `dropped` counters, never silently.
- **I3 equivalence on MAIN.** On MAIN every wrapper is a pass-through: the same call, the same result, no counter
  except `inMain`.
- **I4 refusal values are contract-valid "nothing moved"**, as in AE2-COMPAT-DESIGN-001.1 M2:
  `IItemHandler.insertItem` → input stack; `extractItem` → EMPTY; `getSlots` → 0; `getStackInSlot` → EMPTY;
  `IEnergyStorage.receive/extract` → 0; getters → 0 (until a snapshot exists); `MEStorage.insert/extract` → 0;
  `getAvailableStacks` → empty; `IFluidHandler.fill` → 0; ME Chest `allowInsert` → false.

### 7.2 Where to hook (exact, `required` per registry presence; `@Pseudo` for addons; WrapMethod, never Overwrite)

| site | hook |
|---|---|
| C1/C2 | `@Pseudo @Mixin(targets="com.extendedae_plus.content.ae2.TagInventoryMEInterfaceBlockEntity$TagFilteredItemHandler")` `@WrapMethod` `getSlots`, `getStackInSlot`, `getSlotLimit`, `isItemValid`, `extractItem` (`insertItem` untouched, C3) |
| C4/C5 | `@Pseudo` `com.glodblock.github.appflux.common.caps.NetworkFEPower` `@WrapMethod` `receiveEnergy`, `extractEnergy`, `getEnergyStored`, `getMaxEnergyStored` |
| C6 | `@Pseudo` `...xmod.fluxnetwork.FluxNetworkPower` `receiveEnergyL`, `extractEnergyL`, `getEnergyStoredL`, `getMaxEnergyStoredL` |
| C7 | `@Pseudo` `...xmod.gtceu.GTEUPower` (only when gtceu loaded; mixin plugin gate) |
| C8 | `appeng.helpers.InterfaceLogic.getInventory` is too broad (MAIN storage buses call it). Hook the **returned `NetworkStorage`** only when reached through `getCapability(STORAGE)`: `@WrapMethod InterfaceLogic.getCapability` returning, for `STORAGE` with `!hasConfig`, a thin `MEStorage` delegate whose `insert`/`extract`/`getAvailableStacks` run `Ae2IngressGate.sync`. The local-config path is unchanged. |
| C10 | `appeng.blockentity.storage.ChestBlockEntity$InputInventoryFilter.allowInsert`, `ChestBlockEntity.onChangeInventory` (only the `inputInventory` branch), `ChestBlockEntity$FluidHandler.fill`, and the `STORAGE` branch of `getCapability` (same delegate as C8) |
| C12/C13 | detector only: `@WrapMethod` FluixPool `receiveMana` / `getCurrentMana` and `MECorporeaNode.work`, counting off-MAIN entries, live call (never enforce, §6) |
| P7/P8 (experiment only) | prefer a **Core veto** (keep a player with an AE2-ecosystem menu on MAIN). Addon fallback: `@WrapMethod AbstractContainerMenu.broadcastChanges` filtered by registry menu classes → `Ae2IngressGate.async` |
| P3b/P6/P9 (experiment only) | **Core veto hook** in `PlayerUseItemDispatcher` / `PlayerUseItemOnBlockDispatcher` (`Optional.empty()` for registry items/blocks). An addon cannot do this without a Core API. |
| affinity | §3 rows (TileFluxAccessor predicate, `AENetworkPowerBlockEntity`/Chest mixin, TagInventoryME `@Pseudo` mixin) |

### 7.3 Fail-closed default

- Mode default **OBSERVE**: the wrapper counts, records the caller (when Core attribution exists) and executes live.
  Behaviour is identical to today, so OBSERVE can ship as a pure diagnostic (shadow-mode rule).
- **ENFORCE** per site, owner-gated, only after (a) affinity is extended, (b) caller-lease (§5.2) or transit (§5.3) is
  available for that site, and (c) a pack run shows `refusedOffMain` → 0 after warm-up.
- A site with an unknown or unregistered version runs **FAIL_CLOSED in ENFORCE** and **live + loud in OBSERVE** (as
  WP1: an exact allowlist).

### 7.4 Telemetry (`/nwcompatae2 ingress`, per site, LongAdder)

- `inMain`, `offMain` (by thread kind: REGION_BE / REGION_ENTITY / REGION_SCHEDULED / OTHER), `refused` (ENFORCE),
  `observedLive` (OBSERVE).
- `snapshotServed`, `snapshotMissing`, `snapshotMaxAgeTicks`.
- `asyncEmitted` = `asyncInline` + `asyncDeferred`; `asyncDeferred` = `asyncRanOnMain` + `asyncStale` +
  `asyncDropped` (+ in flight).
- `leaseRequested`, `leaseGranted`, `leaseRefusedCap`, `leaseLive`, `leaseReleased`.
- Affinity (existing `NeighborAffinity.summary()` plus per-host-class split).
- Top-N callers: (caller BE type, pos) when Core attribution exists; otherwise the caller class from a sampled
  `StackWalker` (1 in 1024, OBSERVE only).
- Accounting invariants are checked at quiescence (as `BlockEntityExecutionDomain.pending()`).

---

## 8. Summary table

| # | entry | today | contract | decision | gameplay | conf |
|---|---|---|---|---|---|---|
| P1 | JeiSyncManager | **MAIN** | ASYNC_SIDE_EFFECT | MAIN_NATIVE (future MAIN_CALLBACK) | none | C |
| P2 | AdvancedAE quantum armor | **MAIN** | ASYNC_SIDE_EFFECT (unit) | MAIN_NATIVE (future MAIN_CALLBACK of unit) | none | C |
| P3a | crazy notification terminal | **MAIN** | ASYNC_SIDE_EFFECT | MAIN_NATIVE (future MAIN_CALLBACK) | none | C |
| P3b | crazy PortableAutobuilder | MAIN (REGION only under experiment) | UNKNOWN→SYNC_MUTATING_RESULT | dispatcher veto (Core) | none | C / S |
| P4 | ae2wtlib magnet/restock-able | MAIN | ASYNC_SIDE_EFFECT | MAIN_NATIVE | none | C |
| P5 | ae2wtlib pickup → ME | MAIN | SYNC_MUTATING_RESULT | MAIN_NATIVE; never FAIL_CLOSED | — | C |
| P6 | ae2wtlib restock | MAIN (RightClickBlock REGION under experiment) | SYNC_MUTATING_RESULT | dispatcher veto | none | C / S |
| P7 | AE2 terminal menus broadcast | MAIN (REGION under experiment) | ASYNC_SIDE_EFFECT | MAIN_CALLBACK or keep player on MAIN | ≤1 tick UI | C |
| P8 | appliedenhancements confirm-menu reservation | MAIN, dormant | ASYNC_SIDE_EFFECT | as P7 | none | C |
| P9 | wireless terminal open | MAIN (REGION under experiment) | SYNC_MUTATING_RESULT | dispatcher veto | none | S |
| C1 | TagInventoryME reads | **REGION callers** | SYNC_RESULT (mutating underneath, F3) | FAIL_CLOSED + affinity + caller lease; SNAPSHOT rejected | FAIL_CLOSED alone = change | C |
| C2 | TagInventoryME extract | **REGION callers** | SYNC_MUTATING_RESULT | FAIL_CLOSED + affinity + caller lease | FAIL_CLOSED alone = change | C |
| C3 | TagInventoryME insert | any | SYNC_RESULT (pure) | none | none | C |
| C4 | Flux Accessor receive/extract | **REGION callers** | SYNC_MUTATING_RESULT | FAIL_CLOSED + affinity (+TileFluxAccessor) + lease or FE transit | FAIL_CLOSED alone = change | C |
| C5 | Flux Accessor getters | REGION callers | SYNC_RESULT | SNAPSHOT (§4.1) | ≤1 tick display | S |
| C6 | Flux Networks adapter | MAIN [H] | SYNC_MUTATING / SYNC_RESULT | as C4/C5, detector | none expected | C / H |
| C7 | GT adapter | dormant | — | registry row only | — | C |
| C8 | interface STORAGE (whole grid) | MAIN callers | SYNC_MUTATING_RESULT | MAIN_NATIVE + fail-closed detector | none expected | C / H |
| C9 | interface / provider item & fluid caps | REGION callers | SYNC_MUTATING_RESULT (host-local) | outside facade; affinity covers adjacent; lease for remote | — | C |
| C10 | ME Chest caps | **REGION callers** | SYNC_RESULT / SYNC_MUTATING_RESULT | FAIL_CLOSED + chest affinity + lease (option: input-buffer deferral) | FAIL_CLOSED alone = change | C |
| C11 | AE2 FE acceptors | any | SYNC_MUTATING_RESULT | existing energy-domain kernel | — | C |
| C12 | FluixPool | MAIN (botania pin) | SYNC_* | MAIN_NATIVE; detector only (never enforce: void) | — | S |
| C13 | Corporea node | MAIN (botania pin) | SYNC_MUTATING_RESULT | MAIN_NATIVE; C8 guard | — | S |
| C14 | addon machines' local caps | REGION callers | host-local | outside facade | — | S |

---

## 9. Open questions (owner)

1. **Correct WP3-A.2 §2.** Downgrade "player tick on a region thread" to "only under the opt-in player experiments
   (ServerPlayer.tick / use dispatchers); `doTick` is MAIN". Should the facade then cover only the experiment paths,
   via Core vetoes, and leave P1/P2/P3a/P4 as MAIN_NATIVE rows with no hook?
2. **Core attribution API** for §5.2 (`currentTicker()` around `ticker.tick()`): fold it into
   CORE-AUTOPIN-DIAGNOSTICS-001, or a separate Core gate?
3. **Caller lease semantics.** Is "one refused operation per lease start" acceptable under GAMEPLAY_EQUIVALENCE
   item 7, or must known remote callers be pre-pinned (warm-up from OBSERVE telemetry)? What cap on leased callers?
4. **ME Chest:** pure FAIL_CLOSED, or the "input-buffer deferral" option (AE2's own `serverTick` retry; the item may
   sit in the input slot when vanilla would refuse it)?
5. **FE:** §5.2 lease only, or also design §5.3 FE transit now (it overlaps WP3-B accounting and WP5 proofs)?
6. **C5 snapshot:** accept §4.1 (≤1-tick getter staleness) or keep the getters FAIL_CLOSED until lease or transit
   exists, which would leave them at 0 in ENFORCE?
7. **Caller threads of the event-ticked networks** (Mekanism, Flux Networks, Thermal, EnderIO, Integrated Dynamics)
   are [H]. Verify by bytecode or OBSERVE telemetry before ENFORCE on C4/C6?
8. **Remote-caller mods** (XNet, LaserIO, SFM, Modular Routers): confirm they appear in OBSERVE as REGION_BE
   callers. Also: should their BE types simply be pinned (a cheaper, coarser alternative to leases)?
9. **E6 escape** (entity / free-running scheduled-tick callers, `TICK_WAIT_LIMIT` abandon): accept as
   detector-only, or treat any non-zero count as a release blocker?
10. **C9/C14 host-local caps** (remote pipe → pinned AE2 / addon host buffer): open a separate AE2-PIN-LEAK
    follow-up, or include them in this gate's lease mechanism?

---

**STOP FOR REVIEW.** No code, no commit, no server or gradle action taken. Implementation of AE2-MAIN-HOP-FACADE-001
stays NOT APPROVED (owner decisions 2026-11-09).
