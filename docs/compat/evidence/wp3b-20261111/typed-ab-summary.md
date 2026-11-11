# Typed inventory dispatch A/B — no measured benefit

Frozen candidate 0.9.2 SHA256 `4edeffa40d3610c7c051b4d1b8cbcc39cf57d95d473e390ecd846be1ad127d9a`.
Evidence: `/root/airis-2026-10-25/WP3B-MINING-20261111/comparison-20261111-144410`.

Same frozen world, 65 unique mining claim chunks, pins, transit ON, heap/affinity/config. Typed dispatch alone OFF then ON. Each warmup 90s, neutral JMX collection 120s; fixtures disabled. Both qualified without gaps/errors. Separate deterministic trace passed plain, side-effect getter, replacement during extraction, serializable Mekanism energy tablet capability (see typed-dispatch-trace.txt).

| Metric | Typed OFF | Typed ON |
|---|---:|---:|
| MAIN CPU ms/observed tick | 70.7458 | 70.8514 |
| Observed TPS | 13.7497 | 13.7415 |
| MSPT average | 61.1342 | 61.2233 |
| MSPT p50 | 53.8721 | 52.5105 |
| MSPT p95 | 100.7929 | 103.8006 |
| MSPT p99 | 124.0092 | 127.8002 |
| Young GC aggregate collection ms | 648 | 391 |

MAIN delta +0.1056 ms/tick (+0.1493%). No practical benefit established; tiny difference does not establish regression either. Do not enable/deploy this optimization for TPS. Default stays OFF. ON was exercised: typedRechecks=1012024, originalDispatch=0. These counters include warmup, not only measurement.

ON transit ledger: 48 extracted from network, 48 delivered, 0 return/drop/held; imports 0 units. 102041 records completed, 0 missed/outstanding/commitFailures/domain/region failures. Productive workload is small; this is not full item-conservation acceptance for every source/type or MM.

Both test JVMs stopped; comparison lock released. Shutdown reports neighbour affinity release warnings after Core disposal (`NestworldRegionSystem not initialised`); not a clean no-warning lifecycle claim. No live changes.

## Current performance verdict

DO NOT DEPLOY the new candidate as a TPS fix. Transit-only earlier pair yielded about 1.75% lower MAIN, not a repeatable practical benefit; typed dispatch yields none. MM transit remains unexercised. AE2 network-side storage extraction remains MAIN, while unfiltered-import strategy and target ownership guards leave much heavy inventory work there. One-region/autoSplit=false is a separate possible bottleneck, not tested by changing layout here.
