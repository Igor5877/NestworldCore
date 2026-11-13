# 0.9.3 loaded mining-claim A/B

Artifact SHA256: `0269a42e043b5d6e716fe27b328d4ed84714f4dcb38efc53198d64a2373f2f25`.
Evidence root: `/root/airis-2026-10-25/WP3B-MINING-20261111/comparison-20261111-183659`.

This copied-world test was explicitly requested after the live-only diagnosis. Both OFF/ON restored the same frozen world, forceloaded all **66 unique chunks** in the current claim list, warmed for90s, and verified66/66 `execute if loaded` successes before collecting120s. Both neutral JMX windows qualified. Typed optimization OFF, fixtures OFF, production pins retained. No live deployment or layout change.

| Metric | Transit OFF | Transit ON |
|---|---:|---:|
| Observed completed ticks/sec | 11.000 | 10.633 |
| MAIN CPU ms/observed tick | 85.947 | 88.110 |
| MSPT mean | 77.850 | 80.130 |
| MSPT p50 | 65.513 | 68.394 |
| MSPT p95 | 146.372 | 158.126 |
| MSPT p99 | 168.774 | 175.990 |

MAIN delta +2.163ms/tick (+2.52%). Single sequential pair does not prove a systematic regression, but establishes no performance win in this run. Intermediate Forge rolling TPS snapshots12.67 OFF /12.92 ON are not the full-window observed rates. Partial-tick CPU boundary and neutral collector limitations are preserved in metrics JSON.

ON cumulative lifecycle totals (include warmup):80626 completed,0 missed/outstanding/commit/domain/region failures; transitExport988/import61. Ledger export34 from network=34 delivered,0 returned/dropped/held; import0 units. Thus the fixed safe export avoided extract/return churn here, but productive transit volume was small and most strategy/main-neighbour/linked-domain work stayed inline.

Both test JVMs stopped gracefully, lock released. **DO NOT DEPLOY0.9.3 as a proven TPS fix.** This run does not satisfy production performance acceptance or establish MM transit acceptance.
