# Core track plan (owner 2026-11-09)

Runs in parallel with the AE2 runtime track (`docs/compat/AE2-COMPAT-ARCHITECTURE.md` §6). Generic Core mechanisms only.

| item | status |
|---|---|
| CORE-AUTOPIN-DIAGNOSTICS-001 | IMPLEMENTED (dad112879) · SMOKE VERIFIED · LIVE DEPLOYED · LIVE STARTUP VERIFIED · REAL-FAILURE EVIDENCE PENDING (first live `AUTO_PIN_FIRST_FAILURE`) |
| CORE-SPLIT-DENSE-BE-GUARD-001 | NEXT — auto-split must not cut through dense BE areas (live: split through the base → ghost-zone 44–56 ms) |
| CORE-GHOST-ZONE-DELTA-001 | NEXT — ghost-zone sync of dirty state only; skip BEs that tick on MAIN |
| DistanceManager CME (11-08 16:41) | AUDIT / ROOT CAUSE FIRST — `chunksToUpdateFuturesBulk.forEach` under concurrent modification in the `CHUNK_GEN_BUDGET<=0` branch; no fix before review |

## Priority (owner 2026-11-09, by production impact)

```
1. CORE-SPLIT-DENSE-BE-GUARD-001   AUDIT / DESIGN FIRST  (confirmed repeated live regression: split through the base
                                    3× → ghost-zone 44–56 ms/tick, overworld ~89 ms, after merge ~46 ms)
2. CORE-GHOST-ZONE-DELTA-001       after the split cost model is understood
3. DistanceManager CME             root-cause audit
4. DistanceManager implementation  only after review
```

CORE-SPLIT-DENSE-BE-GUARD-001 direction: not `BE > N → never split` (loses useful parallelism). Target
`net_gain = expected_parallel_gain − expected_boundary_cost; net_gain <= safety margin → VETO`, or as a first
conservative step: candidate crosses a dense BE zone + predicted ghost cost high → reject this boundary → try another
boundary → only then reject the split. Veto a bad split line, not the region's split.
