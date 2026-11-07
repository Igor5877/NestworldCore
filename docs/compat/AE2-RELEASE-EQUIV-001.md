# AE2-RELEASE-EQUIV-001 — admitting AE2 15.4.11 to the nestworld-compat-ae2 gate

Status: **VERIFIED on the rig (engineering)** — addon 0.7.1, detector 0.1.1. Requested 2026-11-07 (the owner's pack ships
AE2 15.4.11). Production / canary: NOT APPROVED (unchanged).

## 1. Release diff (CONFIRMED)

Source: `git diff forge/v15.4.10 forge/v15.4.11` (AppliedEnergistics/Applied-Energistics-2), 7 commits, 6 files under
`src/main/java`. Bytecode: per-class `cmp` of the two release jars (15.4.10 `fbfee05c…0390fcc`, 15.4.11
`f2ff9ee5…c7d30f9e`) — **5 changed + 2 added classes**, exactly the 6 source files:

| class | change | addon relevance |
|---|---|---|
| `appeng/client/render/overlay/OverlayManager` | spatial anchor overlay rendering | none (client) |
| `appeng/core/localization/Tooltips` | huge byte amounts tooltip crash | none |
| `appeng/core/sync/packets/FillCraftingGridFromRecipePacket` (+ new `$DisplacedItem`) | crafting grid recipe fill | none |
| `appeng/server/testplots/TestPlots` (+ new `CraftingTerminalTestPlots`) | AE2 test plots | none |
| `appeng/me/cluster/implementations/CraftingCPUCalculator` | `verifyInternalStructure`: `(IAEMultiBlock) be` + `te == null \|\| !te.isValid()` → `instanceof CraftingBlockEntity` | none: `CraftingBlockEntity.isValid()` is constantly `true`, so the predicate is the same; no addon mixin targets this class; the detector only wraps the unchanged call site in `CraftingBlockEntity.updateMultiBlock` |

Unchanged: everything the addon mixes into or ports — `CraftingBlockEntity` (D4a port of `breakCluster`),
`CraftingCPUCluster`, `MBCalculator`, QNB / spatial pylon, grid node / connection, services, `blockentity/`, `me/`, `parts/`,
`helpers/`. Resources: only `mods.toml` version, `ko_kr.json`, and `ae2.mixins.refmap.json` key order (semantically equal).

## 2. Gate change (addon 0.7.1)

- `StartupGate.AE2_RELEASES`: audited base 15.4.10 + 15.4.11 with its exact jar sha256 and the set of classes changed vs
  the base. An AE2 class from a non-base release counts as audited (ALLOWLIST `IAEPowerStorage` closure and the
  compatibility manifest) only when it is NOT in that set; the release is refused when any mixin of this addon targets a
  changed class. Any other AE2 version / hash → FAIL, addon inactive (unchanged fail-closed behaviour).
- `mods.toml` dependency `ae2` `[15.4.10]` → `[15.4.10,15.4.11]`. Without this, Forge refuses to load the server at all
  (`Mod nestworld_compat_ae2 requires ae2 15.4.10` — negative control below); the gate remains the real check.
- Test-only detector `nestworld_ae2detect` 0.1.1: same dependency widening (it targets `MBCalculator`, unchanged).

Adding the next AE2 release: bytecode-diff vs the base, add an `Ae2Release` entry, re-run the matrix below.

## 3. Evidence — H401BEDOM (Core 47.4.401, fresh pristine world per run)

`/root/airis-2026-10-25/evidence-be-ready/rel-*`; invalid: `invalid/rel-pos-071-15411-*-detector-dep` (the detector's own
`[15.4.10]` dependency stopped Forge before the addon was reached — not addon evidence).

```
NEG  0.7.0 + AE2 15.4.11       Forge: "Mod nestworld_compat_ae2 requires ae2 15.4.10" — server does not start
REG  0.7.1 + AE2 15.4.10       gate PASS; teardown fixture PASS; tick failures 0; crash 0
POS  0.7.1 + AE2 15.4.11       gate PASS (56883 classes, ~5 s) in every run
  teardown fixture             PASS (CONCURRENT 37/37/37, old cluster claims 1; REBUILD new cluster untouched)
  S1 (D1)                      PASS (its 1 tick failure / crash report = the NEG phase reproducing S1 by design)
  neighbor (D2)                PASS
  endtick                      PASS
  lifecycle (scope 2/3)        PASS
  churn 90 s, production pins  S1/S2/S3 0; clusterDestroyOffMain 0; teardown offDomain 0; tick failures 0; crash 0
```

Not covered: the owner's full pack also ships ExtendedAE 1.4.21 / AdvancedAE 1.3.6 (audited 1.4.15 / 1.3.5) and AE2
add-ons absent from the manifest — the gate will still refuse there (addon inactive, pins stay) until those jars are
diffed the same way.
