# AE2-COMPAT-LEGACY-ORIENTATION-001 — legacy orientation write in `onReady` after removal

**Status:**
- CONFIRMED RACE WINDOW FROM CODE.
- LEGACY-WORLD ONLY.
- FIX DIRECTION β (owner-local migration): CONDITIONALLY APPROVED (owner, 2026-10-14).
- IMPLEMENTATION PENDING the affected-block-entity audit.
- Blocks full AE2 unpin.

Split out of AE2-COMPAT-QUEUE-001.1 §2.3.

## Race (AE2 15.4.10, decompiled)
`AEBaseBlockEntity.onReady` runs on main via `TickHandler.readyBlockEntities`:
```java
if (pendingOrientationChange != null) {
    BlockState state = getBlockState();                 // block entity's CACHED state
    level.setBlockAndUpdate(pos, IOrientationStrategy.get(state).setOrientation(state, …));
    pendingOrientationChange = null;
}
```
```
region (owner): LevelChunk.setBlockState(AIR) ──(window)──▶ setRemoved()
main:                       onReady → setBlockAndUpdate(pos, cached AE2 state)
```
- The remover's linearization point is the chunk write, which happens **before** `setRemoved`.
- `nw$life` (QUEUE-001.1 §4.3) cannot close it: the world mutation precedes the life-lock path.
- Effect: the AE2 block is written back into a just-broken position, after its drop. This is a
  resurrection or duplication shape, i.e. a gameplay correctness issue.
- Reachable only when `pendingOrientationChange != null`, which is set when legacy-format
  orientation NBT is loaded.

## Fix direction β (conditional)
Move the legacy orientation migration off main `onReady`, onto an execution point that runs on the
block entity's **owning region thread**, the same thread as any removal of that block. Then the
read of the current state and the write are ordered with respect to removal.

Condition, required before implementation (owner): a narrow audit that must
1. enumerate every AE2 block-entity class whose load path can set `pendingOrientationChange`;
2. for each, prove a guaranteed owner-local execution point:
   - if every one is guaranteed a region-owner server tick, use the first owner tick;
   - if any is non-ticking, sleeps forever, or has optional tick registration, find another
     specific owner-local lifecycle hook for it.

   **No** generic Mod API `submit()` (excluded by the owner).

Option α (a conditional `getBlockState(pos) == cached` check on main) was not specified precisely
enough for a ruling, and is not pursued.

## Not allowed
Leaving this only recorded and then fully unpinning AE2.
