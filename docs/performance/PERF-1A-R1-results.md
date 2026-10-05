# PERF-1A Revision 1 — official W1 results, N = 1 / 2 / 4 / 8 (Core 47.4.264)

**Status: PERF-1A W1 (N = 1 / 2 / 4 / 8) — PASS (owner, 2026-10-05). 12 / 12 VALID, CV ≤ 5% at
every N.**

Owner decisions:
- **N = 12 / 16 → PERF-1A-EXTENDED.** Optional, run later when those cores can be freed at no
  operational cost.
- **PERF-FIX-001: not now.** TickAttribution is not shown to be the wall up to N = 8; the
  MEASURE-FIRST status was justified.
- **Wording.** Throughput grew 8.41× from 1 to 8 cores and the cost per unit of work fell by 18%.
  The cause of the cost reduction is not yet proven. Do not present this as "Core scales better
  than linearly".
- **Next:** W2 negative control (pilot / freeze → official), then PERF-1B, then
  MOD-API-REALWORLD-1.

This dataset must never be combined with the invalidated pre-revision dataset in
`PERF-1A-results.md`.

## Setup
| Item | Value |
|---|---|
| Harness | frozen at `cc0a19510` (owner re-freeze `cf2e88b9d`); series wrapper `76bb661b8` |
| Core | 1.20.1-47.4.264, `dab499ff`, Git-Dirty false, Source-Patch-Integrity MATCH |
| Workload | W1 Revision 1: 16 sealed cells × 2 304 cows with AI = 36 864 entities; snapshot `4d87bd52` (walls verified) |
| CPUs | benchmark on 2 … 9 in core order (N uses the first N); housekeeping (harness + JIT) on 1; SMT siblings 19–27 idle |
| Order | series `perf1a-r1-official`: 1→8, 8→1, 1→8 |
| Replacement runs | series `perf1a-r1-official-b`: one N=4 and one N=8 run, after host quiet was re-established |
| Evidence | `/root/perf1-evidence/perf1a-r1-official{,-b}/` (JSON, metadata, JFR, GC and console logs, inputs); the valid-run index is `r1-valid-runs.json` |

**VOID runs (kept as evidence, not overwritten):**
- **N4-r3:** the pre-run interference gate saw foreign load on CPUs 4–5 and siblings 22–23 for
  30 minutes.
- **N8-r3:** SMT siblings 22–24 were 3.2–6.0% busy during the window. Its throughput was 251k
  against about 275k in valid runs (≈ −10%), which independently validates the interference
  gate.

## Scaling
| N | T(N) ticks/s | runs | S(N) | E(N) | CV | TPS | MSPT mean / p50 / p95 / p99 |
|---|---|---|---|---|---|---|---|
| 1 | 32 694 | 33 117 / 33 923 / 31 042 | 1.000 | 100.0% | 3.7% | 0.88 | 1 134 / 1 050 / 1 475 / 3 501 ms¹ |
| 2 | 69 790 | 70 788 / 67 523 / 71 059 | 2.135 | 106.7% | 2.3% | 1.89 | 526 / 489 / 701 / 792 ms |
| 4 | 137 230 | 139 584 / 138 036 / 134 070 | 4.197 | 104.9% | 1.7% | 3.72 | 268 / 248 / 364 / 416 ms |
| 8 | 274 923 | 273 014 / 286 761 / 264 996 | **8.409** | **105.1%** | 3.3% | 7.45 | 134 / 127 / 145 / 160 ms |

¹ N=1 has only two 100-tick MSPT blocks per window (accepted limitation); read its tail MSPT with
caution.

Every valid run had:
- deferred = 0; split = 0 and merge = 0 (identical topology);
- every region owning and ticking exactly its cell in every sample, and all cows inside their
  cells at the end;
- region queue depth max = 0;
- primary and secondary within 0.4%;
- foreign load ≤ 0.6% and siblings ≤ 2.2%.

## Attribution (TZ §12 order; measured facts first)
1. **Dispersion.** The superlinearity survives the third repetition: E = 104.9–106.7% for every
   N ≥ 2, and every CV is ≤ 3.7%. It is systematic, not an outlier.
2. **Per-tick cost falls with N.** Benchmark CPU per 1 M executed ticks:

   | N | total | region threads | used / available |
   |---|---|---|---|
   | 1 | 30.4 s | 24.6 s | 99% |
   | 2 | 28.1 s | 24.0 s | 98% |
   | 4 | 27.7 s | 24.1 s | 95% |
   | 8 | 24.9 s | 22.5 s | 85% |

   - **E > 100% is explained by the cost side:** an entity tick costs about 18% less CPU at N=8
     than at N=1. Utilisation of the benchmark cores falls at the same time.
   - **Emerging limiter:** at N=8, 15% of the benchmark CPU is idle. This is not lock blocking
     (see 3); it is consistent with the per-tick barrier waiting for the slowest region plus
     serial main-thread work (main 0.05 → 0.16 cores). It is not yet attributed further.
3. **PERF-FINDING-001.** JFR monitor blocking ≥ 20 ms, all in
   `TickAttribution.recordChunkCost` (100% of recorded monitor blocking):

   | N | blocked per 300 s window | events |
   |---|---|---|
   | 1 | 385 s | 8 377 |
   | 2 | 274 s | 9 102 |
   | 4 | 122 s | 4 646 |
   | 8 | 7 s | 281 |

   The contention is worst where cores are fewest. That fits lock-holder preemption: 16 runnable
   region threads share N cores, so a preempted holder stalls the others. It therefore inflates
   the cost of the N=1 baseline more than of large N. **Up to N=8 it is not the scaling wall.**
   Whether it explains the superlinearity is a candidate, not proven. An A/B with it removed
   (PERF-FIX-001) would quantify it.
4. **Kernel share of JVM CPU** (JFR `jdk.CPULoad`): 3.1% → 3.1% → 2.2% → 1.3%, consistent with
   less contention-driven parking at higher N. JFR `ThreadContextSwitchRate` is host-wide, so it is
   not used.
5. **GC / JIT.**
   - GC pause per window is flat (1.57–1.67 s).
   - GC CPU share: 0.066 cores at N=1 against 0.115 at N=8 for 8.4× the work, so smaller per tick.
   - JIT runs on housekeeping, 24–30 s per window, outside N.
6. **Cache / SMT / NUMA.** Not investigated yet. The host is single-socket (one NUMA node), so
   NUMA is excluded. Per-core cache residency (16 regions thrashing one core's L1 / L2 at N=1) is
   an open hypothesis.

## Reading (owner rules: no claims beyond the data)
- On W1, Core 47.4.264 scales **8.41× on 8 dedicated physical cores** (E = 105%). Throughput is
  limited by work, not by the 20 TPS cap (TPS 7.45 at N=8).
- The above-linear efficiency comes from a costlier N=1 baseline per unit of work. Lock
  contention in TickAttribution, worst at N=1, is the strongest measured candidate.
- The first sign of a scaling limiter is idle benchmark CPU at N=8 (85% utilisation).
  N = 12 / 16 are needed to see whether it turns into a wall.
