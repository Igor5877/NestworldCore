# STRESS-001 — remove hot-path diagnostic logging distortion

**Status: CLOSED / VERIFIED (2026-10-02).**

**Goal:** default execution of Mod API primitives emits no per-operation INFO logs. Diagnostic
tracing stays available only behind an explicit flag. Semantics, routing, temporal ordering,
mailbox transport and delivery contracts are unchanged.

## Inventory and classification

| Site | Lines | Class | Default runtime now |
|---|---|---|---|
| `EntityMutationDispatcher.dispatch` | `[API1A7]` SUBMIT, OWNER_RESOLVED, ENQUEUE, APPLY (unmanaged / owner-null / same-region / backpressure-fallback), TERMINAL | diagnostic trace | off (`ApiOpTrace`) |
| `EntityMutationDispatcher.applyQueued` | `[API1A7]` TERMINAL TARGET_NOT_FOUND, REROUTE, APPLY, TERMINAL | diagnostic trace | off |
| `EntityMutationDispatcher.applyQueued` | REROUTE_LIMIT_DROPPED | **operational** (degradation) | always-on counter `ApiOpTrace.rerouteLimitDropped`; WARN rate-limited (the first 10, then every 1000th, with the total); the per-op trace line is off |
| `EntityMutationDispatcher.applySetInventorySlot` | `[API1B6] SETSLOT outcome=…` (4 sites) | test evidence | requires `PER_INTENT` **and** `ApiOpTrace` (was `PER_INTENT` only) |
| `NestworldVelocityBackend.setVelocity` | `[API1A7] NestworldVelocityBackend.setVelocity()` | diagnostic trace | off |
| `MailboxRetargeting` | `[API1A7]` REDELIVER, TERMINAL | diagnostic trace | off; the operational signal is the `MailboxLifecycle` terminal counters |
| `MailboxRetargeting` | re-resolution exception WARN | operational (error path) | unchanged |
| `NestWorldInventory.setSlot` | invalid-slot WARN | operational (caller error) | unchanged |
| `VelocityBackendRegistry` | `[API1A7-LIFECYCLE]` install/uninstall | lifecycle, once per server | unchanged |
| `NestworldRegionSystem` | `[API1B6] LIFECYCLE(telemetry)` LOGIN/RESPAWN | lifecycle, once per player event | unchanged |
| `PlayerIncarnationRegistry` | `[API1B6] INCARNATION minted` | lifecycle, once per ServerPlayer object | unchanged |

## Mechanism

`ApiOpTrace.ENABLED` is a volatile flag:
- off by default;
- set by `-Dnestworld.apiOpTrace=true` or `/nestworld apitrace on|off`;
- set by a deterministic fixture for its own duration, then restored.

Every trace site is `if (ApiOpTrace.ENABLED) ApiOpTrace.LOG.info(…)`. The per-call
`LogManager.getLogger("NestWorld/DIAG")` lookups (one per `dispatch`, one per `applyQueued`, one per
SetInventorySlot apply) are replaced by a static logger.

Bytecode check (`javap`), one `getstatic ApiOpTrace.ENABLED` / `ifeq` per site:
- `EntityMutationDispatcher`: 16 sites;
- `NestworldVelocityBackend`: 1 site;
- `MailboxRetargeting`: 2 sites.

The argument evaluation (`getUUID`, `Long.valueOf`, `Thread.getName`, varargs) is inside the
branch, and no per-call `getLogger` remains. With the flag off, a trace site costs one volatile
read: no formatting, no payload construction, no allocation.

The API-1B stress harness used to mute the logger (`Configurator.setLevel(WARN)`), which still paid
argument construction at every call. It now runs with tracing **off**, the real default path.

## Evidence (isolated scratch instance, build 1.20.1-47.4.247, uncommitted tree)

| Check | Result |
|---|---|
| 300 × `setVelocity`, tracing off | **0** `[API1A7]` lines; velocity applied |
| 300 × `setVelocity`, `apitrace on` | 1800 lines = exactly 6 per op (backend, SUBMIT, OWNER_RESOLVED, ENQUEUE, APPLY, TERMINAL) |
| 300 × `setVelocity`, off again | **0** lines |
| `stress-churn`, before STRESS-001 (logger muted) | the old harness muted DIAG to WARN, so its line volume was not comparable; with the mute removed and only `[API1A7]` gated, the first run still logged **46,229** SETSLOT lines (finding below) |
| `stress-churn` / `stress-pinned` / `stress-unpinned`, final | **0** `[API1A7]`, **0** SETSLOT; the whole churn run logged 367 lines (vs 46,560) |
| API-1A deterministic set (`testmutate det`) | traces present with the same phase sequences as before; flag restored to OFF afterwards |
| CORE-MAILBOX fixtures (`testmutate mailbox all`) | 39 pass / 0 fail / 1 skip (L2c needs free-running); REDELIVER traces present |
| API-1B fixtures (17) | 16 PASS; D-ROUTED FAIL, attributed below to a fixture predicate, not to STRESS-001 |
| stress, 11 consecutive runs (7 pinned, 4 churn) | MISSING_TERMINAL 0, invariants 0, nothing queued at end |
| `tests/modapi-compat/run-matrix.sh` | 10 rows, 0 mismatches |

## Findings

**F1 — SETSLOT trace lines were gated only by a telemetry flag (fixed here).** `[API1B6] SETSLOT
outcome=` lines were gated by `InventorySetTelemetry.PER_INTENT`. The stress harness needs that flag
for its final-state check, so the earlier `Configurator` mute was all that kept them out of the
log. They are now additionally gated by `ApiOpTrace`: telemetry stays, logging is off.

**F2 — Intermittent late delivery in stress (OPEN, not fixed; registered as candidate
CORE-PARKED-REGION-001).**
- **Observation:** on build s2 (production code identical to the final build), 2 of 3 runs ended
  with `MISSING_TERMINAL` = 1 (churn) and 8 (pinned).
- **Not lost.** The same runs' accounting showed `unaccounted=0`, with the missing messages
  `stillInFlight` and `reachableInTransport`, i.e. queued in a **live** region. Minutes later the
  accounting showed `stillInFlight=0`, `applied == accepted`. So these were late deliveries past
  the harness's 2-second quiescence window.
- **Not reproduced since:** 11 consecutive runs after an attribution line was added
  (`queued-at-end`, listing every live region with queued messages, its owned-entity count and
  whether it is dispatched each tick).
- **Leading hypothesis, unproven:** the known API1A7-NW-PARKED-REGION liveness gap.
  `RegionThreadPool.tickAllRegions` does not dispatch a non-free-running region that has no owned
  entities and no deferred work, so its mailbox waits until an entity enters.
- **Why it surfaced now:** removing per-op logging changes timing. CORE-MAILBOX-MERGE-001's
  no-loss property is unaffected (C10 clean, `unaccounted=0` in every run).
- **Next step:** reproduce with `queued-at-end` attribution before any fix.

**F3 — D-ROUTED fixture predicate is order-dependent (test bug; fixed in the following separate
commit).** The predicate requires `lastApplied(domain A) == null`. When D-DIRECT runs just before
it, object A is D-DIRECT's respawned object, whose own V1 was legitimately applied there, so the
domain already holds 1. The run itself left A untouched (`tryAcceptCallsDelta=0`,
`versionTrackerTouched=NO`, INCARNATION_MISMATCH, V1 applied once, slot = apple). The correct
predicate is "domain A unchanged by this run".

## Out of scope
- the late-delivery/liveness fix (F2);
- performance measurement (PERF-1);
- any primitive semantics.
