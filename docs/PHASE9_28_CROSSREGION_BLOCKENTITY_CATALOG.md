# #28.1 — Catalog: vanilla paths where BlockState + BlockEntity + Ticker change as one operation

Per the user's rescoped #28 (2026-08-27): before writing any code, find every vanilla call site
where a SINGLE logical operation changes block state, block entity, and ticker registration
together, physically implemented as more than one Java method call. Grepped every
`.setBlockEntity(`/`.removeBlockEntity(` call site under `projects/forge/src/main/java/net/minecraft`.

## Confirmed compound (state+entity+ticker), region-thread-reachable — IN SCOPE for #28.3

**`PistonBaseBlock.moveBlocks()` / retract path — the ONLY real-world region-thread-driven compound
mutation found.** Three call-site pairs, all `Level.setBlock(pos, newState, flags)` immediately
followed by `Level.setBlockEntity(configuredEntity)` on the SAME `pos`, same synchronous call stack:

1. **Retract, piston's own position** (`PistonBaseBlock.java:187-189`): `setBlock(pistonPos,
   MOVING_PISTON_state, 20)` then `setBlockEntity(newMovingBlockEntity(...))`. Always the piston's
   OWN position — same region as the piston itself by construction, never cross-region. **Not a
   risk** (kept in the catalog for completeness, not in #28.3's fix scope).
2. **Push, each pushed block's destination** (`PistonBaseBlock.java:308-310`, inside the `for` loop
   over `list`): `setBlock(blockpos3, MOVING_PISTON_state, 68)` then
   `setBlockEntity(newMovingBlockEntity(blockpos3, ...))`. `blockpos3` is the DESTINATION the block
   is being pushed INTO — can be arbitrarily far from the piston (up to vanilla's push limit, 12
   blocks by default) and CAN cross a region boundary. **THIS is the confirmed, live-reproduced
   race** (see [[piston-cross-region-atomicity-open-design-issue]]).
3. **Sticky-piston head retraction, piston's own position, second variant** (`PistonBaseBlock.java:
   317-320`): same shape as #1, always the piston's own position. **Not a risk**, same reasoning.

**Finalization side** (`PistonMovingBlockEntity.finalTick()` lines 253-269, and the static
`tick()` companion lines 274+): `this.level.removeBlockEntity(this.worldPosition)` then, if still
`MOVING_PISTON`, `this.level.setBlock(this.worldPosition, finalState, 3)` — the REVERSE pair
(remove BE, then set final state), same atomicity shape, at `worldPosition` (== wherever the
moving_piston block currently sits, i.e. the destination from #2 above). **Different exposure
than #2**: this runs via the normal block-entity TICKING phase
(`NestworldRegionSystem.runBlockEntityPhase`/`NestworldDimensionRegion.runBlockEntityPhase`),
which ALREADY routes border-band positions to main and interior positions to their owning region's
own thread (confirmed in `docs/PHASE9_1_BLOCK_MUTATION_AUDIT.md`) — so by the time `tick()`/
`finalTick()` runs, `Thread.currentThread()` is already correct for `worldPosition` BY
CONSTRUCTION of the ticking dispatch. **Not itself a redirect target** — but its correctness
DEPENDS on #2 having correctly registered the block entity (map + ticker) in the first place, which
is exactly what's currently broken. Once #2 is fixed, this side should already work (and is exactly
what the earlier `-Dnestworld.beTracePos` trace confirmed: for the WITHIN-region case, `tick()`
fires exactly once and correctly finalizes — the SAME code, only reachable because #2 registered it
correctly in that case).

## Other `.setBlockEntity`/`.removeBlockEntity` call sites found — OUT of #28's scope

- `WorldGenRegion.java` (2 call sites) — worldgen-time only (`ChunkAccess.setBlockEntity`/
  `removeBlockEntity`, not `Level`'s), runs during chunk generation on whichever thread is doing
  generation for that chunk, not during live region-ticking. Different problem class (if any),
  not this session's concern.
- `ChunkSerializer.java`, `ImposterProtoChunk.java` — chunk deserialization (NBT load) /
  proto-chunk-to-full-chunk transition. Not live-tick, not region-thread-driven in the sense #28
  cares about.
- `ChunkPalettedStorageFix.java` — an ancient DataFixer (world-format upgrade), completely
  offline/single-threaded, irrelevant.
- `ServerGamePacketListenerImpl.java:573-575` (command-block edit via packet) — main-thread-only
  (network packet handling always runs on main), calls `getChunkAt(pos).setBlockEntity(...)`
  directly (bypasses `Level.setBlockEntity()` entirely). Same class as the already-documented
  "main-thread block break/place" gap (`PHASE9_1_BLOCK_MUTATION_AUDIT.md`) — separately tracked,
  not a NEW finding, not in #28's rescoped region-thread-vs-region-thread focus.
- `BlockBehaviour.java:165`'s `removeBlockEntity` — this is `onRemove`'s default handling for a
  block transitioning away from having a BE, called SYNCHRONOUSLY as a side effect INSIDE
  `LevelChunk.setBlockState()` itself (i.e., inside the SAME call that the Stage 3 `BLOCK_WRITE`
  guard already protects). Not a separate top-level call outside that guard's coverage — already
  safe by the existing mechanism, confirmed by re-reading `LevelChunk.setBlockState()`'s call
  order (`blockstate.onRemove(...)` runs before the guard would even have deferred, or after it's
  already been applied on the correct thread — either way, always inside the already-guarded call).

## #28.2 — Transaction boundary rule

> If a vanilla operation creates/replaces a BlockEntity as part of a BlockState change, cross-
> region dispatch carries the WHOLE operation as one message — never an individual Java method.

Concretely: when a region thread's call chain issues `Level.setBlock(pos, state, flags)`
immediately followed (same synchronous execution, no intervening tick) by
`Level.setBlockEntity(entity)`/`removeBlockEntity(pos)` for the SAME `pos`, and `pos` is outside
that thread's own region, the pair is captured and dispatched as ONE atomic cross-region message —
never two independently-drained ones. Applied to the ONE real risk found in #28.1
(`PistonBaseBlock.moveBlocks()`'s push-destination pair) via a new helper call-site pattern, not a
generic `Level.setBlockEntity()` guard (that shape is exactly what broke the first time).

## Conclusion — #28.3's actual target

Exactly ONE real risk: **`PistonBaseBlock.moveBlocks()`'s push-destination pair** (item #2 above).
The retract-own-position pairs (#1, #3) are same-region by construction and don't need fixing. The
finalization pair is downstream of #2 and should self-correct once #2 is fixed (confirmed by the
earlier within-region trace test already showing correct finalization once the entity is properly
registered). This narrows #28.3 to a single, well-understood call site — good, matches the user's
"first target: piston cross-region operation, not a universal system" instruction exactly.
