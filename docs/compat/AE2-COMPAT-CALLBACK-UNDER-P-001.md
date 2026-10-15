# AE2-COMPAT-CALLBACK-UNDER-P-001 — deferred topology callbacks delivered while P(G) is held

**Status: OPEN (owner, 2026-10-15). CONFIRMED MECHANISM. No fix yet; inventory first.**
- Separate gate. It does **not** reopen GRID-PHASE-001, whose proven invariant still holds:
  *an active Grid phase XOR a structural topology mutation*.
- **REDSTONE-P2P-CALLBACK-001 is the CONFIRMED MANIFESTATION** of this mechanism (P2P-10,
  `AE2-COMPAT-P2P-001-EVIDENCE.md` §3).

## Mechanism (CONFIRMED by run)
```
GridPhaseGate.topology()                     acquires P(G)
  └─ KERNEL.guardedStrict(...)               T0 → domains
        operation completes                  T0 / domains released (exitOuter)
        Ae2Kernel.deliver(deferred)          ← M1 / deferred callbacks run HERE
  guardedStrict returns
P(G) released                                ← only now
```
Any M1 / deferred callback produced by a topology operation is delivered **after T0 and domains
are released, but while P(G) is still held**. Probe stack (P2P-10):
`Ae2Kernel.deliver > run > guardedStrict > guardedStrictUnchecked > nw$create lambda > GridPhaseGate.topology > GridConnection.create`.

TOPOLOGY-CALLBACK-001's claim "G2 node listeners … not under P / T0 / domains" is therefore
correct for T0 and domains, and **wrong for P**.

## Manifestation: REDSTONE-P2P-CALLBACK-001 (CONFIRMED)
- A grid merge (`GridConnection.create`) → `P2PService.addNode` → `updateTunnel` → Redstone P2P
  `setNetworkReady` → `putInput` → `notifyBlocksOfNeighbors`.
- It ran on main with `P(G)` held, at a position owned by another free-running region (#39).
- This is world / foreign neighbour-block code under P(G). The cross-region part is H3 class.

## Required before any fix (owner rule)
Inventory **every** deferred callback class that can be queued during a topology operation and
classify each one:

| Class | Under P? |
|---|---|
| pure / internal callback (AE2 / addon bookkeeping, no foreign code, no world access) | potentially acceptable under P |
| external / world / capability / event callback | **must not run under P** |

- Do **not** move all callbacks out of P blindly.
- The fix direction from Q4: under P(G), record a stable intent → release P(G) → execute in a valid
  owner context. A cross-region target falls under H3 (fail-closed).

## Release hygiene TODO (owner, 2026-10-15)
- Before RC, `RedstoneP2PProbeMixin` (P2P-10 probe) should **not be applied at all** when fixtures
  are off. Being a runtime no-op is not enough.
- Even a no-op mixin couples production to the target class / method, and can break startup if the
  signature changes.
- Not a blocker on the development branch.
