# Core track plan (owner 2026-11-09)

Runs in parallel with the AE2 runtime track (`docs/compat/AE2-COMPAT-ARCHITECTURE.md` §6). Generic Core mechanisms only.

| item | status |
|---|---|
| CORE-AUTOPIN-DIAGNOSTICS-001 | IMPLEMENTED dad112879; smoke self-tests PASS; deployed to project-infinity 2026-11-09; pushed; live verification pending (first real auto-pin event) |
| CORE-SPLIT-DENSE-BE-GUARD-001 | NEXT — auto-split must not cut through dense BE areas (live: split through the base → ghost-zone 44–56 ms) |
| CORE-GHOST-ZONE-DELTA-001 | NEXT — ghost-zone sync of dirty state only; skip BEs that tick on MAIN |
| DistanceManager CME (11-08 16:41) | AUDIT / ROOT CAUSE FIRST — `chunksToUpdateFuturesBulk.forEach` under concurrent modification in the `CHUNK_GEN_BUDGET<=0` branch; no fix before review |
