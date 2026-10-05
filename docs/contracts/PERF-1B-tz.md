# PERF-1B — Mod API end-to-end performance validation (owner TZ, 2026-10-05)

**Status: ACTIVE (owner, 2026-10-05). The full TZ was received the same day; this file keeps the
earlier summary.** The owner's full text (34 sections) governs: B0–B3, path attribution, closed
accounting, the correctness gate, latency / CPU / GC, saturation vs sustainable runs, pilot →
freeze → official, and GUARANTEED / MEASURED / NOT GUARANTEED. The audit and spec draft are in
`PERF-1B-spec.md`.

| Item | Value |
|---|---|
| Precondition | PERF-1A W1 PASS; W2 completes separately |
| Core | trusted reference 47.4.264 |
| API under test | `NestWorldEntities.setVelocity` first; `NestWorldInventory.setSlot` optionally, as a separate series |

PERF-1B validates the economics of the existing Mod API. It is **not** API-1C.

## 1. Goal
What does it really cost an ordinary Forge mod to use the NestWorld Mod API, and does the API let
that mod use NestWorldCore's parallelism without access to Region, Mailbox, BSP, the scheduler or
other Core internals?

Two separate properties are tested: **API overhead**, and **aggregate scaling through safe
parallel execution**. Proving that one Mod API operation is faster than a direct Forge call is
explicitly not a goal.

## 2. Consumer
`benchmark-mod.jar` is an ordinary Forge consumer.

| Allowed | Forbidden |
|---|---|
| Forge API, Minecraft API, public `net.nestworld.modapi.*` | `net.nestworld.core.*`, Region, RegionThread, Mailbox, BSP, internal Core dispatchers, reflection around the public API, Core-specific hooks |

**One and the same consumer binary** is used in every compatible mode.

## 3. Primitive
`NestWorldEntities.setVelocity(entity, velocity)`. Why:
- API-1A is CLOSED / VERIFIED;
- the operation is bounded;
- its temporal semantics (LATEST_INTENT_WINS) are known;
- it has both a Forge and a NestWorld backend;
- an owner fast path and a mailbox / reroute path can each be created.

No new API is added for the benchmark.

## 4. Modes
| Mode | Path | Measures |
|---|---|---|
| B0 | raw Forge equivalent | minimum cost of the equivalent direct operation |
| B1 | Mod API, Forge backend | abstraction overhead alone |
| B2 | Mod API, NestWorld owner fast path | routing, identity, ownership and ordering on the cheap path |
| B3 | Mod API, NestWorld mailbox / reroute | the most expensive normal path |

## 5. Workload
- A controlled number of target entities. For the scaling workload, the targets are distributed
  over independent ownership domains.
- Matrix N = 1, 2, 4, 8.
- PERF-1A W1 is not evidence for PERF-1B; this is a separate benchmark.
- Pilot, then freeze, before the official series.

## 6. Primary metrics
For each mode:
- throughput: OpsSubmitted/s, OpsAccepted/s, OpsCompleted/s (applied or terminal);
- S(N) = T(N) / T(1), E(N) = S(N) / N;
- end-to-end latency from accepted intent to APPLIED or terminal: p50, p95, p99, plus max
  (diagnostic only).

## 7. CPU cost
- CPU seconds per 10⁶ accepted operations, per mode.
- Broken down where reliably separable: consumer, API / backend, routing, mailbox, region
  execution, main thread, GC, JIT. A component that cannot be reliably isolated stays aggregated;
  attribution is never inferred from assumptions.

## 8. Correctness gates
A performance run is **INVALID** if any of these is non-zero: wrong-owner mutations, duplicate
applications, ownership violations, unaccounted accepted operations.

- **LATEST_INTENT_WINS:** after quiescence, each target's final state equals the newest accepted
  intent of its version domain. A stale intent may end as terminal / stale-drop under the
  contract; that is not a loss.
- **Accounting after drain:** Accepted = Applied + ExplicitTerminal.

## 9. Path validity
- **B2** is valid only if instrumentation confirms that the required share of operations really
  took the owner fast path.
- **B3** is valid only if instrumentation confirms that operations really went through the
  mailbox / reroute path.

A mode is never classified by the scenario the benchmark intended to create.

## 10. Comparisons
| Comparison | Answers |
|---|---|
| B0 → B1 | what the Mod API abstraction costs on Forge |
| B1 → B2 / B3 | what NestWorld ownership, routing and mailbox semantics add |
| scaling | whether that price is repaid by safely using several independent execution domains |

## 11. Forbidden criterion
"Mod API must be faster than raw Forge" is not a criterion.

Acceptable shape: one operation costs 1.0× on raw Forge and 1.4× through the Mod API, while the
aggregate independent workload reaches 1.0× capacity on Forge and 5–7× on NestWorld.

## 12. Process
SPEC → implementation → pilot.
- Methodology defect: STOP → revision → new pilot.
- Otherwise: FREEZE → official dataset.

The frozen harness is never changed after official results have been seen.

## 13. Artefacts
- `docs/contracts/PERF-1B-spec.md`, `docs/performance/PERF-1B-results.md`.
- `tests/perf1b/{benchmark-mod, harness, official, README.md}`.
- Evidence:
  - Core commit, build and provenance; Mod API / provider version; consumer JAR SHA-256;
  - harness commit; Java and JVM flags; CPU allocation; workload configuration;
  - raw counters, latency distributions, JFR and GC logs;
  - a VALID / VOID verdict.

## 14. Done when
PERF-1B is complete when three questions are answered with evidence:
1. What the public Mod API costs compared with a direct Forge operation.
2. What NestWorld routing, ownership and mailbox semantics add.
3. Whether an ordinary mod, through the public API, gets an aggregate multicore benefit without
   knowing the internal region architecture.

There is no automatic step to API-1C. Next gate: PERF-1B → MOD-API-REALWORLD-1 (AE2, Mekanism,
Create, storage, pipes, automation) → recurring semantic patterns → only then API-1C / 1D.
New primitives come from real mod needs.
