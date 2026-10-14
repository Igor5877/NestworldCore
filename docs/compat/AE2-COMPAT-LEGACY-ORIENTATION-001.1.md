# AE2-COMPAT-LEGACY-ORIENTATION-001.1 — load-path audit for the pre-publication migration

**Status: AUDIT (2026-10-14). No code.**
- Owner direction: **pre-publication chunk-load migration**. Fallback: a Core owner-local block
  write. Last resort: main under the owner's chunk lock.
- β "first owner tick" is REJECTED.

Sources:
- NestWorld tree, `ChunkMap` / `LevelChunk` (the patched Forge 47.4.x sources in this repository);
- AE2 15.4.10 (`fbfee05c…`).

## 1. Exact load / deserialization path (CONFIRMED)
```
disk → ProtoChunk (block-entity NBTs kept as NBT)
→ ChunkMap.protoChunkToFullChunk   [thenApplyAsync on the main-thread mailbox; Core routes it to main]
    new LevelChunk(level, protochunk, postLoad)   pendingBlockEntities := protochunk BE NBTs
    setFullStatus; runPostLoad; setLoaded(true); registerAllBlockEntitiesAfterLevelLoad
    ChunkEvent.Load
→ chunk is FULL (readable through getChunk / getChunkNow) but NOT ticking
→ ChunkMap.prepareTickingChunk  [thenApplyAsync(..., mainThreadExecutor)]
    LevelChunk.postProcessGeneration()
        for every pos in pendingBlockEntities: getBlockEntity(pos) → promotePendingBlockEntity
            BlockEntity.loadStatic(pos, chunkState, nbt) → AEBaseBlockEntity.loadTag
                → legacy "forward"/"up" present ⇒ pendingOrientationChange := BlockOrientation(...)
            setLevel; addAndRegisterBlockEntity (publish into chunk.blockEntities; clearRemoved → scheduleInit)
        pendingBlockEntities.clear()
    level.startTickingChunk(chunk)        ← the chunk becomes ticking only here
→ later, main TickHandler.readyBlockEntities → onReady → setBlockAndUpdate   (today's racy live write)
```
**Lazy promotion.** `LevelChunk.getBlockEntity(pos)` promotes a pending block entity **on the calling
thread**, whenever any code queries that position before `postProcessGeneration`. A neighbour block
entity's query is one example; it can run on a region thread for a FULL-but-not-ticking chunk.

## 2. The publication moment
| What | Published when |
|---|---|
| block-entity object | inside `promotePendingBlockEntity`, at `addAndRegisterBlockEntity` (into the chunk's map). Before that, the block entity is local to the promoting thread |
| ticking (region threads tick it) | `startTickingChunk`, strictly **after** `postProcessGeneration` has promoted every pending block entity |

**Pre-publication point:** inside `promotePendingBlockEntity`, after `loadStatic` returns and before
`addAndRegisterBlockEntity`. A pending block entity exists only before the chunk ticks, because
`postProcessGeneration` drains them all first.

## 3. Can the BlockState and block-entity data change before publication?
Yes:
1. compute `newState = IOrientationStrategy.get(state).setOrientation(state, front, spin)`, exactly
   as `onReady` does;
2. write it **into the chunk section directly** (`LevelChunkSection.setBlockState`), not through
   `Level.setBlock` / `LevelChunk.setBlockState`;
3. `be.setBlockState(newState)`, so the block entity's cached state matches;
4. clear `pendingOrientationChange` through an accessor, so `onReady` writes nothing;
5. `chunk.setUnsaved(true)`.

Only orientation properties of the same block change, so:
- light emission and opacity are unchanged;
- the heightmaps are unchanged;
- the block entity is not recreated.

## 4. No normal neighbour update or event machinery (CONFIRMED by construction)
- A direct section write does not call `onRemove` / `onPlace` (those live in `LevelChunk.setBlockState`).
- It sends no neighbour updates, no `updateShape`, no block-change packets and no game events.
- Clients receive the migrated state in the initial chunk packet. The chunk is not yet ticking, and
  is in practice not yet sent to players: sending follows the ticking promotion.
- **Deviation from AE2:** AE2's `onReady` uses `setBlockAndUpdate` (flags 3), so neighbours get a
  shape update.
  - AE2's cables and parts derive connections from nodes and block entities, not from neighbour
    block shapes.
  - Visual-only shape effects on neighbours, if any, are not refreshed. Recorded as a possible
    deviation (HYPOTHESIS: no visible effect).

## 5. Save writes the new format (CONFIRMED)
- AE2 15.4.10 `AEBaseBlockEntity` writes no legacy `forward` / `up` keys. The string `"forward"`
  occurs only in the legacy `loadTag` read.
- Orientation lives in the BlockState.
- After the migration, `chunk.setUnsaved(true)` makes the next save persist the migrated state, and
  the block-entity NBT is already in the new format.

## 6. Residuals (not covered by the chunk-load hook)
| Path | Status |
|---|---|
| Lazy promotion on a non-main thread (a region thread querying a FULL, not-ticking neighbour chunk) | The migration runs on that thread, at the same pre-publication point. The chunk is not ticking, so no owner removal races it. Promotion itself already mutates the chunk's maps on that thread: that is a vanilla / Core property, not new |
| Block-entity NBT loaded **into a live world**: structure placement, `/setblock {…}` / `/data merge` with legacy keys, schematic mods, **AE2 spatial storage export** of a cell made with an old version | Not chunk load. Still flows through `onReady` today. **Proposal:** apply the same migration **synchronously at `loadTag`** when the block entity already has a server level, through `level.setBlock(pos, newState, 2 \| 16)` (no neighbour updates, no drops) on the **placing thread**. That orders it with the placement itself. If the owner wants it closed by construction, this is the fallback case for the Core owner-local write |
| Client side | out of scope (server-side migration only) |

## 7. Implementation sketch (after approval)
Two mixins.
- **Primary:** `LevelChunk.promotePendingBlockEntity`, a `@WrapOperation` on `BlockEntity.loadStatic`.
  If the result is an `AEBaseBlockEntity` with a pending orientation, run §3 steps 1–5 against
  `this` chunk's section.
- **Residual path:** `AEBaseBlockEntity.loadTag`. If a server level is present and the block entity
  is in the world, apply the §6 synchronous migration.

Fixtures:
1. A saved chunk with a legacy-NBT AE2 block entity: load it, and check that the BlockState is
   migrated before `startTickingChunk`, `onReady` performs no `setBlock`, and the saved NBT is free
   of legacy keys.
2. Region removal of that block right after the chunk starts ticking: no resurrection.
3. Negative control: hook removed → the `onReady` live write happens (counter / log).
