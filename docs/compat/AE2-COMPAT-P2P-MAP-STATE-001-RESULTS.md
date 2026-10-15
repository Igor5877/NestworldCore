# P2P-MAP-STATE-001 (AE2-COMPAT-P2P-001 H1) — implementation and evidence

**Status: IMPLEMENTED; deterministic fixtures, negative controls, 120 s stress and regression PASS
(2026-10-15). STOP for owner review.** Proposed status: CLOSED / VERIFIED, pending review.

Other sub-gates are unchanged:

| Sub-gate | Status |
|---|---|
| P2P-ACCESS-DEPTH-001 (H2) | OPEN |
| P2P-FORWARDING-001 (H3) | CONFIRMED / OPEN; re-run with H1: unchanged, §6 |
| AE2-COMPAT-P2P-001 | OPEN |
| CALLBACK-UNDER-P-001 | OPEN |

**No unpin.**

## 1. Model (audit §4.1, owner Q3)

One `P2PState` per `P2PService`, i.e. per grid. It is compatibility-side synchronization metadata;
AE2's maps are not moved.
- A `ReentrantReadWriteLock` plus a `generation` counter.
- **Leaf lock:** nothing is acquired while it is held.

| Path | Under the guard | After release |
|---|---|---|
| `getInput(freq)` | read lock | — |
| `getOutputs(freq, cls)` | read lock; the stream is **materialized** (`toList`), so a live view is never returned | iterate the snapshot |
| `newFrequency()` | read lock; **semantics unchanged** (candidate only) | — |
| `wakeInputTunnels` | read lock: copy of `inputs.values()` | `wakeDevice` (T2) on the copy |
| `addNode` / `removeNode` / `updateFreq` | write lock: map mutation, `freq` field write (accessor), `generation++` | `updateTunnel` callbacks (targets captured under the guard, AE2's order), `host.markForSave/markForUpdate` |
| **`allocateAndBind(input, candidate)`** (new) | write lock: use the candidate if still free (or already this tunnel's), else choose another unused one; bind via the reentrant `updateFreq`; `generation++` | the callbacks of the nested bind |
| call site `P2PTunnelPart.onPartShiftActivate` | `candidate = newFrequency(); updateFreq(this, candidate)` → `allocateAndBind(this, candidate)` | — |

- **Diagnostics:** `p2pReads`, `p2pWrites`, `p2pDeferredCallbacks`, `p2pAllocRebound`,
  `p2pInputDisplaced`.
  - The displacement detector keeps vanilla behaviour and only counts and logs.
  - It ignores freq 0, AE2's "unbound".
- The callbacks run outside the guard, and the evidence shows it: the jstack in §7 has
  `markForSave → getChunk` executing from `P2PState.write`'s post-release loop.

## 2. Files

Production:
- `glue/P2PState.java`;
- `glue/NwP2PService.java`;
- `mixin/P2PServiceMixin.java`;
- `mixin/P2PTunnelPartMixin.java`;
- `mixin/P2PTunnelPartAccessor.java`;
- counters in `glue/Ae2Compat.java`;
- 3 entries in `nestworld_compat_ae2.mixins.json`.

Fixtures and rig:
- `glue/P2PFixtures.java` (`h1setup` / `h1` / `h1stress`);
- `NestworldCompatAe2.java` (commands);
- `rig/h1-run.sh`;
- `rig/negp2p_noguard.py` (negative control: `P2PState.PROTECT = false`, i.e. vanilla AE2).

## 3. Deterministic fixtures (`rig/h1-run.sh`)

The rig is `/root/compat-ae2-rig` (scratch).
- **G1:** 5 FE tunnels on one ad-hoc grid.
- **G2:** one FE tunnel on another grid.
- Fixture threads are plain threads coordinated by latches and barriers.
- H1-2 goes through the **real** memory-card call site (`onPartShiftActivate`, a `FakePlayer`
  holding a memory card).

| Case | Interleaving | Guard ON | Guard OFF (negative control = vanilla AE2) |
|---|---|---|---|
| **H1-1 LIVE-VIEW** | the reader pauses after the first element of `getOutputs(F)` while a writer moves one output to F2 | **PASS**: the reader saw the stable snapshot (2) | **FAIL: `ConcurrentModificationException`** |
| **H1-2 ALLOCATE-RACE** | two fake players shift-activate T3 / T4; both `newFrequency()` calls are forced to 777; barrier between pick and bind | **PASS**: 777 / 29396, both inputs present, `allocRebound=1`, `inputDisplaced=0` | **FAIL: both bound 777; input A displaced** (`inputs[777]` = B), `inputDisplaced=1` |
| **H1-3 UPDATE-RACE** | writer 1 pauses inside `updateFreq` (removed, not yet re-inserted) for up to 300 ms; writer 2 updates the same tunnel | **PASS**: freq 203, membership `[203]` (writer 2 blocked → serial order) | **FAIL: freq=202 but member under `[203, 202]`**, which matches no serial order |
| **H1-4 INDEPENDENT-GRIDS** | a G1 writer parked 2 s inside its guard; a G2 writer and a second G1 writer start | **PASS**: G2 writer **0 ms**; second G1 writer **1999 ms** (serialized only within its grid) | (no lock, so nothing to serialize; not a discriminating case by design) |

Every negative control reproduced exactly the violation its fixture asserts against. After the
negative run, the clean jar was rebuilt (`PROTECT = true`, verified).

## 4. Stress (120 s, `nwcompatae2 p2p h1stress`, JFR recording)

| Workload | Count |
|---|---|
| reader threads: `getOutputs` / `getInput` / part `getOutputs` | 4 |
| writer threads: `updateFreq` of outputs, 1/50 `allocateAndBind` via the real call site | 2 |
| **RegionThread topology churn**: add/remove an FE tunnel part on G1 (`addNode` / `removeNode` under P(G)) | — |
| atomic invariant checker (membership == [freq] or empty, under the read guard) | 1 |

| Result | Value |
|---|---|
| verdict | **PASS** |
| reads | 49,383,744 |
| writes | 8,090,914 |
| allocateAndBind (real call site) | 162,266 |
| topology churn | 2,400 |
| invariant checks | 56,225 |
| **violations** | **0** |
| `p2pInputDisplaced` during the run | **0** |
| exceptions / CME | 0 |
| kernel | NORMAL, `unauditedPaths=0`, `callbackExceptions=0` |

**JFR** (`jdk.ThreadPark` and `jdk.JavaMonitorEnter` at a 1 ms threshold, profile settings,
`evidence/h1-stress120-110338.jfr`):
- **Guard waits ≥ 1 ms:** 2212 events against about 57 M operations.
  - p50 1.85 ms, p99 26.1 ms, max 65.3 ms; 5.8 s total across all threads in 120 s.
  - **RegionThread topology writers:** 7 waits, max 3.0 ms.
- **The longest waits coincide with GC.** The longest GC pause in the recording is **64.6 ms**,
  against a max guard wait of 65.3 ms. A parked waiter's duration includes the STW pause.
  CORRELATED, not proven per event.
- An earlier 120 s run showed a max wait of 28.8 ms against a 9.6 ms GC pause. The holder of that
  window was not identified; it is most likely a lock holder descheduled under a tight hot loop.
  HYPOTHESIS.
- **Top CPU frames:** QUEUE-001 staging drain (`QueueStaging.drainLevel`). This is a stress
  artifact: about 67,000 frequency changes per second each queue an AE2 `addCallable`, which is
  far beyond real play.
- **Not measured:** realistic-load overhead (TPS / MSPT against the pinned baseline). That belongs
  to the canary.

## 5. Regression (same rig, H1 jar)

| Suite | Result |
|---|---|
| QUEUE | 8/8 PASS (staged=32000) |
| T2 | 4/4 PASS (STALE / WAKE / ALERT / SELF) |
| GRID-PHASE | STAGING ×5, OVERLAP 6/6, REPLAY-FRESH, MULTIGRID (600/600, starvation 0), STRESS (1201 toggles), NESTED (→ DEGRADED as designed): all PASS |
| integration | LIFE DURING / BEFORE, STAGING: 3/3 PASS |
| LEGACY | migrated 2 / unchanged 2, no legacy keys saved, no re-migration, live-world fail-closed `liveSkipped=1`: PASS |
| exceptions | 0, apart from NESTED's expected `UnauditedPathException` |

**Rig defect found and fixed (not a regression).** In `legacy-run.sh`, the initial `fill … air`
runs in the same RCON call as `forceload add`, before the chunk is loaded. A drive from an earlier
run therefore stayed at x=1005, and the live-world `/setblock` with identical block and NBT never
reached `onReady` (first attempt: `liveSkipped=0`). The script now clears the position first. A
manual probe gave 4/4 increments with the WARN, and the scripted re-run gave `liveSkipped=1`.

## 6. H3 unchanged (expected)

`rig/p2p-run.sh` re-run with the H1 jar:
- 9A conserved (FE 3/3, item 2/2);
- 9B FE: accepted 1000 / destination +0 (3/3);
- 9B item: NPE into the caller (2/2);
- 9C: live cross-owner mutation (FE 3/3, item 2/2).

H1 makes the maps safe. It does **not** make forwarding safe across regions.

## 7. Fixture defects found while building this evidence (kept for the record)
- **The synchronous fixture command deadlocked against itself.** Fixture threads calling
  `updateFreq` reach AE2 `markForSave → saveChanges → level.getChunkAt`. That hops to main, which
  was blocked in the command's `join`. Fix: asynchronous coordinator, status polled.
  - The jstack showed `markForSave` executing **after** `P2PState.write` released the guard, the
    designed callback phase.
- **Off-thread `getBlockEntity` returns null** (the vanilla guard on a thread that is neither main
  nor a region thread). Tunnels are now resolved on main before the fixture threads start.
- **The stress coordinator's join budget started at run start, not at run end**, so the first
  report said "threads did not finish" while the threads were still in their run window. Fixed.
- **The displacement counter counted freq-0 inserts** (unbound tunnels, vanilla AE2). Now excluded.
- `p2pInputDisplaced=5` at world load after the negative run: the negative run left two inputs
  bound to 777 in the saved world, and vanilla load displaces one. This is world pollution from the
  negative control, not a guarded-run event; it was 0 during every guarded fixture and the stress.

## 8. Open / not covered
- Integrated server: not run (no client on the rig).
- Realistic-load overhead: canary.
- H2, H3, CALLBACK-UNDER-P-001: untouched.
