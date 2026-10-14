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
| GRID-PHASE-001: structural topology mutation races an active grid phase (the pivot NPE was its first runtime manifestation) | CONFIRMED. Direction: P(G) phase/topology exclusion, order `P → T0 → domains`. Implementation BLOCKED on the 001.2 audit (submitted). The pivot liveness lease is defense in depth only |
| Selective energy-only unpin | **BLOCKED on GRID-PHASE-001** too: region-thread block-entity removal reaches `Grid.remove` |
| LEGACY-ORIENTATION-001 | OPEN (direction decided, see below) |
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
