# Tracker / ownership / explosion optimization package

Status as of the overnight session (branch `claude/nestworld-hybrid-arch-invq4h`).
Driven by spark profiles under a synthetic ~150–170k PrimedTNT stress on `/root/zc-server`
(ZombieCraft modpack server, 14G heap, `nestworld.trackerSpatialCull=true`).

## TL;DR
- **A1 (move throttle) — SHIPPED & VALIDATED (v47.4.126).** Fixes the "flying collapses TPS to
  ~0, one thread at 100%" stall the user reported. Spark confirms `Server thread` is idle with no
  `ChunkMap.move` hotspot even while flying through the pile.
- **B1 (event-driven ownership) — VALIDATED (deployed v47.4.128, flag-on).** Measured win and
  correctness confirmed (see "B1 validation results" below). Still flag-gated default-off in code;
  recommend flipping to default-on after a glance.
- **A2 / C1 / C2 — designed, NOT implemented.** They touch entity-visibility tracking or vanilla
  collision/explosion physics; deliberately deferred to a supervised session (see rationale).

## The key profiling finding: the bottleneck is now a single point-hotspot region
Under 173k entities (169k actively-exploding TNT), spark `All` view showed **every entity in ONE
region (`Region #1` of 39)**; the other 38 region threads + the ForkJoinPool broadcast workers +
netty were all parked/idle. Densest chunk held 7625 entities. The single hot region thread:

```
PrimedTnt.tick 339ms -> explode 237ms
  Explosion.nestworldSeenPercent (per-block exposure raytrace, clip/traverseBlocks) = 154ms
  Entity.move -> getEntityCollisions (Level.getEntities over EntitySection AABB) = 75ms
```

This is fundamental: **BSP spatial sharding cannot split a point hotspot** (all entities in a few
chunks) — a child region just inherits the same pile. The only levers are (a) make each unit of
explosion work cheaper, or (b) intra-region entity parallelism (which even Folia does not do —
explosions mutate shared blocks+entities). 169k-TNT-in-one-spot is a magnifying glass for finding
hot methods, **not** representative of realistic 200-player load (there entities are spread, all
regions work, and the wall is the network/tracker O(entities×viewers) broadcast).

## A1 — throttle ChunkMap.move() entity re-eval to 1×/tick/player  (SHIPPED)
`ChunkMap.move` re-evaluates nearby-entity visibility on every move packet; a fast flyer emits
several packets/tick, so near a dense pile that is O(entities-near-player) × packets/tick on the
main thread → the flying stall. Fix: a `tickCount` guard on the player's `TrackedEntity` caps the
re-evaluation to once per server tick; the periodic (parallel) tracker `tick()` and the next move
pick up the remainder. Own-chunk loading still runs every move. Flag `nestworld.trackerMoveThrottle`
(default **on**). Validated in spark.

## B1 — event-driven region-ownership reassignment  (BUILT, default OFF)
`BoundaryEntityTransfer.checkAndReassign` scanned every loaded entity each tick. Now the section-
move callback (`PersistentEntitySectionManager.Callback.onMove`) marks the moved entity dirty via
`NestworldRegionSystem.markOwnershipDirty`, and `checkAndReassign` processes only the drained dirty
set. Safety net: a full scan every `OWNERSHIP_FULL_PASS_TICKS` (=200) ticks and on any layout change,
so a missed mark self-heals within ~10s and can never double-tick (the `inTransfer` guard holds).
Marks are written by region threads during the tick, drained on main after the barrier (no
concurrent access). The per-entity body is extracted to `processEntity` and shared, so **flag-off
behaviour is byte-identical** to before.

- Flags: `nestworld.eventDrivenOwnership=true`, `nestworld.ownershipFullPassTicks=200`.
- Expected win: cuts the `entityXfer` phase (~14–19ms on main) when most entities are stationary;
  neutral when everything is moving (so the all-exploding-TNT test will *not* show it well — test
  with a large but mostly-settled entity count).

#### B1 validation results (overnight, 47.4.128, synthetic 35k load)
Controlled test on `/root/zc-server` (`nomod` world): 35 000 stationary armor stands spawned via a
datapack function in a forceloaded ±120-block area, then 200 wandering zombies added.

| | `entityXfer` phase | TPS |
|---|---|---|
| **B1 off** (baseline) | **7.6 ms** | 15.0 |
| **B1 on** (35k stationary) | **0.45–0.74 ms** | 17.7 |
| **B1 on** (+200 wandering zombies) | **0.45 ms** | 17.6 |

- **~10–17× reduction** in the entityXfer phase, and it stays flat at ~0.45ms even with movers
  (only entities that actually changed section are processed).
- **Correctness clean:** no `wasn't found in section` / double-tick / ownership errors across the
  move window; `/nestworld status` showed entities correctly distributed across 27 dynamically
  split/merged regions; zombies kept ticking/wandering across region borders. The only ERROR lines
  in the log were spark's own protobuf classloader noise, unrelated.
- Sparks: baseline `https://spark.lucko.me/Ptj30HC1ZB`, B1-on `https://spark.lucko.me/AjMPomEZZe`.
- The server is left running 47.4.128 with `nestworld.eventDrivenOwnership=true` in
  `user_jvm_args.txt` as an overnight soak (synthetic load cleaned up afterwards; world back to idle
  ~20 TPS). To reproduce the load: `/reload` then `/function nest:spawn` (datapack in
  `nomod/datapacks/nest`, ±115 armor stands ×5000 per call) after `/forceload add -120 -120 120 120`.

## Deferred designs (need a supervised session)

### A2 — boundary-ring move() re-eval
Only re-evaluate entities in the shell `[effectiveRange−delta, effectiveRange+delta]` of the new
player position (where tracking status can flip), skipping entities deep inside view. Lower value
now (move is no longer hot after A1) and it couples to per-entity `effectiveRange` inside
`TrackedEntity.updatePlayer` — i.e. it touches the **entity-visibility** path, the exact area of the
invisible-items saga. **Do not ship unsupervised / without a flying tester.**

### C1 — skip the wasted entity-collision broad-phase (the 75ms)
TNT/items/falling-blocks never hard-collide with entities (`canBeCollidedWith()` is false for all of
them), yet `getEntityCollisions` iterates all 7625 neighbours in the dense section to return empty.
A correct fix needs a per-section/per-region count of "hard-collidable" entities (boats, minecarts,
shulkers, armor stands — and mod entities, so a static type list is unsafe) to short-circuit the
scan when zero are near. `canBeCollidedWith()` is dynamic, so the counter must never under-count.
This is the Lithium pattern but it touches vanilla collision correctness → **review + flag-gate +
supervised test** before shipping.

### C2 — explosion exposure raytrace (the 154ms)
`nestworldSeenPercent` already reads blocks through `NestworldExplosionCache`; the remaining cost is
the per-entity ray traversal, which depends on each entity's exact position → no bit-identical
cross-entity memoization. The only lever is Paper-style per-block-pos caching of the density (a
*small, non-bit-identical* behaviour change) or capping the entity count an explosion processes.
Both change gameplay slightly → opt-in only.

## Morning test plan (supervised)
1. Build current branch (v47.4.127), deploy to `/root/zc-server` (installer → `--installServer`).
2. Spawn a large but **mostly-settled** entity load (e.g. lots of items/mobs, let them settle).
3. Enable B1: add `-Dnestworld.eventDrivenOwnership=true` to `user_jvm_args.txt`, restart.
   Spark + compare the `entityXfer` phase vs flag-off. Verify entities still tick/transfer correctly
   across region borders (walk an entity across a border; check `/nestworld status`).
4. If clean, consider default-on.
5. Then A2 (with a flying tester) and decide on C1/C2 with the user.

## Deploy / version notes
- Version = git commit-offset; each commit bumps `47.4.X`. Build: `./gradlew :forge:installerJar
  -x :forge:checkAndFixPatches`. `genPatches` regenerates *all* patches with offset noise — revert
  every patch except the intended one before committing.
- Server launch (reliable): `( tail -f /dev/null | sh run.sh nogui > bootNNN.log 2>&1 ) &`.
- spark link while running: `/spark profiler open` (does NOT stop the profiler); `stop` ends it.
  rcon: port 25582, pw `nestworld-dev` (multi-line responses don't come back over rcon — read the URL
  from the boot log).
