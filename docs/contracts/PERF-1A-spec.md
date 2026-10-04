# PERF-1A — scheduler scaling baseline (spec, DRAFT for owner review)

**Status: DRAFT (2026-10-04), amended by the pilot (§7). BLOCKED on the rig (§6) and on the owner
decision in PERF-FINDING-001.** No Core change. Reference build
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
