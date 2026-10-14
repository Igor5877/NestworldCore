# AE2-COMPAT-DESIGN-001.3 — DEGRADED handover protocol and corrected M1 exception contract

**Status: DESIGN-1.3 (2026-10-14) — for owner review. No code.**
- Supersedes the M5c runtime barrier (001.2 §1b) and M1 exceptions (001.2 §2.7).
- Everything else in 001.2 and 001.1 stands.

Owner rulings recorded (2026-10-14):
- M3 WITHDRAWN: approved.
- M5c: DEGRADED is chosen over a controlled stop, provided the transition is specified safely.
- M5c startup fail-closed: kept as designed.
- M1: deferred same-thread exact FIFO conditionally approved. Exception aggregation is rejected,
  and so is the word "atomic".

## 1. The problem the protocol must solve
Operations already running per-domain in NORMAL mode, and new global-G operations in DEGRADED mode,
must never mutate AE2 state at the same time. Example:
- Thread A (NORMAL) holds domain 10 and is mutating.
- Thread B detects an unknown participant.
- Thread C starts in DEGRADED and takes G.
- A never sees G, so A and C overlap.

A further constraint, specific to this design:
- **The detector is itself an in-flight NORMAL operation.** Detection happens in
  `EnergyService.addNode`, during a node move, i.e. under T0 plus the involved domains.
- That thread cannot wait for a mode handover: it would wait for itself.
- It must not run the unknown participant's code under per-domain locks either. That would be
  the unproven path M5c forbids.

## 2. State machine
```
NORMAL ──(unknown participant detected in addNode)──▶ QUIESCING ──(in-flight NORMAL == 0)──▶ DEGRADED   (terminal until restart)
```
`mode` is a single `volatile` field. The **in-flight NORMAL count** is a striped set of atomic
counters, one per thread-hash stripe. A single global atomic would make every AE2 entry write one
shared cache line, which defeats the parallelism the adapter exists for. The sum is read only
during a transition.

**Outermost entry (any thread, any guarded AE2 entry)**:
```
if mode == DEGRADED:            acquire G; run op; release G; flush M1 queue; return
if mode == QUIESCING:           if sum(stripes) == 0: complete transition (§3); goto top
                                else wait (timed park on the mode monitor, then re-check); goto top
// NORMAL path
stripe.increment()                                    // (1) announce in-flight
acquire T0 if topology op; acquire domains ascending  // (2) as 001.1 / 001.2
if mode != NORMAL:                                    // (3) validate mode AFTER acquisition, BEFORE first mutation
    release domains, T0; stripe.decrement(); goto top   // the QUIESCING branch re-checks sum == 0
validate domain identity (001 §3)                     // unchanged
run op                                                // (4) mutation
release domains, T0; stripe.decrement()               // (5)
if mode == QUIESCING and sum(stripes) == 0: complete transition (§3)
flush M1 queue; return
```
Nested entries inside an in-flight operation (reentrant AE2 calls on the same thread) do not
increment, do not check the mode and do not take G. They belong to the outer operation.

**Detection (inside an in-flight NORMAL `addNode`, thread B, holding T0 and its domains):**
1. Record the full diagnostic (§4).
2. Do **not** add the unknown participant to `providers` / `requesters`, and do not call any of
   its methods, its getters included. Instead, append `(service, node, participant)` to the global
   **pending-admission list**. The rest of `addNode` runs normally, so the node joins the grid and
   all other services.
3. Publish `mode = QUIESCING`. This is a volatile write, done while still holding the domain locks.
4. Finish B's operation, release its locks, decrement its stripe. Step (5) of the entry protocol
   may complete the transition.

## 3. Completing the transition
- **Trigger.** Any thread that observes `mode == QUIESCING` and `sum(stripes) == 0`:
  - at step (5) of its own exit;
  - in the QUIESCING branch at the top (a waiter's timed re-check, or a thread that backed out at
    step 3).
- Whichever thread performs the last decrement reads the sum after it and sees 0, by sequential
  consistency. A completion can therefore never be missed. Waiters re-check on a timeout as a
  liveness backstop.
- **Detection by mode.**
  - In DEGRADED: under G, the participant is admitted directly.
  - In QUIESCING, from an operation that was already in flight: appended to pending admission.
- **The completer:**
  1. acquires G;
  2. re-checks `sum(stripes) == 0`, then sets `mode = DEGRADED`;
  3. **runs every pending admission** under G (`addProvider` / `addRequester`, plus the accounting
     `addNode` skipped for that participant);
  4. releases G and signals all waiters.
- **Bound on the pending window.** It lasts until in-flight NORMAL operations drain.
  - The main thread's END tick always enters AE2 (`Grid.onServerEndTick`), so it would complete
    the transition at the latest one tick later. No waiting for an unrelated event is needed.
  - The window is visible in diagnostics: participant, start time, completion time.
- **Not a silent exclusion.** The participant is admitted with full gameplay semantics once AE2 is
  serialized. The delay equals the drain time, and the diagnostic is loud.

## 4. Proof: no overlap between per-domain and global-G execution
**Claim.** No NORMAL operation mutates AE2 after the first DEGRADED operation begins. The unknown
participant's code never runs under per-domain locks.

1. **Validation point.** A NORMAL operation mutates only after step (3) observed `mode == NORMAL`.
   That read happens after step (1)'s increment and after the domain acquisition.
2. **Publication.** `mode = QUIESCING` is published before any DEGRADED operation can exist.
   DEGRADED is set only by the completer, which requires `sum(stripes) == 0` after QUIESCING is
   visible.
3. **Dekker argument** (Java volatile and atomic accesses are sequentially consistent).
   - Consider a NORMAL operation X that is not counted when the completer reads the sum.
   - Either X's increment comes after that read in the total order, or X already decremented.
   - If X incremented later, its step (3) read of `mode` comes after the completer's earlier
     write of QUIESCING, so X sees non-NORMAL and backs out before mutating.
   - Hence `sum == 0` observed after publication means **no operation is in, or can enter, the
     mutation phase of the NORMAL path.**
4. **DEGRADED operations start only under G, after DEGRADED is set** (by 2–3). So no DEGRADED
   operation overlaps a NORMAL mutation. Operations queued at the QUIESCING wait hold nothing.
   Operations that backed out at step (3) released everything before waiting.
5. **No wait-cycle during the drain.**
   - In-flight NORMAL operations wait only on T0 and domain locks, in the global order of 001.1.
   - QUIESCING waiters and backed-out operations hold no AE2 lock.
   - The completer takes G only when no NORMAL operation is in flight.
   - Hence the drain terminates. Detector thread B is one of the in-flight operations and never
     waits on the mode.
6. **Unknown participant isolation.**
   - B does not call it.
   - It is not in any power set until admission under G.
   - Other in-flight NORMAL operations on B's domain cannot reach it. Those that were waiting on
     B's locks reach step (3) and back out.
   - Operations on other domains never had it.
7. **After DEGRADED**, every outermost entry is serialized by G. Single outer lock plus reentrancy
   means no AE2 lock inversion. M1 still applies, and flush happens at G release.

**DEGRADED is terminal until restart.** The class is persisted, so the next startup fails closed
(001.2 §1a). There is no path back to NORMAL at runtime.

## 5. Diagnostic record (DEGRADED trigger)
One structured record, written to the log and to a world-data file:
- **Participant:** class; mod id; mod version; code source (jar path and sha256).
- **Where:** AE2 domain (grid serials and member count), node position and dimension, region id
  if the thread is a region thread, thread name.
- **What:** reason (`UNKNOWN_IAEPowerStorage_AT_RUNTIME`, or another detector, e.g.
  `UNAUDITED_NOTIFICATION_PATH`, `T1_OUTSIDE_HELD_SET`); the detecting stack.
- **When:** timestamp and NestWorld run / server id; the QUIESCING → DEGRADED duration; the
  pending-admission count.

## 6. M1 exception contract (corrected)
- Notifications are queued in exact FIFO order inside a guarded section.
- At the outermost exit, after releasing all AE2 locks, the queue is flushed on the same thread:
  - delivery in order 1, 2, … ;
  - **at the first exception, stop.** The remaining notifications of that flush are discarded,
    never executed, and the original exception propagates;
  - no later exceptions can arise, so there is no aggregation and no suppressed list.

**If the guarded AE2 operation itself threw (`E_op`)** after queueing notifications:
- Those notifications were issued before `E_op`. In vanilla they would have run inline, before
  `E_op`.
- They are flushed in order:
  - if none throws, `E_op` propagates;
  - if notification k throws `E_k`, the flush stops there and `E_k` propagates, as in vanilla,
    where k would have unwound before `E_op` existed.
- `E_op` is then logged separately, loudly, as a NestWorld diagnostic. It is not attached to
  `E_k`.

**Honest contract (replaces "atomic").**
- The domain mutation runs to its end under the concurrency guard. Deferred callbacks are
  delivered after the guard.
- An exception in a callback does not roll back a domain mutation that already happened.
- There is no transactional atomicity and no rollback.
- **Divergence from vanilla.** In vanilla, a callback exception would have unwound the energy
  operation part-way. Here the operation has already finished when the callback runs. The
  external caller sees the same first exception at the same call boundary.

## 7. Status
| Item | State |
|---|---|
| M1 | conditionally approved; exception contract corrected (§6) |
| M2 | unchanged |
| M3 | WITHDRAWN (approved) |
| M4 | unchanged |
| M5c startup | fail-closed (approved) |
| M5c runtime | DEGRADED via the §2–§4 handover; for review |
| Implementation | still not started |

## 8. Owner approval and binding invariants (2026-10-14)
**Design-1.3 APPROVED. AE2 compatibility addon implementation APPROVED. Production unpin NOT
approved yet.** Designs 1, 1.1 and 1.2 are superseded by 1.3. M3: WITHDRAWN / VERIFIED UNNECESSARY.

Invariants that the code and tests must hold:

1. **Correctness-grade stripes.** The in-flight count is an `AtomicIntegerArray` (or `VarHandle`
   equivalent) with explicit volatile semantics. `LongAdder.sum()` is **not** a quiescence proof.
   - Publishing QUIESCING is a volatile write.
   - The final NORMAL check before the first mutation is a volatile read.
   - Both are memory-ordering boundaries.
2. **No dependence on the next main tick.** Every NORMAL exit that observes QUIESCING calls
   `tryCompleteTransition()`, including detector thread B after its own decrement.
   - Contract: transition latency ≤ the time until the longest already-active guarded operation
     completes, plus scheduling overhead.
   - The "at most one tick" claim of §3 is withdrawn. A hung operation also holds back DEGRADED.
3. **Exactly one transition owner.** QUIESCING → DEGRADED is a CAS. Only the winner finalizes
   and drains pending admissions. The others observe DEGRADED and use G.
4. **Synchronous `addNode` stays synchronous.**
   - The queued item is the **exact continuation** of the unfinished part of `addNode`: the
     `IAEPowerStorage` branch, with its accounting and `addProvider` / `addRequester`. It is not
     a re-run of `addNode`, so `localStorage.addNode`, topology bookkeeping and the other branches
     are never duplicated.
   - The detector's **original outermost call does not return to its caller until its
     continuation has executed under G.**
   - Order at B's outermost exit:
     1. release the locks;
     2. decrement;
     3. `tryCompleteTransition()`;
     4. wait for its own continuation, retrying the completion;
     5. deliver post-commit callbacks (M1 and G2);
     6. return.

**M1 note.** The `E_op` rule of §6 is an **exception-path semantic approximation**, not vanilla
equivalence. Side effects AE2 performed after the point where vanilla would already have thrown are
not rolled back.

**Required gate after implementation, in this order:**
1. deterministic tests;
2. transition-race tests;
3. deadlock tests;
4. AE2/Pipez reproduction;
5. same-domain contention;
6. different-domain parallelism;
7. topology churn;
8. the SolarFlux conservative case;
9. soak;
10. production shadow / canary;
11. only then an unpin review.

**Mandatory deterministic scenario:**
1. A holds D1.
2. B detects an unknown participant under NORMAL.
3. C attempts a new NORMAL entry.
4. A exits.
5. B and C race to complete.

Expected, over thousands to tens of thousands of controlled iterations:
- exactly one DEGRADED transition;
- exactly one admission;
- zero overlap between NORMAL and G mutation;
- B's original call returns only after its admission.
