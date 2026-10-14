# PIPEZ-COMPAT-001 — Pipez energy pipes as an external writer into other mods' energy networks

**Status: AUDIT-1 COMPLETE (2026-10-14).** The Pipez → AE2 writer path is CONFIRMED to exist in the
installed code. That it caused the observed incidents is CORRELATED only. No implementation.

## Exact version
| Item | Value |
|---|---|
| Jar | `pipez-forge-1.20.1-1.2.26.jar` |
| sha256 | `f692b0a24c46c954…` (full value in `/root/mod-compat-audit/jars/SHA256SUMS`) |
| Copies compared | 15 local copies, all identical, including the island deploy set |
| Island jar | not yet hashed on the island itself |
| Code inspected | installed bytecode, decompiled with Vineflower 1.10.1 (outside the repository) |

## Observed
- Auto-pin pinned `be:pipez:energy_pipe` on 2026-10-12 at 23:45 (Kyiv) after 2 own-tick errors.
  The recorded reason is `ReportedException: Ticking block entity`; the root cause was not
  recorded.
- No AE2 energy race followed until the end of the observation window, 2026-10-14. Before the
  pin it had recurred 17 times after `ae2` itself was pinned.

## Call graph (installed bytecode)
```
PipeLogicTileEntity.tick()                       <- block-entity tick; a NestWorld region thread unless pinned
  energyCache[side].ifPresent(PipeEnergyStorage::tick)
  PipeEnergyStorage.tick -> EnergyPipeType.pullEnergy(pipe, side)
    source = extractingConnection.getEnergyHandler(level)          (Forge ENERGY capability of the source block)
    connections = pipe.getSortedConnections(side, type)            (every endpoint of the pipe network)
    insertOrdered / insertEqually:
      destination = connection.getEnergyHandler(level)
          -> Connection.getCapabilityRaw: level.getBlockEntity(connection.pos).getCapability(ENERGY, dir)
      EnergyUtils.pushEnergy(source, destination, n):
          source.extractEnergy(n, true); destination.receiveEnergy(n, true)
          source.extractEnergy(m, false); destination.receiveEnergy(m, false)   <- synchronous, caller's thread
```

If the destination is an AE2 energy acceptor or controller, the call continues as in
AE2-COMPAT-001:
`ForgeEnergyAdapter.receiveEnergy` → `injectExternalPower` → `funnelPowerIntoStorage` →
`EnergyService.injectPower` → `injectProviderPower`.

## Findings
1. **CONFIRMED (code).** A Pipez energy pipe ticking on a region thread enters AE2's shared
   energy-service state synchronously from that thread. This holds for any destination's Forge
   Energy implementation, Mekanism included. This answers MOD-COMPAT-1 §8.
2. **CONFIRMED (code).** Destinations are every endpoint of the pipe network, not only the pipe's
   neighbours.
   - They are resolved with `level.getBlockEntity(pos)` on the pipe's thread.
   - An endpoint can be in another chunk or region than the ticking pipe, so the pipe writes into
     a block entity that a different region owns.
   - **Class:** MULTI_OWNER_BOUNDED, with an UNBOUNDED_CALLBACK_GRAPH on the destination side,
     because the destination is any mod's capability.
3. **CORRELATED.** The AE2 incidents stopped after the `pipez:energy_pipe` pin. Not proven
   causal; other FE writers near the AE2 grid are not ruled out.
4. **Pipez itself is not unsafe.** Its own state (round-robin index, recursion guard) is per pipe.
   It acts like a vanilla single-threaded mod, calling other mods' capabilities synchronously.
   That design conflicts with NestWorld's parallel block-entity execution, not with Pipez's own
   contract.

## Candidate adapter boundary
- Is a Pipez addon needed? Probably not as the primary fix.
  - The defect is on the receiving side: AE2's and Mekanism's state is not safe for concurrent
    entry.
  - A Pipez-side adapter (for example, routing pushes into foreign-region block entities through
    the owner) would fix only Pipez. Every other FE pipe would keep the problem.
- To keep under review: Pipez's cross-region `getBlockEntity` writes are an instance of the general
  "foreign-owner capability call" class. That class may need a NestWorld-level answer later, once
  AE2 and Mekanism are compared (MOD-COMPAT-1 §25 step 10).

## Pin state
- **Before:** `be:pipez:energy_pipe` (auto, 2026-10-12).
- **After:** unchanged.

## Open items (owner)
- The BE-tick error behind the pin, from the rotated log of 2026-10-12 around 23:45. If it is
  AE2's "Cannot modify energy providers while energy is being extracted" on a Pipez stack, finding
  3 becomes STRONGLY INDICATED for that incident.
