# AE2-COMPAT-ALLOWLIST-002 — owner pack project-infinity (2026-11-07)

Status: **AUDIT + SMOKE (engineering)**. Addon 0.7.2. Production / canary approval: owner.

The startup gate refused addon 0.7.1 on the owner's pack (18 `IAEPowerStorage` closure classes). Criterion unchanged
from ALLOWLIST-001: a participant is admitted when its power path executes only AE2's audited code — an AE2 power family
subclass with no override of a power-path method — or after a bytecode audit of its own power path. Power-path set
derived from AE2 source (`IAEPowerStorage`, `IEnergySource`, `IExternalPowerSink`, `AEBasePoweredBlockEntity`,
`EnergyCellBlockEntity`). Tool: `/root/ae2-pack-audit-2026-11-07/audit.py` (javap chain + declared-method screen).

| classes | release (sha256) | chain | power-path overrides | verdict |
|---|---|---|---|---|
| ExtendedAE `TileCaner`, `TileCircuitCutter`, `TileExCharger`, `TileExInscriber` | expatternprovider 1.20-1.4.21-forge (`30fc8abe…`) | → `AENetworkPowerBlockEntity` | none | CONFIRMED admissible (same as 1.4.15) |
| AdvancedAE `QuantumCrafterEntity`, `ReactionChamberEntity` | advanced_ae 1.3.6-1.20.1 (`e216f95a…`) | → `AENetworkPowerBlockEntity` | none | CONFIRMED admissible |
| crazyae2addons `EnergyStorage{1k..256m}BE` (10) | crazyae2addons 2.6.2 (`6a0e023b…`) | → AE2 `EnergyCellBlockEntity` | none (`emitPowerEvent` is private in both — not an override); own `stored` field written in the constructor only (dead); variants differ only in the capacity constant | CONFIRMED admissible |
| neoecoae `ECOIntegratedWorkingStationBlockEntity` | neoecoae 20.4.2 (`e6ba909b…`) | → `AENetworkPowerBlockEntity` | none | CONFIRMED admissible |
| neoecoae `ECOEnergyCellBlockEntity` | neoecoae 20.4.2 | own `IAEPowerStorage` + `IExternalPowerSink` | own implementation | STRONGLY INDICATED admissible: bytecode = AE2 `EnergyCellBlockEntity` pattern — own `StoredEnergyAmount`; side effects only `setChanged`, `TickManager.alertDevice`, grid `postEvent`, `markForUpdate` deferred via `server.execute`; `injectExternalPower` = unit conversion + `injectAEPower`; no foreign callback, no lock |

Gate change: `ALLOWLIST` maps a class to a list of audited releases (any match admits); runtime barrier
`Ae2Compat.ALLOWED_PARTICIPANTS` gains the 12 new concrete classes. Compatibility manifest NOT extended: 39 entries
(appflux, appliede, mae2, neoecoae, crazyae2addons, molecularmanipulator, bmaddon, extendedae_plus 1.6.3, ExtendedAE
1.4.21 providers) → DEGRADED (global serialization, pins kept) — protective, no fail-open.

## Smoke — isolated copy `/root/pi-smoke` (127.0.0.1, no players, world snapshot, free-running ON)

```
gate                     PASS (105356 classes, 9.3 s); manifest NOT ELIGIBLE (39) → mode DEGRADED
mixin errors             0
10 min, free-running     0 crash reports, 0 tick failures; TPS 11 → 18–20; 2 regions
addon                    unauditedPaths 1 (the manifest itself); teardown/member offDomain 0
negative reference       same world WITHOUT the addon (live server 18:57): AE2 TickManagerService PriorityQueue NPE every
                         tick ~1 min after start, 6 failures/60 s → crash shutdown (single observation — CORRELATED)
other                    10 entity tick CMEs: Botania / botanicalextramachinery mana spark WeakHashMap (not AE2)
```
Evidence: `/root/perf1-evidence/infinity-2026-11-07/smoke-072/latest.log`; artifact
`/root/canary-artifacts/ae2-compat-0.7.2-dev/` (sha256 `b34068df…`).
