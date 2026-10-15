# AE2 compatibility layer — gate status (owner, 2026-10-14)

**Normative.** Separates "mechanism proven" from "AE2 ready to unpin". After `cbbb6577f` the design is
protected against "improvements": no refactor of T2, the domain kernel or the queue state machine
before canary, unless a regression requires one.

| Item | Status |
|---|---|
| ENERGY-001 runtime gate (60-min soak, multi-region, border grid) | **CLOSED / VERIFIED** |
| T2 / Design-1.5 | **CLOSED / VERIFIED** |
| QUEUE `setChanged` protocol | **VERIFIED** |
| QUEUE staging protocol | **VERIFIED** |
| QUEUE T2 regression | **PASS** |
| QUEUE-LIFE-001 | **VERIFIED** (`6e67c7e44`; GHOST negative controls) |
| QUEUE-STAGING-001 | **VERIFIED** (`6e67c7e44`; hopper → charger on a RegionThread) |
| **QUEUE-001 overall** | **CLOSED / VERIFIED** (owner, 2026-10-14); discarded fixture designs kept as evidence |
| GRID-PHASE-001: structural topology mutation races an active grid phase (the pivot NPE was its first runtime manifestation) | **CLOSED / VERIFIED** (owner, 2026-10-15; see the review-round table below). History: 001.1 (phase side effects) AUDITED; 001.2 (reentrancy / synchronicity) AUDITED. **Asymmetric P(G) APPROVED IN PRINCIPLE**: non-main threads all-or-nothing with no wait (bounded backoff or park, no busy spin); main may reentrantly take its own P and wait for others. **Progress invariant:** a synchronous topology mutation that remains applicable must eventually obtain phase exclusion; repeated main phases may not starve it. Implementation **BLOCKED ON TOPOLOGY-CALLBACK-001** |
| TOPOLOGY-CALLBACK-001 | AUDITED. The add/remove path is AE2 / addon bookkeeping only: no foreign capability code (the storage bus mounts a pre-built handler), no wait for main, no synchronous topology or recursion. NEW: structural service mutations outside topology (provider `requestUpdate`, `StackWatcher`, `P2P updateFreq`) must join the P(G) set. Owner choice needed: P or defer-safe handoff |
| Selective energy-only unpin | **BLOCKED on GRID-PHASE-001** too: region-thread block-entity removal reaches `Grid.remove` |
| LEGACY-ORIENTATION-DISK-001 | **CLOSED / VERIFIED** (`dad15e74b`) |
| LEGACY-ORIENTATION-LIVE-001 | OPEN / FAIL-CLOSED (owner-routed bounded migration requirement recorded; no primitive now) |
| (former LEGACY-ORIENTATION-001 row) | disk / chunk-load path: **IMPLEMENTED + fixtures PASS** (`dad15e74b`; client-side and visual neighbour shapes not verified) (migration in `promotePendingBlockEntity`). Live-world `loadTag` mutation: **REJECTED**. Owner-routed bounded live migration: APPROVED DIRECTION; record the requirement and do not add a primitive now. Until then the live-world case is fail-closed: detect, emit a diagnostic, no automatic write |
| SOLARFLUX-COMPAT-001: cross-mod shared mutable state | OPEN / gate 8 BLOCKED |
| shadow / canary (pin still enabled) | PENDING |
| final selective/full unpin review | PENDING |

## LEGACY-ORIENTATION-001: owner decision
| Option | Ruling |
|---|---|
| β first owner tick | REJECTED (refuted by the affected-BE audit) |
| **pre-publication chunk-load migration** | **APPROVED DIRECTION.** Convert the legacy representation in chunk-local state before the chunk or block entity is published to normal runtime. No `onReady → setBlockAndUpdate` in a published world |
| Core owner-local block write | FALLBACK, only if AE2 migration fundamentally needs a live-world `setBlock` |
| main under the owner's chunk lock | LAST RESORT: it adds a Core synchronization primitive and a lock-order contract for a legacy-only case |

**Required before implementation: LEGACY-ORIENTATION-001.1 audit.**
1. The exact load / deserialization path.
2. The moment the chunk and block entity are published.
3. Whether the BlockState and block-entity data can be changed before publication.
4. That no normal neighbour update or event machinery runs during it.
5. That a save after the migration writes the new format.

## SolarFlux: owner decision
- **Do not extend the AE2 domain.** The ownership boundary is SolarFlux's energy storage.
- Classification: **CROSS_MOD_SHARED_MUTABLE_STATE**.
- Needed: a narrow audit of every access to the panel's energy: region tick, Forge Energy
  receive/extract, the AE2 callback, other capability users, load/save, setters and admin paths.
- **Desired fix:** every mutation of the panel's internal energy state goes through one
  linearizable SolarFlux-owned primitive.
  - The SolarFlux lock is never held across an external Forge Energy or AE2 callback, to avoid
    `S(panel) ↔ AE2 domain` inversion.
  - Read → external transfer → commit uses capture / revalidate / commit, or reservation.
  - A point patch of SolarFlux itself is acceptable if it is cleaner.
- Until then: SolarFlux gate BLOCKED. Neither AE2's global G nor an AE2 pin resolves this race.

## Order of work
1. QUEUE-LIFE-001 deterministic fixture.
2. QUEUE-STAGING-001 real-producer integration fixture.
3. LEGACY-ORIENTATION-001.1 load-path audit.
4. SOLARFLUX-COMPAT-001 audit.
5. Shadow/canary with the pin still enabled.
6. Only then review a selective or full unpin.

## Architecture note (owner, 2026-10-14)
`nestworld-compat-ae2` has no `net.nestworld.modapi` and no Core imports, and no Core compile
dependency. It is an ordinary Forge compatibility jar, not a hidden Core plugin. This boundary is
intended and is to be kept.

## GRID-PHASE boundary (owner, 2026-10-14) and SERVICE-MUTATION-001
| Item | Status |
|---|---|
| P(G) topology exclusion | APPROVED |
| Storage / Crafting `requestUpdate` staging (coalesced per grid, provider, kind) | APPROVED |
| `StackWatcher` semantics | AUDITED: phase-deferred → staging (FIFO per watcher) — proposed |
| P2P `updateFreq` semantics | AUDITED: synchronous postcondition. The real race is with transfer readers, so it falls outside GRID-PHASE → AE2-COMPAT-P2P-001 — proposed |
| Startup compatibility manifest | APPROVED. Allowlist by mod id, mod version, fully qualified class name and compat contract version; unknown → eligibility UNKNOWN → DEGRADED / no unpin. P(G) is **not** disabled by unknowns: no fail-open |
| Mechanism classes | SYNCHRONOUS STRUCTURAL → P(G); PHASE-DEFERRED INVALIDATION → staging; UNKNOWN → fail-closed |

## Owner decisions, implementation round (2026-10-14)
| Item | Decision |
|---|---|
| P(G) | **APPROVED FOR IMPLEMENTATION**: non-main all-or-nothing tryLock, bounded backoff, rediscover / revalidate; main blocks; progress instrumentation (`p_try_failures`, `p_retry_count`, `p_wait_ns`, `p_max_retry`, `p_writer_starvation_warning`) |
| Storage / Crafting `requestUpdate` | STAGING APPROVED, coalesced per (grid, provider, kind); deterministic boundary test at the drain cut |
| `StackWatcher` | FIFO STAGING APPROVED, **no coalescing** in v1; destroyed at drain → drop |
| Immediate execution while the relevant phase is active | **FORBIDDEN**, even from main. Normative: *mutations published while a service phase is active never alter that phase's structural view; they become visible at the next permitted phase boundary* |
| Startup manifest | APPROVED FOR IMPLEMENTATION. Checked before the layer runs NORMAL. Unknown → `COMPAT_MANIFEST_UNKNOWN_PROVIDER mod=… class=… interface=…` → eligibility failure → DEGRADED / retain pin |
| AE2-COMPAT-P2P-001 | NEW SEPARATE OPEN finding (CONFIRMED FROM CODE). `updateFreq` under P(G) REJECTED as insufficient. Direction: a dedicated per-grid P2P state guard (snapshot reads; linearizable `newFrequency` allocation as one writer transaction; never held across external transfer) |
| Selective energy unpin | GRID-PHASE no longer blocks it once implemented. P2P-001 blocks it only if FE P2P runs region-parallel in scope, so FE P2P has to be excluded by **real enforcement**, not documentation |

## Implementation round result (2026-10-14)
| Item | Status |
|---|---|
| P(G) + Storage / Crafting staging + StackWatcher staging + startup manifest | **IMPLEMENTED, fixtures PASS** (docs/compat/AE2-COMPAT-GRID-PHASE-001-RESULTS.md); deterministic OVERLAP negative control FAILS as required; review round done → CLOSED / VERIFIED (owner, 2026-10-15) |
| region-thread topology stress | 0 exceptions, but **not discriminating** (also 0 without P); OVERLAP is the evidence |

## Review round result (2026-10-15)
| Item | Status |
|---|---|
| **GRID-PHASE-001** | **CLOSED / VERIFIED** (owner criterion: all review items fixed, regression clean, negative controls discriminate). docs/compat/AE2-COMPAT-GRID-PHASE-001-RESULTS.md § Review fixes |
| ENERGY-001, T2, QUEUE-001, LEGACY-DISK | unchanged (regression PASS); not refactored |
| Still open | AE2-COMPAT-P2P-001, SOLARFLUX-COMPAT-001, LEGACY-ORIENTATION-LIVE-001 (fail-closed), shadow / canary, unpin review |
| **Frozen** (owner, 2026-10-15) | ENERGY-001, T2 / Design-1.5, QUEUE-001, GRID-PHASE-001: not to be touched unless a regression appears. Any change needs its own regression evidence |
| Order (owner, 2026-10-15) | P2P-001 → SOLARFLUX-COMPAT-001 → canary → selective unpin → addon v1.0-rc1 |
| AE2-COMPAT-P2P-001 | **AUDITED (2026-10-15), STOP for review**: `docs/compat/AE2-COMPAT-P2P-001-AUDIT.md`. H1 map races + `newFrequency`/bind gap; H2 per-tunnel `accessDepth` recursion counter; **H3 cross-region capability forwarding (ghost-zone detached copy → loss; deep → foreign-thread mutation), present with the pin on**; audit gap in TOPOLOGY-CALLBACK-001 (Redstone P2P synchronous neighbour update under P(G)). Q1–Q5 **decided** (audit §9): H3 for RC = fail-closed cross-owner refusal via a narrow read-only Core ownership query; generic remote call REJECTED; P2P-9 / P2P-10 first; H1/H2 close independently (P2P-001 stays OPEN, blocked by H3); unknown P2P subclass → manifest-ineligible **and** forwarding fail-closed. **Pin ≠ P2P safe** (normative) |
| P2P-9 / P2P-10 evidence (2026-10-15) | `docs/compat/AE2-COMPAT-P2P-001-EVIDENCE.md`. **H3 CONFIRMED by run**: 9A conserved; 9B ghost → energy destroyed (FE) / NPE into caller (item); 9C → live cross-owner mutation. **P2P-10**: the Redstone P2P neighbour notify runs on main **under P(G)** (inside `GridPhaseGate.topology` → `Ae2Kernel.deliver`), targeting another region. Correction: TOPOLOGY-CALLBACK-001's "not under P" is wrong for kernel-deferred notifications. REDSTONE-P2P-CALLBACK-001 proposed. STOP for review |
| CALLBACK-UNDER-P-001 | **OPEN, CONFIRMED MECHANISM** (owner, 2026-10-15): deferred topology callbacks are delivered after T0/domains release but **with P(G) held**. Manifestation: REDSTONE-P2P-CALLBACK-001 CONFIRMED. Inventory and classify (internal vs external/world/capability/event) before any fix. GRID-PHASE-001 is not reopened. `docs/compat/AE2-COMPAT-CALLBACK-UNDER-P-001.md` |
| P2P sub-gates | P2P-MAP-STATE-001 (H1) OPEN → implementing · P2P-ACCESS-DEPTH-001 (H2) OPEN · P2P-FORWARDING-001 (H3) CONFIRMED / OPEN · AE2-COMPAT-P2P-001 OPEN. No unpin after H1 |
