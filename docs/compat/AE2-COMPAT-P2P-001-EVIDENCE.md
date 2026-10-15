# AE2-COMPAT-P2P-001 — evidence: P2P-9 (H3) and P2P-10 (Redstone callback boundary)

**Status: EVIDENCE RECORDED (2026-10-15) — STOP for owner review** (approved order, step 4). No P2P
fix exists. H1 and H2 are not implemented yet.

| Item | Before | Now |
|---|---|---|
| H3, loss branch (ghost zone) | CONFIRMED FROM CODE | **CONFIRMED (run)**, 3/3 FE |
| H3, live foreign-owner mutation | CONFIRMED FROM CODE | **CONFIRMED (run)**, 3/3 FE, 2/2 item |
| H3, third failure mode (new) | — | **CONFIRMED (run)**: the ghost-zone item target throws an NPE into the caller, 2/2 |
| Redstone P2P neighbour callback under `P(G)` | CONFIRMED FROM CODE | **CONFIRMED (run)**, on transitions 2/2 |
| Correction to TOPOLOGY-CALLBACK-001 | — | the kernel's deferred node notifications are delivered **while `P(G)` is still held**; the claim "not under P" is wrong (§3.3) |

## 1. Setup

| Item | Value |
|---|---|
| Rig | `/root/compat-ae2-rig` (isolated scratch). Core 47.4.306, AE2 15.4.10, Mekanism, Pipez, addon at HEAD + fixtures |
| JVM | `-Dnestworld.compatAe2.fixtures=true -Dnestworld.freeRunningRegions=true`. All regions free-running |
| Geometry | The region boundary at chunk x = 0 was verified with `nwcompatae2 p2p owner`: region **#38** = chunks x ≤ −1 (`NestWorld-Region-38`), region **#39** = chunks x ≥ 0 (`NestWorld-Region-39`) |
| Networks | Ad-hoc AE2 grids: a glass cable line, a creative energy cell, a P2P input on `in` (side DOWN), a P2P output on `out` (side UP) facing `target = out.above()`, one frequency per pair. All tunnels `isActive=true` |
| Driver | Armor-stand hosts (`RegionTasks`) run each step on the owning region thread. **The input call is made directly on thread A** (`receiveEnergy` / `insertItem` on the input's capability), which is what a pipe ticking in region A does |
| Measurement | `E0` / `E1` are read on the **target owner's** thread from the live block entity. `view` is `level.getBlockEntity(target)` evaluated on thread A, the same routing `CapabilityGuard.get` uses, compared by identity with the owner's live instance |
| Fixture code | `glue/P2PFixtures.java`, `mixin/RedstoneP2PProbeMixin.java` (probe only; no-op unless fixtures are on and the probe is armed) |
| Script | `rig/p2p-run.sh` (mirrored in `compat/nestworld-compat-ae2/rig/`), `rig/redeploy.sh` |
| Raw log | `/root/compat-ae2-rig/evidence/p2p-001-p2p9-p2p10-100922.log` (an end-to-end run from redeploy) |

| Case | input → target | target chunk | owner / edgeDist |
|---|---|---|---|
| 9A SAME-OWNER | (−80,−60,z) → (−70,−59,z) | −5 | #38, deep (4) |
| 9B REMOTE-GHOST | (−80,−60,z) → (5,−59,z) | 0 | #39, **edgeDist 0 → ghost zone** |
| 9C REMOTE-LIVE | (−80,−60,z) → (60,−59,z) | 3 | #39, deep (3) |

Targets: `mekanism:basic_energy_cube` for FE, `minecraft:chest` for items.

## 2. P2P-9: results (end-to-end run; identical to two earlier runs)

| Case | Kind | Runs | accepted (source decrease) | destination increase | view | threadA → owner | Verdict |
|---|---|---|---|---|---|---|---|
| 9A | FE 1000 | 3/3 | 1000 | 1000 (E 4000→5000→6000→7000) | LIVE | Region-38 → Region-38 | **CONSERVED / LOCAL** |
| 9A | item 16 | 2/2 | 16 | 16 | LIVE | 38 → 38 | **CONSERVED / LOCAL** |
| 9B | FE 1000 | 3/3 | **1000** | **0** (E stays 0 across all runs, including the earlier runs on the same cube) | **DETACHED** (a new `TileEntityEnergyCube` instance each call) | 38 → 39 | **VIOLATION: energy destroyed** |
| 9B | item 16 | 2/2 | — | 0 | DETACHED (`ChestBlockEntity`) | 38 → 39 | **VIOLATION: `NullPointerException` thrown out of the P2P transfer into the caller** |
| 9C | FE 1000 | 3/3 | 1000 | 1000 | **LIVE** (the owner's instance) | **38 → 39** | **VIOLATION: cross-owner mutation** (region #39's live cube mutated on Region-38) |
| 9C | item 16 | 2/2 | 16 | 16 | LIVE | 38 → 39 | **VIOLATION: cross-owner mutation** |

The three H3 failure modes are now distinguished by run evidence:
1. **Detached-snapshot loss (9B FE).**
   - `RegionChunkView` returns `BlockEntity.loadStatic(...)` from the ghost snapshot.
   - The copy accepts the transfer, the source counts it as delivered, and the copy is
     discarded.
   - There is no delayed write-back: the same persisted cube stays at 0. It had 7 accepted
     transfers across 3 deployments, 7000 FE destroyed; 6 of them have E printed.
2. **Detached-snapshot crash (9B item, new).** The detached `ChestBlockEntity` has no level, and
   `ChestBlockEntity.createHandler` → `ChestBlock.getContainer` → `DoubleBlockCombiner` →
   `BlockEntityType.getBlockEntity(null, pos)` throws:
   ```
   java.lang.NullPointerException: Cannot invoke "BlockGetter.getBlockEntity(BlockPos)" because "p_58950_" is null
     at BlockEntityType.m_58949_ / DoubleBlockCombiner.m_52822_ / ChestBlock.m_5641_ / ChestBlock.m_51511_
     at ChestBlockEntity.createHandler(ChestBlockEntity.java:189)
     at LazyOptional.orElse
     at appeng.parts.p2p.CapabilityP2PTunnelPart$CapabilityGuard.get(CapabilityP2PTunnelPart.java:100)
     at appeng.parts.p2p.ItemP2PTunnelPart$InputItemHandler.insertItem(ItemP2PTunnelPart.java:84)
     at … RegionThread.tickEntities / runOneTick / runFreeRunningTick  (NestWorld-Region-38)
   ```
   - In production the caller is a pipe's tick. The NPE escapes into that mod's region tick on
     every attempt.
   - `try-with-resources` closes the guard, so `accessDepth` is not left stuck by this path.
3. **Live foreign-owner mutation (9C FE and item).** The owner's live instance is mutated
   synchronously on another region's thread, with no exclusion against region #39's own
   free-running tick.

## 3. P2P-10: Redstone P2P callback boundary

The scenario on the redstone pair: input in #38, output deep in #39, a `redstone_lamp` on top of
the output:
1. cut the cable next to the input (`rscut`): the grid splits and the lamp is unchanged;
2. change the input's power while cut;
3. put the cable back **on Region-38's thread** (`rsreconnect`): the grids merge, the input is
   added to the output's P2P service, and the output's power changes;
4. `notifyBlocksOfNeighbors` runs, recorded by `RedstoneP2PProbeMixin` at HEAD and RETURN of
   `RedstoneP2PTunnelPart.notifyNeighbors`.

| Transition | Thread at notify | `P(G)` held | phase active | kernel in section (T0 / domains) | Output position owner | Lamp at RETURN | Lamp later | Global kernel counters in the window |
|---|---|---|---|---|---|---|---|---|
| OFF→ON (`redstone_block`) | **Server thread** | **true** | false | false | **region #39** (deep) | unlit | **lit** | acquisitions +27, refusals +0, unaudited +0, pTryFailures +0 |
| ON→OFF (`grass_block`) | **Server thread** | **true** | false | false | **region #39** (deep) | lit | **unlit** | acquisitions +0, refusals +0, unaudited +0, pTryFailures +0 |

The earlier interactive runs gave the same values, with acquisitions +0 and +2.

Probe stack at HEAD (innermost first, filtered):
```
Ae2Kernel.deliver > Ae2Kernel.run > Ae2Kernel.guardedStrict > Ae2Kernel.guardedStrictUnchecked
  > GridConnectionMixin lambda nw$create > GridPhaseGate.topology > GridConnection.nw$create > GridConnection.create
```

Answers to the owner's P2P-10 questions:

| Question | Answer | Confidence |
|---|---|---|
| thread | `Server thread`. The cable was placed on Region-38, but AE2 creates the grid connection when the node becomes ready, on main (`GridConnection.create` under the addon's topology wrapper) | CONFIRMED (run) |
| `P(G)` held? | **yes** | CONFIRMED (run) |
| target ownership | the output and its neighbours belong to **region #39** (deep). Main is not their owner, and region #39 is free-running | CONFIRMED (run) |
| does the neighbour callback re-enter AE2? | not observed for this neighbour (a vanilla lamp): refusals / unaudited / pTryFailures +0. The `acquisitions` counter is global (+0, +2, +27 across runs) and **cannot be attributed** to this callback; a per-thread counter would be needed to decide | INCONCLUSIVE for re-entry; no nested-P refusal was observed |
| does it request topology? | no `P` contention or refusal was recorded in the window | STRONGLY INDICATED (no) for this neighbour |
| does it touch another region? | **yes**. The lamp's state change at the #39 position is not visible at RETURN, but it appears later. That is consistent with Core deferring the foreign write (ON) and the scheduled tick (OFF) to the owner. The deferral mechanism was not instrumented | CONFIRMED that it touches #39 / STRONGLY INDICATED that Core defers it |

### 3.3 Correction to TOPOLOGY-CALLBACK-001 (for review; GRID-PHASE not touched)
- TOPOLOGY-CALLBACK-001's row reads: "G2 node listeners (`onGridChanged`, `notifyStatusChange`) →
  deferred after the outermost exit (implemented): **not under P / T0 / domains**."
- The stack above shows `Ae2Kernel.deliver` (the deferred-notification queue) running **inside**
  `GridPhaseGate.topology`'s body, so **`P(G)` is still held**. T0 and domains are released
  (`kernelInSection=false`), which matches that half of the claim.
- That is how arbitrary neighbour-block code (Redstone P2P → vanilla / mod neighbours) runs under
  `P(G)`.
- **The GRID-PHASE invariant itself (active phase XOR topology) is not violated.** What is wrong is
  the documented callback boundary.
- Per the owner's Q4 ruling this becomes the narrow finding **REDSTONE-P2P-CALLBACK-001**. The
  scope question for review: is it only Redstone P2P, or **all deferred node notifications
  delivered while `P(G)` is held**? The stack shows the mechanism is the generic deferred
  delivery, not something Redstone-specific.

## 3.4 Owner classification (2026-10-15)
- **CALLBACK-UNDER-P-001: OPEN, CONFIRMED MECHANISM.** Every deferred callback of a topology
  operation is delivered with P(G) held (`docs/compat/AE2-COMPAT-CALLBACK-UNDER-P-001.md`).
  - **REDSTONE-P2P-CALLBACK-001: CONFIRMED MANIFESTATION.**
  - GRID-PHASE-001 is **not** reopened. Its invariant holds.
- **H3 NONLOCAL_SYNCHRONOUS_FORWARDER: CONFIRMED / REPRODUCED.** FULL AE2 PIN does not protect
  against it; this is now evidence, not assumption.
- **Normative conservation invariant.** For a synchronous forwarding operation without a persistent
  transport buffer: accepted / extracted at the source **==** persistently committed at the
  destination. The ghost case violates it directly.
- **Sub-gate statuses (kept separate):**

  | Sub-gate | Status |
  |---|---|
  | P2P-MAP-STATE-001 (H1) | OPEN, implementation next |
  | P2P-ACCESS-DEPTH-001 (H2) | OPEN |
  | P2P-FORWARDING-001 (H3) | CONFIRMED / OPEN |
  | AE2-COMPAT-P2P-001 | OPEN |

  **No unpin after H1.**

## 4. What this changes

- **H3 is CONFIRMED** in all three forms. The pinned baseline is affected too: the transfer runs on
  the caller's thread (§0 of the audit).
- **The approved H3 direction (fail-closed cross-owner refusal) covers all three forms.** A refusal
  happens *before* `getBlockEntity` → `getCapability`, so neither a detached copy nor a live foreign
  instance is ever touched.
- **REDSTONE-P2P-CALLBACK-001** (proposed, OPEN):
  - Redstone P2P neighbour notification runs under `P(G)` on main, targeting another region.
  - The general form is "kernel-deferred notifications are delivered under `P(G)`".
  - Narrow fix direction per Q4: under `P(G)`, record the intent → release `P(G)` → execute in a
    valid owner context. A cross-region target is H3 class.
- Negative-control status: P2P-9 has no "fixed" side yet. 9A is the positive control. 9B and 9C are
  the discriminating failures that the future H3 enforcement must turn into **refusals** (accepted
  = 0, destination +0, no exception, no foreign mutation).

## 5. Next (approved order)

5. Implement H1 (`P2PState`, `allocateAndBind`).
6. H1 deterministic negative control.
7. Implement H2 (`ThreadLocal` depth).
8. H2 concurrent-transfer negative control.
9. Stress H1 / H2.
10. H3 stays OPEN with the enforced cross-owner refusal design, which needs the narrow Core
    ownership query (audit §9 Q1).
