# Phase 9.1 — BLOCK + BLOCK_ENTITY completeness audit (first pass)

Per `docs/REGION_OWNERSHIP_CROSS_REGION_SPEC.md` section 5. Table format:
`Mutation | Owner | Guard | Cross-region | Action`.

Same first-pass-consolidation caveat as `PHASE9_1_ENTITY_MUTATION_AUDIT.md` applies here.

## Already guarded (CONFIRMED safe)

| Mutation | Owner | Guard | Cross-region | Action |
|---|---|---|---|---|
| `Level.setBlock()` (region-thread caller) | region-owned position | Stage 3 generic block-write guard (`nestworldDeferForeignBlockWrite`, `Level.java:242`) | Yes | mailbox `BLOCK_WRITE` — deployed, validated |
| Scheduled block ticks (`getBlockTicks().tick`) | region-owned position, border-band aware | `routeScheduledTick`/`interiorRegionFor` | Border-band positions route to main | main-thread execution for border-band, region thread otherwise |
| Scheduled fluid ticks | same as above | `routeScheduledTick` | same | same |
| Block events (piston/note-block/chest sound events) | region-owned position | `routeScheduledTick` via `runBlockEventsPhase` | same | same |
| Block-entity ticking (`TickingBlockEntity`) | region-owned position, border-band aware | `runBlockEntityPhase` (pinned→main, cascade-safe→direct, else→`interiorRegionFor`) | Border-band → main | main or region thread depending on classification |
| `MapItemSavedData.save()` | shared per-dimension cache | `synchronized` | N/A (lock, not mailbox — different class of race) | fixed this session |
| `RandomSequence.random()` | shared per-dimension sequence | synchronized delegating wrapper | N/A (lock) | fixed this session |

## Known gap — CONFIRMED, REVERTED after live regression (OPEN, gated on Phase 9.2/CrossRegionBlockMutation)

| Mutation | Owner | Guard | Cross-region | Action |
|---|---|---|---|---|
| `Level.setBlockEntity()` | region-owned position | **none** (P1's per-method `BLOCK_ENTITY_WRITE` mailbox reverted — see [[piston-cross-region-atomicity-open-design-issue]]) | Yes | **must NOT be a standalone guard** — needs `CrossRegionBlockMutation` composite operation (spec §9-10, task #28) bundled with the paired `setBlock()` call, ticker rebind included |
| `Level.removeBlockEntity()` | region-owned position | same as above | Yes | same — composite operation |

## Not yet audited (genuinely open)

`destroyBlock()`, neighbor-update propagation beyond what routeScheduledTick already covers,
`setBlockAndUpdate()` as a distinct call shape from plain `setBlock()`, redstone-specific mutation
paths beyond scheduled-tick routing (e.g. any block that mutates a NEIGHBOR's block entity directly
rather than through `setBlockEntity()` — `ComparatorBlock` was flagged in the earlier full-core
audit as reading a neighbor BE directly, bypassing `CrossRegionCapabilityBus`, PLAUSIBLE not
CONFIRMED, read-only staleness not a write race). `NoiseBasedChunkGenerator` vs `BulkSectionAccess`
lock-ordering (worldgen-time, not tick-time — PLAUSIBLE from the original full-core audit, never
fully traced). None of these have been re-examined this session.

## Piston root-cause summary (for reference, full detail in the linked memory)

`PistonBaseBlock.moveBlocks()`/retract path calls `setBlock(pos, MOVING_PISTON_state, flags)`
immediately followed by `setBlockEntity(configuredPistonMovingEntity)` on the SAME position, as one
synchronous vanilla call sequence. `setBlock()`'s internal `LevelChunk.setBlockState()` creates a
DEFAULT block entity via `newBlockEntity()` and registers ITS ticker (`updateBlockEntityTicker` ->
`level.addBlockEntityTicker`). The immediately-following `setBlockEntity()` REPLACES the map entry
with the fully-configured entity but does NOT re-run `updateBlockEntityTicker()` — the ticker
(`LevelChunk.BoundTickingBlockEntity`) holds a `private final T blockEntity` fixed reference to the
STALE default object. In vanilla/same-region execution this works only because both calls run
back-to-back in one execution context (the ticker registration is still "fresh" when the second
call's map-swap happens, and — empirically confirmed by this session's live test — the swap alone
turns out to be harmless in the same-thread case; splitting the two calls across independently-
drained mailbox messages breaks this, since nothing re-binds the ticker after the deferred
`setBlockEntity()` finally applies). See task #28 for the composite-operation fix.
