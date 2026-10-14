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

## Affected-block-entity audit (2026-10-14, the owner's β condition)
**Where the state is set (CONFIRMED, decompiled AE2 15.4.10):** `AEBaseBlockEntity.loadTag` sets
`pendingOrientationChange` whenever the NBT carries the legacy `forward` / `up` strings.
- Affected set: **every subclass of `AEBaseBlockEntity`**, which means all of AE2's block entities
  plus the addons that extend them (ExtendedAE, AdvancedAE, megacells, …).
- The flag is not limited to a few classes.

**Owner-local execution point: does each affected class tick on its region thread?**
- Only the implementors of AE2's `ServerTickingBlockEntity` get a vanilla block-entity ticker:
  - `QuantumBridgeBlockEntity`, `CrankBlockEntity`, `ChestBlockEntity`;
  - `LightDetectorBlockEntity` (through `CommonTickingBlockEntity`);
  - debug block entities (`EnergyGenerator`, `CubeGenerator`, `ChunkLoader`).
- Every other AE2 block entity has **no** owner-region tick: cable bus, drive, controller, energy
  cells, interface, pattern provider, inscriber, charger, molecular assembler, IO port, crafting
  units and others.
  - Their periodic work runs through AE2's grid `TickManager`, on **main**, not on the owning region.
- AE2 has 44 or more `AEBaseBlockEntity` subclasses in total.

**Result: "first owner tick" is NOT a guaranteed execution point.** For most affected classes the
migration would never run. Per the owner's rule, β as worded ("first region tick") is not
implementable. Another specific owner-local hook is required.

**Candidates.** No generic Mod API `submit()` is allowed, so the choice is the owner's:
1. **Main, under the owning region's chunk exclusion.** Run the orientation write and its
   revalidation (current state equals the cached state, and this block entity is still at the
   position) while holding the owning region's chunk write lock. That is the lock every region-thread
   consumer runs under (CORE-MAILBOX-MERGE-001 C3), so it is exclusive with a removal on the owner.
   - Needs a Core extension point: a *generic* "execute under the owning region's exclusion"
     capability. That is a Core design item, not addon code.
2. **Load-time migration.** Apply the orientation change while the chunk is being promoted on main,
   before the chunk is published to region ticking.
   - Needs an audit of the Core chunk-promotion ordering.
   - Vanilla discourages world writes during load, which is why AE2 defers this.
3. **Conditional owner-routed block write.** Route the write through Core's block-write ownership
   path, with an expected-state precondition that the owner applies atomically.
   - Needs Core to support conditional writes (UNKNOWN; not checked).

**Status:** OPEN. β "first owner tick" is refuted by this audit, and an owner decision among 1–3 is
needed. Until then, full AE2 unpin stays blocked.
