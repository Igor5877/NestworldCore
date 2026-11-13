# Why 0.9.3 failed the performance target: OFF/ON JFR

Actual profile recordings from the loaded-claim experiment:
`comparison-20261111-183659/1-off/profile.jfr` and `2-on/profile.jfr`, under the isolated test root. JFR approx130s; counter deltas bracket the120s JMX measurement. They are related windows, not exactly identical. The recording files were collected during the test; analysis was completed after reporting its negative TPS result.

| MAIN inclusive ExecutionSample attribution | OFF | ON |
|---|---:|---:|
| MAIN execution samples | 17173 | 17441 |
| StorageImportStrategy.transfer | 2837 (16.52%) | 3193 (18.31%) |
| StorageExportStrategy.transfer | 2546 (14.83%) | 2593 (14.87%) |
| InterfaceLogic ticker | 1602 (9.33%) | 1634 (9.37%) |
| Soph CachedFailedInsertInventoryHandler.getStackInSlot | 1077 (6.27%) | 1021 (5.85%) |

These are sampled CPU shares, not ms/tick. Nested rows overlap; no summation. Truncated MAIN traces5993 OFF,6923 ON limit ancestor attribution. GC aggregate pauses891.69ms OFF /818.96ms ON, around0.6–0.7% of recording time; not the explanation for sustained low TPS. Native MAIN samples1 OFF /0 ON.

## Measured coverage, using before/after counters

ON window scheduled47360 bus operations:

- only510 transit operations (33 import +477 export), about1.08% of scheduled work;
- strategy refusal29551 (~62.40%);
- linked-cross-domain refusal9488 (~20.03%);
- MAIN neighbour4642 (~9.80%);
- crafting3043 (~6.43%).

Counter time deltas: inline MAIN35247.343ms; transit entry79.267ms; MAIN commit12.931ms; region work19.093ms. Scopes are measured instrumentation intervals, not independently additive total CPU. Region work is only ~0.015ms per observed ON tick (1276 ticks). This is insufficient transferred work to yield the requested MAIN benefit. Only21 export units were delivered within the counter window; import delivered0.

## Code causes of low coverage

BusTransit.onBusWork gates strategies before attempting transit. strategiesAllowed rejects the entire facade when any component is neither builtin nor an exact audited no-op. ItemNoopStrategies accepts foreign FE/soul strategies only with a positive item-only filter; unfiltered/inverted imports remain inline. This preserves their original resource semantics, but prevents broad import offload.

BusTransit.linkedSameDomain returns false when namespaceHasTypePin is true. Thus the MM ownership guard can reject a port because a controller type in its namespace is pinned. Removing that guard without proving the actual controller/port execution relationship would risk cross-domain access.

Moving only adjacent inventory operations does not move the network-side Storage Bus lookup/extraction in AE2 authority. The JFR confirms both import and export MAIN paths remain prominent with transit ON. No global pins or ownership checks were removed.

## Conclusion and next work

The performance failure is primarily a coverage failure of this WP3-B implementation: it offloads about1% of operations and a negligible measured amount of inventory CPU. Optimizing dispatch wrappers or exporting latency alone cannot deliver the required TPS benefit on this workload. This does not prove the single pair's +2.52% MAIN delta is deterministic transit overhead.

Further work must safely admit the actual rejected bus/inventory combinations (with per-strategy/resource behavior and linked ownership proof), or adapt the expensive network-facing inventory path. Do not declare the candidate production ready or remove safety guards to obtain benchmark coverage.

Analysis files: `/tmp/ae2-093-1-off-analysis.txt`, `/tmp/ae2-093-2-on-analysis.txt`, and corresponding `*-focused.txt`. Raw JFR is intentionally not committed.
