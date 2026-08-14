# Region-Count Scaling Test — barrier vs free-running, full vs 2-CPU

Status: governing spec for the 2026-08-13 overnight scaling study, given verbatim (design
confirmed with the user) after the user raised a real architectural concern: 3 real players
spreading out already pushed the server to 25-32 regions with `actual_latch_wait` at 40-49% of
tick time. Does this get much worse at "200 players" scale, and how much does free-running
regions (validated for correctness the same night, not yet for scaling) actually help --
especially on the real production target, which has only 2 CPUs (vs. this test rig's 20 threads).

## Test matrix

| Run | CPU | Free-running | Region-count steps |
|---|---|---|---|
| A | full (20 threads) | OFF | 8, 16, 32, 64 |
| B | full (20 threads) | ON | 8, 16, 32, 64 |
| C | pinned to 2 cores (`taskset -c 0,1`) | OFF | 8, 16, 32 |
| D | pinned to 2 cores (`taskset -c 0,1`) | ON | 8, 16, 32 |

## Method

Force region count by dispatching `/nestworld gentest start <count> <baseX> <baseZ>` at many
points spread ~500+ chunks apart around the session's fixed anchor (chunk 312500,625000 / block
5,000,000/10,000,000), staying within `|X|,|Z| < 10,000,000`. Settle ~60-90s per step, then record
`/nestworld status` (region count), `/nestworld mspt`, `/nestworld polltask`'s tick-correlation
section (`actual_latch_wait` is the key metric), `/nestworld tickphases`. For B/D, toggle every
new region free-running via `/nestworld freerun <id>` before the settle wait.

World backed up once before Run A, restored before each subsequent run (clean baseline every
time). Final teardown restores world + boots clean on production config (full CPU, no taskset).

## Questions this answers

1. Does free-running measurably reduce `actual_latch_wait` at the same region count (A vs B, C
   vs D)?
2. Is free-running's benefit smaller under CPU constraint (C/D vs A/B), confirming the "benefit
   depends on spare cores" hypothesis?
3. Is there a qualitative cliff (not smooth degradation) at some region count?
4. Grounded answer to "200 spread-out players on the real 2-CPU box" from runs C/D specifically.
