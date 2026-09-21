# GMEIL validation gates

## Phase 0 and gateway

- Inventory all server-side entrypoints in `EXECUTION_ENTRYPOINTS.md` with sync/result/phase,
  owner, reentrancy, cross-region, and loader fields. Treat unresolved entries as not certified.
- Unknown callback runs under `MAIN_COMPAT` on the actual server thread with same result,
  exception, phase, ordering, and owner exclusion; compare against ordinary Forge behavior.
- OBSERVE mode records key, policy, context, duration, thread, region, and outcome without
  changing callback behavior.

## Transport and scheduler

- Local fast path avoids mailbox; foreign writes apply once on fresh owner, including after
  split/merge and reroute. Count sent, claimed, applied, failed, pending, and duplicate IDs.
- `SYNC_REQUEST` covers A→B, A→B→A cycle, cancellation, deadline, receiver-claimed timeout,
  callback exception, and token/lease cleanup. No silent replay of opaque callbacks.
- Compound actions retain one gameplay transaction boundary. Player interactions preserve their
  existing result and claimed-CAS semantics.

## Loader and real mods

- Forge events retain listener order, priority, cancellation, result, synchronous completion,
  and exception semantics. NeoForge adapter must pass the same Core contract tests.
- Confirm Forgified Fabric's original chunk-unload Mixin applies against the built `ChunkMap`;
  no NestWorld error handler may downgrade its failures or call its event as a replacement.
- Canary alone is the authorized config exception: when its JAR is detected, only the four
  overlapping collection/POI options become false before Mixin loading. Verify an already-safe
  config is untouched, unrelated options survive, and a late detection requires restart.
- Run original mod JARs, verify unchanged hashes, and disable legacy transformers for the GMEIL
  acceptance workload. Reuse current Mekanism, AE2, Draconic, Flux, Sophisticated Core, Tom's
  Storage, and Industrial Foregoing regressions as behavior oracles.
- Test a large modpack with unrelated certified regions still parallel. Record correctness,
  crash/race/failure counters, main-bridge latency, serialized-lane wait, mailbox depth, and
  throughput; success is safe operation, not maximizing parallel callbacks.

Current 47.4.223/224 coremod tests and clean bootstrap validate the legacy layer only. They do
not satisfy the GMEIL acceptance gates above.
