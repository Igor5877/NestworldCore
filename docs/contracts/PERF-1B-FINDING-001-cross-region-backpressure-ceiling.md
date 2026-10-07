# PERF-1B-FINDING-001 — cross-region setVelocity throughput is capped by the mailbox backpressure policy; overload collapses

**Status: OPEN, found in the PERF-1B pilot calibration (2026-10-07); measured, mechanism
identified from code, not fixed.**

Rig: Core 47.4.306 (trusted PERF-1B reference); B3 = the consumer's producer in cell k writes the
targets of cell k^1 (another region) through the public `NestWorldEntities.setVelocity`. N = 1
(CPU 2), 16 regions, short calibration windows of 60 s. Evidence:
`/root/perf1-evidence/perf1b-pilot-cal/cal-B3-n1-k*.{json,out,console.log}`.

## Measurements (B3, N = 1)
| K per producer per tick | TPS | submitted / s | terminal / s | benchmark CPU | State |
|---|---|---|---|---|---|
| 256 | 20.0 (cap) | 81 923 | 81 879 | 0.53 | stable; accounting closes (3 600 stale of 16.9 M) |
| 512 | 20.0 (cap) | 163 826 | **95 941** | **0.71** | backlog grows with idle CPU; 4.34 M stale dropped |
| 1 024 | 5.8 | 109 606 | 32 067 | 0.83 | collapse |
| 2 048 | 0.18 | 6 842 | 1 033 | 0.83 | deep collapse; latency p95 > 137 s |

For comparison, at the same N = 1, B2 (owner-fast, same API call) gives ≈ 1.0 M ops/s.

## Mechanism (code, `WorldRegion.nestworldSendOrQueue` / `nestworldFlushOutboundPending`)
- Cross-region ENTITY_MUTATE is sent with `nestworldSendOrQueue`
  (`EntityMutationDispatcher.java:117`).
- If the destination mailbox depth is ≥ `MAILBOX_BACKPRESSURE_THRESHOLD` (300), the message is held
  in the sender's `outboundPending`.
- That queue is retried at most `MAILBOX_BACKPRESSURE_FLUSH_BUDGET` (64) times per sender tick, and
  only while the destination is below the threshold.
- The destination drains its whole ENTITY_MUTATE mailbox once per region tick.

**Ceiling.** Each region therefore accepts about 300 messages per tick: 300 × 16 regions × 20 TPS =
**96 000 messages/s**, matching the measured 95 941/s. The limit is the configured policy, not CPU:
at K = 512 the benchmark core was 29% idle while the backlog grew.

**Reordering.** Once the destination drops below the threshold, new sends go directly into its
mailbox while older ones still wait in `outboundPending`, so newer intents overtake older ones.
LATEST_INTENT_WINS then drops the older ones as STALE_INTENT_DROPPED: 4.34 M at K = 512. Final-state
correctness holds; the drops are wasted work, and they show that per-pair FIFO is not kept under
backpressure.

**Collapse.** Above the ceiling, `outboundPending` grows without bound, by ~27 k messages per tick at
K = 2048. Tick time and dispatch time then grow (vanilla phase up to 210 ms, dispatch up to 93 ms),
TPS falls and throughput drops by ~80×. A plausible amplifier, not proven: the typed mailbox drains
(`nestworldDrainMailbox*`) walk the whole `ConcurrentLinkedQueue`, skipping other types, which costs
O(queue) per drain call. Mailbox depth over time was not sampled, so the collapse dynamics are not
attributed in detail.

## Confirmation: the ceiling does not depend on N
| B3 run | TPS | submitted / s | terminal / s | benchmark CPU |
|---|---|---|---|---|
| N = 8, K = 512 | 20.0 | 163 947 | **96 288** | 0.73 of 8 cores |
| N = 8, K = 256 | 20.0 | 81 911 | 81 943 (stable; 188 stale) | 0.61 of 8 cores |

The same ≈ 96 k/s ceiling appears with 8 benchmark cores as with 1. This confirms a policy bound
(threshold × regions × TPS), not a compute bound.

## Why it matters
- **For PERF-1B:** B3 cannot be measured as a CPU-bound saturated run on this policy.
  - Below the ceiling the server is held at the 20-TPS cap, and the harness rule "not capped by
    20 TPS" voids the run.
  - Above it the run collapses.
- **For real mods:** an ordinary mod issuing cross-region `setVelocity` faster than about
  300 per destination region per tick can drive the server into collapse. It does not merely see
  a throughput plateau.

## Open owner decisions
1. **How B3 is measured.** Options:
   - report the maximum sustainable throughput (the knee) at the 20-TPS cap, as a policy-bound
     metric, with an explicit exception to the cap rule for B3;
   - or run with a raised `-Dnestworld.mailboxBackpressureThreshold` as a separate sensitivity
     series (a non-canonical config).
2. **Whether overload collapse and reordering are Core defects** to schedule after PERF-1B:
   bounded `outboundPending`, per-pair FIFO under backpressure, type-indexed mailbox.
