# AE2-COMPAT-LEGACY-ORIENTATION-001.1 — load-path audit for the pre-publication migration

**Status:** AUDIT (2026-10-14). Disk path APPROVED by the owner and IMPLEMENTED. **§1–§2 are
corrected by §8** (runtime evidence).
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

## 8. Correction from runtime (2026-10-14): the regular disk path is `ChunkSerializer.postLoadChunk`
The first implementation hooked only `promotePendingBlockEntity`, and **migrated nothing** on reload
(`migrated=0`).

The regular disk path for FULL chunks is different:
```
protoChunkToFullChunk → runPostLoad → ChunkSerializer.postLoadChunk (lambda):
    keepPacked ? chunk.setBlockEntityNbt(tag)                       // pending path (§1), rare
               : BlockEntity.loadStatic(pos, state, tag) → chunk.setBlockEntity(be)   // regular path
→ setLoaded(true) → registerAllBlockEntitiesAfterLevelLoad → … → startTickingChunk
```
- `promotePendingBlockEntity` handles only `keepPacked` entries and proto-chunk NBT.
- §1 overstated it as *the* path. The safety argument is unchanged.
- **Pre-publication point used:** the head of `LevelChunk.setBlockEntity` while `LevelChunk.loaded`
  is false. That is exactly the `runPostLoad` window: before `setLoaded(true)`, before the block
  entity is in the chunk's map, before ticking.
- Live placements reach `setBlockEntity` with `loaded == true` and are not migrated.
- The `promotePendingBlockEntity` hook is kept for the `keepPacked` path.

## 9. Implementation and fixture results (jar on the scratch rig; `rig/legacy-run.sh`)
Real disk path:
1. place blocks;
2. `save-all flush`;
3. unload the chunk;
4. inject legacy `forward` / `up` into the saved block-entity NBT through `ChunkMap` storage, the
   loader's own IOWorker;
5. reload.

| Check | Result |
|---|---|
| drive `forward=east, up=up` | **migrated** → `DriveBlock[facing=east,spin=0]`, orientation `EAST_UP`, `pending=null` |
| inscriber `forward=south` | **migrated** → `facing=south`, `SOUTH_UP` |
| molecular assembler, pattern provider | no orientation BlockState properties: `unchanged` (computed state = old state); nothing written |
| node and connectivity | node present. After migration the drive's front faces the inscriber, and the drive's grid separated (2 nodes vs 4): AE2's front-not-connectable semantics, applied through `be.setBlockState → onOrientationChanged` on the unpublished block entity. Correct AE2 behaviour |
| save after migration | **no `forward` / `up` keys** in the saved NBT of all four |
| second reload | `migrated` counter unchanged: migration does not run again |
| `onReady` live write | none; `liveSkipped=0` on the migrated path |
| exceptions | 0 |
| regression (integration 3/3, queue 8/8, T2 4/4) | all PASS, 0 exceptions |

**Natural negative control:** the first run, with the hook at the wrong point.
- The legacy orientation was not migrated and reached `onReady` (`liveSkipped=4`).
- The fail-closed wrapper suppressed the live world write: the states stayed `facing=north`.
- This shows both that the pre-publication hook is what migrates, and that the live-world path is
  fail-closed.

Not verified, recorded:
- **Client receives the migrated state.** No client on the rig. Holds by construction: the section
  state is migrated before the chunk is sent.
- **Neighbour-shape effects.** Node connectivity is verified. Visual neighbour shapes (cable
  rendering) need a client: still a HYPOTHESIS.
- **Deliberate live-world `/setblock … {forward,up}` via RCON** did not reach `loadTag` with the
  keys. STRONGLY INDICATED cause: main's write into a region-owned chunk is routed by Core, so
  `getBlockEntity` right after `setBlock` saw no block entity and the command's NBT was not applied.
  UNKNOWN. The live-world fail-closed behaviour is evidenced by the negative-control run above.
