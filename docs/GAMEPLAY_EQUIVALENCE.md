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

Every performance gate gets a criterion **`GAMEPLAY_EQUIVALENCE = PASS`**; without it an optimisation cannot reach
production. Behaviour tests: same inputs, same world / mod configuration, same logical tick schedule →
vanilla/mod reference result == NestWorld optimised result; tolerated difference = real wall-clock order of internal
execution only, never visible game semantics.

Open item recorded with this invariant: the AE2 transit split (WP3/WP4) keeps throughput (operations per scheduled tick)
but delivers items 1–2 tick boundaries later than plain Forge — needs an explicit owner ruling under this invariant.
