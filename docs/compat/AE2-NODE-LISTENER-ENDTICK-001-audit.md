# AE2-NODE-LISTENER-ENDTICK-001 — source audit (no code)

Status: **AUDIT COMPLETE — design decision required (owner)**. Source: AE2 15.4.10 (tag `forge/v15.4.10`).
Scope: the residual off-domain node-listener calls after R1-a scope 3 — fired by AE2's server-end-tick paths on the
server thread, **outside any kernel section** (not part of a T0 topology transaction), delivered to hosts whose BE
execution domain is a region. Measured: 57/run under production pins (unpinned AE2-integrated hosts, e.g.
`expatternprovider`); 1164 + ≈785/run in the test config with `ae2` unpinned (topology lifecycle runs).

## 1. Emission paths (all main, server end tick)

| state | emitter | path | notes |
|---|---|---|---|
| GRID_BOOT | `PathingService.postBootingStatusChange` | `Grid.notifyAllNodes(GRID_BOOT)` → every node `notifyStatusChange` | twice per reboot: booting=true before channel assignment, booting=false after `ChannelFinalizer` |
| POWER | `EnergyService.publicPowerState` (from its end-tick power refresh; power-up buffered 30 ticks) | `Grid.notifyAllNodes(POWER)` | only on public power state change |
| CHANNEL | `PathingService.onServerEndTick` → `pivot.beginVisit(new ChannelFinalizer())` | `GridNode.finalizeChannels` / `GridConnection.finalizeChannels` → `notifyStatusChange(CHANNEL)` on both sides when used channels changed | after pathing compute, before the second GRID_BOOT |

`Grid.notifyAllNodes` copies nodes into the static `ITERATION_BUFFER` (main-only, AE2-AUDIT-STATIC) and throws on
recursion ("Recursively trying to notify all nodes is not allowed"); its comment notes a power change may boot
adjacent nodes (QNB) and modify the grid during the iteration.

## 2. What listeners do (`onStateChanged` → `onMainNodeStateChanged`, 27 implementations; representative)

| host | effect | world/AE2 effect class |
|---|---|---|
| `ControllerBlockEntity.updateState` | `level.setBlock(controller_state)` (no neighbour update) | **world write** of the host chunk |
| `InterfaceBlockEntity` / `InterfacePart` (`InterfaceLogic.notifyNeighbors`) | `TickManager.wakeDevice` + `Platform.notifyBlocksOfNeighbors` | **neighbour updates** (other mods' callbacks) + AE2 T1 |
| `SmartCablePart` / `AEBasePart` | `markForUpdate` / `markForUpdateIfClientFlagsChanged` → `sendBlockUpdated` | client sync of the host chunk |
| `CraftingBlockEntity` | `updateSubType(false)` → `setExposedOnSides` (node) + block state | node + world write (the S2 neighbourhood) |
| `DriveBlockEntity` | `IStorageProvider.requestUpdate` + visual state | AE2 storage service + client sync |
| `QuantumBridgeBlockEntity` | `updateStatus = true` (flag) | none immediate |
| level emitter / redstone P2P | `updateState` / `setNetworkReady` | **redstone output** (neighbour updates) |

Every listener re-reads **current** grid state (`isOnline`, `isPowered`, `hasGridBooted`, `isNetworkPowered`,
channel state) at call time; the `reason` is used only to skip GRID_BOOT in some hosts.

Consequence today: these effects run on main for region-domain hosts — cross-domain world writes / neighbour
updates into region-owned chunks (same class as AE2-NODE-LISTENER-OWNER-001, outside topology).

## 3. The owner's questions

| question | finding |
|---|---|
| same-tick delivery required? | **No evidence of a requirement.** Listeners read live state at call time; their effects (block state, client sync, neighbour notify, wake) are not consumed by the rest of AE2's end tick. Deferred delivery = next safe point of the host's domain (a region's next tick drain). AE2 itself defers power-up visibility by 30 ticks. |
| coalescing of identical notifications? | **Safe in principle** — listeners are idempotent w.r.t. the notification (they recompute from state). Coalescing per (host, node) to one delivery per safe point would also absorb the double GRID_BOOT of a reboot. Not required for correctness; a candidate for volume only. Must not coalesce across a node identity change. |
| ordering POWER / CHANNEL / GRID_BOOT | Emission order per reboot: GRID_BOOT(booting) → CHANNEL(changed links) → GRID_BOOT(booted); POWER independent. Because listeners read state at delivery, the **final** state matters, not the sequence; a FIFO per host (what the Core primitive gives per domain) preserves the emitted order anyway. |
| lifecycle generation | Same as scope 3: revalidate at delivery — host not removed, still the BE at its position, node still ready (`TopologyCallbacks.runInDomain`). |
| host removed before delivery | Cancel (cbStale) — identical to scope 3; already verified. |
| callback re-entering AE2 | Yes: `wakeDevice` (TickManager, T1-protected), storage `requestUpdate`, `setExposedOnSides` (node → may reach topology/T0), neighbour updates that reach other AE2 hosts. Re-entry from a deferred delivery runs **outside** `notifyAllNodes`' loop and outside any section → no recursion on `ITERATION_BUFFER`, normal kernel admission for any AE2 mutation. |
| pathing completion before callback | CHANNEL/second GRID_BOOT are emitted after `PathingCalculation` and `booting=false`; a deferred delivery runs later still → it observes completed pathing (never earlier than today). A later reboot before delivery is harmless (listener reads the newer state). |

## 4. Design candidate (for decision)

Route a node-listener call made **outside** a kernel section, on a thread that is not the host's BE execution
domain, through the same `TopologyCallbacks.deliver` (resolve → inline if in domain, else enqueue + revalidate).
Effects:

- pinned AE2 hosts (domain MAIN, production pins): end-tick delivery stays inline — **no change** in production
  for AE2 itself; changes delivery only for unpinned AE2-integrated hosts in a region (the 57/run);
- test config (ae2 unpinned): moves ≈2k/run end-tick deliveries to region threads;
- `ITERATION_BUFFER` / recursion check: unaffected (delivery after the loop);
- latency: up to one region tick for region hosts.

Open points for the decision:
1. Scope: only the three end-tick emitters (match `notifyStatusChange` reasons POWER/CHANNEL/GRID_BOOT from
   `Grid.notifyAllNodes` / `finalizeChannels`), or **every** out-of-section off-domain listener call (also
   `onOwnerChanged`, `onSaveChanges` paths) — the latter is simpler and domain-generic, but wider.
2. Coalescing: none (FIFO, exact) vs per-(host, node) coalescing per safe point.
3. Fixture: deterministic power toggle / reboot of a grid with a region-domain host and a MAIN host; assert each
   host's listener runs in its domain, final state correct, no stale delivery, ordering per host FIFO.
