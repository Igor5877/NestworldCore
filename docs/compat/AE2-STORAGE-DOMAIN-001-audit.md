# AE2-STORAGE-DOMAIN-001 — WP3-A audit (source, no code)

Status: **WP3-A FINDINGS + PROPOSED CONTRACT — STOP FOR REVIEW** (2026-11-07). AE2 15.4.10 source; owner pack
classes by bytecode where noted.

## 1. Entry points (first pass)

33 AE2 source files reach network storage (`getStorageService()`, `getInventory().insert/extract`,
`getCachedInventory()`, `StorageHelper.powered*`): automation parts (import/export/formation/annihilation, 7),
reporting parts (level emitter, panels, 3), terminals / menus and their packets (`menu/me`, `core/sync`, wireless host,
tools, 6), crafting execution (`CraftingCpuLogic`, crafting inventories, 3), `CraftingCalculation`, interface logic,
pattern provider logic, chest / drive block entities, API helpers, test plots. All of them run on the server thread
today: grid ticks (TickHandler), player packets, block-entity ticks of pinned AE2 types. The owner pack adds callers
(ExtendedAE buses / interfaces, appliedenhancements mixins on `NetworkStorage` / `HandlerStrategy` /
`ExternalStorageFacade`, crazyae2addons, neoecoae storage system, AdvancedAE crafting) — to be inventoried in WP3-A.2.

## 2. Findings

| # | finding | evidence |
|---|---|---|
| S1 | **AE2 itself assumes network storage is server-thread-owned.** The only off-thread AE2 work, crafting calculation on `CRAFTING_POOL`, takes a **snapshot** of the network inventory in its constructor on the server thread (`CraftingCalculation.<init>` → `new NetworkCraftingSimulationState(storage, …)` iterating `getCachedInventory()` and simulated extractions) and never touches live storage from the pool. | `crafting/CraftingCalculation.java:72-74`, `crafting/inv/NetworkCraftingSimulationState.java:43-57` |
| S2 | **Every network-storage operation can run foreign code.** Storage buses mount an external inventory into the network (`StorageBusPart.mountInventories` → `StorageBusInventory` over `ExternalStorageFacade` → the neighbour's Forge capability). `NetworkStorage.insert/extract` routes by priority into those mounts synchronously. | `parts/storagebus/StorageBusPart.java:115, 438` |
| S3 | Storage-bus neighbours are affinity-pinned to MAIN (AE2-NEIGHBOR-AFFINITY-001), because main's storage phases read them. | addon 0.7.5 |
| S4 | Consequence of S2 + S3: **a network-storage operation performed on a region thread would call into MAIN-owned foreign block entities** (storage-bus targets) while main ticks them — a cross-domain race independent of any AE2 lock. A per-grid storage lock does not help (it serialises AE2, not the foreign inventory) and would hold foreign code under an AE2 lock (owner red flag). | from S2, S3 |

## 3. Conclusion: no storage lock — storage stays MAIN-owned

A storage domain that lets other threads operate on network storage is **not safe** with storage buses in the network
(S4), and AE2's own design (S1) is single-owner. Proposed contract instead:

```
network storage, cached inventory, crafting service, energy for storage ops   → MAIN only (unchanged, no new lock)
a region thread never calls MEStorage / NetworkStorage / StorageService / CraftingService
```

T3′ for import/export buses then splits each bus tick into a **region part** (only the bus's own neighbour, which
shares the region thread) and a **main part** (only network storage), exchanging items through a per-bus transit
buffer:

```
import:  region tick N   extract from neighbour → transit            (foreign code on its owning thread, no AE2 lock)
         main end of N   insert transit into network (powered)        (main, as today)
         region tick N+1 leftover → back to neighbour, or keep in transit
export:  region tick N   simulate neighbour capacity → request(what, max)
         main end of N   powered-extract up to request → transit
         region tick N+1 insert transit into neighbour; leftover → main returns it to the network
```

Properties: no new lock, so no new lock-order edge (the forbidden REGION-lock → grid-lock cycle cannot form); foreign
code only ever runs on the thread that owns it; conservation `source_before + created = source_after +
destination_after + in_transit` holds by construction (transit is explicit, persisted with the part, never voided);
latency ≤ 1 tick per leg (owner limit: 1 tick — export uses one round trip within it as above). What moves off main:
the neighbour-side work, which is the measured hotspot (appliedenhancements `ExternalStorageFacade` scanning ≈ 20 % of
main, `recheckExtractionType` / `extractFromHandler`), not the network insert/extract.

Offload eligibility per bus (exact allowlist, ТЗ §9): AE2 / ExtendedAE import & export buses; host chunk interior of a
region; neighbour position not pinned by another main-side host (e.g. a storage bus facing the same block); no
crafting card / craft-only; no fuzzy card (fuzzy needs the cached inventory on the region side).

## 4. Decision requested

1. Replace "storage domain lock" by "storage stays MAIN-owned + region/main split via transit" (WP3-B then = transit
   protocol + split bus tick, no lock).
2. Continue WP3-A.2: owner-pack callers of network storage (appliedenhancements, ExtendedAE, crazyae2addons, neoecoae,
   AdvancedAE) and the region-part reach of the split bus tick (what else a bus touches besides its neighbour).

## 5. Owner decisions 2026-11-07

```
WP3-A.1  storage-domain source audit            DONE
         decision: AE2 network storage domain = MAIN; NO new global / storage lock;
         a region machine never performs a network-storage operation
WP3-A.2  ecosystem storage audit (all pack AE2 integrations)   APPROVED / NEXT — before any MachineTickDispatcher code
WP3-B    transit protocol design                NEXT AFTER A.2
WP3-C    conservation + crash / lifecycle fixtures   REQUIRED BEFORE MACHINE OFFLOAD
WP4      MachineTickDispatcher                  BLOCKED BY WP3 transit verification
```
Ownership model: REGION owns external-side interaction, MAIN owns AE2 network-side interaction, TRANSIT owns the
handoff state. Not an atomic cross-inventory transaction — a two-phase resource transfer with explicit intermediate
ownership (source relinquishes → transit owns → destination accepts).

Transit requirements (WP3-B): per transfer `transferId, machineId / hostGeneration, direction, resource identity,
amount, source reservation / result, destination state, phase, createdTick`; phases PREPARED → IN_TRANSIT → COMMITTED |
CANCELLED | RECOVERY_REQUIRED; invariant `source + destination + transit = constant` throughout. Interruption between
halves (bus broken, chunk unload, grid gone, server stop) must never lose, duplicate or re-extract: transit lifecycle is
not owned by the BE alone — a recovery owner applies exactly one defined policy (commit to network / return to source /
materialise a safe recovery drop), never a plain cancel. Latency: ≤ 1 tick per handoff phase; end-to-end ≈ one scheduler
cycle, up to ~2 tick boundaries — to be MEASURED, not promised as ≤ 1 tick.

WP3-A.2 classification per integration: MAIN_NATIVE · TRANSIT_SAFE · EXTERNAL_MOUNT · CUSTOM_SHARED_STATE · UNKNOWN
(UNKNOWN → MAIN / fail-closed). Questions per mod: who mounts inventory/storage; foreign capability calls; custom
MEStorage; custom monitor/listener; crafting/storage callbacks; async access; static/shared state; which thread enters
storage today.

## 6. Owner decisions 2026-11-07 (2)

```
WP1 Registry            IMPLEMENTED; equivalence tests PASS; runtime E1/E2/E3 PENDING — not CLOSED yet
WP3-A.1                 DONE (MAIN-owned storage ACCEPTED)
WP3-A.2                 IN PROGRESS / APPROVED
WP3-B transit protocol  DIRECTION APPROVED; implementation waits for consolidated A.2
WP4                     BLOCKED by WP3 evidence
GAMEPLAY_EQUIVALENCE    finalized: SEMANTIC_EQUIVALENCE hard; TIMING_TOLERANCE 0–2 ticks bounded, non-accumulating
split watcher           operational mitigation only (not architecture) until the split heuristic is fixed
```

WP3-A.2 consolidated document: one row per dangerous entry —
`source / class / method → current thread/domain → state touched → AE2-owned | external-owned | mixed → can invoke
foreign code (YES/NO) → same-tick requirement (YES/NO/UNKNOWN) → routing candidate (MAIN / REGION / TRANSIT / CALLBACK /
FAIL-CLOSED) → evidence`. extendedae_plus JeiSyncManager, TagInventoryME, Wireless Transceiver, MirrorPatternProvider,
EntitySpeedTicker: check own shared state outside the AE2 grid and whether they can run world / capability code from
inside a storage operation.

WP3-B mandatory invariants (fixture-proven, import and export tested separately — no symmetry assumed):
```
scheduled operations = reference;  completed operations = reference;  missed operations = 0
normal transfer latency p100 ≤ 2 ticks
latency does not grow with runtime;  transit backlog does not grow with runtime
a transfer still in transit never causes the next scheduled bus operation (e.g. every 5 ticks) to be skipped
IMPORT:  REGION external inventory → TRANSIT → MAIN AE2 storage
EXPORT:  MAIN AE2 storage → TRANSIT → REGION external inventory
```
