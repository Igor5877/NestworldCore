# P1.5 — Final Acceptance Test Matrix

Status: governing spec for P1.5, given verbatim by the user on 2026-08-12 after P1.0-P1.4 closed
(two of the last two sub-phases closed negative: no commonPool bug, no updateFutures() coalescing
opportunity). P1.5 does not hunt for a new bug — it proves the production configuration does not
regress under real scenarios after all P0/P1 changes.

## Production config under test

`shards=8 / chunkGenBudget=2 / chunkGenMaxConcurrent=4`

## Scenarios

| Test | Load | Proves |
|---|---|---|
| A | Idle | no regression |
| B | Chunk generation | worldgen doesn't break tick |
| C | Forceload | bulk pipeline doesn't spike |
| D | Mass promotion | Tier1/Tier2 don't go O(n) |
| E | Player-load | behavior under real players |

## Metrics per test

TPS; MSPT p50/p95/p99/max; pollTask() total/avg/p95/p99; TIER2_SELECT; WORLDGEN; CHUNK_PROMOTION;
NON_WORLDGEN; region thread count; region tick time; chunk-gen throughput; `chunksToUpdateFuturesBulk`
backlog; ticket count; watchdog/"Can't keep up" occurrences; GC pauses; CPU utilization.

## Coordinate safety (after 2 prior methodology crashes)

`|X|, |Z| < 10,000,000` (tighter than the general 30M world-border rule). Reuse the SAME verified
coordinates across all 5 tests for comparability — chunk (312500, 625000) / block
(5,000,000, 10,000,000), the range already used successfully in P1.0/P1.2/P1.4. No new/incremented
coordinates without prior verification.

## Per-test procedure (all 7 steps required)

1. Clean restart
2. Warm-up
3. Baseline capture
4. Apply load
5. Stabilization window
6. Collect results
7. Return to production config

## Acceptance criteria (PASS conditions)

- TPS ≈ 20
- No watchdog
- No "Can't keep up" in the normal scenario
- No new race/deadlock
- MSPT has no sustained growth after warm-up
- `TIER2_SELECT` examine/select stays ~1-2x, not hundreds/thousands
- worldgen stays worker-side
- main-thread worldgen glue does not become a new O(n)
- backlog returns to normal level after load ends

Explicitly NOT required: worldgen having zero main-thread impact at all — P1.1/P1.3 already showed
part of promotion glue is main-thread by design.

## After P1.5

If the matrix passes, P1 is closed — no further P1 work. Next real direction is **P2 —
RegionThreadPool / global barrier / awaitLatch()**, since NestWorldCore's remaining fundamental
architectural limit is that one slow region can still delay the entire main thread. This is where
the next real throughput gain lives, unlike P1.3 which already proved there's little left to
optimize at the glue layer.
