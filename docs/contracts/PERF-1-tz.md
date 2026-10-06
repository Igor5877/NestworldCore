# PERF-1 — Core scaling baseline + Mod API execution cost (owner TZ, 2026-10-04)

**PERF-1A: CLOSED / VERIFIED / FROZEN (owner, 2026-10-06).** W1 (16 independent domains, S(8) = 8.409) and W2 (one unsplittable domain, S(8) = 1.404) together show that scaling depends on available region-level parallelism. N = 8 is the final maximum for this campaign; PERF-1A-EXTENDED (> 8 cores) is out of the current scope, not a blocker and not an open DoD item. Not to be done: N12/N16, a third synthetic workload, repeating W1, another hotspot geometry, or optimising TickAttribution only because contention was observed. Next gate: PERF-1B.

**Status: ACTIVE.** The PERF-1A harness is VERIFIED / FROZEN at `d5321f70e`; the owner accepted
the §9 method changes of `PERF-1A-spec.md` through this TZ (§5, §6 below).

| Item | Value |
|---|---|
| Reference Core | 47.4.264, `dab499ff`, Source-Patch-Integrity MATCH, Git-Dirty false, canary 4/4 |
| Harness | `tests/perf1` at `d5321f70e` (frozen). The series wrapper `tests/perf1/official/series.py` adds metadata and gates only; it does not touch measurement |

Normative summary of the owner's TZ. Section numbers follow the TZ.

## 1. Goal
- **A. Core scaling.** Does NestWorldCore's execution capacity grow with dedicated physical cores
  when the workload and Core are unchanged?
- **B. Mod API economics.** What does the public Mod API cost compared with raw Forge, and does it
  pay for itself through safe parallel execution of independent operations?

Not in scope:
- universal Forge-mod compatibility;
- players-per-server capacity;
- scaling of one hotspot;
- multi-host;
- CPU comparison;
- production affinity;
- NeoForge.

## 2. Baseline is immutable
- No Core change to improve results until PERF-1 ends. A correctness problem that invalidates the
  test stops the gate.
- A performance bottleneck is **not** fixed during the baseline. PERF-FINDING-001 stays
  OPEN / MEASURE-FIRST; if it limits scaling, that is a PERF-1 result.

## 3. PERF-1A matrix
- N ∈ {1, 2, 4, 8, 12, 16}, with 3 VALID repetitions each.
- Order interleaved: 1→16, then 16→1, then 1→16. If host availability forbids this, blocks are
  allowed; the method is unchanged.

## 4. CPU isolation
- N counts physical cores. The SMT sibling of each benchmark core carries no foreign work.
- Before and during the measurement, foreign load is ≤ 3% on the benchmark CPUs and on their
  siblings. A violation makes the run **VOID**; it is never corrected statistically.

## 5. Housekeeping / JIT
- PERF-1A measures NestWorld/Minecraft execution capacity at N dedicated physical cores.
- The JIT compiler threads are not part of N: they run on a fixed housekeeping CPU, together with
  the harness, at every N. They never return to the benchmark cores at large N.
- `-XX:-UseDynamicNumberOfCompilerThreads` stays. JIT CPU is recorded.
- This is benchmark isolation, not a production optimisation.

## 6. Warmup
At least 15 000 000 executed LivingTickEvent ticks, then the frozen JIT-settling criterion. The
warmup is the same for every N (same wall time ≠ same JVM history).

## 7. Workload W1 (frozen)
- 16 isolated cells, 576 cows each (9 216 total), deterministic diagonal placement, 16-region
  reference topology.
- In the measurement window: split = 0, merge = 0, deferred = 0. Otherwise the run is invalid.
- *Correction to the TZ text:* the frozen W1 cows have AI enabled. NoAI existed only in an early
  layout attempt. The layout is now built on markers, and the cows are summoned with AI into the
  finished layout.

## 8. Throughput
- Primary: executed LivingTickEvent ticks per second, from the external Forge counter mod. TPS is
  not a throughput metric.
- Secondary: TPS × entity count. |primary / secondary − 1| ≤ 1%; otherwise the run is
  investigated and is not automatically in the dataset.

## 9. Metrics per run
- N; ticks/s; TPS; MSPT mean / p50 / p95 / p99.
- Region execution time; main-thread time; region queue depth max.
- CPU: worker, process, JIT, GC; allocations and GC pauses.
- Split / merge / deferred.
- Foreign and sibling interference; actual affinity.
- Raw files: JFR, GC log, `/proc` samples. Raw artefacts are never deleted.

## 10. Repeatability
Over VALID runs only:

| CV | Verdict |
|---|---|
| ≤ 5% | ACCEPT |
| 5–10% | UNSTABLE, repeat |
| > 10% | ENVIRONMENT INVALID, investigate |

## 11. Scaling metrics
T(N) = mean throughput; S(N) = T(N) / T(1); E(N) = S(N) / N. No target S(16) is set: the goal
is to measure the curve.

## 12. Scaling wall
If throughput stops growing, do not change Core. Attribute the wall with JFR (locks, CPU,
allocation, GC, JIT, hot methods), Linux (utilisation, placement, SMT, scheduler) and Core
metrics (queues, region and main-thread work, mailboxes).

Classify it as one of:
- CORE_SERIALIZATION
- JVM_OVERHEAD
- MEMORY_LIMIT
- HOST_LIMIT
- WORKLOAD_LIMIT
- UNKNOWN

PERF-FINDING-001 is checked separately at this stage.

## 13. Negative control W2 — single hotspot
Run after W1. The work cannot distribute across regions, so throughput must **not** scale roughly
linearly. If it does, explain the execution path; it does not count as success.

## 14–20. PERF-1B — external Mod API benchmark (only after the Core baseline)
- **Consumer.** An ordinary Forge `benchmark-mod.jar` that imports only Forge and the public
  NestWorld Mod API (no Region / RegionThread / Mailbox / BSP). **One consumer artifact** runs on
  both Forge and NestWorld.
- **Modes:**
  - B0: raw Forge;
  - B1: Mod API on the Forge backend;
  - B2: Mod API on the NestWorld owner fast path;
  - B3: Mod API on the NestWorld mailbox / reacquisition / revalidation / reroute path.
- **Primitives.** `NestWorldEntities.setVelocity` first, then possibly `NestWorldInventory.setSlot`.
  No new primitive; no API-1C.
- **Measured:**
  - single-operation cost (submission, routing, allocation, fast path, mailbox, reroute);
  - aggregate ops/s at 1 / 2 / 4 / 8 / 12 / 16 independent domains where the baseline allows;
  - p50 / p95 / p99 latency.
- **Correctness:** wrong-owner, duplicate, unaccounted-accepted, ordering and ownership
  violations are all 0. For LATEST_INTENT_WINS, final state equals the latest accepted intent.
  A violation makes the run INVALID.
- **Not a criterion:** "API op < raw op". The questions are the API overhead and the parallel
  throughput gain.

## 21. Mod API performance contract
`docs/performance/MOD_API_PERFORMANCE_CONTRACT.md`, with claims split into **GUARANTEED** (only
architectural / correctness properties), **MEASURED** (reference-system numbers) and **NOT
GUARANTEED** (for example fixed latency, linear scaling, zero allocation, zero mailbox delay, or
beating a raw call). Benchmark numbers are never API guarantees.

## 22. Frozen during the official dataset
Changing any of the following needs a revision gate:
- entity count, geometry, warmup, window;
- JIT placement, JVM flags, ActiveProcessorCount, GC, region count, split thresholds;
- metric definitions, validity and interference thresholds;
- the Mod API or Core implementation.

On a methodological defect: STOP → document → invalidate the affected dataset → revise the method
→ new pilot → freeze again. The harness is never fixed mid-curve.

## 23. Artefacts per run
- **Metadata:**
  - Core version and provenance (commit, dirty, both digests);
  - harness commit and counter-mod hash;
  - Java and JVM args;
  - N, CPU IDs, SMT siblings, housekeeping CPU;
  - snapshot and config hashes;
  - start and end; VALID / VOID with the reason.
- **Raw files:** summary, JFR, GC log, CPU samples, Core metrics, counter output, server log,
  validation report.

`series.py` writes `series.json` and `<run>-metadata.json` next to the frozen harness output, and
keeps the inputs under `<evidence-root>/<series>/inputs/`.

## 24. DoD — PERF-1A
1. Harness frozen.
2. Core unchanged.
3. 3 VALID runs for every official N, with the full matrix 1/2/4/8/12/16 for a full claim.
4. CV ≤ 5%.
5. Interference gates pass.
6. Same topology; deferred = 0; no correctness violations.
7. Primary and secondary throughput agree.
8. T / S / E built.
9. Any scaling wall attributed.
10. Negative control done.
11. Raw evidence kept.
12. No optimisation in the baseline.

## 25. DoD — PERF-1B
1. External ordinary benchmark mod, one artifact for both platforms.
2. B0–B3 measured; `setVelocity` end-to-end.
3. Single-operation cost, aggregate throughput and async p50 / p95 / p99.
4. Clean correctness counters.
5. API overhead separated from Core scaling.
6. Contract document with GUARANTEED / MEASURED / NOT GUARANTEED.

## 26. After PERF-1
Nothing is optimised automatically.

| Outcome | Next step |
|---|---|
| Core good and API good | MOD-API-REALWORLD-1 |
| Core scales, but API overhead is too high | optimise the API / backend |
| Core stops scaling | localise the wall, then a fix gate |
| PERF-FINDING-001 is the limiter | PERF-FIX-001, A/B on the same frozen harness |

> Normative principle: first prove the execution substrate scales, then prove the public Mod API
> delivers that scaling economically to an ordinary mod; only then widen the API.

## Environment state at TZ adoption (2026-10-04)
- The host cannot pin the container. It has freed exactly 3 quiet physical cores: 1 is the
  housekeeping CPU, 2 and 3 are benchmark CPUs (60 s measurement; siblings 19–21 idle).
- Official N ∈ {1, 2} can be measured now. N ≥ 4 is blocked until the host frees
  N + 1 quiet physical cores, siblings included.
