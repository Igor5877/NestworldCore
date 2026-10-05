# PERF — CPU limit and host-interference policy (owner, 2026-10-05)

**Status: NORMATIVE from PERF-1B official onward.** It is not applied retroactively.

## Context
The performance rig is a **shared, production-like host**, not a dedicated laboratory machine.
Small background load on the benchmark CPUs and their SMT siblings is expected and allowed.
Production resources are **not** freed or re-pinned just to get a perfectly clean benchmark host.

## CPU limit (unchanged)
| Role | CPUs |
|---|---|
| Benchmark | `N ≤ 8` physical cores, CPUs 2–9 |
| Housekeeping (harness, JIT) | CPU 1 |
| SMT siblings | CPUs 19–27 |

N = 12 / 16+ is neither tested nor requested.

## Interference: measured and recorded, not an automatic gate
- About 0.1% background CPU is **not** a VALID condition.
- A small, stable background load is acceptable if it:
  - creates no evident systematic bottleneck on one benchmark core;
  - does not saturate the benchmark CPUs;
  - produces no large sustained bursts;
  - keeps results reproducible;
  - does not change the qualitative scaling curve.
- Interference is **measured and recorded for every run**: benchmark CPUs and siblings, mean and
  burst.

| Level (mean, per benchmark CPU or sibling) | Meaning |
|---|---|
| ≤ 3% | normal |
| 3–5% | warning; the run may be VALID |
| > 5% | investigate |
| bursts | assessed separately |

These levels are **not** an automatic PASS / VOID classifier.

**A run becomes VOID only on material interference**, meaning evidence that external load could
have materially distorted the result:
- a strong burst;
- significant occupancy of a benchmark CPU or sibling;
- abnormal throughput deviation;
- high run-to-run variance;
- a concrete foreign process competing with the workload.

Small background load alone never voids a run.

## Primary protection of the official dataset
- repetitions;
- the CV gate;
- alternating N order;
- per-run host telemetry;
- preservation of outliers and VOID runs, which are never deleted.

## Scope
| Dataset | Rule |
|---|---|
| **PERF-1B** | this policy applies to its pilot and official series |
| **PERF-1A W1 (Revision 1)** | stays as obtained under its frozen method (hard 3% gates); no retroactive reclassification of its VALID / VOID runs |
| **PERF-1A W2** | frozen at `d18a4c2b3` with the hard 3% gates and not yet run officially; owner to decide whether its official series runs under this policy (a W2 harness revision) or under its frozen gates |

## Implementation note
The frozen W1 / W2 validator (`tests/perf1/perf1.py`) keeps its hard gates for the datasets frozen
on it. The PERF-1B harness implements this policy:
- per-second host telemetry of the benchmark CPUs and siblings (mean and maximum burst);
- tier flags (normal / warning / investigate) instead of an automatic VOID;
- an explicit, recorded VOID decision with its reason when material interference is found;
- a pre-run check that records the host state but does not wait for laboratory quiet.
