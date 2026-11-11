# WP3-B mining-world comparison — 2026-11-11

Candidate 0.9.2 SHA256 `458cd7d65165586bdb758ba5d84e51db5299251e74cde84fc9525486deb1626d`.
Evidence: `/root/airis-2026-10-25/WP3B-MINING-20261111/comparison-20261111-141011`.

Each mode restored the same completed production backup (13:30), loaded 65 unique mining claim chunks, retained production pins, warmed for 90 seconds and measured for 120 seconds. Private network namespace; identical 8-core affinity and 4–8 GiB heap. Fixtures and optional typed recheck disabled. This is a copied-world diagnostic, not a live A/B.

| Metric | Transit OFF | Transit ON |
|---|---:|---:|
| MAIN CPU ms/observed tick | 71.4395 | 70.1915 |
| Observed TPS | 13.5330 | 13.6663 |
| Mean sampled MSPT | 62.4947 | 61.5730 |
| MSPT p50 | 53.6334 | 51.1612 |
| MSPT p95 | 104.8207 | 107.5700 |
| MSPT p99 | 133.4630 | 125.7337 |

MAIN delta: -1.2479 ms/tick (-1.7469%). TPS delta: +0.1333 (about +0.99%). Both neutral JMX windows qualified with no detected sampling gaps or read errors. MAIN CPU includes between-tick work; sampled MSPT may exclude some of that work. This single pair does not establish a repeatable practical benefit.

ON lifecycle totals include warmup, so they are not measurement-window throughput: 100834 scheduled/completed, 0 outstanding/missed/commit failures/domain violations/region failures, 48 export units extracted and delivered, 0 import units. Region inventory work totals only 42473 microseconds across the run. Observed export p100 latency was 1 tick; this workload does not exercise every finite-energy/multi-key case. Most expensive network-side extraction still runs on MAIN. MM transit was not exercised and remains an acceptance gap.

## Requested no-addon control

Separate attempt: `comparison-20261111-140535/1-none`. Removing the whole addon also removes verified compatibility fixes. AE2 TickManager PriorityQueue threw NPE during warmup; no valid performance window exists. See `no-addon-failure.txt`. Do not interpret the failed baseline as a measured TPS benefit or reopen verified TickManager work for this benchmark.

## Decision at this stage

No production recommendation for the new candidate: meaningful MAIN benefit and MM transit acceptance are not established. Candidate is not installed on live. An additional, default-disabled inventory dispatch optimization is undergoing detached equivalence testing before any performance comparison.
