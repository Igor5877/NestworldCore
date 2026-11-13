# AE2-GRID-DOMAIN-001 — an AE2 grid runs on ONE region thread (Core: CORE-GRID-DOMAIN-001)

Status: ТЗ DRAFT (2026-11-11, owner: "займайся роботою для AE2 МАШТАБНОЮ", "потім робимо для нашого ядра всю
підсистему, но щоб для гравця нічого не змінилося"). No runtime change yet. Supersedes, for eligible grids only, the
standing rule "AE2 network storage = MAIN-owned" (AE2-COMPAT-ARCHITECTURE §6 WP3-A.1); ineligible grids keep today's
verified MAIN path unchanged (fail-closed).

## 1. Why (evidence)

| source | AE2 share of MAIN |
|---|---|
| live spark `sOvHeJVwJX` (2026-11-11, pre-deploy, long window with player) | `Grid.onLevelEndTick` 32.8% + `Grid.onServerEndTick` 8.8% ≈ **42%** |
| rig JFR `comparison-20261111-213523` | AE2 grid/tick **62.9%** (IOBus 30.3%, Interface 9.8%, updateCachedStacks 7.5%) |

Live with one player: ~68–77 ms/tick → 13–14.7 TPS. TPS 20 needs MAIN < 50 ms. Cheaper-AE2 work (AE2-COST-001) yields
single-digit %; only moving the grid work off MAIN gives headroom.

Budget sketch (live, one player, ~70 ms MAIN, overworld region #0 ~16 ms): moving ~30 ms of grid work of eligible
overworld grids to region #0 → MAIN ≈ 40 ms (20 TPS + margin), region #0 ≈ 45 ms (still < 50). Further headroom needs
safe auto-split (CORE-SPLIT-DENSE-BE-GUARD-001) so big bases get more than one region thread.

## 1a. G0 census — LIVE 2026-11-11 22:06–22:07 (51 s, one player, all claims force-loaded)

`/nwcompatae2 gridcensus`: 77 grids, **41.27 ms/tick** total.
- **Grid #1: 4126 nodes, 34.18 ms/tick (83%)** (levelEnd 26.17, serverEnd 8.01); levels overworld 3658, jamd:mining 405,
  the_nether 42, the_end 21; **54 QNB, 2 P2P** ⇒ one grid in four dimensions.
- Grid #2: 263 nodes (jamd:mining), 5.07 ms. Remaining 75 grids ≈ 2 ms.
- Devices (ms/tick): ExtendedAE PartExExportBus 8.35, PartExImportBus 7.91, PartOversizeInterface 5.66, AdvancedAE
  ImportExportBusPart 2.45, AE2 ExportBus 2.36, AE2 ImportBus 1.66, AE2 StorageBus 1.17.

**Consequence: the single-region model of §2 cannot host grid #1** (four dimensions = four threads). Options:
- **D (dedicated domain)**: Core gets an additional single-threaded execution domain ("AE2 domain", not MAIN, not a
  region); grid #1 + all its node hosts in every dimension + every BE it reaches run there. MAIN −≈34 ms. Largest Core
  change: second main-like thread, cross-dimension group, player/entity/world ingress hops, chunk lifecycle in four
  dimensions.
- **R (region model)**: as §2, only for single-dimension grids (≈7 ms total today).
- **T (transit extension)**: WP3-B transit for ExtendedAE/AdvancedAE bus classes (≈18 ms device time, but only the
  neighbour half leaves MAIN).

**Owner decision 2026-11-11 22:10: option D — dedicated AE2 domain thread** ("Окремий AE2-потік"). R and T are not
the main line. Next: D0 audit/design of the Core facilities (§3′), then phased rig work.

## 2′. Many grids (owner question 2026-11-12 "а якщо в світі декілька мереж?")

Unit of placement = **grid cluster**: one grid plus every grid it is coupled to (storage bus onto another grid's
interface/storage, P2P/QNB to another grid, subnet interfaces, addon cross-grid services). Clusters are independent by
construction (AE2 shares nothing between unlinked grids; GRID_ISOLATION).

- Core provides a small pool of **AE2 domain workers** (dedicated threads, default 2, configurable; never more than
  the CPU planner allows). Each cluster is assigned to exactly ONE worker; a worker runs its clusters one after another
  each tick, so clusters on different workers run in parallel with each other, with MAIN and with the regions.
- Placement: by measured cost (gridcensus ms/tick), largest first onto the least-loaded worker (bin packing). Live
  today: grid #1 32 ms → worker A; grid #2 5 ms + small grids → worker B.
- Small single-region grids may instead stay with their region thread (option R) — fewer hops, same rule "one grid,
  one thread".
- A cluster moves between workers (or back to MAIN) only at a tick boundary, atomically (all members at once), and only
  on: grid merge/split (two clusters become one ⇒ both go to the bigger one's worker), sustained imbalance (hysteresis,
  e.g. > 30 s), or failure ⇒ MAIN fallback.
- Ingress (players, Fluix Pool, Packaged* crafters, crafting pool) is routed to the worker that owns the target grid;
  a call that needs two clusters at once (rare cross-grid case) ⇒ both clusters must share a worker (they are one
  cluster by the definition above).
- Per-worker and per-cluster telemetry: ms/tick, backlog, hop latency p50/p95/p99, starvation ticks.

## 2″. One grid on several threads (owner 2026-11-12 "якщо є можливість робити одну мережу многопотоною то роби")

AE2's network storage (NetworkStorage → MEInventoryHandler → cells / storage-bus facades, KeyCounter caches) and the
crafting service are single-writer by design; making every device mutate them concurrently would need a storage
transaction layer inside AE2 and every addon (ExtendedAE, AdvancedAE, appliedenhancements …) — not equivalence-safe.
What CAN run in parallel inside one grid, with byte-identical results:

| layer | parallel? | how |
|---|---|---|
| network commit (insert/extract/craft/energy) | **no** — one owner thread per grid (the worker) | serial, AE2 order kept |
| external half of bus/interface ops (reading/writing the neighbour inventory) | **yes** | WP3-B transit, extended from AE2 buses to ExtendedAE `PartExImportBus/ExportBus`, AdvancedAE `ImportExportBusPart` (≈18 ms device time live): neighbour side runs on the neighbour's own domain, network side commits on the grid worker |
| read-only scans owned by the grid (storage-bus inventory caches `CompositeStorage$InventoryCache.update`, `StorageService.updateCachedStacks` per-provider listing, crafting-provider lookups) | **yes** | fork-join on helper threads WHILE the grid worker waits (nobody else can mutate members then), results merged in the original provider order ⇒ identical output |
| device ticks of independent grids | yes | different clusters on different workers (§2′) |

Order of work: (1) worker + single-owner grid (§2/§3); (2) transit for ExtendedAE/AdvancedAE buses; (3) fork-join
read-only scans, each scan class audited (pure read, no lazy init side effects) with an equivalence fixture
(parallel result == serial result on recorded inventories).

## 2. Model

A grid G is **domain-eligible** iff, re-evaluated every topology change and every N ticks:

1. every node host of G resolves (geometry + type policy, `BlockEntityGroups.geometricDomain`) to the same REGION R;
2. no node of G is in an unloaded chunk; G spans one dimension;
3. G has no link to another grid or dimension: no storage bus onto another grid's interface, no P2P/QNB/spatial,
   no wireless access point (player wireless terminals are ingress, §4), no addon cross-grid service
   (neoecoae/advanced_ae clusters only if fully inside G);
4. every block entity that G's hosts reach (neighbour capabilities: bus targets, interface/pattern-provider neighbours,
   storage-bus targets) also resolves to R and is not type/mod-pinned to MAIN;
5. every participating mod class is on the exact-version allowlist (WP1 registry), else ineligible.

Eligible G: the whole grid — node host BE ticks, `Grid.onLevelEndTick/onServerEndTick` services (TickManager, storage,
crafting, energy, P2P), bus/interface work — executes on R's thread. Ineligible G: unchanged (MAIN, pins, transit).

The decision is a **group**: G's hosts + reached neighbours are registered as one `BlockEntityGroups` group whose domain
is R (Core change §3), so they never split.

## 3. Core (CORE-GRID-DOMAIN-001, generic — no AE2 types)

1. **Group domain override**: a registered group may carry a *target domain* (a region); members resolve to it even if
   their type/mod is pinned to MAIN (`ae2` is mod-pinned today). Any member position-pinned by someone else, or outside
   R's interior, ⇒ group resolves to MAIN (fail-closed, current behaviour).
2. **Domain end-of-tick hook**: per-region "after BE phase" callback list so a domain owner (the AE2 addon) runs the
   grid services once per region tick on R's thread (today they run from Forge level/server end-tick on MAIN).
3. **Atomic domain switch**: a group moves MAIN ↔ R only at a tick boundary of both threads (the BE-domain handoff
   protocol already used by WP3-B), never while a member executes.
4. `blockEntityPolicy` fast path (live spark: 2.1% MAIN) — per-chunk "has pins/groups" bit before the hash lookup.

## 3′. Worker models (D0 design, 2026-11-12)

Facts from the Core: vanilla/Forge code distinguishes only `isSameThread()` (MAIN) and Core code `instanceof
RegionThread` (owner of a chunk rectangle). A third thread is neither: `getChunk` from it blocks on MAIN, `setBlock`
from it bypasses the Stage-3 foreign-write guard, Phase-9 ownership assertions do not know it. The AE2 addon already has
the per-grid fair lock **P(G)** (`GridMixin.nw$p`, GridPhaseGate) that every grid phase and topology change takes, with
a non-MAIN `tryLock` path.

**Model A — full ownership.** The worker owns the grid AND every node host BE and every reached neighbour BE (MAIN /
regions skip them); world writes from the worker are deferred to the chunk owner; chunk access via the region fast path
(worker identity = a RegionThread that owns no chunks). Gain ≈ all grid time (≈32 ms live). Cost: Core thread identity
work, deferred world writes (setBlock from AE2 parts/planes) change intra-tick timing, BE save/autosave must not run
while the worker mutates, botania spreaders etc. that call AE2 synchronously must hop. Large.

**Model B — pipelined overlap under P(G) (recommended first).** Nothing moves; only WHEN the grid ticks:
- tick N: MAIN finishes dimension X's BE phase → instead of running `Grid.onLevelEndTick` inline, the addon hands
  grid work (under P(G)) to the worker and MAIN continues with its own non-BE work (other dimensions' chunks /
  entities / players / network / chunk tasks);
- MAIN waits for the worker only at the next point that could touch the same state: before the next BE phase of a
  dimension containing the grid's nodes or reached neighbours, before autosave/BE serialisation, and before topology
  changes (already under P(G));
- any other thread that touches the grid meanwhile (player menus, Fluix Pool, Packaged* `chargeMEEnergy`, foreign
  capability calls) takes P(G) → serialised with the worker, synchronous result, no staleness;
- bus/interface neighbour access during the worker's grid tick: neighbours are BEs and BE phases are excluded by the
  wait above, so the neighbour cannot tick concurrently (same guarantee as today's MAIN-only execution); region
  free-running neighbours keep using transit / NeighborAffinity exactly as today.
Gain ≈ the overlap = min(grid time, MAIN's non-BE time). Live spark: AE2 ≈42% of MAIN, BE phase ≈21%, the rest ≈37%
⇒ expected ≈ 18–22 ms MAIN off on live (≈60 → ≈40 ms, 20 TPS with a small margin). Risks to close in G1 audit:
AE2 code paths that touch ENTITIES or the CHUNK SYSTEM during the grid tick (annihilation/formation planes, spatial IO,
item drops, chunk loads by P2P/QNB) — such grids stay inline (eligibility) until audited; AE2/addon globals touched from
MAIN's non-BE work (e.g. `TickHandler` queues, crafting pool callbacks) — must take P(G) or be inline.

Plan: G1 = Model B for clusters without planes/spatial (eligibility), Core gets two small generic hooks
("before BE phase of level L" and "before world save") where a domain owner can await its job; everything else lives in
the addon. Model A remains the later step for the remaining grid time.

### 3′.1 G1 audit findings (2026-11-12)

1. **Thread identity (blocking).** Core-patched `Level.getBlockEntity` / `nestworldGetBlockEntityRaw`,
   `ServerChunkCache.getChunk/getChunkNow` accept only MAIN or `RegionThread`; any other thread gets `null` BEs, `null`
   chunks, or a blocking hop to MAIN (deadlock while MAIN awaits the worker). A `RegionThread` identity is WRONG for the
   worker: foreign-region reads return detached ghost copies (writes vanish ⇒ item loss). ⇒ Core needs a new identity
   **"acting-main lease"**: a thread holding a lease for a set of levels passes the main-thread checks of exactly those
   levels (BE lookup, `getChunkNow`, loaded `getChunk`); unloaded `getChunk` from a lease holder ⇒ null/fail-closed,
   never a blocking hop; `server.execute()` from a lease holder still queues (never inline). Vanilla-class patch
   (genPatches/installer; ASM overlay for the rig).
2. **Exclusion scope.** Grid #1's storage spans four dimensions (storage-bus targets in mining/nether/end are
   NeighborAffinity-pinned MAIN BEs). Any network insert from any device may reach any of them ⇒ while the worker runs
   grid #1, MAIN must not run the BE phase or chunk tasks of ANY level the grid touches (all four), not only the level
   whose tick is running. Overlap remains with MAIN's non-BE, non-chunk work (entities, players, network, misc).
   Revised live estimate for model B: **≈10–15 ms** MAIN (not 18–22). Player menus run in that window ⇒ P(G) on AE2
   menus is required before live (G5), not optional.
3. TickManager alert/wake/sleep and EnergyService are already kernel-domain guarded (T1/T2, ENERGY-001) — safe ingress.
   NetworkStorage insert/extract are not ⇒ P(G) ingress wrapper (written, `NetworkStorageGridMixin`).
4. Correction: the "Packaged* energy race" reported 2026-11-12 00:45 was wrong — `extractAEPower` is ENERGY-001
   guarded; no pin needed.

5. G1 run 1–2 (2026-11-12): the heavy grid #1 was INELIGIBLE (GrowthAccelerator on the deny list) ⇒ only 131 small grids
   ran on the worker, no MAIN gain. Decision: deny only entity/large-world writers (planes, spatial); Growth Accelerator
   world ticks hop to MAIN via `server.execute` (tickingRequest returns SAME unconditionally ⇒ schedule unchanged);
   Charger/Inscriber inventory-only; EntropyCradle cap-state writes accepted (same region-thread exposure as MAIN today).
   Run 2 correctness: 4.58 M phases on the worker, 0 failures, kernel NORMAL, ingressContended 0.

### 3′.2 Split Core / addon via the ExecutionDomain primitive (owner 2026-11-12)

Core mechanics (worker threads, acting-main lease, exclusion awaits before BE phase / chunk tasks / save / stop) move
behind `net.nestworld.modapi.domain` (docs/contracts/ExecutionDomainPrimitive.md). The AE2 addon keeps only AE2 logic:
eligibility, P(G) ingress, touched-level set per grid, topology awaitAll. The addon's own Level/ServerChunkCache await
mixins written earlier (gridworker/mixin) are superseded and will be removed.

## 3″. Owner decision 2026-11-12 16:2x — model B closed, model A APPROVED

G1/B = correctness PASS (run 3: 4.69 M phases, 0 failures, kernel NORMAL, ingressContended 0), performance ceiling
confirmed (MAIN CPU 75 → 41 ms, MAIN wait ≈ 43 ms/tick ⇒ wall unchanged). Model A approved, formalised as real
temporary ownership: **the AE2 network domain owns the network AND the execution of its participating host BEs**;
MAIN/region BE phases read a published ownership snapshot and never tick those hosts.

Invariants (each a fixture/counter on the rig gate):
1. one host BE in a given tick has exactly ONE executor;
2. MAIN never skips a BE on stale ownership — snapshot carries a generation/epoch, applied only at a barrier;
3. handoff MAIN/REGION → domain and back passes a lifecycle barrier (domain idle, epoch bump, publish);
4. unload/reload, remove/replace, split/merge never leave a host in two domains (claims keyed by position + expected
   BE type; mismatch ⇒ MAIN; chunk unload releases its claims at a barrier first);
5. the worker ticks ONLY hosts explicitly claimed for that domain — never arbitrary blocks;
6. ownership not provably safe ⇒ fallback to MAIN (model B / inline), never concurrent execution.

**A-PERF gate** (same workload): correctness 0 failures; duplicate/missed BE ticks 0; MAIN wait on the worker drops
sharply; MAIN and worker overlap in time; wall MSPT → max(MAIN, worker), not the sum. Honest first target: stable
18–20 TPS on this copy (≈45–50 ms p95) — not "20 TPS guaranteed". After A: profile first; only if one grid stays the
critical path ⇒ transit buses → parallel scans → bounded intra-grid parallelism.

### 3″.1 Participant classes (owner 2026-11-12, binding) — the AE2 domain must not eat world ownership

| class | examples | mechanism |
|---|---|---|
| 1. AE2-internal hosts | grid node host BEs of the grid (all dimensions) | **CLAIM** (domain ticks them, may touch them directly) |
| 1′. explicitly validated compat groups | MasterfulMachinery complete multiblock | **CLAIM WHOLE GROUP** only via an explicit adapter policy (`wholeGroupClaimSafe=true` declared by nestworld-compat-mm after a fixture) — NOT a generic "pinned member + whole group ⇒ ignore pin" rule; another type pin may exist for an unrelated reason |
| 2. foreign inventories / machines | Sophisticated Storage, vanilla chest, machine inventories, pipes | **never claimed**. Access through TRANSIT to the owner domain (MAIN or the owning region): `InventoryOp{inventoryId, expectedVersion, op, amount}` → owner executes → result. Region-owned inventory ⇒ AE2 worker ↔ region worker, MAIN not involved |
| 3. read-only / read-mostly | BlockState, BE presence, capacity, approximate contents, comparator state | published immutable SNAPSHOT `InventorySnapshot vN`; mutation = request with expected version; stale ⇒ retry/rescan |
| 4. unknown / unsupported | anything not covered | **FALLBACK** model B / MAIN |

Plain blocks: BlockState read via snapshot/read-safe access; setBlock/removeBlock/BE update only through the owner domain;
synchronous APIs that must return a value now and have no safe snapshot ⇒ fallback/pin until a path-specific semantic
exists — never block an arbitrary other thread.

Lifecycle waits: the 250 ms bound is a diagnostic ceiling for fixtures only, not production behaviour. Production
target: timeouts = 0 and typical waits very short; if long waits are structural ⇒ lifecycle mutation via barrier/owner
queue, not a larger timeout.

Status of model-A run 2 (2026-11-12 16:4x): it still claims all 6 neighbours (incl. class-2 inventories) and lets a
pinned MM member in through a generic group rule ⇒ **diagnostic upper bound only, not a promotion candidate**.
Implementation order after it: (a) claim class 1 only; (b) MM explicit policy (Core group flag set by compat-mm);
(c) transit for class-2 access of buses / interfaces / pattern providers (generalised WP3-B, incl. ExtendedAE /
AdvancedAE buses); (d) storage-bus snapshot + versioned transactions; until (c)/(d) cover a grid's neighbours, that grid
stays model B.

### 3″.2 (c) transit for active operations — owner decisions 2026-11-12 (binding)

- Shared transport, **typed operations** with their own semantics: `InventoryOp`, `ScheduleTickOp`, `NeighborNotifyOp`,
  `BlockMutationOp` — never a generic `RemoteOp(Runnable)`; BusTransit must not become a hidden cross-thread world API.
- Planner on the worker: an A-grid's commit queue drains under P(G) inside that grid's phase; the MAIN pump never sees
  it. B → A and A → B only through a published ownership epoch / barrier (no double drain).
- Inline class-2 access from the worker is forbidden even when the target is MAIN-owned: resolve current owner →
  transit → owner executes (same for MAIN and RegionThread).
- Unsupported semantics (crafting card, fuzzy, random, unknown strategy, ExtendedAE / interface / storage bus without
  an adapter) ⇒ NOT TRANSIT SAFE ⇒ the grid cannot enter A; no partial A.
- Coverage: every host that reaches a class-2 target must be transit-safe; one uncovered host ⇒ grid stays B.
- Ownership of a claimed host permits its OWN state only. World side effects from a domain thread: foreign BE/inventory
  → transit; scheduleTick in another domain → owner op; neighbour notification → owner op; setBlock/remove → owner /
  lifecycle path; a synchronous call needing an answer now without defined remote semantics ⇒ fallback B.
- Escrow: an item never belongs to source and destination at once. Import: extract → escrow(opId) → network commit →
  finalize; commit failed ⇒ escrow → return to source ⇒ return impossible ⇒ deterministic recovery/drop + accounting.
  Export: one logical ownership state machine likewise. Dedup protects re-execution; escrow protects a crash between
  the two sides.
- First rig gate: grid A (import bus → MAIN-owned barrel, export bus → REGION-owned furnace/barrel) + grid B (same pair,
  isolation); scenarios: normal import, normal export, target replacement between enqueue and execute, target unload,
  owner/domain change before execution, duplicate delivery of one opId, network full / inventory full, both grids at
  once with different targets. Acceptance: loss 0, dup 0, cross-grid contamination 0, direct foreign inventory access 0,
  stale-op blind commit 0, outstanding after quiescence 0, grid goes B → A, MAIN wait on the worker does not return.
  Latency: import 1–2 ticks, export ≤ 2 ticks (3–4 not accepted as the final contract).
- Order: vanilla AE2 import/export → ExtendedAE / AdvancedAE buses → interface → pattern provider → MM ports →
  storage bus snapshot + versioned transaction. Real A-PERF again only when network #1 has no unsupported class-2 ingress.

### 3″.3 Fast-Live coverage audit — 2026-11-12 evening (blocking-closure list, cheapest path first)

Tooling: `beaudit.py` (class-level) gave a FALSE NO-TOUCH (it missed helper classes: ExtendedAE `FCUtil.ejectInv`,
`WirelessConnect`). Replaced by `/root/perf1b-rig/wp3b/bereach.py` — method-level reachability from every method of the
BE and its non-library supers through non-library helpers (`--deep-appeng` follows AE2 internals), shortest call path
per risk call. Every allowlist entry is re-audited with it; runtime counter `worldOps unhandled` cross-checks it.

| finding | evidence | action |
|---|---|---|
| ExtendedAE TileWirelessConnector / TileWirelessHub were allowlisted NO-TOUCH | `serverTick > WirelessConnect.updateStatus > canUseNode > Level.getBlockEntity`; updateStatus also mutates global `Locatables` and creates/destroys `GridConnection` (topology) | CONFIRMED (bytecode). `ExtendedAeWireless{Connector,Hub}DeferMixin`: while the `updateStatus` flag is set the domain thread does nothing, the grid is demoted, the unchanged original runs on MAIN next tick; flag clear ⇒ original returns immediately (nothing skipped). Applied only when ExtendedAE is installed (plugin resource check). |
| AE2 QuantumBridgeBlockEntity (54 QNB in grid #1) | `serverTick > QuantumCluster.updateStatus > canUseNode > getBlockEntity` (cross-dimension) + `neighborUpdate` multiblock recalculation, only under `updateStatus` flag | `QuantumBridgeDeferMixin`, same rule |
| AE2 own-blockstate setBlock from grid callbacks | runtime `worldOps unhandled=551`: EnergyCell#updateStateForPowerLevel 521, AEBaseBlockEntity#markForUpdate 30; bytecode: own pos, result `pop`, no read-after-write | registered as audited `BlockMutationOp` sites (+ Controller#updateState, Crafting#updateSubType) |
| GrowthAcceleratorBlockEntity (24) | whole `onTick` hops to MAIN (`GrowthAcceleratorMainHopMixin`) = same thread as before model A | allowlisted |
| extendedae_plus SuperAssemblerMatrixFrame (56) | only neighbour read `refreshLoadedFrameModels` runs under `level.isClientSide()`; server side = Wall (already audited) | allowlisted |
| ExtendedAE TileCircuitCutter (8) | `pushOutResult > FCUtil.ejectInv > InternalInventory.wrapExternal` (auto-eject into neighbours) | class 2 — stays a blocker (needs eject transit) |
| AppliedFlux FE strategies on item-only buses | `FEStackExportStrategy.transfer/push`: `!(key instanceof FluxKey) ⇒ return 0`; import: `!isKeyTypeEnabled(FluxKeyType) ⇒ return false`; constructor only `BlockApiCache.create` (lazy) | `ItemNoopStrategies` (exact SHA-256 of the audited bytecode; items-only filter required) |
| AE2 import buses on machines (sim chamber etc.) "not transit-safe" | in model A the continuation's MAIN suffix ran in `resume()` on the AE2 worker ⇒ `currentThreadIn` false ⇒ abort (no loss, no import) | `runSuffixOnOwnerModelA`: suffix runs in the handoff callback on MAIN (lease = source owner) into the transit buffer (`BusTransitContext` never reaches grid state); present non-item resource ⇒ not run, grid demoted. Fixture `importchest` (double chest = never local). PENDING evidence |
| Latent model-A bug | `terminal()` on the worker called `BusImportContinuation.release()` ⇒ Core `Lease.close()` throws off MAIN | release posted to MAIN (`leaseReleaseToMain` counter) |
| Gate `outstanding=1` after quiescence (174736) / `invariant=32` (180027) | export station still exporting (network 6–7k items, barrels not full); `count` is not atomic (network + transit now, barrels later on their domain) ⇒ an in-motion batch is counted twice | CORRELATED. Fixture `pause <id> on` (export config cleared under P(G)) before counting + `records-recheck` 5 s later |
| A-PERF signal | `neighborNotify=892 691` deferred comparator notifications applied on MAIN (top `mm:gigantic_fluid_port_output`) — previously on region threads for MM groups | measuring: `worldOps mainMs / drains / maxDrainUs` |

### 3″.4 HOST_ESCAPE_MAIN replaces grid demotion for topology-sensitive hosts (owner 2026-11-12 ~18:30)

Gate 5 (comparison-20261112-181355) / coverage 5 (181922): promotions 1867 / demotions 1784, demotionRequests = topology
deferrals (2520 / 2126) — every demotion came from the QNB / wireless defer mixins. Cause CONFIRMED by bytecode:
`onMainNodeStateChanged(any state) ⇒ updateStatus = true` (AE2 QNB, ExtendedAE TileWirelessConnector) — the flag marks
ordinary power/channel/boot transitions, so "flag ⇒ demote grid" flaps by definition. Owner: remove defer+DEMOTE.

Status (owner): ownership/BE execution PASS · conservation PASS · world side-effect audit PASS (current coverage) ·
grid serialisation PASS (one domain thread, FIFO) · topology-sensitive host policy FAIL → HOST_ESCAPE_MAIN ·
performance MEASURING · production promotion BLOCKED. Grid #1 is NOT returned wholesale to model B.

HOST_ESCAPE_MAIN (`glue/HostEscape.java`), separate from DEMOTE_GRID:
- worker reaches the host with `updateStatus` pending → topology part NOT run on the worker → one-shot MAIN request;
- ≤ 1 outstanding escape per host (identity); further requests coalesce; while pending the worker skips the host's tick;
- MAIN runs it at the ownership barrier right after the domain await (`GridWorker.awaitAll`, domain idle — the point MAIN
  topology already uses; never during a MAIN commit pass) after identity revalidation (not removed, same BE at pos);
  the unchanged original `serverTick` runs; pending cleared ⇒ host back to the domain; latency ≤ 1 tick;
- ordinary state transition ⇒ host escape; a failing body (unknown/unsupported condition) ⇒ explicit grid demotion.
Expected: promotions sharply down, demotions 0 in steady state, inlineRefused 0. inlineRefused=28 in gate 5 is
CORRELATED/UNCONFIRMED with the flapping windows; if it stays > 0 with demotions = 0 it becomes its own blocker.

#### Gate 7 / coverage 7 (HOST_ESCAPE_MAIN, 2026-11-12 18:32–18:43; evidence comparison-20261112-183244 / 183804)
- Flapping gone: steady state promotions 94/89, demotions 0; hostEscape requested=executed 72/80, coalesced 0, stale 0,
  failures 0 (8 per host per run: 1 QNB + wireless connectors, overworld).
- importchest (double chest, never local): handoffs = modelASuffix = 512, aborted 0, failures 0; 15 232 items imported;
  conservation 0 in snapshot #1 and #2 (5 s apart) for all 5 stations, outstanding 0 ⇒ old outstanding=1/+32 =
  non-atomic census artifact (CONFIRMED by the quiescent protocol).
- inlineRefused=25 (= demotions 25) appears ONLY after the quiesce step (0 at mid-status, 0 in coverage 7): STRONGLY
  INDICATED cause — `pause` empties export-bus config ⇒ `ItemNoopStrategies.itemsOnly` requires ≥1 item key ⇒ AppliedFlux
  FE strategy rejected ⇒ inlineStrategy ⇒ refusal. Fix proposal (owner review): empty-config export bus = AE2 does no
  work (config loop skips null keys) ⇒ allowed.
- Perf (diagnostic; grid #1 still B — exclusive worker job, MAIN waits): MAIN mspt p50/p95/p99 69.7/125.5/205.1,
  MAIN own 37.7/54.7/159.3, worker 31.4/79.9/101.8, MAIN await 29.8/78.3/99.3, worldOps 2.10/7.66/10.54 ms/tick
  (p95 FAILS provisional < 5), overlap 4.9 % ⇒ today = MAIN + waits + worker. Next run splits worker time and world-op
  cost A(owned)/B(exclusive).

#### Owner decisions after gate 7 (2026-11-12 ~18:50)
- Old outstanding=1/+32 = CONFIRMED non-atomic census artifact. HOST_ESCAPE_MAIN: PASS. Conservation/transit: PASS.
- Empty-config export bus FIX APPROVED: `BusTransit.strategiesAllowed` — AE2 15.4.11 `ExportBusPart.doBusWork` skips null
  config keys and calls the export strategy only per configured key (bytecode CONFIRMED) ⇒ empty config = no-op for ANY
  strategy (counter `exportEmptyConfig`). Closure by a narrow targeted run (mode `emptycfg`, not a full gate): normal
  export → empty config 300 ticks (transfer 0, inlineRefused 0, demotions 0) → restore (exports resume) → quiescent
  two-snapshot conservation 0.
- A/B attribution run APPROVED: worker.A vs worker.B ms, worldOps A vs B mainMs. If B dominates comparator cost ⇒ the
  priority is grid #1's 103 blockers (overlap needs grid #1 in A); if A (esp. mm:gigantic_fluid_port_output) is
  significant ⇒ model A moved region work to MAIN ⇒ separate perf blocker before promotion. No coalescing before that.
- Status: correctness foundation PASS · HOST_ESCAPE_MAIN PASS · conservation/transit PASS · empty-config refusal: fix
  approved · performance BLOCKED (narrowed to grid #1 + worldOps) · production NO. 5 % overlap ≠ model A failed: the
  heaviest grid still runs in B.

#### Empty-config closure + A/B attribution (2026-11-12 18:58–19:10; evidence comparison-20261112-185817 / 190308)
- emptycfg (station grid in A): normal export ✓; empty config 300 ticks: station network 16544→16544, consumed
  3456→3456 (transfer 0, generator on) ✓; inlineRefused 0, demotions 0 ✓; restore: consumed 3456→4672 (exports resume) ✓;
  quiescent snapshots conservation 0 / 0, transitHeld 0 ✓. The script's first `empty_transfer_zero=False` used the GLOBAL
  export ledger (other grids export via transit too) — measurement error, fixed to station-local counts.
  `exportEmptyConfig` was already 440 before the pause: the real world has empty-config export buses. ⇒ CLOSED.
- A/B attribution (120 s window, 2355 ticks; promotions 88 / demotions 0, hostEscape 62 = 62, inlineRefused 0,
  unhandled 0): worker total mean 34.9 / p50 25.4 / p95 78.7 ms/tick, of which owned (A) only 3.6 / 3.4 / 4.4 ⇒
  B (grid #1 exclusive) ≈ 31 ms/tick ≈ MAIN await 32.3 / 23.3 / 76.1. MAIN mspt 59.9 / 118.3 / 183.8, MAIN own
  35.7 / 52.3 / 125.5, overlap 7.4 %. worldOps/tick mean 1.29, p50 0.39, p95 6.22, p99 8.60; top 10 world-op keys are
  ALL B(exclusive) (netherite_chest comparator 1.58 s, mm colossal/gigantic ports, entropy cradle capacitor …);
  each A key < 37.5 ms over the run (A totals not yet summed — next status prints A/B totals).
  ⇒ STRONGLY INDICATED: the comparator/worldOps cost and the MAIN wait are grid #1 B work, not a new A→MAIN cost.
  mm:gigantic_fluid_port_output dropped from 615k ops (coverage 7) to 18k (B) here — run-to-run machine activity,
  UNKNOWN. Priority: grid #1 blockers (103 hosts) → grid #1 in A → real overlap.

### 3″.5 AE2-GRID-DOMAIN-MVP-001 — grid #1 census (2026-11-12 19:20, evidence comparison-20261112-192002, `gridcensus grid1`)
grid #1 = 3933 nodes, B, 282 distinct uncovered host/side pairs. Device cost of grid #1: 25.8 ms/tick — PartExImportBus
8.56, PartExExportBus 7.55, AdvancedAE ImportExportBus 2.69, AE2 Export 2.03, AE2 Import 2.03, StorageBus 1.10,
OversizeInterface 1.00, TileExInterface 0.29, rest < 0.15 each. Blockers by STRUCTURAL cause (A is all-or-nothing):
| group | pairs | cause (bytecode) |
|---|---|---|
| C crafting | 170 | `CraftingCpuLogic.executeCrafting → ICraftingProvider.pushPattern` (35 TileExPatternProvider, 33 PatternProviderPart, 6 Ex/block PP, crafters/packagers/assembler/reaction chamber/circuit cutter ~33) and crafting-card export buses (63: `insertCraftedItems → StackExportStrategy.push`) — synchronous neighbour access from the crafting CPU tick |
| I interfaces / oversize | 32 | foreign pipes pull synchronously from a claimed host's inventory (ingress) |
| S storage bus | 29 | every network insert/extract iterates the mounted foreign inventory (sophisticated chests 25+) synchronously |
| A AdvancedAE IE/IO bus | 20 | own `doBusWork` override (not the audited AE2 path) |
| M other hosts | 22 | botanicalextramachinery pools/greenhouse, FluixPool, ChemicalP2P, WirelessAccessPoint, EntropyCradleController — bereach pending |
| F FE strategy on fluid/chemical exports | 9 | FE strategy is a no-op for every non-FluxKey (bytecode) — FIXED (`noFluxKey`) |

#### Storage-bus chest touch census (owner-approved, 2026-11-12 19:48–19:53, evidence comparison-20261112-194856)
grid #1: 89 storage buses, 25 Sophisticated targets (64 other targets). Static neighbours of the 25 chests: 69 chest
(adjacent chests), 30 ae2 cable bus, 5 integrateddynamics multipart, 5 flowline:pipe, 1 botany pot, 1 mek tube.
Ops in 120 s (all counted): read 69.4 M @AE2-worker, 0.99 M @REGION, 0.16 M @MAIN; insert-sim 415 k worker / 8.6 k REGION;
extract-sim 195 k worker / 432 REGION / 16 MAIN; extract 18 k worker; insert 2 k worker / 12 MAIN / 8 REGION.
REGION caller = `com.knozyy.flowline.pipe.transfer.ItemTransfer#run` (flowline pipes beyond the chests, not adjacent to
AE2 ⇒ not affinity-pinned). ⇒ CONFIRMED: the chests are touched concurrently by flowline pipes on REGION threads and by
AE2 (worker in this run; MAIN on live) — Sophisticated InventoryHandler is not thread-safe ⇒ an EXISTING cross-domain
race independent of model A (STRONGLY INDICATED on live: AE2 on MAIN vs flowline on REGION). Volume (~29 k slot reads
and ~260 facade ops per tick) rules out one synchronous MAIN call per storage operation.

#### BARRIER_MAIN_SERVICE implementation + smoke 1 (2026-11-12 ~20:00, evidence comparison-20261112-195755)
Core `ExecutionDomains.callMain(key, delegation, body)` / `serviceMainCalls()` (service points: domain await loop,
`RegionThreadPool.awaitLatch`, `beforeBlockEntities`, `awaitAllDomains`, between-tick server task) — nested await from a
serviced call fails closed; `currentDelegation()`. Addon `MainService` (refuses inside a kernel section; delegated ingress
skips P(G); `lockOnMain` services calls while waiting for any P(G)) + `MainServiced` (C/I hosts unclaimed, parts' device
ticks, unsupported-config buses) + crafting CPU `isBusy/pushPattern` + `ExportBusPart.insertCraftedItems`.
Smoke 1: mainCalls 21 743, failures 0, nestedAwait 0, kernelRefused 0, offDomain 0, no deadlock; worker wait per key:
AdvancedAE IE bus n=2962 total 3.3 s p95 2.3 ms, Oversize n=915 1.5 s p95 4.2, Export(unsupported cfg) n=1952 1.1 s,
Ex export 0.7 s, TileExInterface 0.5 s, CircuitCutter n=12072 0.3 s (≈ 5 ms/tick of worker waiting in total);
crafting calls 63. grid #1 block-host blockers 103 → 25; the 263-node grid became CLAIMABLE. New: worldOps unhandled 28 =
MM `MachineControllerBlockEntity#setPortState`/`#updateControllerState` (claimed MM groups now inside A grids) — bytecode:
own controller / own group's port, result popped, no read-after-write ⇒ registered as explicit audited sites.
Remaining grid #1: storage bus 30 (25 Sophisticated + 4 Mek/IF + AdvancedIO→chest), Souls export strategy 10 (fixed:
SoulsStackExportStrategy returns 0 for every non-SoulKey ⇒ "no configured own-key" rule, generalised from FE),
ChemicalP2P 2 (synchronous forwarding on the pushing caller's thread — NOT a device tick ⇒ stays a blocker),
other block hosts ~25 (5 more packaged crafters, BloodAltar assembler, botanical machines, FluixPool, EntropyCradle
controller, WirelessAccessPoint) ⇒ MAIN-serviced hosts (local fallback: BE tick on MAIN as today).

#### Storage-bus option (1) — owner decision 2026-11-12 ~20:20 + lock-order proof
Owner: option (1) APPROVED as an EXPLICIT MVP exception to "worker never mutates a foreign inventory directly" — only
storage-bus targets of grid #1 (`chestGuardMinNodes`, default 1000), only Sophisticated handlers, only because EVERY
accessor of that handler enters through the same P(G). Option (2) REJECTED (40–80 ms/tick sync MAIN service). Option (3)
DEFERRED (only if (1) fails the lock-safety or performance gate). PASS needs: lock-order proof; no long external
callbacks / world mutation re-entering AE2 under P(G); P(G) wait MAIN/REGION p50/p95/p99/max; conservation/dup/loss/
outstanding 0. Live pipe race: FIX NOW (narrow MAIN pin of flowline pipes; temporary until option (1) is verified) —
auto-mode classifier blocked the agent's live RCON; owner runs `nestworld pin flowline:pipe` (type pin; position pins
have no live command; flowline ItemTransfer may run from a non-adjacent pipe's tick).

Implementation: `ChestGuard` + `SophisticatedChestGuardMixin` (20 data-surface methods of
`sophisticatedcore.inventory.InventoryHandler`, @WrapMethod, reentrant), registration in `GridOwnership.compute` for
StorageBusPart / AdvancedIOBusPart → Sophisticated target on grids ≥ MIN_NODES.

Lock / wait graph (edges = "waits for"):
| thread | holds | may wait for | breaks the cycle |
|---|---|---|---|
| AE2 worker (phase) | P(G) (one grid — serial FIFO) | MAIN (`callMain`) | MAIN services calls at every MAIN wait below |
| MAIN | — / P(G') via lockOnMain | worker (domain await), P(G) (`lockOnMain`), REGION (`RegionThreadPool.awaitLatch`, interaction dispatchers `awaitServicing`) | every one of these waits runs `serviceMainCalls()` while waiting |
| MAIN in a serviced call (delegation for G) | (acts for the parked G worker) | P(G') of OTHER grids only (free: worker serial) | P(G) of its own G: delegated ⇒ skipped; a domain await / topology wanting P(G) ⇒ fail-closed (`nestedAwait`, `nestedLockRefused`, gate 0) |
| REGION | no AE2 lock | P(G) (`lock()`) in a guarded handler access | worker never waits for REGION; worker's MAIN call is serviced while MAIN waits for REGION |
| any thread in a kernel section (T0) | T0 | P(G) by `tryLock` only (miss counted, gate 0) | never T0 → P(G) blocking (order P → T0 → domains) |
Therefore no cycle: the only waits on P(G) are by threads holding no other AE2 lock (or tryLock under T0); the P(G)
holder (worker) waits only for MAIN; MAIN never waits without servicing. Residual: Core cross-region read locks
(tryReadLock + timeout) are bounded, not blocking. Callbacks under P(G) on MAIN/REGION: the handler body incl.
Sophisticated `onContentsChanged` → `setChanged` → comparator/neighbour notifications (inline; the worker's are deferred
via NeighborNotifyOp) — measured as hold time per thread kind (`hold ...` in chestGuard status); re-entry into AE2 on the
same thread is reentrant (no deadlock).

#### Option (1) evidence (2026-11-12 20:20–20:35)
- chestguard fixture (comparison-20261112-202025; guard threshold 1 ⇒ fixture + grid #1 chests): storage bus → Sophisticated
  chest, hopper as second accessor; quiescent snapshots conservation 0 / 0; kernelMiss 0; inlineRefused 0.
- Real world (comparison-20261112-202524, grid #1 chests only, grid #1 still B): REGION P(G) wait n=4.27 M, p50/p95/p99
  0.000 ms, max 145 ms (Σ 12.5 s / 2006 ticks ≈ 6.2 ms/tick across region threads); REGION hold under P(G) p99 0.000,
  max 1.05 ms (no long callbacks); MAIN accesses all delegated (60.9 M) — MAIN never waited; mainCalls 58 209 failures 0;
  worldOps unhandled 0. grid #1 blockers 32 → 6 (2 radioactive waste barrel + 1 evaporation valve + 1 IF fluid laser base
  storage buses, 2 chemical P2P). demotions 4 with demotionRequests 0 ⇒ claim-release / participant-set path ⇒ demotion
  reason attribution added (`demotion reasons:` in gridcensus worker).
- Chemical P2P: `ChemicalP2PInputGuardMixin` — pipes push into the input on THEIR thread, the tunnel forwards via grid P2P
  state ⇒ same P(G) contract (hot grids only); covered in ownership.

#### Owner decisions ~20:50 + implementation (cross-grid rule, tanks)
- Lock-order CORRECTION: the worker holding P(G1) can take P(G′) of a subnet (storage bus → other grid's interface ⇒
  ingress(G′)); MAIN can hold P(G′) and want P(G1) ⇒ ABBA possible. Owner APPROVED fail-soft: a thread holding ANY P never
  blocks for another — `PLocks` per-thread held count at every acquisition (phase, topology outer/nested, ingress,
  inventory guard); ingress miss ⇒ 0-result, guard miss ⇒ neutral answer (insert = whole input, extract/read = empty),
  no same-tick retry; counters crossGridTryLockMiss/Hit + attribution; mutators that cannot answer neutrally: 2 ms
  bounded tryLock then unguarded + counted (`unguardedMutation`, gate 0). MAIN topology: may block only for its FIRST P
  while holding none; further P's tryLock, all-or-nothing retry; escalation waits only while holding nothing. The lock-order
  proof above holds only with this patch.
- Tanks (owner): 2 radioactive waste barrels → MAIN service (`MekanismChemicalFacadeMainServiceMixin`, ~0.1 op/tick);
  thermal evaporation valve + IF fluid laser base → P(G) guard on the identity-registered tank objects (valve multiblock
  inputTank/outputTank, laser FLUID_HANDLER FluidTank) via Mekanism BasicFluidTank / VariableCapacityFluidTank, Forge
  FluidTank, Titanium FluidTankComponent mixins (Titanium overrides fill/drain + forced variants + setFluidStack).
- Long-wait attribution (tank census run 204509, census-inflated): 149.5 ms / 155.2 ms REGION waits at ticks 64 / 92
  (startup, grid #1 boot); steady state > 20 ms waits are 21–25 ms ≈ one grid #1 phase, rare (p99 0).
- Demotion attribution (clean run 203408): refusal nodes=3532 (grid #1 still forming: 249 MM-port paths), participant
  set changed 4→9 and 7→56 (small grids growing), refusal nodes=44 (chemical P2P on a sub-1000-node grid) — server tick
  now recorded per demotion to separate startup from steady state.

#### grid #1 ENTERS MODEL A — first measurement (2026-11-12 22:32–22:39, evidence comparison-20261112-223227, light census)
- `grid1 nodes=3933 verdict=CLAIMABLE (A)`; worker owned (A) 40.3 ms/tick mean (p50 36.3 / p95 77.3) of 47.2 total.
- BUT MAIN await 42.8 ms/tick (p50 38.3 / p95 77.4), overlap 9.4 % — MAIN mspt 87.2 mean / 77.6 p50, MAIN own 44.4,
  TPS 14.8 ⇒ no real gain yet. STRONGLY INDICATED structural cause: grid phases are submitted at level/server END of tick
  N and awaited by `GridWorker.awaitAll()` at `onServerStartTick` of tick N+1 (GridMixin) — with MSPT > 50 ms there is no
  sleep between them, so the worker's phase window coincides with MAIN's wait, not with MAIN's level ticking.
- Flapping: promotions 679 / demotions 568, demotionRequests 1563, all "requested by
  BusImportContinuation#runSuffixOnOwnerModelA" (a present non-item resource on a non-local import source ⇒ demote).
- crossGrid miss 0, inlineRefused 0, worldOps unhandled 0; worldOps 1.12 mean / 2.15 p95 ms/tick (A: below the 5 ms gate).
- Per owner rule: STOP code changes at first A ⇒ report; next design decisions belong to the owner.

#### LIVE crash 2026-11-12 22:36:47 (_Hiklee_ online)
compat-ae2 0.9.5 `Specs.overlay` (per-tick energy lock-set walk) → `IEnergyOverlayGridConnection.connectedEnergyServices`
→ AE2 `QuartzFiberPart.getTheirEnergyServices` `requireNonNull` NPE (quartz fiber whose other-side node is absent; stacks
also show the fiber's removeFromWorld / writeToNBT) — 6 tick failures in 60 s ⇒ catch-and-continue gave up ⇒ full crash
shutdown; wrapper killed the hung JVM, systemd restarted 22:39 (NRestarts=1). Hotfix prepared (NOT deployed):
`/root/core-hotpatch-2026-11-12-quartzfiber/nestworld-compat-ae2-0.9.5-all.jar` = live 0.9.5 with only `Specs*.class`
replaced (Specs.java unchanged since 0.9.5; NPE ⇒ the connection links nothing now, counter HALF_LINKED_FIBERS).

## 4. Ingress (who touches G from outside R)

| ingress | today | in grid domain |
|---|---|---|
| player GUI (terminal, crafting, pattern encode) | MAIN | MAIN-hop facade already audited (AE2-MAIN-HOP-FACADE-001): async request → R → result |
| wireless terminals (ae2wtlib, crazyae2addons) | MAIN | same facade; else G ineligible while a wireless terminal is bound |
| crafting calculation pool (`CRAFTING_POOL`) | async → MAIN submit | submit hops to R |
| foreign pipes/machines into AE2 interfaces | their domain | must resolve to R (§2.4) else ineligible |
| commands / JEI sync / jade/top tooltips | MAIN | read-only snapshot or hop |
| chunk unload / BE remove / grid split-merge | MAIN topology | topology stays under P(G); domain switch on structural change |

## 5. Phases (each: audit → design → owner review → implementation → evidence; STOP between)

| phase | content | runtime change |
|---|---|---|
| G0 | census on live/rig: per-grid ms, nodes, domains, links, reached neighbours, ingress counts; what % of AE2 MAIN time is in eligible grids | diagnostics only |
| G1 | Core §3.1–3.3 + fixtures (pure Java + rig), no AE2 | Core |
| G2 | addon: eligibility evaluator + group registration in SHADOW (decide, log, do not move) | none |
| G3 | move ONE eligible grid class on rig (simple: drives + import/export + interface, no crafting) | rig only |
| G4 | crafting CPUs, pattern providers, addons (advanced_ae, neoecoae, crazyae2addons, extendedae) per audit | rig |
| G5 | ingress hops (§4) | rig |
| G6 | live canary: one grid, owner go | live |

## 6. Hard invariants (every phase)

GAMEPLAY_EQUIVALENCE PASS (same results as reference Forge under the same logical tick schedule); dup 0 / loss 0
(items, fluids, energy, crafting jobs); no throttling / tick-rate change; GRID_ISOLATION (a heavy grid never delays
another grid's work); deterministic negative controls; one switch per grid at a time; instant fallback = grid back to
MAIN path (flag + per-grid).

## 7. Open questions for the owner

1. G3 first target: is it OK to start with grids that have no crafting CPUs (most bus/interface MAIN time)?
2. Wireless terminals: keep such grids on MAIN in G3–G4 (simpler), or design the hop first?
