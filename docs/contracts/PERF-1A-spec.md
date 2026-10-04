# PERF-1A — scheduler scaling baseline (spec, DRAFT for owner review)

**Status: DRAFT (2026-10-04). BLOCKED on the rig (§6).** No Core change. Reference build
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
- **Throughput** = executed entity ticks per second = TPS × total entity count, valid only while
  deferred = 0.

  S(N) = throughput(N) / throughput(1), and E(N) = S(N) / N.

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
