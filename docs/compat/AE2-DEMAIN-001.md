# AE2-DEMAIN-001 — remove the production requirement `pinmod ae2 → MAIN` (owner decision 2026-11-09)

Goal: stop using the server MAIN thread as AE2's global mutex. Shared AE2 state may stay serialized, but in an AE2
execution domain, not on MAIN. NOT a goal: making all of AE2 concurrent. No direct unpin before the gates below.

Runs in parallel with Track A (CORE-AUTOSPLIT-FIX-001: Stage 1 shadow → calibration → Stage 2 enforce → Stage 3
regret merge → autoSplit=true); the tracks only coordinate on physical CPU for stress tests (heavy tests at 0 live
players). Reopens the frozen WP3-B and WP4 (AE2-COMPAT-ARCHITECTURE §6).

| step | content |
|---|---|
| B0 | baseline on a production-like workload: AE2 MAIN time, AE2 BE tick time, storage ops/tick, crafting CPU time, MBCalculator time, import/export/interface activity, callbacks, transit ops, MAIN MSPT contribution |
| B1 | MBCalculator gate — reproduction: invalid H run1 (`MB_MODIFICATION concurrent 1259`, ISE "modification already in-progress", BE init failures). MBCalculator no longer relies on MAIN for serialization. First safe form: one serialized AE2_SHARED / MULTIBLOCK execution lane off MAIN; every shared multiblock structural operation goes through it. No ThreadLocal of the global flag to hide the race |
| B2 | AE2_SHARED never mutates the world, calls neighbour callbacks, mutates foreign BEs, or waits for MAIN / REGION; world mutation = intent → BlockEntityExecutionDomain → revalidate → execute |
| B3 | after verification: keyed domains (per grid / per cluster / striped) so independent networks do not serialize each other; correctness first |
| B4 | WP3-B REOPENED: region-local AE2 machines tick in their REGION; shared-storage operations go through the AE2 grid domain; never pull a whole machine to MAIN for one storage op |
| B5 | asynchronous transit: REGION host → request → AE2 grid domain transaction → result → host applies; no synchronous wait; ≥ 1 tick latency allowed if gameplay semantics hold |
| B6 | transaction safety: operationId, host generation, grid generation, item/fluid identity, requested / committed amount, terminal result, dedup; no duplicate extract/insert, no loss, no stale result to a replacement host or new grid generation |
| B7 | WP4 REOPENED: by profile — most expensive MAIN path → targeted migration → fixture → performance measurement |
| B8 | pin decomposition: AE2_ALL_MAIN → only unresolved shared types MAIN → only legacy exceptions → no AE2 MAIN pin; pinned AE2 BE count decreases monotonically |
| B9 | per removed pin/path: off-domain 0, duplicate 0, item loss 0, MB concurrent 0, S1/S2/S3 0, lifecycle / topology / accounting clean, no watchdog, MAIN time not increased; performance before / after |
| B10 | final no-pin soak (30 min, production-like copy): crafting / storage / import-export / interfaces / power-channel / cluster teardown-rebuild / unload-reload / split-merge / several independent networks; MB concurrent 0, ISE 0, loss/dup 0, detectors 0, crash 0, watchdog 0 |
| B11 | `AE2-NO-MAIN-PIN-PRODUCTION-VERIFIED` → production `pinmod ae2` removed |

Status 2026-11-09: B0 + B1 source audit started (audit → design → STOP for review).

B0/B1 audit: `AE2-DEMAIN-001-B0-B1-audit.md` (STOP for review, decisions D1–D9). Immediate live action 2026-11-09
from it: MEGA crafting unit / storage are AE2 `CraftingBlockEntity` types registered under `megacells:` (CONFIRMED by
javap of megacells-forge-2.4.6 MEGABlockEntities) and `megacells` was NOT pinned on live → same MBCalculator race class
as invalid H run1; `/nestworld pinmod megacells` applied on live (0 players, persisted; standing pin permission).

## Owner decisions 2026-11-09 — B1 (D1–D9)

| | decision |
|---|---|
| D1 lane | APPROVED: non-blocking serialization token, no AE2 thread. Free → MBCalculator runs now in the current host domain; busy → register request, caller returns; on release the next target is enqueued into THAT target's current BE execution domain, revalidated, calculated there. MAIN is not the lane executor. The token decides who may touch MBCalculator's global state, not which thread does world access |
| D2 cross-domain multiblock | APPROVED as transitional: position-pin such members to MAIN + counter `crossDomainMultiblockPinned`; not final (cluster execution domain later) |
| D3 queue | NO unbounded FIFO: per multiblock / target max `1 active + 1 dirty rerun` (`dirtyGeneration`, one fresh calculation after the active one) |
| D4 D4a | D4a stays; its MBCalculator part takes the same B1 token; exactly-once untouched; nested acquisition of another token forbidden; repeated same operationId defined, never deadlock |
| D5 addons | one shared semantic entrypoint if they really go through MBCalculator; per-addon fixture proving the wrapper intercepts its calculation path |
| D6 megacells | KEEP pinned until the selective-unpin gate after B1 |
| D7 unpin order | selective only: verified crafting / multiblock types → transit / WP3-B → other machine paths → global ae2 pin last |
| D8 acceptance | negative control + positive no-pin run: MB concurrent 0, ISE 0, silent-skip 0, init failures 0, dup / loss 0, queue outstanding 0 at quiescence; ticket latency p50/p95/p99/max; dirty-rerun / coalesced counts. Instrument BEFORE B1: concurrent entry, already-in-progress ISE, silent skipped recalculation (MBCalculator:84), foreign-finally reset anomaly, recalculation requested / completed; after B1 `requested = completed + coalesced + stale` |
| D9 B0 | APPROVED now; diagnostic / debug, no noticeable hot-path overhead; fix soak harness `gridcensus report → start` |

Production prerequisite (current versions): **AE2 and every MBCalculator / CraftingBlockEntity-based addon MUST be
pinned until B1 selective-unpin verification.** Check: `compat/nestworld-compat-ae2/tools/mbcalc-pin-audit.sh <server>`
(exit 1 on any unpinned user). Live 2026-11-09: the audit found `ae2omnicells` (OmniCraftingBlockEntity extends
CraftingBlockEntity) and `mae2` (DENSE_ACCELERATOR = BlockEntityType<CraftingBlockEntity>) unpinned besides megacells →
all three pinned on live (0 players, persisted); audit now exit 0.

## B0 + B1 — implementation and evidence (2026-11-09)

Confidence words: CONFIRMED / STRONGLY INDICATED / CORRELATED / HYPOTHESIS / UNKNOWN. Evidence root
`E/` = `/root/airis-2026-10-25/evidence-be-ready/`; harness copies in `compat/nestworld-compat-ae2/tools/b1/`.
Nothing was deployed to live; no live pin was changed; no unpin is proposed here (D7: owner gate).

### Built

| commit | what |
|---|---|
| d41ebbb6f | race detector 0.2.0 (test-only): B0 timing `/nwae2b0 start\|stop\|report\|callers` (TickHandler server/level tick, readyBlockEntities, per-service serverEnd/levelEnd split, CraftingCpuLogic, NetworkStorage insert/extract MOD/SIM, AE2 generic item/fluid handlers = B5 transit proxy; MAIN/REGION/OTHER; off by default) and D8 counters `/nwae2mb [stacks]` (concurrentEntry, ISE per site, silentSkipForeign at MBCalculator:85 vs silentSkipOwnChain, foreignFinallyReset, requested/completed/failed, calc time) |
| d8c440b9a | addon: deferred callback delivery time per channel (`cb/st/bk/clDeliverNanos`, `deliverMaxNanos` in `nwcompatae2 stats`) |
| 4684851e8 | addon 0.8.0: B1 MULTIBLOCK lane `glue/MbLane` + `MBCalculatorLaneMixin` (D1–D5, D2 fence), D4a transition under the token |
| ceca600bc | deterministic lane fixture `nwcompatae2 mblanefx skip\|ise <a> <b>` + runtime switch (fixtures flag only); read-only `nwcompatae2 mblane [calculators]` |

Harness fix (D9 / K9): `h-soak.sh` now calls `gridcensus start` at t0 and `report` + `devices` at the end (the addon's
subcommands are `start / report / devices`). The new soak (`b1-soak.sh`) produced the first valid AE2 census windows
(e.g. P0: 5179 ticks / 601.4 s, 86 grids).

Lane design as implemented (CONFIRMED by code + fixture): one try-only token, no thread; free → inline in the caller's
domain; owned → re-entrant inline; busy → per-target pending (max 1 active + 1 pending; `coalesced`, `dirtyRerun`);
release reserves the token for the FIFO head and enqueues it into its target's CURRENT BE domain (Core
`enqueueForBlockEntityDomain`), revalidated there (BE-ticking, same BE identity, not removed, no calc of that target
started since the request → else `stale`). D4a: claim first (exactly-once unchanged), the cluster-state transition runs
under the token; TEARDOWN tickets are never stale, Core terminal → server-thread fallback. Ticket id = operationId,
CAS RESERVED→RUNNING, repeats are counted no-ops (`repeatIgnored`). Unknown writers of the flag
(`setModificationInProgress`) adopt the token when free, else `flagWithoutToken` (verifier). D2: at
`checkMultiblockScale` (bounds known, nothing mutated) a structure whose positions do not all resolve to the current
thread's domain is position-pinned to MAIN (Core CORE-PINZONE-001, reflective), the calc aborted before mutation and
re-queued (`crossDomainMultiblockPinned`); pins released by the D4a teardown of a cluster with those bounds; Core
without position pins (rig Core 401) → `straddleUnfenced`, AE2 behaviour kept. AE2's static flag is untouched (no
ThreadLocal). Lock order: the token is try-only (no wait-for edge) and the lane monitor is a leaf (held only for O(1)
bookkeeping, never across AE2 / Core / logging) → no cycle can be added to `nw$life → P(G) → T0 → domains`; a token
holder may block on nw$life / P / T0 exactly as AE2 code on MAIN does, and no holder of those waits for the token.
Accounting: `requested = completed + coalesced + stale + dropped + failed + outstanding` (`balance` printed).

### Deterministic lane fixture (rig H401BEDOM, AE2 15.4.10, Core 401, TEST-ONLY narrow unpin of ae2 crafting types)

`E/mblane-rig-run3` (valid). Two 2-storage CPUs at (−200,300,−200) REGION#0 and (200,300,200) REGION#1.

| case | lane OFF (negative control) | lane ON |
|---|---|---|
| skip (A held in its flag window, B recalcs) | REPRODUCED: silentSkipForeign +1, concurrentEntry +1 | PASS: all detector deltas 0; lane queued +1, ticketRan +1; both CPUs formed |
| ise (A held after bounds scan, B held in its flag window) | REPRODUCED: ISE +1 at `MBCalculator.calculateMultiblock:140` on A, foreignFinallyReset +1 (A's finally cleared B's flag) | PASS: ISE 0, reset 0, concurrent 0; B queued, ran once after A; both formed |

Invalid runs kept: `E/invalid-mblane-rig-run1-autopin-carryover-repinned-ae2` (Core autoPin re-pinned `ae2` at startup from
the pristine world's carried-over error count; fixed by clearing `mod:ae2` from the test copy's autopin counts);
`E/mblane-rig-run2` lane-ON cases (detector MBCalculator mixin at priority 2000 was the OUTER wrapper and counted calls the
lane only queued — lane OFF cases of run2 are valid; fixed: detector priority 100 = inner, CONFIRMED by run3).

### pi-smoke runs (production-pack copy, AE2 15.4.11 + all addons; 600 s window, h-soak churn: 2 region drivers toggling `ae2:1k_crafting_storage` every tick + MAIN network toggles + region split + chunk unload/reload)

Configs: **P** = production pins (byte-identical to live); **U** = TEST-ONLY narrow unpin: mods `ae2 megacells mae2
ae2omnicells` removed from pinned mods, every NON-crafting BE id of those mods pinned per type (`tools/b1/b1-u-be-pins.txt`,
38 ids; simplest faithful setup, because a mod pin wins over type pins), `-Dnestworld.autoPin=false` for U only (else autoPin
re-pins ae2 from the carried-over error count or from the negative control's own races). Pins restored after every run and
verified byte-identical to live (`cmp`, logged in each `result.txt`). Lane OFF = `-Dnestworld.compatAe2.mbLaneOff=true`.

| | P0 (P, lane on) `b1-P0-prodpins-lane-on-run2` | N (U, lane OFF) `b1-N-narrowU-lane-off` | Y (U, lane ON) `b1-Y-narrowU-lane-on` |
|---|---|---|---|
| crafting storage domain at picks | MAIN | REGION#0 / REGION#4 | REGION#6 / REGION#5 |
| MB calc requested / offMain | 9 061 / 0 | 9 502 / 7 527 | 8 624 / 7 300 |
| silentSkipForeign | 0 | **1** (flag held by Server thread, region readiness skipped) | 0 |
| ISE / foreignFinallyReset | 0 / 0 | 0 / 0 | 0 / 0 |
| concurrentEntry (MbDetect) / MB_MODIFICATION concurrent (StaticDetect) | 0 / 0 | 0 / **3** | 0 / 0 |
| readyInitFailed, S1/S2/S3, crash, watchdog | 0 | 0 | 0 |
| lane requested = completed + coalesced + stale | 9 061 = 9 061 + 0 + 0 (all inline) | lane off | 8 646 = 8 563 + 44 + 39 (balance 0, dropped 0, failed 0) |
| lane queued / ticket ran / dirtyRerun / outstanding at quiescence | 0 / 0 / 0 / 0 | — | 311 / 272 / 61 / **0** |
| ticket latency p50 / p95 / p99 / max | — | — | 89 / 182 / 275 / 333 ms (server at ~10 TPS under churn) |
| teardown under token: inline / queued / ran / reentrant / fallback | 2 647 / 0 / 0 / 2 647 / 0 | — | 2 586 / 53 / 53 / 2 599 / 0 |
| setter adopt / flagWithoutToken / foreignClearWithoutToken | 0 / 0 / 0 | — | 0 / 0 / 0 |
| D2 straddle → crossDomainMultiblockPinned (positions pinned / released) | 0 | — | 61 (183 / 180; 3 still held by the live structure) |
| lane offDomain / bodyConcurrent | 0 / 0 | — | 0 / 0 |
| D4a drops captured = delivered, retained | 0 = 0, 0 | 0 = 0, 0 | 0 = 0, 0 |

Reading:
- D8 acceptance on Y (positive no-pin run of the crafting types): MB concurrent 0, ISE 0, silent skip 0, init failures 0,
  dup/loss 0 (no drops in this workload — see open issues), queue outstanding 0 at quiescence, requested = completed +
  coalesced + stale; S1/S2/S3 0. CONFIRMED for this workload.
- The pi-smoke negative control N is **weak but non-zero** (1 silent skip, 3 static-site overlaps in 10 min) — much less
  than invalid H run1 (concurrent 1 259 / 30 min), because only the crafting types are unpinned now (run1 had all of AE2
  on regions). The deterministic rig fixture is the strong negative control (ISE / reset / skip on demand).
- D2 fence fired 61× on Y: the MAIN-side test network's CPU (−796..−794, 300, 800) was recalculated on the server thread
  while its members resolved to a region → pinned to MAIN, re-run there, released on teardown, re-pinned on the next
  toggle. Which entry ran that calc on MAIN: HYPOTHESIS (rcon `setblock` on MAIN → AE2 path not routed by D2). Without the
  lane this is an off-domain member mutation; the fence turned it into a MAIN-local structure.

### D5 per-addon coverage (pi-smoke P0, `b1-addon-probe.sh`: natural `setblock` → onReady / neighbour path)

Entries counted by the lane's shared entrypoint, by calculator class / target BE type (CONFIRMED):
AE2 `CraftingCPUCalculator` (ae2:crafting_storage, ae2:crafting_unit — also the BE type of extendedae_plus x-accelerators),
`QuantumCalculator` (ae2:quantum_ring), `SpatialPylonCalculator`; megacells (`megacells:mega_crafting_storage`), mae2
(`mae2:dense_accelerator`), ae2omnicells (`omni_crafting_block_entity`, omni / complex / quantum units) — all through AE2's
CraftingCPUCalculator; AdvancedAE `AdvCraftingCPUCalculator` (quantum_core / quantum_unit); ExtendedAE
`CalculatorAssemblerMatrix` (assembler_matrix_frame; extendedae_plus hybrid_plus seen in the world); neoecoae
`NECraftingClusterCalculator`, `NEStorageClusterCalculator`, `NEComputationClusterCalculator`.
Not shown by the probe: `extendedae_plus:super_assembler_matrix_frame` (single placement produced no calc — UNKNOWN whether
it recalcs through another path); molecularmanipulator (no own MBCalculator subclass — it mixes into ExtendedAE's matrix
calculator and AE2's CraftingCPUCalculator, so it runs inside the wrapped base method: STRONGLY INDICATED, javap);
crazyae2addons (CraftingBlockEntity references only). Addon cluster `destroy()` paths are covered only by the setter
verifier (`flagWithoutToken` 0 in all runs; the addons stayed mod-pinned).

### Performance (B0, 10-min windows; same churn script, production world)

| per 10 min, MAIN thread | P0 | N | Y |
|---|---|---|---|
| mb.calc MAIN ms (region ms) | 622 (0) | 40 (768) | 44 (625) |
| mb.destroy MAIN ms (region ms) | 383 (0) | 0 (768) | 36 (845) |
| tick.ready MAIN ms | 664 | 318 | 301 |
| AE2 MAIN total (tick.server + tick.level) us/tick, share of avg tick | 13 953, 16.0 % | 8 344, 8.6 % | 17 126, 16.7 % |
| server avg tick ms / grid census total ms/tick | 87.2 / 12.7 | 97.4 / 7.2 | 102.7 / 15.9 |

The multiblock work itself left MAIN (mb.calc −93 %, mb.destroy −91 %, readiness −55 % MAIN time; CONFIRMED). Totals are
**not comparable**: they are dominated by one production grid (3 428 nodes) whose cost swung 3.9 → 9.3 → 12.5 ms/tick
between runs with its crafting / storage activity (storage.insert MOD 11 k / 40 k / 51 k), not by the lane. No
before/after MSPT claim is made. Transit proxy: 0 off-MAIN callers into AE2 generic handlers / NetworkStorage in P0 (all
storage traffic on MAIN under production pins). Core on pi-smoke: universal jar sha256 prefix `2bd504e48addc91c` (N, Y);
it was swapped and restored by the parallel split-guard test between P0 and N — identity with P0's Core not verified by me.
Invalid run kept: `E/invalid-b1-P0-prodpins-lane-on-power-loss` (host power loss at 21:22; poll at t+303 s was clean).

### Open issues / decisions needed

1. Selective-unpin gate (D7) — owner decision; not proposed here. Evidence covers the crafting types only (QNB / pylon stayed
   pinned in U; addon multiblocks stayed mod-pinned).
2. Drop accounting under the lane was not exercised on pi-smoke (CPUs held no items); the rig D4a teardown fixtures should be
   rerun on 0.8.0 (lane on) before the gate.
3. D2 fence: the MAIN-side calc of a region-resolving structure (61×) — identify its entry (HYPOTHESIS: unrouted rcon/MAIN
   path); fence pins are runtime-only and released only by D4a teardown (non-D4a destroys, e.g. `QuantumCluster.onUnload`, do
   not release).
4. Addon cluster destroys under contention would only be counted (`flagWithoutToken`), not serialized — per-addon entry
   adapters needed before any addon multiblock is unpinned.
5. Ticket latency p99 ≈ 275 ms at ~10 TPS (handoff waits for the target domain's next safe point while the token is
   reserved); acceptable as TIMING_TOLERANCE per D1, to be re-measured at normal TPS.
6. Longer soak (30 min, B10 shape) and repeat runs for variance not done.

## B4–B6 — WP3-B bus transit: design note (2026-11-09)

Scope: exact classes `appeng.parts.automation.ImportBusPart` / `ExportBusPart` (AE2 15.4.10/15.4.11, `getClass() ==`,
no subclasses: ExtendedAE / AdvancedAE / crazy / appliede stay MAIN-whole). Flag `-Dnestworld.compatAe2.busTransit=true`,
default OFF (addon 0.9.0). Everything not eligible stays on MAIN exactly as today, counted per reason.

### What the live profile actually is, and where the split goes

Bus work does not run in the cable-bus BE tick: it runs in AE2's grid tick on MAIN (`TickManagerService.onLevelEndTick →
tickQueue → unsafeTickingRequest → IOBusPart.tickingRequest → doBusWork`, already wrapped by T2 `nw$suspend`). The
expensive part of `doBusWork` is the **neighbour side** (`ExternalStorageFacade` slot scans / capability extract-insert);
the network side is a few `MEStorage` calls. Unpinning `ae2:cable_bus` moves nothing (no BE ticker). So:

- **Scheduling stays in AE2's TickManagerService on MAIN, untouched.** One scheduled `tickingRequest` = one bus
  operation, at the same tick as without transit → `scheduled == reference` by construction; the operation is never
  skipped or delayed by a transfer still in flight (a new transfer record is created per operation; no per-bus mutual
  exclusion that could defer an operation).
- **The neighbour half runs in the NEIGHBOUR's current BE execution domain** (Core `enqueueForBlockEntityDomain`,
  re-resolved before execution). WP3-A.2 §3.4: the external block's owner is the neighbour's domain, which can differ
  from the bus host's at a region edge; with production pins the host (cable_bus, `ae2` pinned) is MAIN anyway, the
  neighbour (chest, machine) is in its region. No Core pin change is needed for the transit (the parent's
  `pinmodexcept` hook is not used; `ae2` stays mod-pinned).
- **The network half runs on MAIN** (the AE2 grid domain; storage stays MAIN-owned, no storage lock), in the addon's
  per-grid commit scheduler at the end of the server tick (Forge `ServerTickEvent END`, priority LOWEST = after AE2's
  `TickHandler`), outside any `tickQueue` iteration, under `Specs.grid` (T1) only for tick-queue updates.

Import (`ImportBusPart.doBusWork`), op at MAIN tick t:
```
MAIN  t    tickingRequest → doBusWork (wrapped): eligible? → record PREPARED{ops, filter, inverted} → enqueue to the
           neighbour domain; return SAME (or SLEEP/IDLE exactly as AE2 decides, those do not depend on the work)
REGION t+1 revalidate (record PREPARED→RUNNING CAS, bus incarnation, neighbour BE-ticking) → run AE2's OWN import facade
           (StackImportFacade → StorageImportStrategy / appmek / appbot import strategies, unchanged code) with a
           transit context: getInternalStorage().getInventory() = the record's transit MEStorage (insert SIMULATE =
           amount, MODULATE = held += amount). → IN_TRANSIT{held}, published to the grid's queue
MAIN  t+1  commit scheduler: network insert MODULATE (plain insert, as AE2) → COMMITTED; deferred modulation applied
           (FASTER if anything moved, else SLOWER) to the same tracker; leftover → RETURNING (neighbour push in the
           neighbour domain, t+2) → remainder (if refused) stays held, persisted, retried (never voided; AE2 voids here)
```
Export (`ExportBusPart.doBusWork`), op at MAIN tick t:
```
MAIN  t    candidate slots in AE2 order (DEFAULT / ROUND_ROBIN from nextSlot), key != null, network poweredExtraction
           SIMULATE > 0 → record PROBE{keys, max} → enqueue to the neighbour domain; return SAME
REGION t+1 capacity probe: exportFacade.push(key, max, SIMULATE) per candidate (side-effect-free) → PROBED, published
MAIN  t+1  plan = AE2's export loop with "adjacent insert SIMULATE" replaced by the probed capacity: poweredExtraction
           MODULATE (energy charged exactly as AE2) → IN_TRANSIT{held}, ops / slot cursor x as AE2 → enqueue insert
REGION t+2 exportFacade.push(key, held, MODULATE) → inserted; leftover → RETURNING, published
MAIN  t+2  leftover → network insert (as AE2's "give the overflow back"; remainder kept held, retried, never voided);
           COMMITTED; deferred modulation + round-robin cursor update (if work done)
```
Deferred modulation: `tickingRequest` returns SAME for a transit operation; when the terminal result arrives (≤ 2 ticks,
min bus rate is 5 ticks) the commit scheduler applies AE2's own rule (`FASTER: rate−2`, `SLOWER: rate+1`, clamped by
`setCurrentRate`) to the tracker, only if it is still the same tracker, awake, and `lastTick == opTick` (no later op);
re-queued through AE2's `removeFromQueue/addToQueue` (heap order). The next due tick is then identical to the reference.
A result that arrives after the next op already ran is counted `modulationLate` (not applied; must be 0).
Known divergence (TIMING / SEMANTIC, counted): import uses an optimistic network acceptance (no network SIMULATE on the
region); items the network then refuses go back to the source ≤ 1 tick later (`importRefusedReturned`) instead of not
being extracted; AE2 would void what the source does not take back — transit keeps it. Export plans from a capacity probe
one tick old; if the neighbour's capacity shrank, the leftover returns to the network (`exportLeftoverReturned`).

### Eligibility (exact allowlist, fail-closed, re-evaluated at every op)
transit active (flag) · exact class · no crafting card, no craft-only, no pending crafting links (export) · no fuzzy card ·
scheduling mode DEFAULT or ROUND_ROBIN (RANDOM consumes level RNG on MAIN → excluded) · every strategy in the bus's
facade is an allowlisted class (AE2 `StorageImport/ExportStrategy`, appmek 1.4.3 `MekanismStackImport/ExportStrategy`,
appbot 1.5.2 `ManaStorageImport/ExportStrategy` — bytecode-checked: only context getters, network insert / powered
extraction, neighbour capability calls; `push` = neighbour insert only) · neighbour domain (resolved now) is a REGION.
Neighbour domain MAIN (border band, pinned type, a storage bus / interface affinity-pinning the same block) → the
operation runs inline on MAIN = AE2 reference (counted `inlineMainNeighbour`). Any other ineligibility → the addon's
neighbour-affinity pin for that bus is taken first (the neighbour becomes MAIN-domain, as today) and the op runs inline.
Affinity for the two exact classes becomes dynamic: an eligible bus releases its neighbour pin, an ineligible one holds
it. All other parts keep the static affinity pin (unchanged).

### Transfer record (B6) and phases
`transferId` (random 63-bit, unique across sessions) · bus incarnation (part identity + addToWorld counter = host
generation) · grid identity at op time (grid generation; commit goes to the bus's CURRENT grid, a change is counted
`gridChanged`) · direction · opTick (AE2 currentTick) / server tick at each phase · per resource: key, requested,
held, committed, returned · terminal result. Phases (owner model): `PREPARED` (nothing moved) → `IN_TRANSIT` (held > 0,
transit owns) → `COMMITTED` | `CANCELLED` (nothing moved) | `RECOVERY_REQUIRED` → recovery terminal (`RECOVERED_NETWORK`,
`RECOVERED_SOURCE`, `RECOVERED_DROP`). Internal sub-states (RUNNING, PROBED, RETURNING) are CAS transitions on the
record; each phase is executed by exactly one owner: PREPARED/PROBE by the neighbour domain (CAS PREPARED→RUNNING —
a cancelled record is never executed), IN_TRANSIT by MAIN. Dedup: a session set of terminal transferIds; a record whose
id is already terminal (NBT copied by spatial IO / pickup mods, a part NBT loaded twice) is dropped as duplicate
(`dedupDropped`). Invariant per record: `extracted = committed + returned + held`; globally (fixtures): `source + destination
+ transit = constant (+ created by the fixture generator)`.

### Persistence and the recovery owner
`IOBusPart.writeToNBT` (MAIN) writes the records with held resources (`nwTransit` list: id, direction, key NBT, amount);
the snapshot is taken under the record monitor so a region phase is either fully before or after it. Ownership rule:
**after the host leaves the world, NBT owns exactly what the last save wrote; the live object owns the rest.**
- chunk unload (`CableBusBlockEntity.onChunkUnloaded` marks the host): records are SEALED — live processing stops;
  amounts written by the last save are left to the NBT; anything a still-running region phase adds after that save is
  recovered live. Every MAIN-side change of persisted transit state marks the host `setChanged` (the unload save then
  writes the current state, never a stale one).
- server stop: same as unload (the final save writes held records; on restart they load as RECOVERY_REQUIRED).
- bus broken / part removed (no unload mark): records are handed to the global recovery owner (MAIN) immediately.
- loaded RECOVERY_REQUIRED records are owned by the recovery owner from the bus's `addToWorld`.
Recovery owner = the commit scheduler, one policy per resource unit, applied exactly once: import-held → commit to the
network of the bus's current grid (destination), refused → return to source (neighbour, in its domain), refused → keep,
retry every tick; export-held → insert into the neighbour (destination), refused → return to the network (source),
refused → keep, retry. After 200 ticks without a live bus and with no taker, item keys become a safe recovery drop at
the bus position (`RECOVERED_DROP`, ItemEntity on MAIN only if the position's domain is MAIN, else in its domain);
non-item keys stay RECOVERY_REQUIRED (counted, logged, retried) — never voided. In-flight Core tasks that end by a Core
terminal (`REDELIVERY_LIMIT_DROPPED`, `LEVEL_GONE`) before RUNNING → CANCELLED (nothing moved); after RUNNING the record
holds only what was published (the transit MEStorage counts under the record monitor).

### Lock order (no new cycle with `nw$life → P(G) → T0 → domains`)
New monitors: the record monitor and the per-grid queue monitor — **leaves**: held only for O(1) bookkeeping, never
across AE2, Core, foreign code, logging or another monitor. The region half runs the strategies under the region's own
chunk write lock (Core mailbox drain) and takes **no AE2 lock**: it touches only the neighbour (owned by that thread) and
the record (leaf). The MAIN half runs AE2 storage / energy calls exactly as AE2's own MAIN code does (energy → `Specs.energy`
via the existing mixins, tick-queue updates under `Specs.grid`), and never waits for a region. Nothing waits for
anything: every hand-off is a non-blocking enqueue (Core mailbox / per-grid ConcurrentLinkedQueue). Forbidden cycle
(region chunk lock → waits grid lock while MAIN grid lock → waits region state) cannot form: the region side never
requests an AE2 lock, MAIN never requests region state.

### GRID_ISOLATION scheduler
Per grid: own result queue + own accounting (no global FIFO). Commit pass on MAIN each server tick: grids with pending
results in a rotating order (start index advances each tick); deficit round robin with a per-grid quantum = budget /
active grids (default budget 20 ms/tick total, `-Dnestworld.compatAe2.busTransitBudgetUs`; a grid always gets ≥ 1 entry
per tick so it cannot starve). An overloaded grid keeps its own backlog (`budgetExhausted`), the others are served in
the same tick. Per-grid metrics (`nwcompatae2 bustransit grids`): gridId, scheduled, completed, missed, transit backlog
(outstanding records + held units), main commit ns, queue latency in ticks p50/p95/p99/p100 (import / export separate),
budget exhausted, starvation ticks (pending but 0 served). `missed` = an operation whose transfer was neither executed
nor cancelled for a valid reason (enqueue failure, Core terminal while the bus is alive and eligible).

### Fixtures (B4–B6 acceptance, rig H401BEDOM, production pins: `ae2` mod-pinned, chests unpinned)
import and export separately: N transfers, conservation exact, latency p100 ≤ 2, scheduled == completed == reference
(transit-off run), missed 0, backlog bounded; interruptions mid-transit (records held by a fixture-only commit hold):
bus broken, chunk unload + reload, grid split / grid gone, server stop + restart → 0 loss / 0 dup; GRID_ISOLATION: huge
grid (many buses, tiny budget) vs small grid → small grid missed 0, latency unchanged, no starvation; negative controls:
transit OFF (reference), recovery OFF (loss detected → the fixture can see loss), FIFO scheduler (small grid latency grows).
