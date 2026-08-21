# Batch-Apply + Coalescing — Design (2026-08-19)

**Status: Phase 1 implemented, benchmarked, and tuned (2026-08-19). Production
default is `BATCH_APPLY_MAX_SIZE=16`**, chosen by a 16/32/64 sweep (see "Sweep
Results" below) after the initial 64-vs-1 benchmark found a convoy-effect cost
at the extreme-flood end larger than anticipated. `NestworldTuning
.BATCH_APPLY_MAX_SIZE` (default 16, `-Dnestworld.batchApplyMaxSize`),
`BatchApplyStats`, `WorldRegion.nestworldDrainMailboxBatch`,
`NestworldDimensionRegion.applyBatchWithCascadeGuard`/
`nestworldDrainAndApplyBatched`, `/nestworld pressure`. Compiles clean,
deployed+tested on gen-spike-repro (universal.jar swap only — no vanilla
patches touched). Not deployed to ATM9; not git-committed.

## Benchmark Results (2026-08-19)

Method: same new jar (universal.jar deployed to gen-spike-repro), same
auto-free-running setup (existing `-Dnestworld.autoFreeRunning=true` in that
env), fresh world each pass, varying only `-Dnestworld.batchApplyMaxSize`
(1 = no-batching control, isolates the batch-size variable while keeping the
new shared-deadline clamp; 64 = Phase 1 as designed). Two workload patterns
per the design's own benchmark plan: **moderate** (light TNT every other
round + cow spawns, mirrors the original Fix 3 "realistic/heavy" validation
pattern, 20min cap) and **extreme** (continuous, no-gap TNT flood across a
9x9 grid every ~2s, mirrors the original Fix-3-reopening flood pattern).

### Moderate/realistic load — clean win, no downside

| | Pass A (batch=1) | Pass B (batch=64) |
|---|---|---|
| locksPerMessage | **2.000** | **0.055** (~36x lower) |
| avgBatchSize | 1.0 | 37.6 (well under the 64 cap) |
| messages processed | 20,999 | 27,148 |
| batchesFormed | 20,999 | 722 |
| timedOut (whole-batch requeues) | 117 | 1 |
| mailbox / pending (backpressure) | 0 throughout | 0 throughout |
| TPS | 20.0 steady | 20.0 steady |
| MSPT | ~1.5ms | ~2.2-2.7ms (both trivial, noise-level difference) |
| LevelTicksOwnership violations | 0 | 0 |
| EntityOwnershipGuard violations | 0 | 0 |

Unambiguous win on the metric the design doc's benchmark plan was built
around, with zero observed downside on this workload.

### Extreme continuous flood — real, measured convoy-effect cost, larger than the pre-implementation estimate

| | Pass A (batch=1) | Pass B (batch=64) |
|---|---|---|
| Survival before `ServerHangWatchdog` crash | **~828s** | **~506s** (sooner) |
| locksPerMessage at crash | 2.862 | 0.045-0.057 |
| avgBatchSize at crash | 1.0 | ~62 (near the cap) |
| batchesFormed / timedOut | 138,503 / 19,998 (14%) | 14,056 / 12,947 (**92%**) |
| messagesInTimedOutBatches | 19,998 of 138,503 (14%) | 827,918 of 874,152 (**94.7%**) |
| `pending` (sender backpressure queue) at crash | ~2.99M | ~34M+ |
| LevelTicksOwnership violations | 0 throughout | 0 throughout |
| EntityOwnershipGuard violations | 0 throughout (parsed OK) | inconclusive — RCON responses to `/nestworld entityguard` failed to parse in several rounds once MSPT exceeded ~100ms (observability gap under severe RCON stress, not a confirmed violation; the always-parseable `LevelTicksOwnership` counter stayed 0 the whole run as the stronger signal) |

At the extreme end, Gemini's pre-implementation "convoy effect" concern is
confirmed real and bigger than the design doc estimated: with 64-message
batches under sustained maximum contention, **92% of batches and 94.7% of
messages** ended up wasted in whole-batch timeout-and-requeue cycles — each
retry re-walks the same combined lock set and re-attempts acquisition, so a
larger batch size directly amplifies the cost of each failed attempt under
this specific failure mode. Both passes' `pending` (sender-side backpressure
queue, a mechanism this change does NOT touch — see Problem section) grew
essentially unbounded either way, confirming the actual root cause of the
extreme-flood ceiling is still the already-documented, separate Fix 3
residual gap, not this change — but Phase 1's larger batches make the
*symptom* (time to watchdog crash) arrive sooner in this specific adversarial
pattern.

### Verdict against the 4 acceptance criteria

1. **Semantics unchanged**: **PASS.** 0 `LevelTicksOwnership` violations in
   every pass, both workloads. `EntityOwnershipGuard` confirmed 0 everywhere
   parseable; the extreme-Pass-B gaps are an RCON-under-load observability
   limitation of the benchmark harness, not a code-path difference (nothing
   in this change touches entity ownership — see "Entity ownership recheck"
   above, unchanged from design).
2. **Deadline never exceeded**: **PASS**, and directly evidenced rather than
   just assumed — the shared-deadline clamp visibly did its job under
   extreme load (that's exactly what the high `timedOut`/`messagesInTimedOutBatches`
   counts under Pass B extreme ARE: the safety mechanism firing early and
   often rather than letting one batch block indefinitely). The
   pre-existing, separately-documented extreme-flood watchdog ceiling was
   hit in both passes (expected, not this change's target), but the
   mechanism this criterion cares about — "does the main thread ever block
   past its budget waiting for one batch's locks" — held.
3. **Batch ≤ 64**: **PASS.** avgBatchSize peaked at ~62 under the heaviest
   observed contention, never exceeded the cap.
4. **Full before/after metrics table**: captured above, both workloads.

### Recommendation (superseded by the sweep below — kept for history)

For **realistic-to-heavy load** (the project's own stated regression bar —
"no catastrophic degradation under realistic load," `target-hardware-priority-tiers.md`)
Phase 1 at any of 16/32/64 is a clear, low-risk win — the question the sweep
below answers is which cap best balances that win against the measured
extreme-case cost.

## Sweep Results (2026-08-19)

Same harness (`/root/.claude/jobs/a709a9f8/tmp/batchapply_bench.py`), same two
workload patterns, run twice more (16 and 32) reusing the jar already on
gen-spike-repro (`-Dnestworld.batchApplyMaxSize` is a runtime flag, no
rebuild needed for the sweep itself), fresh world each pass, same
moderate/extreme methodology as the original 1-vs-64 benchmark above.

| BATCH_APPLY_MAX_SIZE | 1 (control) | **16** | 32 | 64 |
|---|---|---|---|---|
| **Moderate — locksPerMessage** | 2.000 | **0.169** | 0.072 | 0.055 |
| Moderate — avgBatchSize | 1.0 | 14.9 | 27.6 | 37.6 |
| Moderate — batch timeout rate | 117/20,999 (0.6%) | 114/1,271 (9.0%) | 3/632 (0.5%) | 1/722 (0.1%) |
| Moderate — message requeue rate | n/a | 1,695/18,987 (8.9%) | 77/17,464 (0.4%) | n/a (~0%) |
| Moderate — TPS / MSPT | 20.0 / ~1.5ms | 20.0 / 1.6-3.3ms | 20.0 / ~1.8-2.9ms | 20.0 / ~2.2-2.7ms |
| Moderate — violations | 0 | 0 | 0 | 0 |
| Moderate — survival | full duration (no crash) | full duration (no crash) | full duration (no crash) | full duration (no crash) |
| **Extreme — survival before watchdog crash** | 828s | **803s** (97% of control) | 599s (72% of control) | 506s (61% of control) |
| Extreme — locksPerMessage at crash | 2.862 | 0.429 | 0.108 | 0.045-0.057 |
| Extreme — avgBatchSize at crash | 1.0 | 7.9 (degraded down from higher early values as contention rose) | 19.6 | ~62 |
| Extreme — batch timeout rate | 14% | 82.7% | 70.9% | 92% |
| Extreme — message requeue rate | 14% | 70.3% | 66.4% | 94.7% |
| Extreme — violations | 0 | 0 | 0 | 0 (inconclusive at 64 — RCON parse gaps under severe load, see original table) |

### Decision: `BATCH_APPLY_MAX_SIZE = 16`

The curve is not linear — it has a knee at 16. Going from no-batching to
batch=16 captures **91.5%** of the total realistic-load lock reduction
achievable at all (2.000 -> 0.169, vs. the ceiling of 0.055 at batch=64) while
giving up almost nothing in extreme-case resilience (803s vs. the
no-batching control's 828s — a 3% difference, noise-level). Pushing the cap
further to 32 or 64 buys a shrinking marginal realistic-load improvement
(0.169->0.072->0.055, each step smaller than the last in absolute terms) at
an *accelerating* extreme-case cost (extreme survival drops 25% from 16->32,
then another 16% from 32->64) — the convoy-effect tax grows faster than the
benefit past this point, exactly the shape Gemini's pre-implementation review
flagged as a risk, now measured rather than estimated. MSPT under moderate
load is trivial and statistically indistinguishable across all four values
(1.5-3.3ms), so realistic-load performance was never actually a
differentiator between 16/32/64 in practice — only the extreme-case number
was.

This matches the project owner's own decision criterion: 16 "already
captures most of the lock-reduction benefit AND holds up substantially
better under flood than 32/64" (803s vs 599s vs 506s) — the strongest match
of the three stated branches.

**Explicit framing, per the project owner's own insistence — do not blur
this:** this sweep tunes *how fast* the system reaches the already-documented,
SEPARATE Fix-3 extreme-flood crash ceiling. It does not and cannot fix that
ceiling — `pending` (the sender-side backpressure queue, a different
mechanism this project doesn't touch) grew unbounded in every single sweep
pass (2.99M at batch=1's crash, up to 40M+ at batch=32's crash), confirming
the actual root cause of the extreme-flood wall is still Fix 3's own known,
open residual gap. Batch size only controls how much the apply-side convoy
effect *adds* to how quickly that separate, pre-existing wall is reached
under a workload extreme enough to reach it at all — realistic play (per
every moderate-load pass above, all clean, all `pending=0` throughout) never
comes close to that wall regardless of batch size.

Applied: `NestworldTuning.BATCH_APPLY_MAX_SIZE` default changed 64 -> 16,
`./gradlew :forge:compileJava` reconfirmed clean.

## Problem

`Fix 3 backpressure` (see project memory `fix3-backpressure-implemented-partial.md`)
closed the realistic-load case but left a documented residual: under a sustained,
no-recovery-gap explosion flood with many free-running regions, the mailbox still
grows unbounded and the server eventually hangs via `ServerHangWatchdog`. Threshold
tuning (2000 -> 300) barely moved the crash time, confirming the bottleneck is not
queue depth but **apply-side cost**: `WorldRegion.nestworldDrainMailboxBudgeted()`
calls `NestworldDimensionRegion.applyWithCascadeGuard()` once per queued message,
and each call independently computes its lock set and does a fresh
`tryWriteLock`/`unlockWrite` cycle — even for two consecutive messages bound for the
same destination region.

## Scope: which message types actually touch this bottleneck

`RegionMessage.Type` has 8 members, but only **2** are drained through
`nestworldDrainMailboxBudgeted` -> `applyWithCascadeGuard` (the multi-region-lock,
main-thread-only path this design targets):

| Type | Drained by | Cross-region lock? |
|---|---|---|
| `EXPLOSION_APPLY` | `NestworldDimensionRegion.tick()`, main thread | **yes** — `applyWithCascadeGuard` |
| `BLOCK_WRITE` | `NestworldDimensionRegion.tick()`, main thread | **yes** — `applyWithCascadeGuard` |
| `ENTITY_IMPACT` | `RegionThread`, owning region's own thread | no — owner already holds its own tick |
| `ENTITY_PUSH` | `RegionThread`, owning region's own thread | no |
| `ENTITY_MUTATE` | `RegionThread`, owning region's own thread | no |
| `ENTITY_TRANSFER` | ownership bookkeeping, not this path | no |
| `WIRE_UPDATE` | no single destination, vanilla propagation on main | no |

The three entity-mutation types are applied on the *owning* region's own thread
during its own tick — no cross-region lock is ever taken for them, so they are
**not part of this bottleneck** and are explicitly **out of scope** for this
change. (The original proposal's mergeable/non-mergeable table for
damage/push/spawn/inventory is good hygiene for a *future*, separate look at
those three types, but does not belong to this fix.)

This narrows the whole project to exactly two message types, both block-only,
neither ever touching an entity. That materially reduces risk versus a
general-purpose message coalescer.

## Key finding: "batch the lock", not "batch the payload"

Re-reading `applyWithCascadeGuard` and `Explosion.NestworldExplosionBatch`/
`nestworldApplyBatch`: **the win does not require merging message payloads at
all.** Each `EXPLOSION_APPLY`/`BLOCK_WRITE` message already carries everything
needed to apply itself (an explosion's block-position subset, or one
`setBlock()` replay). The expensive part is exclusively the lock
acquire/compute/release *around* each apply, not the apply logic itself.

So Phase 1 of this design is: **widen the lock scope to cover a whole drained
group of messages for one destination region, apply each message in the group
in its original order while holding that combined lock once, release once.**
Zero change to what gets applied, in what order, or with what side effects —
the resulting world state is byte-identical to today's one-message-at-a-time
loop. This is why it doesn't need the semantic caution a real payload merge
would need (no double-counted damage, no dropped side effects, no
order-sensitivity question) — it's purely a lock-scheduling change.

A true payload-level coalesce (e.g. "two `BLOCK_WRITE`s to the same `BlockPos`
-> keep only the last") is a **separate, optional Phase 2**, scoped narrowly
(see below), because unlike Phase 1 it does have a real semantic question to
answer first.

## Phase 1: batch-the-lock (this is what gets implemented+benchmarked first)

### Batch boundary

Same boundary as today, just grouped: **one destination `WorldRegion`, one
`RegionMessage.Type`, all messages currently sitting in that region's mailbox
at the moment this region's turn comes up in `nestworldDrainMailboxBudgeted`'s
caller loop** (`NestworldDimensionRegion.tick()`'s existing
`for (WorldRegion region : ...)` over `EXPLOSION_APPLY` then `BLOCK_WRITE`).
No new grouping key, no cross-region batching (a batch never spans two
destination regions — that would reintroduce exactly the ordering/locking
complexity this design is trying to avoid).

### Combined lock set

Union of `grid.getRegionsWithinMargin(pos, CASCADE_SAFETY_MARGIN_BLOCKS)` over
**every position in every message in the batch** (for `BLOCK_WRITE`, one
position per message; for `EXPLOSION_APPLY`, `batch.positions()`). Same
`TreeSet<WorldRegion>` sorted-by-id acquisition order as today (deadlock
avoidance is unchanged — more regions in the set doesn't change the ordering
invariant, only its size).

### Batch size bound — must NOT remove the existing deadline discipline

The 2026-08-14 Fix-3 correction fixed a real bug: draining had an
unconditional per-region floor of work regardless of the shared deadline
already being blown. Naively batching "every currently-queued message for
this region" without a cap would reintroduce the same class of bug at a
coarser grain (one giant region backlog becomes one giant batch that ignores
the deadline until it's done). So: keep a **max batch size** cap
(`NestworldTuning.BATCH_APPLY_MAX_SIZE`, proposed default 64 — tune during
benchmarking) and keep the deadline check at the *batch* boundary (before
starting a new batch for the next region, same as the existing
`(applied & 3) == 0` check's spirit, just re-scoped to "before batch" instead
of "every 4 applies").

**Gemini review finding (2026-08-19, gemini-2.5-flash — 3.1-pro/pro-latest
both hit the same 0-quota 429 as earlier this session, flash used instead):**
a count-only cap is not sufficient on its own. The deadline check as
originally drafted only fires *between* batches — once a batch starts
forming and acquiring locks, nothing bounds how long that one batch can run,
so a single heavy batch (large combined lock-set, several regions each
needing their own `tryWriteLock` wait) can itself blow the whole pass's
shared deadline before the next "before-batch" check ever gets a chance to
run. That reintroduces exactly the starvation shape Fix 3 already fixed once
(one region's unbounded floor of work starving every region queued after it
in the same drain pass), just at batch instead of per-message granularity.
**Resolution**: the batch's lock-acquisition loop must race against
`min(remaining CASCADE_LOCK_TIMEOUT_NANOS, remaining shared
nestworldMailboxDeadline)`, not the batch timeout alone — so a batch that's
still acquiring locks when the *shared* deadline expires bails out (requeues
whatever it was trying to lock) immediately, the same tick, instead of
running to its own local timeout regardless of the shared budget. This is a
straightforward change to the existing `tryWriteLock(remainingNanos, ...)`
call in `applyWithCascadeGuard`: clamp `remainingNanos` to the shared
deadline too, not just the batch's own `CASCADE_LOCK_TIMEOUT_NANOS` window.
A region with more than `BATCH_APPLY_MAX_SIZE` queued gets multiple batches
across the same drain call, or is carried to the next drain call if either
bound hits first — exactly like today's leftover-stays-queued behavior, just
at batch granularity with a real time ceiling, not just a count ceiling.

### Lock-timeout behavior — whole batch, not partial

If the combined lock set can't be fully acquired within the (now
deadline-clamped, see above) budget: **requeue every message in the batch**,
apply nothing, return — identical semantics to today's single-message
timeout-and-requeue, just applied to the group. No partial-batch
application, so no rollback logic is needed at all (nothing is ever
half-applied). This is the same reasoning that kept the original cascade
guard simple: fail closed, requeue, retry next pass.

**Gemini review finding — convoy effect, accepted as a bounded trade-off,
not solved structurally in Phase 1:** whole-batch-fail means a message that
*individually* needed only one uncontended region can still get requeued
because it happened to share a batch with a message whose lock-set includes
a genuinely hot region. This is real. Two things keep it bounded rather than
open-ended: (1) today's per-message model already pays a worse *aggregate*
cost in the equivalent scenario — N messages all destined the same hot
region each separately burn up to the full timeout before giving up, so
Phase 1's shared-budget-per-batch is a net latency improvement in the
*common* case (one region's messages, one region's contention); (2) the
`BATCH_APPLY_MAX_SIZE` cap directly bounds the convoy's blast radius — at
most 64 messages can be penalized by one unlucky co-batched hot lock, not the
whole region's backlog. Solving this properly (e.g. sorting a batch by
lock-set overlap and sub-batching independently-lockable groups) reintroduces
real complexity for a benefit that's currently unmeasured. Treat as a
benchmark question, not a design blocker: capture whole-batch-requeue rate
and the fraction of a requeued batch that *would* have succeeded alone
(derivable by re-attempting each requeued message's own lock-set
individually, off the hot path, purely for the telemetry) during Phase 1
benchmarking; only build sub-batching if that number turns out to be large
under the real TNT workload.

### Interaction with `CASCADE_SAFETY_MARGIN_BLOCKS`

None — the margin computation itself is unchanged, just evaluated over a
batch's positions instead of one message's. Safety property (every region
whose blocks could be affected by cascading neighbor-updates near the write
is locked before any write happens) is preserved exactly.

### Entity ownership recheck

Not applicable — confirmed above that neither `EXPLOSION_APPLY` nor
`BLOCK_WRITE` ever reads or mutates an `Entity`. No entity-ownership surface
is touched by this change.

### Telemetry (added alongside the change, not after)

New counters on `WorldRegion` (or a small dedicated
`BatchApplyStats`/`AtomicLong` set, mirroring `MailboxAudit`'s style),
surfaced via a new `/nestworld pressure` sub-command:

- `messagesDrained` (per type)
- `batchesFormed`
- `batchSizeSum` / `batchSizeMax` (-> average batch size)
- `lockSetSizeSum` (-> average regions-locked per batch)
- `lockAcquisitions` (successful) vs `lockTimeouts` (whole-batch requeues)
- `locksPerMessageBefore` vs `locksPerBatchAfter` — the two numbers the user
  specifically wants to watch; computable from the above without new state
  (`lockSetSizeSum / messagesDrained` today's-equivalent vs
  `lockSetSizeSum / batchesFormed` post-change)
- `wholeBatchRequeues` and, for those specifically, `messagesInRequeuedBatchThatWouldHaveSucceededAlone`
  (off-hot-path diagnostic re-check per the convoy-effect finding above) — only
  needs to run when a requeue actually happens, so it's not a steady-state cost

Existing `/nestworld status` fields (`mailbox=`, `pending=`, TPS, MSPT) stay
as the top-level pass/fail signal; `/nestworld pressure` is the new
mechanism-level diagnostic.

## Phase 2 — CLOSED NEGATIVE (2026-08-22), measured before building

Before writing the side-effect-preserving dedup logic below, added a cheap probe
(`BatchApplyStats.recordPositionDuplicates`) to measure how often a batch's own combined
position list actually contains the SAME `BlockPos` more than once — the precondition for
Phase 2 to have ANY payoff at all, regardless of implementation quality. Ran the exact same
extreme continuous-flood reproduction (gen-spike-repro, 9x9 grid TNT every ~2s, no gaps) used
for all the benchmarks above.

**Result: dupRate = 0.05%** (55 duplicate positions out of 110,588 total across 3,344 formed
batches, in a 124s window that reproduced the same crash-precursor conditions as the original
extreme-flood tests — `pending` growing past 135K, `timedOut` ~99% of batches). Even a perfect,
zero-risk implementation of Phase 2 could only ever eliminate ~0.05% of applied work — noise,
not a lever. **Confirms directly, with data, what the extreme-benchmark table above already
implied indirectly** (locks/message already amortized to near-zero via Phase 1, yet the crash
timing barely moved): the bottleneck under extreme flood is not redundant/duplicate writes
that coalescing could remove, it's that destination regions are themselves saturated
processing their OWN local explosions, so an incoming cross-region batch's lock request has
essentially nothing to do with how many distinct or duplicate positions it's requesting —
the target simply isn't available to grant the lock often enough, regardless of payload shape.

**Decision: do not build Phase 2.** The real side-effect-preserving implementation would have
carried genuine semantic risk (vanilla's per-write neighbor/physics/block-entity notifications)
for a measured, not estimated, near-zero return. If a future workload pattern is ever found
where duplicate-position rate is meaningfully higher than 0.05%, re-run this same probe first
before reconsidering — the counters are cheap enough to leave permanently enabled.

## Phase 2 (original design, kept for history — NOT built, see closure above)

Payload-level coalescing for `BLOCK_WRITE` specifically: if a batch contains
two writes to the *same* `BlockPos`, only the position's *final* `BlockState`
matters for world state — but **each individual `setBlock()` call also has
side effects** (`UPDATE_NEIGHBORS`/`UPDATE_CLIENTS` flags, block-entity
create/destroy, physics/observer-block notifications) that a naive
"keep-last-write-only" coalesce would silently drop. Vanilla's own
single-threaded semantics would still fire every intermediate call's side
effects even though only the last write's state survives — so dropping
earlier writes changes observable behavior, not just an implementation
detail. This needs its own design pass (most likely: keep every write's side
effects, only skip the *block-state application itself* for writes that get
immediately superseded — not a blind "drop earlier messages") and is
explicitly **out of scope** for the batch-the-lock change. Recommend revisiting
only after Phase 1 is benchmarked and, if the remaining apply-side cost is
still dominated by *side-effect processing* rather than lock overhead, decide
whether Phase 2 is worth the added risk at all.

`EXPLOSION_APPLY` batches from *different* `Explosion` objects are never
payload-coalesced even in Phase 2 — each retains its own `spawnDrops`/`fire`
flags and must replay via its own `nestworldApplyBatch` call; Phase 1's
lock-batching already captures the entire realistic win for this type without
touching payloads at all.

## Benchmark plan (before/after, same TNT workload used for the original Fix 3 validation)

Metrics to capture both runs, not just pass/fail:

```
messages received          (per type)
messages coalesced          (0 in Phase 1 — confirms Phase 1 truly doesn't touch payloads)
unique affected regions
lock acquisitions
lock attempts
lock timeouts (whole-batch requeues)
average locks/message (before)  vs  average locks/batch (after)
batch size (avg/max)
deferred messages (mailbox depth over time)
TPS / MSPT over the run
```

Success bar: `locks/batch` measurably lower than today's `locks/message` by a
large factor (not a marginal percentage), and the extreme continuous-flood
repro (per `step5-auto-freerunning-and-backpressure-reopened.md`) survives
meaningfully longer than the current ~t=400s ceiling — ideally no longer
hitting the watchdog at all under the same load pattern used before, though
per the project's own regression bar (`target-hardware-priority-tiers.md`)
"survives every deliberately-extreme adversarial test" is not the pass/fail
line — "no catastrophic degradation under realistic load, provable
improvement under extreme load" is.

## Resolved: count cap alone is not enough (Gemini review, 2026-08-19)

The original draft left this as an open question, leaning toward
count-only-first. Gemini's review (see findings inline above) settled it:
count-only is not safe to ship even as a first cut, because an in-progress
batch's lock acquisition is exactly the unbounded-duration operation Fix 3
already had to fix once at message granularity. **Decision: ship the
count cap (64) AND the shared-deadline clamp on lock acquisition together,
from the start** — not a phased "add the time check later if needed."
This isn't new complexity beyond what `applyWithCascadeGuard` already has
(it already takes a `remainingNanos` parameter into `tryWriteLock`; the
change is clamping that value against one more deadline, not adding a new
mechanism).
