# GAMEPLAY_EQUIVALENCE — standing invariant of NestWorldCore (owner, 2026-11-07)

**An optimisation may not change gameplay semantics.** It may change only how, where, in which safe order and with how
much parallelism work is executed. The player-visible result must stay equivalent to plain Forge + the same mods.

Rejected as performance fixes (never proposed or applied):
- `doMobSpawning false`, mob-cap reductions;
- changes of recipes, mechanics, blocks, AI, ranges;
- slowing machines or AE2 bus tick rates (e.g. 5 → 15–20), lowering any machine's work frequency;
- disabling mod mechanics;
- restricting players ("do not open the terminal") as a technical fix;
- anything that makes the same farm / machine / network behave differently or slower.

Allowed: moving work between MAIN and REGION threads, phase splitting, routing, transit / handoff, removing lock
contention, cache / delta snapshots, better scheduling, bad-split prevention, ghost-zone optimisation, parallel spawn
execution (if equivalent). Same spawn, same items, same bus speed, same redstone semantics, same machine recipes, same
world state.

## Two levels (owner refinement 2026-11-07)

**SEMANTIC_EQUIVALENCE — mandatory:** same mechanics, same resource conservation, same throughput, same capacities,
same spawn rules, same recipes, same machine speeds / configuration, same game rules.

**TIMING_TOLERANCE — bounded scheduling jitter:** normal internal latency of 0–2 ticks is acceptable when it does not
accumulate and does not change throughput, results, balance, or anything a player practically notices. Example PASS:
1000 items/min reference = 1000 items/min NestWorld, each item +1 tick. FAIL: 1000 → 750 items/min, even with +10 TPS.
Mobs: same cap, same spawn attempts, same rules; an attempt may be computed slightly later inside the scheduler if it
does not systematically reduce mob counts.

**`GAMEPLAY_EQUIVALENCE = PASS` when:**
1. the logical result of the mechanic is unchanged;
2. balance and configuration are unchanged;
3. throughput is not reduced;
4. no scheduled operation is skipped;
5. resource costs are unchanged;
6. no duplication / loss;
7. bounded scheduling latency ≤ 2 ticks is allowed if it does not accumulate and does not change gameplay materially.

Every performance gate carries this criterion; without PASS an optimisation cannot reach production. Evidence: same
inputs, same world / mod configuration, same logical tick schedule → reference result vs NestWorld result compared on
the items above (throughput per minute, conservation, scheduled operations, costs), not on bit-identical internal timing.

AE2 transit (WP3/WP4): 1–2 tick bounded latency **APPROVED**, conditional on WP5 proving unchanged throughput,
conservation and mechanic behaviour (same bus tick interval, operations / minute, items / operation, energy cost; no
duplication, loss or starvation). The earlier "extra full-tick latency forbidden" ruling is revoked.
