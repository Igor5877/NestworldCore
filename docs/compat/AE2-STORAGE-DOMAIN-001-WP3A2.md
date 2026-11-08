# AE2-STORAGE-DOMAIN-001 WP3-A.2 — ecosystem storage audit, consolidated (2026-11-09)

Status: **DONE (audit) — STOP for review.** Bytecode audits of every AE2 integration in the owner pack (three parallel
audits; per-mod detail and the per-entry register in the owner format in `wp3a2/appliedenhancements.md`,
`wp3a2/extendedae.md`, `wp3a2/other-addons.md`). Confidence tags there: C confirmed from bytecode, S strongly
indicated, H hypothesis.

## 1. Classification (per mod / feature)

| mod | MAIN_NATIVE | TRANSIT_SAFE (conditions) | EXTERNAL_MOUNT | CUSTOM_SHARED_STATE | UNKNOWN / region writer |
|---|---|---|---|---|---|
| appliedenhancements 1.1.2 | NetworkStorageMixin (infinite cells, reservations), CPU/plan mixins | import facade cache, StackImportFacade short-circuit | ExternalStorageFacade slot index | global synchronized export slot-hint cache, static cell registry | menu reservation thread (H, planner off) |
| ExtendedAE 1.4.21 | network side of Ex buses, interfaces, drive, matrix crafting | Ex import / plain + fuzzy export external side | special storage buses, interface STORAGE capability | assembler-matrix LRU, SuperMatrix maps | crafting-card push, precise export batch |
| extendedae_plus 1.6.3 | channel-card tail (MAIN only), PatternCorePlus | — | TagInventoryMEInterface item handler | static channel registry, JeiSyncManager.playerStates | **JeiSyncManager (player tick), Wireless Transceiver, MirrorPatternProvider, EntitySpeedTicker** |
| crazyae2addons 2.6.2 | `insertIntoCpus` overwrite (getMachines per insert), NBT / mob export buses (MAIN-whole) | — | MobFormationPlane (spawns mobs on insert), NBT storage bus | — | wireless notification terminal (player tick), portable autobuilder |
| neoecoae 20.4.2 | crafting bridge, ECO storage ops | — | ECO drive / storage system | static WeakHashMaps, own planning pool / WAL I/O threads | ECOStorageSystem BE tick (pinned → MAIN) |
| AdvancedAE 1.3.6 | `insertIntoCpus` overwrite, quantum CPU, IO/stock buses (MAIN-whole) | — | — | — | **Quantum armor upgrades (player tick)** |
| AppliedFlux 1.3.7 | FE strategies (MAIN-whole), EnergyDistributeService | — | — | `activeNodes` | **Flux Accessor capability from foreign cables (REGION)** |
| Applied Mekanistics 1.4.3 | chemical strategies (MAIN-whole) | — | QIO adapter | static unsynchronised `CACHE` | — |
| Applied Botanics 1.5.2 | mana strategies (MAIN-whole; void mana on refusal) | — | — | — | FluixPool / Corporea from Botania (Botania pinned → MAIN) |
| appliede 0.14.3 | EMC buses (MAIN-whole) | — | EMCStorage (player knowledge) | — | — |
| mae2 2.1.0 | — | — | — | static P2P blocking flags around pushPattern | — |
| molecularmanipulator 2.0.9 | — | — | — | global `KeyCounter.add` / `getActiveMachines` mixins | **~8 BE tickers inserting into the network** |
| PackagedAuto 3.4.15.46 | pattern push (MAIN) | — | — | — | **BE tick → poweredInsert + ME energy** |
| aeallpattern 0.2.6 | — | — | — | watchdog executor | **PatternLinker BE tick = de-facto import bus** |
| megacells 2.4.6, bmaddon 1.0.4, merequester 1.2.1 | yes | — | — | — | — |

## 2. Live risks found (exist today, independent of the transit split)

Region threads already reach MAIN-owned AE2 storage / topology through:
- **BE tickers of unpinned mods** — molecularmanipulator, PackagedAuto, aeallpattern PatternLinker, extendedae_plus
  Wireless Transceiver (grid connections, can be cross-dimension) / MirrorPatternProvider, AppliedFlux accessor tick.
  **Mitigated 2026-11-09** by `pinmod packagedauto, molecularmanipulator, aeallpattern, extendedae_plus, appflux`
  (tick pins; cost measured +1.0 ms/tick with 0 players: 14.8 → 15.9 ms).
- **Player tick on a region thread** — extendedae_plus JeiSyncManager (lists the whole network every 20 ticks per
  linked terminal), AdvancedAE quantum armor auto-feed / auto-stock, crazyae2addons wireless notification terminal.
  **Not mitigated by pins** (the player, not a mod BE, ticks there).
- **Foreign capability callers on region threads** — extendedae_plus TagInventoryMEInterface item handler, AppliedFlux
  Flux Accessor FE/GT/Flux capability, AE2's own unconfigured interface STORAGE capability (Corporea path).
  **Not mitigated by pins** (the caller is another mod's pipe / cable).
- **EntitySpeedTickerPart** — the AE2 grid tick on MAIN runs the facing BE's ticker again while its region ticks it;
  neighbour affinity now pins that position to MAIN (covered by AE2-NEIGHBOR-AFFINITY-001, S).

Proposed fix class for the two unmitigated groups (new gate, addon-only, owner review): a **MAIN-hop facade** at the
capability / player-tick boundary — region-side calls into AE2 network storage are routed as CALLBACK to MAIN (reads
served from a MAIN-published snapshot where the caller only reads, writes deferred with explicit result) or FAIL-CLOSED
(no insert / extract this tick) — never executed on the region thread.

## 3. Consequences for WP3-B (transit) — binding

1. Split at the **strategy contract** inside the exact AE2 classes `ImportBusPart` / `ExportBusPart` and their
   ExtendedAE subclasses `PartExImportBus` / `PartExExportBus` (only ops-per-tick and upgrade slots differ) — every other
   bus subclass (AdvancedAE IO / stock / advanced, crazyae2addons NBT / mob, appliede EMC) and every non-AE2 strategy
   (AppliedFlux FE, AppMek chemicals, AppBot mana — interleaved SIMULATE/MODULATE, AppBot voids on refusal) runs
   MAIN-whole (exact allowlist, fail-closed).
2. **Never rewrite AE2 method bodies** (`@Overwrite` / body replacement / own strategy objects) — appliedenhancements
   injects at call sites inside `StorageImportStrategy.transfer`, `StackImportFacade.transfer`, `HandlerStrategy$1.insert`,
   `ExternalStorageFacade` with `required=true`: call the halves from outside or chain `@WrapOperation`.
3. REGION half passes a positive amount to `ExternalStorageFacade.extract` (appliedenhancements zero-skip); operations
   are counted on one side (network-inserted amount, as AE2); the whole external half of one bus runs on one thread per
   tick; the MAIN half commits through real `NetworkStorage.insert/extract` (infinite cells, reservations).
4. Excluded features: crafting card / craft-only, precise export (atomic batch), Mod / Tag export (scans all keys),
   fuzzy until the cached-inventory read is MAIN-published; channel-card tail stays on MAIN; the external half runs on
   the **neighbour's** domain (may differ from the bus host at a region edge).
5. MAIN-side insert still runs third-party code (crazy / AdvancedAE `insertIntoCpus` overwrite with `getMachines` per
   insert, neoecoae hook, mounted storages: MobFormationPlane, EMC, QIO) — consistent with "storage MAIN-owned"; batching
   the drain per bus keeps it per batch.
6. REGION transit code must not call `KeyCounter`-based snapshot / provider / pattern code (molecularmanipulator global
   mixins, mae2 statics, neoecoae / AppMek static caches).
