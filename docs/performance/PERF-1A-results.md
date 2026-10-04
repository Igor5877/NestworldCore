# PERF-1A — official results (Core 47.4.264, W1)

**Status: PARTIAL OFFICIAL DATASET (2026-10-04).** N = 1 and N = 2 are complete; N = 4, 8, 12 and
16 are blocked on host capacity. These are official points, measured with the frozen harness
`d5321f70e` and the series wrapper `76bb661b8`, under `PERF-1-tz.md`.

**Claims are limited to N ≤ 2. This is not a scaling curve.**

## Setup (per run in `series.json` and `<run>-metadata.json`)
- **Core:** 1.20.1-47.4.264, Git-Commit `dab499ff`, Git-Dirty false, Source-Patch-Integrity
  MATCH, Patch-Set-Digest `191855784b…`, server jar sha256 `57cd6a51…`.
- **Workload:** W1, 16 cells × 576 cows (9 216), 16-region marker-built topology, snapshot sha256
  `8ecbd07f…`.
- **CPUs:** benchmark on CPU 2 (N=1) and CPUs 2,3 (N=2), one per physical core; housekeeping
  (harness and JIT) on CPU 1. SMT siblings 19–21 are idle. The pre-boot gate and the in-window
  gates passed on every run.
- **Order:** N1-r1, N2-r1, N2-r2, N1-r2, N1-r3, N2-r3.
- **Evidence:** `/root/perf1-evidence/perf1a-official-n12/`. Each run has JSON, metadata, JFR,
  GC log and console log; the inputs are kept beside them.

## Runs (all VALID)
| Run | primary ticks/s | secondary | TPS | MSPT mean / p50 / p95 / p99 | GC pauses | foreign / siblings |
|---|---|---|---|---|---|---|
| N1-r1 | 38 154 | 38 297 | 4.16 | 240 / 226 / 244 / 304 | 15, 0.79 s | 0.01% / 0.3% |
| N1-r2 | 39 217 | 39 385 | 4.27 | 234 / 220 / 240 / 300 | 15, 0.78 s | 0.07% / 0.8% |
| N1-r3 | 39 505 | 39 662 | 4.30 | 232 / 218 / 243 / 293 | 15, 0.78 s | 0.01% / 0.3% |
| N2-r1 | 76 616 | 77 127 | 8.37 | 120 / 112 / 120 / 146 | 30, 0.82 s | 0.17% / 0.6% |
| N2-r2 | 76 936 | 77 639 | 8.42 | 119 / 111 / 120 / 150 | 29, 0.78 s | 0.26% / 0.5% |
| N2-r3 | 73 561 | 74 078 | 8.04 | 124 / 116 / 129 / 157 | 27, 0.70 s | −0.18% / 1.4% |

Every run also had:
- deferred = 0;
- split = 0 and merge = 0 (layout identical);
- region queue depth max ≤ 1;
- primary ≈ secondary within 0.8%.

Per-run resource figures:
- region-thread CPU: 0.82 of N in every run;
- main thread: 0.07 (N=1) and 0.05 (N=2);
- JIT: 28–31 s of housekeeping CPU per 300 s window;
- allocation: about 450 MB/s (N=1) and 625–705 MB/s (N=2).

## Scaling (N ≤ 2 only)
| N | T(N) ticks/s | S(N) | E(N) | median p95 MSPT | CV |
|---|---|---|---|---|---|
| 1 | 38 959 | 1.000 | 100.0% | 243 ms | 1.5% (ACCEPT) |
| 2 | 75 704 | 1.943 | 97.2% | 120 ms | 2.0% (ACCEPT) |

## Attribution notes (observations; no conclusion)
JFR `jdk.JavaMonitorEnter` events ≥ 20 ms, summed over all threads, per 300 s window:
- N=1: 221–240 s;
- N=2: 162–174 s.

More than 99.8% of it is `ConcurrentHashMap$TreeBin` in `TickAttribution.recordChunkCost`
(PERF-FINDING-001), about 5 500–6 200 events per run. At N ≤ 2 this coexists with E(2) = 97%,
so it does not visibly limit throughput there.

Whether it becomes the scaling wall at higher N is exactly what N = 4 … 16 must show. With N = 1
cores and 16 runnable region threads, part of the blocked time is lock-holder preemption rather
than demand.

## Remaining for the PERF-1A DoD
- **N = 4, 8, 12, 16.** These need N + 1 quiet physical cores with idle siblings. The host has
  freed cores 1–3 only. The series wrapper resumes with `--n` and the next CPUs once they are
  free; the method stays unchanged.
- Negative control W2 (single hotspot).
- Scaling-wall attribution, if a wall appears.
