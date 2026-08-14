# P2 — RegionThreadPool / Global Barrier Audit

Status: governing spec for P2, given verbatim by the user on 2026-08-12 immediately after P1.5
passed. P1 is fully closed (P1.0-P1.5 all done, two sub-phases closed negative). P2 targets
NestWorldCore's remaining fundamental architectural limit: the main thread waits for ALL region
threads via `CountDownLatch` (`RegionThreadPool.awaitLatch()`) every tick, across 5 call sites.
If one region goes 5ms → 20ms → 100ms → 1s → 30s, every other already-finished region's visible
progress stalls with it. After `TICK_WAIT_LIMIT_NANOS = 30s`, main thread may proceed while that
region could still be mutating world state in the background — a correctness risk, not just perf.

**Explicit instruction: audit first, no code changes yet.** Do not touch Stage 4/5, do not touch
the latch/concurrency mechanism itself. P0/P1 already showed several "intuitively obvious"
bottlenecks were not real — don't assume this one is either before the data says so.

## Audit questions

1. How much real time does main thread spend in `awaitLatch()`?
2. How much of that is genuine waiting on a region thread vs. `pollTask()`/main-thread work done
   opportunistically inside the busy-wait?
3. Which region is most often the slowest (stable per-region-id ranking, not just an aggregate max)?
4. Is there a stable correlation: slowest region → global tick stall?
5. What exactly happens after the 30s timeout?
6. Can an "abandoned" region thread (past the 30s timeout, main thread has moved on) still mutate
   world state? This is the most safety-critical open question.
7. Is there a safe way to move from "wait for all regions" to a non-globally-blocking model, without
   introducing dangerous local-clock drift?

## Prior art already answering some of these (from [[p0-regionthreadpool-mainthread-wait-audit]])

- **Q1/Q2 — ANSWERED (as of the P0.1/P0.2 readings, pre-P1/P1.5)**: `actual_latch_wait ≈ 0.014ms`
  per tick — main thread essentially NEVER genuinely idle-waits on a slow region. The entire
  `awaitLatch()` window was filled with real main-thread work, overwhelmingly
  `DistanceManager.runAllUpdates()` (which had its own multi-invoke bug, since fixed in P0.2 —
  `nestworldRunValidatorBatch()` gated to once/tick, ~7x pollTask cost reduction). **This reading
  predates P1's telemetry and P1.5's load testing — needs re-verification, not assumed still true.**
- **Q5 — ANSWERED**: `awaitLatch()` logs an ERROR and breaks out (`RegionThreadPool.java:203-207`)
  on timeout — does NOT interrupt or kill the slow region thread.
- **Q6 — FLAGGED, NOT CONFIRMED**: prior audit explicitly named this "a genuine correctness risk"
  but did not trace whether the region thread actually keeps mutating after main thread proceeds,
  or what guards (if any) exist. This is the priority unanswered question for P2.
- **Q3/Q4 — NOT YET ANSWERED**: existing Phase 1 telemetry (`RegionPhase` enum, per-call-site
  dispatch/wait/pollTask/region-tick stats) is aggregate across regions, not broken down by which
  specific region ID is slowest most often. Needs new tracking if not already present.
- **Q7 — design question, explicitly deferred until the audit (Q1-Q6) is done.**

## Constraints

- Audit only. No behavior changes to the latch, timeout, or dispatch mechanism.
- Adding pure measurement telemetry (zero behavior change) is fine, matching the precedent set by
  the existing Phase 1 `RegionPhase`/`PhaseStats` and P0.1/P0.2 attribution work.
- Do not touch Stage 4/5 free-running-region code.
