# AE2-PIN-LEAK-001 — ingress audit and addon-level design options (no code)

Status: **AUDIT COMPLETE — design decision required (owner)**. Source AE2 15.4.10 + addon 0.4.0 + Core 401.
Context: production pins `ae2` (mod-wide tick pin) → every AE2 host's BE execution domain is MAIN. Pin is tick-only
(CORE-PIN-DOMAIN-COMPLETENESS-001), so region-thread world operations still enter AE2 block / BE code. Confirmed
damage through this channel: S1 (AirBlock CCE in readiness), S2 ("already initialized"), MBCalculator ISE; CME in
PathingService CORRELATED.

## 1. Ingress inventory (region thread → pinned AE2 code)

| # | ingress (exact call site) | returns to caller | AE2 state touched | confirmed | defer to host domain? | serialize? |
|---|---|---|---|---|---|---|
| I1 | `LevelChunk.setBlockState` (region thread, e.g. another mod's `setBlock`) — writes the new state into the section, then `onRemove`, then `removeBlockEntity → setRemoved` | `boolean` / old state — **synchronous**, caller expects the world changed | BE lifecycle (`setRemoved → mainNode.destroy` → `GridConnection.destroy` under T0), init data | **S1** (main readiness reads the AIR already written) | **No** (the write is the caller's operation) | **Yes** — candidate D1 |
| I2 | `AbstractCraftingUnitBlock.onRemove` → `breakCluster` (inside I1) and `AEBaseEntityBlock.onRemove` → `addAdditionalDrops` + `spawnDrops` | void, but **must run before** `super.onRemove` removes the BE (reads BE inventory) | cluster (`CraftingCPUCluster.destroy` = MB_MODIFICATION static), BE inventory, item entities | MB ISE (cluster destroy site) | **No** (needs the BE before removal) | **Yes** — same scope as I1 |
| I3 | `AbstractCraftingUnitBlock.neighborChanged` → `CraftingBlockEntity.updateMultiBlock` → `MBCalculator.updateMultiblockAfterNeighborUpdate`; same shape in QNB / spatial pylon BEs | void | multiblock (static `modificationInProgress`), cluster, `updateSubType → setExposedOnSides` (node init data) | **S2**, **MB ISE** | **Yes** — void, no caller result; MBCalculator recomputes from current world state | possible but unnecessary |
| I4 | `CableBusBlock.neighborChanged` / `onNeighborChange` (Forge) → `CableBusContainer.onNeighborChanged` (parts) | void | parts' neighbour state (storage bus, import/export, level emitter …) | not confirmed (cable bus not in the churn) | **Yes** — void | — |
| I5 | `updateShape` overrides (`AbstractCraftingUnitBlock` → `requestModelDataUpdate`; `CableBusBlock` → `onUpdateShape`; others) | **`BlockState`** — but every AE2 override returns `super.updateShape(...)`; the AE2 part is a side effect | client model data / part shape state | not confirmed | **Split**: compute `super` synchronously, defer the AE2 side effect | — |
| I6 | BE placement on a region thread: constructor (`ManagedGridNode` init data), `setBlockEntity → clearRemoved → scheduleInit` | synchronous | AE2 ready queue (already synchronized by the addon) | — | n/a — readiness already runs in MAIN (scope 2) and revalidates | — |
| I7 | redstone / comparator reads (`getSignal`, `getAnalogOutputSignal`) on AE2 blocks | **`int`** — synchronous read | reads AE2 state | not confirmed | **No** (result needed now) | read-only; risk = torn read; not in scope unless shown |

Not region-originated in the confirmed set: player interaction (`use`) is main by default (offload experiments
off); scheduled/random ticks: AE2 blocks have none relevant (one `tick(` override, no `randomTick`).

## 2. Why the confirmed failures happen

- **S1 (I1):** the region thread writes AIR into the section **before** `setRemoved` (where the addon takes `nw$life`),
  so main readiness passes revalidation and reads AIR (check-then-act across threads). Revalidation cannot close it
  — only atomicity of "state write + onRemove + setRemoved" vs readiness can.
- **S2 / MB ISE (I3, I2):** AE2 multiblock code runs on the region thread while main runs the same machinery
  (readiness → `calculateMultiblock`, or another cluster's recalculation — the static flag is global).

## 3. Addon-level design options

- **D1 — lifecycle admission for removal/replacement (I1 + I2).** At the head of `LevelChunk.setBlockState`, when
  the position's current BE is an AE2 base BE (`NwAeBlockEntity`) and the caller is not the BE's execution domain,
  take that BE's `nw$life` lock **before** the section write and hold it through `onRemove` and `setRemoved`
  (lock order `nw$life → T0` preserved; readiness already holds `nw$life`). Effect: readiness and removal of the same
  host are mutually exclusive and readiness revalidation sees either the old block or a removed BE — S1 closed.
  Cost: a region thread may **wait for main** for the duration of one readiness of that host (bounded, per BE).
  Deadlock analysis required (sketch: main never awaits a region round while holding `nw$life`; the region thread
  holds no T0 when it blocks on `nw$life`; T0 is only taken inside `setRemoved`, after `nw$life`). This is the
  former R2 shape — rejected as the *final* BE-READY fix when readiness could move to its owner; here the owner IS
  MAIN by pin policy, so it is re-proposed for pinned hosts only. Does **not** serialize I2's cluster destroy
  against another cluster's MB calculation (global static) → needs D3 or AE2-MB-MODIFICATION-001.
- **D2 — defer void block callbacks to the host domain (I3, I4, I5 side effect).** Wrap the AE2 `neighborChanged` /
  `onNeighborChange` overrides (and the AE2 part of `updateShape`): if the current thread is not the host's BE
  execution domain → `HostDomainCallbackDelivery`-style enqueue with revalidation (host still present, same BE);
  else inline. Synchronous Minecraft semantics preserved (void callbacks; `updateShape` still returns `super`
  synchronously). Effect for pinned AE2: every neighbour-driven multiblock recalculation runs on MAIN → S2 and the
  neighbour-driven MB ISE closed. Latency: next MAIN safe point.
- **D3 — single AE2 "pinned-domain" admission lock** (coarse): every region-thread entry into pinned AE2 block/BE
  code (I1–I5) takes one global lock that MAIN also holds while running pinned AE2 work (readiness, end-tick,
  deferred callbacks). Correct by construction, but region threads may wait for a whole MAIN AE2 phase; heavy
  deadlock surface. Listed for completeness — not recommended.

Recommendation (for decision): **D2 + D1**. D2 removes the confirmed neighbour-update channel without any
cross-thread wait; D1 closes S1 with a bounded per-host wait. Residual after D1+D2: I2's cluster destroy on the
region thread vs a MAIN MB calculation of a *different* cluster (static flag) → re-measure, then
AE2-MB-MODIFICATION-001 if it still reproduces. I7 stays out of scope unless shown.

Evidence plan (no blind A/B): detector counters for each ingress class (region-thread entries into pinned AE2
I1–I5), the existing attribution records, S1/S2/MB/CME counts; negative control = feature off; production pins
churn (same rig) + scope-2/3 and ENDTICK regression fixtures.

## 4. Decision requested

1. Options: D2 + D1 (recommended), D2 only first, or other.
2. D1's region→main bounded wait: acceptable in principle (pending a written deadlock proof), or forbidden?
3. Scope of D2: crafting-unit / QNB / spatial pylon `neighborChanged` only (confirmed), or also cable bus
   `neighborChanged` / `onNeighborChange` and the `updateShape` side effect (unconfirmed but same shape)?
