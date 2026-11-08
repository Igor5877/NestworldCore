# WP1 — AE2CompatibilityRegistry: results (2026-11-08)

Status: **CLOSED / VERIFIED (engineering)** — acceptance "OLD gate decision == NEW registry decision, 0 mismatches" met.

| check | result |
|---|---|
| registry equivalence test (views == snapshot of pre-registry tables: power ALLOWLIST 32 classes, AE2 releases 2, runtime participants 29, manifest rows in file order; every build registered, no duplicate) | **5/5 PASS** |
| negative control (one participant removed) | **2/5 FAIL** as required (allowlist, runtime barrier) |
| E1 owner-pack copy (`/root/pi-smoke`), addon 0.7.5 vs 0.7.6 | identical: gate PASS, manifest ELIGIBLE |
| E2 Airis rig (H401BEDOM, AE2 15.4.10) | identical: gate PASS, manifest ELIGIBLE |
| E3 owner-pack copy with a valid crazyae2addons jar of a different SHA (`b8279a59…`, one extra entry) | identical: gate FAIL, the same 10 `EnergyStorage*BE comes from … audited …` problems (11 lines) |
| activation-mode differences | **0** |

Only difference in raw output: the scanned-class count (+12 = the addon's own new classes; not a decision input),
normalised in the comparison. First E3 attempt invalid (a zero byte appended to the jar broke archive reading before
the gate — not addon evidence); redone with a valid tampered jar. Evidence: `/root/perf1-evidence/wp1-regression/`.
