# AE2-COMPAT-ARCHITECTURE — Unified AE2 Ecosystem Compatibility Runtime (owner ТЗ 2026-11-07)

Status: **GAP ANALYSIS + WORK-PACKAGE PLAN** — no runtime change. Target: `nestworld-compat-ae2` as the single AE2
ecosystem runtime between AE2 (+ addons) and NestWorldCore; the Core stays generic (no AE2 types, no AE2 logic).

## 1. ТЗ ↔ what already exists

| ТЗ § | requirement | existing component (addon `net.nestworld.compat.ae2`) | status |
|---|---|---|---|
| 3 | Core generic only | Core: `BlockEntityExecutionDomain`, mailbox, pins/pinmod, CORE-PINZONE-001 position pins; addon reaches Core reflectively (`BeDomainReady`, `NeighborAffinity`) — no Core compile dependency | DONE (keep) |
| 4.1 | lifecycle: onReady, exactly-once init, init-before-remove, generation, stale cancel | `BeDomainReady`, `FirstTickInfoMixin`, `LifeAdmission` (D1), `LifeHooks`, `NwAeBlockEntity` (`nw$life`) | VERIFIED (scope 2, D1) |
| 4.2 | topology: connection create/destroy, ensureConnection, TOCTOU, split/merge, unload/reload, callbacks after mutation | `GridConnectionMixin` (`nw$ensure`), `GridNodeMixin`, `TopologyCallbacks`, `GridPhaseGate` P(G), `ClusterTeardownCoordinator` (D4a) | VERIFIED (scope 3, GRID-PHASE, D4a engineering) |
| 4.3 | grid shared state | TickManager (T1/T2), EnergyService (ENERGY-001 domain), P2P (H1/H2/H3), staging of Storage/Crafting `requestUpdate`, StackWatcher | PARTIAL — **network storage and CraftingService mutations are NOT guarded** (AE2-MACHINE-TICK-DOMAIN-001 §8 B1); Pathing / Statistics not audited |
| 4.4 | callback ownership, channels | `HostDomainCallbackDelivery` channels TOPOLOGY / STATE / BLOCK / CLUSTER | DONE for those; `MACHINE` channel = new (WP4) |
| 4.5, 8–11 | machine tick runtime (MAIN fallback / host-domain offload, one in-flight, generation, bounded delay) | T2 suspension (device tick outside the grid section + revalidated continuation) | FOUNDATION ONLY — offload blocked on the storage domain (§8 B1–B6 of MACHINE-TICK-DOMAIN-001) |
| 5–7 | compatibility registry, tiers, exact version gates (mod, version, SHA, mixin targets) | `StartupGate` (AE2 release registry, `IAEPowerStorage` ALLOWLIST, multi-release), `compat-manifest.tsv` (BYTECODE-SCREEN-1), runtime barrier `ALLOWED_PARTICIPANTS` | PARTIAL — gates exist but are spread over 3 lists; no tier model, no per-mod policy (machine / tick / callback / storage) |
| 12–14 | lock model, forbidden cycles, no foreign code under wide locks | kernel order `nw$life → T0 → domains`, D1 lock proof, P(G) non-main tryLock | PARTIAL — no single lock-graph document; MACHINE-TICK Phase 1 found the storage gap |
| 15–17 | narrow locks / transactions | — | NOT STARTED (T3′ transit protocol proposed) |
| 18–20 | item / energy / fluid conservation fixtures | D4a drop accounting (captured = delivered + retained); ENERGY-001 soak | PARTIAL — no bus-path item conservation, no fluid fixtures |
| 21, 33 | lifecycle / race fixtures | many deterministic fixtures (topology, lifecycle, endtick, neighbour, s1, teardown, P2P, queue, grid-phase) with negative controls | DONE for audited classes |
| 22 | ExtendedAE first external target | ALLOWLIST-002 (1.4.21 power participants), manifest providers, bus classes extend AE2 buses (bytecode) | PARTIAL — no ExtendedAE machine/callback/static-state audit |
| 27–28 | `/nwcompatae2 status`, per-machine telemetry | `stats` (counters), `gridcensus report/devices`, `domainat` | PARTIAL — no single status view, no percentiles, no per-class domain/latency |
| 31–32 | negative control routing OFF / deterministic transfer fixture | per-feature `…Off` flags exist | transfer fixture NOT STARTED |
| 35 | production compatibility matrix | — | NEW: `AE2-COMPATIBILITY-MATRIX.md` (started with this document) |
| 36 | fail closed | gate FAIL → addon inactive; manifest unknown → DEGRADED | DONE |
| 37 | bounded addon API | — | NOT STARTED (later) |
| 38 | crash attribution | detector `FailureAttribution`, tick-failure attribution | PARTIAL — not in the addon itself |
| 46 | do not rewrite verified primitives | ENERGY, TickManager T1/T2, lifecycle, routing, ensureConnection, callbacks, split/merge, unload/reload | FROZEN (respected) |

## 2. Work packages (proposed order — each: audit → design → owner review → implementation → evidence)

| WP | content | runtime change | depends on |
|---|---|---|---|
| WP1 | **AE2CompatibilityRegistry**: one descriptor per (modId, version, SHA) with tier, machine / tick / callback / storage policy; StartupGate ALLOWLIST + manifest + runtime barrier become views of it; `/nwcompatae2 status` (versions, SHAs, supported / unsupported integrations, mode, counters) | none (refactor of the gate data, same decisions; regression = identical gate result on rig + owner pack) | — |
| WP2 | **AE2-COMPATIBILITY-MATRIX** populated for the owner pack (AE2 15.4.10/15.4.11, ExtendedAE 1.4.21, AdvancedAE 1.3.6, crazyae2addons, neoecoae, extendedae_plus, appflux, appliede, mae2, molecularmanipulator, bmaddon, packagedauto, aeallpattern, appliedenhancements, megacells, SolarFlux) | none | WP1 |
| WP3 | **AE2-STORAGE-DOMAIN-001**: audit of every `MEStorage` / `NetworkStorage` / `StorageService` / cell entry point and its callers on main; design of a per-grid storage domain taken only for short network steps; never across foreign code | yes (later) | owner decision on T3′ |
| WP4 | **MachineTickDispatcher** (T3′): import / export only, exact class allowlist, transit protocol, one in-flight, MACHINE channel, telemetry p50/p95/p99 | yes | WP3, lock-graph proof |
| WP5 | item / energy / fluid conservation fixtures (deterministic transfer chain 100 000 transfers + race variants §33–34) | test only | WP4 (can start in parallel as harness) |
| WP6 | ExtendedAE audit A0–A8 (machines, callbacks, static state) | per findings | WP1 |
| WP7 | crash attribution inside the addon (§38) and per-machine telemetry (§28) | diagnostics only | WP1 |
| WP8 | narrow-lock gate (AE2-TRANSACTION-NARROW-LOCK-001) only if WP4 telemetry shows contention | — | WP4 evidence |

Not started on purpose: distributed bridge (§44), lock-free / sharded grid state (§50).

## 3. Known conflicts between the ТЗ and current findings

1. §8 machine runtime "execute machine work on REGION" is not safe while network storage is unguarded
   (MACHINE-TICK-DOMAIN-001 §8 B1/B2) — WP3 is a hard prerequisite, not an optimisation.
2. §7 names AE2 15.4.10 as the exact target; the owner pack runs 15.4.11 (admitted by AE2-RELEASE-EQUIV-001 with a
   per-class bytecode equivalence). The registry keeps 15.4.10 as the audited base and 15.4.11 as an equivalent release.
3. §42 forbids "redirect all AE2 to MAIN as the final fix": today's production profile is exactly that (pins); it stays
   the fallback until WP3/WP4 are verified — not the final state.

## 4. Owner decisions 2026-11-07 (strategic status)

```
existing safety/runtime primitives   VERIFIED / PRESERVE
WP1 Compatibility Registry           APPROVED FOR IMPLEMENTATION
     acceptance: OLD gate decision == NEW registry decision for every current (mod, version, SHA, runtime state);
     regression table StartupGate / manifest / runtime barrier → descriptor → activation mode, 0 mismatches;
     no SUPPORTED↔DEGRADED or REJECTED→ACTIVE change without an explicit policy change.
     descriptor = modId, version, jarSha256, tier, machine / callback / storage / topology policy, fallback, support status
WP2 pack compatibility matrix        STARTED
WP3 Storage Domain                   AUDIT/DESIGN APPROVED (WP3-A: StorageService entrances, crafting/storage
                                     cross-calls, external capability boundaries, lock/reentrancy graph, contract → STOP);
                                     IMPLEMENTATION NOT YET APPROVED (WP3-B after review)
WP4 Machine Tick Dispatcher          BLOCKED BY WP3
WP5 conservation fixtures            PLANNED
WP6 ExtendedAE                       PLANNED (hotspot evidence available)
WP7 unified diagnostics              PARTIAL → PLANNED
WP8 narrow-lock                      EVIDENCE-TRIGGERED ONLY
```
Target storage form: prepare → short storage admission/transaction → external operation OUTSIDE the storage lock →
short validate/commit; never storage lock → foreign capability → world mutation → callback into AE2.
