# PERF-1A — scheduler scaling baseline (spec, DRAFT for owner review)

**Status (2026-10-05, owner): PERF-1A W1 Revision 1 — HARNESS VERIFIED / RE-FROZEN.**

| Item | Value |
|---|---|
| Frozen revision | `cc0a19510` |
| Workload | 16 isolated cells × 2 304 cows with AI = 36 864 entities |
| Snapshot | `4d87bd52` |
| Validation | pilot 7, 7/7 VALID |
| Primary official matrix | N = 1 / 2 / 4 / 8 |

- **Previous W1 dataset: INVALID.** It must not be combined with Revision 1 results.
- **MSPT limitation (accepted and disclosed):** an N=1 window holds only two complete 100-tick
  aggregation blocks, so p95 / p99 MSPT at N=1 has coarse granularity. Primary throughput uses
  the full 300 s window.
- **No further change.** An unexpected curve is a result to investigate, not a reason to adjust
  the benchmark. A ceiling at N=4 or N=8 (TickAttribution, NUMA / cache, lock contention) is a
  Core PERF finding, not automatically a harness defect.

History: VERIFIED / FROZEN at `d5321f70e` (2026-10-04), voided by the W1 defect (§10). The freeze criterion
was met in pilot round 6 (§9), and the owner accepted the §9 method changes in the PERF-1 TZ
(`PERF-1-tz.md`). The official matrix runs through `tests/perf1/official/series.py`. N ≥ 4 is
blocked until the host frees more quiet physical cores. No Core change. Reference build
47.4.264 (`dab499ff`, trusted artifact, canary 4/4).

## Question
Does the region scheduler scale with physical cores in its best case: N independent, equally
loaded regions with no cross-region interaction? The result separates "Core does not scale"
from "Core scales, but a given workload does not parallelise".

Gate rule:
- If PERF-1A does not scale, stop and find the bottleneck.
- If it scales, run PERF-1B (cross-region), then the unsplittable hotspot (negative control),
  then harness B (the external benchmark-mod over the public Mod API).

**Measurements are recorded, not fixed during PERF-1.**

## 1. Constant across all runs
- Commit / Core: `dab499ff` / 47.4.264. Java, JVM flags, heap (`-Xms = -Xmx`), GC algorithm.
- `-XX:ActiveProcessorCount=K` with fixed `-XX:ParallelGCThreads` / `-XX:ConcGCThreads`, so every
  size derived from the core count is identical in every run: split target, region cap, GC
  threads, common pool, Netty. Only the physical CPU set changes.
- World: one restored snapshot per run (identical bytes; hash recorded).
- Workload and entity count (§3).
- Region layout: K regions with identical bounds. Flags:
  - `-Dnestworld.maxRegions=K`, `-Dnestworld.splitTargetParallelism=K`;
  - layout built once and verified before the warmup, by `/nestworld status full` bounds;
  - **no split or merge during the measurement window** (validity check).
- `-Dnestworld.regionEntityBudgetMs` set high enough that no entity is deferred. Work per tick
  is then constant, which the validity check confirms (deferred = 0).
- No players, no bots, no external integrations. Plain dedicated server, not the SkyBlock pack:
  this is a Core test, not a modpack test.

## 2. Variable
Physical worker budget N ∈ {1, 2, 4, 8, 12, 16}. Mechanism: `taskset` CPU list for the JVM over N
**distinct physical cores, SMT siblings excluded and not used by other tenants**. The exact CPU
list is recorded per run. K = 16 regions in every run, so N=16 means one core per region.

## 3. Workload W1 — independent regions
- K = 16 identical cells, each in its own region, separated by more than the region-boundary
  band. They are forceloaded, so there is no chunk loading during measurement.
- Each cell holds M mobs enclosed in single-block compartments: AI and pathfinding run, but
  nothing moves between cells, so the cost per tick is stable and nothing crosses a region.
- M is calibrated once so that at N=16 the server is still below 20 TPS. The 20 TPS cap must
  never truncate a measurement.
- **Primary throughput** = executed living-entity ticks per second. It is counted by the
  harness probe `tests/perf1/counter-mod` (a `LivingTickEvent` listener with a `LongAdder`; not
  Core) as a cumulative counter, differenced over the window. Core keeps no cumulative counter:
  `EntityFairnessMetrics` holds only an interval ring, and `getLastTickedCount()` is a per-tick
  snapshot.
- **Secondary throughput** = TPS × entity count. Validation: |primary / secondary − 1| < 1% and
  deferred = 0.

  S(N) = primary(N) / primary(1), and E(N) = S(N) / N.

## 4. Per-run protocol and metrics
Each run: restore the world → boot → build and verify the layout → warmup 120 s → measurement
window 300 s → stop. Three repetitions per N; report the median and spread.

| Metric | Source |
|---|---|
| TPS, MSPT mean / p50 / p95 / p99 | `/nestworld mspt`, sampled each second |
| throughput, deferred entities | entity count × TPS; `/nestworld entityfairness` |
| region time per region | `/nestworld status full` (per-region tick distribution) |
| main-thread time | `/nestworld status`, `tickphases` |
| mailbox depth / latency | `/nestworld mailbox` (≈0 expected in W1; non-zero is a finding) |
| region-worker utilisation | per-thread CPU from `/proc/<pid>/task/*/stat`, by thread name |
| CPU utilisation | `/proc/<pid>/stat` vs wall clock × N |
| allocations / GC | `-Xlog:gc*` file plus a JFR allocation summary per run |

## 5. Validity checks (otherwise the run is void, not a data point)
- The region layout before the window equals the layout after it, and split/merge counters do
  not change.
- deferred = 0; TPS < 20 throughout the window.
- The CPU set is exactly N physical cores with no SMT siblings, and the host load on those cores
  from other tenants is ≈0 (sampled).
- World snapshot hash and JVM flags are identical to the reference.

## 6. Blocker — the rig
This workspace runs in an LXC container with **3 logical CPUs** (11, 15, 20), out of the host's
18 cores / 36 threads. They are 3 distinct physical cores, but their SMT siblings (29, 33, 2)
belong to the host and may be shared with other tenants. N ∈ {4…16} cannot run here, and even
N ≤ 2 has no guarantee of noisy-neighbour freedom.

Needed: ≥ 17 dedicated physical cores (16 for N=16 plus one for the OS and harness), with their
SMT siblings unused or disabled, or a separate host.

**Owner decision.** Until then, a pilot at N = 1, 2 here may validate the harness mechanics:
- layout freeze;
- deferred = 0;
- metric collection;
- run-to-run spread.

A pilot is **not** a PERF-1A result.

## 7. Pilot amendments (2026-10-04, N = 1, 2; harness validation only)
The pilot is **HARNESS VALIDATION ONLY — NOT PERF-1A SCALING EVIDENCE**. Changes it forced:

**Layout.**
- Automatic splitting slices cells and fires within seconds even under NoAI load. Core's split
  and merge thresholds are hard-coded, and the region cap is never below 4.
- The harness therefore builds the layout on an **entity-free** world:
  1. merge to one root;
  2. place one inert `minecraft:marker` per cell;
  3. make 15 manual splits. Core cuts at the median entity's chunk and gives that chunk to the
     lower child, so a temporary gap marker per split moves the median into the gap;
  4. remove the markers;
  5. only then summon the cows.
- Cells sit on a diagonal, so every split is clean on either axis.
- `maxRegions` = 16 = layout size, so no automatic split is possible; manual children are pinned,
  so there is no automatic merge.

**Validity checks added** (each found a real defect in an earlier pilot round):
- **CPU placement and use.**
  - The process affinity and the container cpuset are sampled every second in the window and
    must not change. The LXD host moved this container between cores up to three times in one
    5-minute window; the kernel then resets the process affinity.
  - CPU used must be ≤ N cores. One "N=1" run consumed 1.75 cores and had passed the old
    validator.
  - CPUs are picked from the current cpuset (`auto:N`), on distinct physical cores.
- **20 TPS cap.** TPS < 19 and median block p50 ≥ 50 ms. A run at TPS 19.16 passed the old
  threshold of 19.5.
- **Vanilla watchdog.** `max-tick-time = -1`. Any run sustained below ≈ 4 TPS was stopped by the
  vanilla watchdog without a long tick: the lag behind `nextTickTime` is only caught up every
  300 ticks.
- **Spread.** CV of primary throughput per N, over **valid** runs only, ≤ 5%. This threshold is
  proposed; owner to confirm.

**Rig requirement, tightened.** The CPU set must be **statically pinned**, e.g. LXD
`limits.cpu: "<explicit list>"`, never a CPU count, which lets the host rebalance the container.
Benchmark cores must have no active SMT siblings, with host or harness work on separate physical
cores (16 + 2 on the E5-2699 v3).

**Pilot round 3 data** (W1 c=24, i.e. 16 × 576 cows; same snapshot, flags and topology). HARNESS
VALIDATION ONLY:

| Run | Verdict | CPUs | TPS | primary/s | secondary/s | MSPT p50 / p95 | CPU used |
|---|---|---|---|---|---|---|---|
| N1-r1 | valid | 9 | 2.96 | 27 317 | 27 300 | 277 / 396 | 0.86 |
| N1-r2 | valid | 6 | 3.10 | 28 565 | 28 594 | 276 / 374 | 0.86 |
| N1-r3 | valid | 4 | 3.52 | 32 408 | 32 410 | 237 / 320 | 0.90 |
| N2-r1 | valid | 9,17 | 6.76 | 62 132 | 62 269 | 134 / 163 | 1.75 |
| N2-r2 | **void**: cpuset moved in the window | 6,19 | — | — | — | — | 2.09 |
| N2-r3 | valid | 4,21 | 6.95 | 63 815 | 64 014 | 135 / 187 | 1.64 |

Gate results:
- **Not clean.** N=1 CV is 7.4%, above the 5% threshold. Every N=1 run landed on a different host
  core, with SMT siblings of unknown load; this rig cannot hold the cores still.
- Mailbox depth was not collected in round 3 (wrong command). Fixed and verified afterwards
  (depth 0 throughout W1, as expected).
- Passed: primary ≈ secondary (≤ 0.7%), deferred = 0, same topology, split = 0 / merge = 0,
  GC / JFR / `/proc` collected, clean stops.

**Core finding.** PERF-FINDING-001 is a global monitor on the entity-tick hot path. Any curve on
this build includes it.

**Pilot observation.** Pilot observations are consistent with positive multicore scaling, but are
not scaling evidence. N=1 gave 27–32k entity ticks/s and N=2 gave 62–64k, but the runs used
different physical cores, N=1 CV was 7.4%, the cpuset was dynamic, and there are only two points.

## 8. Owner decisions (2026-10-04)
1. **PERF-FINDING-001: OPEN / MEASURE-FIRST.** 47.4.264 is measured as is, with the
   TickAttribution contention included. It is part of the real Core, not benchmark noise.
   - If JFR confirms it as the scaling limiter, the fix becomes a new reference build. That build
     goes through integrity / canary / smoke, and then the **same** PERF-1A is repeated, giving an
     A/B before and after the fix.
   - Exception: if on the pinned rig the bottleneck makes N ≥ 4 practically unmeasurable, PERF-1A
     for 47.4.264 closes as **BOTTLENECK IDENTIFIED** instead of forcing the full matrix.
2. **Rig: explicit CPU pinning on the E5-2699 v3** (18 cores / 36 threads; SMT sibling of CPU n is
   n+18).
   - The benchmark container gets `limits.cpu: "1-17"`:
     - CPU 1 is reserved for the harness driver (`PERF1_HARNESS_CPUS=1`);
     - CPUs 2–17 (16 physical cores) are the benchmark pool.
   - The host, LXD, SSH and monitoring use CPUs 0, 18 and 19 (cores 0 and 1).
   - CPUs 20–35 (siblings of the benchmark cores) are given to nothing. Other containers are
     stopped or pinned inside {0, 18, 19} for the series.
   - `tests/perf1/host-preflight.sh <ct> 2-17 1` checks this on the host (read-only).
   - Any cpuset or affinity change inside the measurement window makes the run **VOID**. It is
     never corrected statistically.
3. **mailboxAudit = false in the primary matrix.** Recorded instead: queue depth, pressure
   counters, JFR. An optional diagnostic run with `mailboxAudit=true` is tagged `diag-` and never
   enters the dataset. Mailbox latency is the subject of the later cross-region workload, with
   its own instrumented series.
4. **Spread gate.** Over ≥ 3 valid runs per N:

   | CV | Verdict |
   |---|---|
   | ≤ 5% | **VALID** |
   | 5–10% | **UNSTABLE**, repeat |
   | > 10% | **INVALID ENVIRONMENT**, investigate |

   Only CV ≤ 5% enters the PERF-1A dataset.

**Freeze criterion.** N=1 and N=2, ×3 each, on the pinned rig: every run VALID, no cpuset or
affinity violation, CV ≤ 5%. Then **PERF-1A HARNESS = VERIFIED / FROZEN**, and the unchanged
harness runs 1 → 2 → 4 → 8 → 12 → 16.

## 9. Pilot round 6: freeze criterion met (2026-10-04)

**Rig.** The host cannot pin the container; the container sees all of 0–35. On the host side,
CPUs 1–3 and their siblings 19–21 were freed:
- benchmark on CPU 2 (N=1) or CPUs 2,3 (N=2);
- harness on CPU 1 (`PERF1_HARNESS_CPUS=1`);
- the container's own processes moved to CPUs 0 and 18.

Isolation is measured, not assumed. Every run checks from host-wide `/proc/stat`:
- SMT siblings of the benchmark CPUs are ≤ 3% busy;
- foreign load on the benchmark CPUs (their busy time minus the server's CPU time) is ≤ 3%.

**Method changes since the owner review** (each forced by pilot data; proposed for the frozen
harness):
1. **CPU accounting.** Server CPU is the whole-process `/proc/<pid>/stat`. The per-thread sum
   lost the time of threads that exited during the window: 0.79 against 0.99 cores at N=1.
2. **JVM: `-XX:-UseDynamicNumberOfCompilerThreads`.** The JIT keeps its ergonomic 12 threads for
   the whole run instead of creating and exiting them, which keeps accounting exact. Nothing else
   about the JIT changes.
3. **Warmup is measured in work, not time.** At least 15 M executed entity ticks (equal for every
   N, because tiered-JIT thresholds count invocations), at least 120 s, and then the JIT threads
   quiet for 60 s. A fixed 120 s left up to 196 s of ≥ 1 s compilations inside the N=1 window.
4. **JIT compiler threads are pinned to the harness CPU.** In steady state at N=1, C2
   recompilation after `unstable_if` deopts kept taking about 22% of the only benchmark core
   (73 compilations ≥ 1 s in a 120 s window, even after the warmup above). JIT is JVM cost, not
   Core cost, and it penalised low N, which inflates S(N).
   - Measured effect at N=1: 26.6k → 38.0k ticks/s.
   - JIT CPU is still recorded per run: about 27–30 s per 300 s window at both N=1 and N=2.
   - Validity check: the JIT threads stay on the harness CPUs.
5. **Per-run JVM options** (the GC log path) go through `JDK_JAVA_OPTIONS`. The harness
   previously appended them to `user_jvm_args.txt`, and interrupted runs left stale `-Xlog`
   lines behind.
6. **Canonical server config** is committed in `tests/perf1/server-config/`. A run refuses to
   start unless `user_jvm_args.txt` is byte-identical and every canonical `server.properties` key
   matches. The check was added after round 6; round 6 used exactly this config (verified).

**Round 6 data** (W1 c=24, 16 × 576 cows; all runs VALID; HARNESS VALIDATION ONLY):

| Run | primary/s | warmup | MSPT p50 / p95 / p99 | foreign on bench | siblings |
|---|---|---|---|---|---|
| N1-r1 | 37 992 | 371 s | 227 / 273 / 334 | 0.03% | 0.3% |
| N1-r2 | 37 685 | 401 s | 227 / 249 / 306 | 0.00% | 0.1% |
| N1-r3 | 38 824 | 366 s | 223 / 240 / 309 | 0.00% | 0.2% |
| N2-r1 | 72 936 | 221 s | 116 / 129 / 218 | −0.01% | ≤ 0.4% |
| N2-r2 | 80 604 | 216 s | 107 / 114 / 142 | −0.23% | ≤ 0.5% |
| N2-r3 | 79 437 | 221 s | 108 / 115 / 157 | 0.16% | ≤ 0.4% |

- **N=1:** mean 38 167/s, **CV 1.3% (VALID)**.
- **N=2:** mean 77 659/s, **CV 4.3% (VALID)**.
- Other checks: same topology, flags and snapshot; deferred = 0; primary ≈ secondary within
  0.8%; no cpuset or affinity change.
- Pilot observations are consistent with positive multicore scaling (N2/N1 ≈ 2.03), but are not
  scaling evidence.

**Remaining blocker for the official matrix.** N ∈ {4…16} needs 4–16 quiet physical cores, with
their siblings idle, plus the harness core. The host has freed 3. The frozen harness runs any N
the host can free, and voids any run with interference.

## 10. Revision 1 (2026-10-05): sealed cells and an M sized for N=16

**Defect** (TZ §22; details in `docs/performance/PERF-1A-results.md`):
- The prepare step built the walls in the same function and tick as the forceloads.
- `fill` failed silently on chunks that were not loaded, and cell 15 got no walls at all.
- Its cows roamed into a neighbouring region and out of ticking chunks. This breaks W1's
  independence and widens the primary/secondary gap as N grows; it surfaced as N4-r1 VOID.

All pilot and official points on snapshot `8ecbd07f…` are void.

**Second defect, found at the same STOP:** c = 24 (9 216 cows) violated §3. At about 39k ticks/s
per core it would hit the 20 TPS cap from N ≈ 8, and those runs would be VOID by construction.

**Revision (`cc0a19510`):**
1. Walls are a separate `perf1:walls` function. It runs only after `execute if loaded` passes for
   every cell corner.
2. `prepare` verifies every cell with a masked clone: the stone count must equal
   2·side² − 2·c² (14 210 for c = 48). Otherwise there is no snapshot.
3. New per-run validity checks:
   - in every fairness sample, each region owns exactly its cell and ticks all of it
     (owned = ticked = cows_per_cell);
   - at window end, all cows of every cell are inside that cell's bounds.
4. **M = c² = 2 304 cows per cell (c = 48), 36 864 in total.** At T(1) ≈ 33k, ideal TPS(16) ≈ 14
   stays below the 19 cap. Snapshot `4d87bd52…`: walls verified, 0 entities.

Unchanged: everything else in §1–§9 (CPU / JIT / warmup / thresholds / config).

**Pilot round 7** (HARNESS VALIDATION ONLY; CPUs 2 / 2–3 / 2–9, housekeeping 1; all runs VALID):

| N | runs | primary/s (mean) | CV | TPS | MSPT p50 / p95 | primary vs secondary |
|---|---|---|---|---|---|---|
| 1 | 3 | 33 023 | 2.6% | 0.87–0.92 | ~1 030 / 1 480 ms | +0.35…+0.40% |
| 2 | 3 | 68 575 | 0.8% | 1.84–1.88 | ~495 / 705 ms | +0.15…+0.18% |
| 8 | 1 (headroom check) | 267 327 | — | 7.25 | 129 / 147 ms | +0.03% |

Other observations:
- Containment held in every run.
- N=1 yields only 2 non-overlapping 100-tick MSPT blocks per 300 s window, the minimum the
  frozen check accepts. Noted, not changed.
- Pilot observations are consistent with positive multicore scaling (N2/N1 ≈ 2.08, N8/N1 ≈ 8.1),
  but are not scaling evidence.

**Freeze criterion met** (N=1, 2 ×3, all VALID, no cpuset or affinity violation, CV ≤ 5%).
Re-freeze at `cc0a19510` with W1 c=48 is proposed for owner approval. After that, the official
dataset restarts from N=1.
