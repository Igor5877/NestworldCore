# AE2-PIN-LEAK-001 D4a — cluster teardown as an exactly-once cluster-domain operation (source audit, no code)

Status: **AUDIT COMPLETE — design decision after D1 evidence (owner decisions 2026-10-30 (16))**. Source AE2 15.4.10.
Ingress I2: removing one multiblock member on a region thread tears down the whole cluster and mutates OTHER members
(confirmed: S2 stack `AbstractCraftingUnitBlock.onRemove → breakCluster → CraftingCPUCluster.destroy → updateStatus →
updateSubType → setExposedOnSides`; 5393 / 8981 cluster destroys off main under addon 0.5.0).

## 1. The I2 family (all teardown entry points)

| cluster | entry point (thread = whoever removes / unloads) | what teardown does |
|---|---|---|
| crafting CPU | `AbstractCraftingUnitBlock.onRemove` → `CraftingBlockEntity.breakCluster` (inside `setBlockState`, after the section write, BEFORE the BE is removed) | `cluster.cancelJob()` (crafting logic → grid crafting state); read the cluster inventory; compute drop places (X's own position + empty neighbours of every other member, `level.isEmptyBlock`); `Platform.spawnDrops` at random places; `inv.clear()`; `cluster.destroy()` |
| crafting CPU | `CraftingBlockEntity.updateStatus(c)` with a different cluster (re-formation) → `oldCluster.breakCluster()` → core member's `breakCluster` | same as above, on the thread running the multiblock calculation (MAIN after D2 for pinned) |
| crafting CPU | `CraftingBlockEntity.disconnect` | `cluster.destroy()` |
| `CraftingCPUCluster.destroy` | — | `isDestroyed = true`; static MB flag set if free (`ownsModification`); grid event `GridCraftingCpuChange` once; for each member `r.updateStatus(null)` → member.cluster = null → `updateSubType(true)` → member `level.setBlock(POWERED/FORMED, UPDATE_CLIENTS)` + `onGridConnectableSidesChanged` (`setExposedOnSides` → node topology) |
| QNB | `QuantumBaseBlock.onRemove` → `breakClusterOnRemove`; `setRemoved` / `onChunkUnloaded` → `disconnect` | `QuantumCluster.destroy`: `isDestroyed`; static MB flag set **unconditionally** (throws if another is in progress); Forge `EVENT_BUS.unregister`; members `updateStatus`; `Locatables.quantumNetworkBridges().unregister` |
| spatial pylon | `SpatialPylonBlockEntity.setRemoved` / `onChunkUnloaded` → `disconnect` | `cluster.destroy()` + `updateStatus(null)` (inside `setRemoved` — i.e. inside D1's section when D1 applies) |

## 2. Owner questions

1. **What breakCluster reads before the host is removed** — `X.cluster` (field), `cluster.craftingLogic` (job,
   inventory list), the member list, `level.isEmptyBlock` around members, `level.getRandom()`, `X.worldPosition`.
   Nothing it needs is destroyed by X's removal: the inventory and membership live in the cluster object.
2. **What must be captured before setRemoved** — only `{cluster reference (identity), level, X position, X type}`.
   X's position stays a valid drop place (AIR after the write; re-checked at execution).
3. **Identity of the teardown** — the cluster instance. A cluster's membership is immutable once formed; re-formation
   creates a NEW cluster object. AE2 already guards `destroy()` with `isDestroyed` and the crafting drops with
   `inv.clear()`; the addon needs a per-cluster "teardown claimed" flag (CAS) for exactly-once scheduling.
4. **Exactly once** — claim by CAS on the cluster (addon field on the three cluster classes) at the first teardown
   request; later requests for the same cluster are no-ops; AE2's own `isDestroyed` / `inv.clear()` are the second line.
5. **Two members removed almost at once** (possibly different threads) — the first claims; the second is a no-op.
   Its position is AIR at execution time, so it is simply another candidate drop place.
6. **Cluster rebuilt before delivery** — if a (D2-routed) multiblock recalculation runs first, AE2 itself calls
   `oldCluster.breakCluster()` from `updateStatus(newCluster)`; that call goes through the same claim → it performs the
   teardown, and the pending intent finds the cluster claimed / destroyed → no-op. Order-safe.
7. **What must stay synchronous** — X's own block-entity removal and node destroy (vanilla / `setRemoved`), and X's own
   additional drops (`AEBaseEntityBlock.onRemove` → `addAdditionalDrops`, needs X's BE). Not required synchronous:
   job cancel, cluster inventory drops (inventory lives in the cluster), member status / block-state updates, member
   exposed-sides changes. Caller semantics: `onRemove` / `setRemoved` are void.
8. **Separation** — yes: synchronous local removal of X ↔ deferred cluster-wide mutation; breakCluster's only
   dependency on X is its position as a drop place and the cluster reference.
9. **Execution domain of a multi-domain cluster** — not assumed MAIN. Production pins: every member is MAIN → one domain.
   Without pins a crafting CPU at a region border can mix REGION (interior) and MAIN (band) members. Candidate contract:
   - claim + cluster-state part (`cancelJob`, inventory extraction + `inv.clear`, `isDestroyed`, membership clearing,
     grid event) executed once at a single serialization point — the domain of the cluster's core member, under the
     existing kernel domain of the cluster's grid;
   - per-member mutation intents (`updateStatus(null)` → block state + exposed sides) delivered in each member's
     current BE execution domain (HostDomainCallbackDelivery, revalidated);
   - drops delivered in the domain of each drop position.
   The static `MBCalculator.modificationInProgress` is a single global: in a multi-domain split it cannot express a
   per-domain modification — that part stays with AE2-MB-MODIFICATION-001.
10. **External callbacks / world mutation under T0** — teardown performs world writes (`setBlock` UPDATE_CLIENTS),
    entity adds (drops), Forge event-bus unregister (QNB), `Locatables` registry changes (QNB), grid events and crafting
    state changes. None may run under T0 — deferred delivery is outside T0 by construction; an in-section request is
    deferred to the section exit.

## 3. Consequence for AE2-MB-MODIFICATION-001

Under production pins, after D2 (all multiblock CALCULATION on MAIN) and D4a (all TEARDOWN in the cluster domain =
MAIN), every user of the static flag runs on MAIN → the confirmed static race has no second thread in the production
configuration (to be verified). Multi-domain (unpinned) clusters still share one static → separate.

## 4. Open points for the design decision

1. Interception level: per entry point (breakCluster / breakClusterOnRemove / disconnect) vs at `*Cluster.destroy` +
   the crafting drop block.
2. Production-pins-first (single domain = MAIN; per-host split later) vs the full per-host decomposition now.
3. Drops timing (≤ one safe point later, same candidate places) — acceptable?
