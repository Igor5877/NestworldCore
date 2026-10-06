# PERF-1A W2 — official results, single unsplittable hotspot (negative control), Core 47.4.264

**Status: PERF-1A W2 — negative control PASS by the owner-approved reading rule
(S(8) = 1.404 ≤ 1.5, single-domain invariant intact). 12 / 12 VALID runs (3 per N), CV ≤ 1.7%.**
Pending owner acceptance.

W2 is a negative control, not a performance target (`docs/contracts/PERF-1A-W2-spec.md`). The question
was whether adding CPUs to one ownership domain that cannot be split leaves throughput flat. It
does: beyond N = 2 the extra cores add nothing.

## Setup
| Item | Value |
|---|---|
| Harness | frozen `tests/perf1/perf1.py` (W2 additions `d18a4c2b3`; interference policy v2-shared `82b9c3527`) |
| Core | 1.20.1-47.4.264, `dab499ff`, Git-Dirty false, Source-Patch-Integrity MATCH |
| Workload | 7 680 AI cows in one chunk column (120 layers × 64 sealed compartments); snapshot `e7858bac`; W1 16-region topology, hotspot in cell 0 |
| CPUs | benchmark on 2 … 9 in core order (N uses the first N); housekeeping (harness + JIT) on 1; SMT siblings 19–27 |
| Interference policy | v2-shared (`PERF-HOST-INTERFERENCE-POLICY.md`): measured and recorded; VOID only on material interference, by explicit review |
| Order | series `w2-official`: 1→8, 8→1, 1→8 |
| Replacement runs | series `w2-official-b`: one N=2 and one N=4 run, on a quiet host (all bench CPUs and siblings ≤ 0.2% before start) |
| Evidence | `/root/perf1-evidence/w2-official{,-b}/`; review decisions `w2-official/review.json`; valid-run index `w2-valid-runs.json`; combined validator output `w2-valid-validation.txt` |

**Metadata reconciliation.** `series.py` at `82b9c3527` parsed the validator line incorrectly, so
every per-run `metadata.json` of `w2-official` carries `"verdict": "VOID"` (defect fixed in
`01892c5e9`). The raw files are not rewritten. `w2-official/metadata-reconciliation.json`
(reason `SERIES_METADATA_PARSER_DEFECT`) supersedes only their verdict fields, from the frozen
validator output `validation-report-reviewed.txt`. `w2-official-b` ran with the fixed parser.

**Review decisions (investigate tier; kept as evidence, never deleted):**

| Run | Telemetry (mean / 5 s burst) | Throughput vs peers | Decision |
|---|---|---|---|
| N8-r1 | 6.0% / 25.2% | −1.2% vs N8-r3 | VALID, no material effect |
| N8-r2 | 6.0% / 21.6% | −0.1% vs N8-r3 | VALID, no material effect |
| N2-r2 | 5.3% / 44.3% (sibling) | −4.7% vs peer mean, three times the peer spread | VOID, replaced |
| N4-r2 | 21.7% / 92.7% (siblings 22–23) | −9.5% / −8.8% vs N4-r1 / N4-r3 | VOID, replaced |

## Scaling
| N | T(N) ticks/s | runs | S(N) | E(N) | CV | TPS | MSPT mean / p50 / p95 / p99 |
|---|---|---|---|---|---|---|---|
| 1 | 21 399 | 21 657 / 21 382 / 21 156 | 1.000 | 100.0% | 1.0% | 2.78 | 356 / 345 / 400 / 774 ms |
| 2 | 29 697 | 29 458 / 30 406 / 29 228 | 1.388 | 69.4% | 1.7% | 3.86 | 257 / 251 / 282 / 506 ms |
| 4 | 30 347 | 30 522 / 30 305 / 30 213 | 1.418 | 35.5% | 0.4% | 3.95 | 250 / 247 / 275 / 315 ms |
| 8 | 30 047 | 29 806 / 30 159 / 30 177 | **1.404** | 17.6% | 0.6% | 3.91 | 255 / 249 / 286 / 405 ms |

CV is the validator's. T(4) and T(8) differ by 1.0%, which is within run-to-run spread. Every
valid run had primary and secondary within 0.12%, deferred = 0, and region queue depth max = 0.

## Structural attribution (measured in every valid run)
| N | entities in one region | owned = ticked | splits / merges | hotspot region thread | main thread | benchmark CPU per 1 M ticks |
|---|---|---|---|---|---|---|
| 1 | 1 region in every sample | 7 680 = 7 680 | 15 layout splits, 0 during the window | 0.77 core | 0.09 core | 46.4 s |
| 2 | same | same | same | 0.95 core | 0.08 core | 38.7 s |
| 4 | same | same | same | 0.95 core | 0.07 core | 38.1 s |
| 8 | same | same | same | 0.96 core | 0.08 core | 38.8 s |

1. **One ownership domain.** All 7 680 entities stay in one region for the whole window, at
   every N. No split or merge happens, so there is never a second execution lane for this work.
2. **The domain saturates one core.** From N = 2 on, the hotspot's region thread uses
   0.95–0.96 of a core and throughput stops growing. The added cores remain idle (process CPU
   ≈ 1.17 cores at N = 8).
3. **The only real gain is N = 1 → 2 (+39%).** At N = 1 the hotspot thread shares its single
   core with the main thread, GC and harness-side work (0.77 core for the region); at N = 2 it
   gets a whole core. CPU per 1 M ticks also falls by 17% between N = 1 and N = 2 and then
   stays flat. This is the same N = 1 penalty seen in W1 (30.4 → 28.1 s); its cause is not
   attributed here.

## Reading together with W1
| Workload | Ownership domains | S(8) |
|---|---|---|
| W1 Revision 1 | 16 independent regions | 8.409 |
| W2 | 1 unsplittable region | 1.404 |

The same harness, CPUs, JVM flags, topology and Core produce 8.4× when the work is split across
independent ownership domains and about 1.4× when it is not. The W1 gain is therefore attributable
to region-level parallelism, not to JVM, GC or JIT effects of running on more cores.

## Measurement note: derived foreign load
`foreign_on_bench_pct` (host busy on benchmark CPUs minus process CPU) came out negative,
which is physically impossible, at N = 2 (−4.5 … −5.9%).
- **Thread placement** (`/root/perf1-evidence/diag-cpu-accounting/`, replacement N2 run): every
  non-JIT JVM thread had affinity 2–3; the JIT threads were on 1 and are already subtracted. The
  JVM did not run outside its CPU set.
- **Controlled diagnostic** (CPU 9, not part of the dataset):
  - a 1 ms period duty cycle at 50% / 25% / 10% shows `/proc/stat` (lxcfs) per-CPU busy
    8–16% below the process's precise runtime (schedstat = utime + stime);
  - at 100% duty, or with a 20 ms period, the two agree.
- **Conclusion:** per-CPU busy from `/proc/stat` under-reports sub-tick busy / idle alternation
  in this environment. At N = 1 the core is saturated, so the bias is absent. At N = 2 the region
  thread parks and wakes at sub-millisecond granularity, and the bias appears.
- **Consequence:** the derived metric is excluded from authoritative interference attribution
  whenever it is negative. Source priority is set by the owner rule recorded in
  `PERF-HOST-INTERFERENCE-POLICY.md`. No W2 decision depended on it; the reviews above used
  throughput deviation.
