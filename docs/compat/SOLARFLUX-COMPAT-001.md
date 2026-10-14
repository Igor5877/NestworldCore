# SOLARFLUX-COMPAT-001 — SolarFlux panel energy written by its own tick and by AE2

**Status: OPEN / SHARED-STATE CONFIRMED / RACE NOT YET REPRODUCED (2026-10-14).**
Production: SolarFlux panel block entities that are AE2 nodes stay **pinned (conservative)** until
audited (owner decision).

## Exact version
`SolarFluxReborn-1.20.1-20.1.11.jar` (island pack). Code read from the installed bytecode with
`javap`: `org.zeith.solarflux.block.SolarPanelTile` and
`org.zeith.solarflux.mixins.compat.ae2.SolarPanelTileMixin`.

## Shared state
`SolarPanelTile.energy` (`long`) and the panel's `SimpleAttributeProperty` capacity / transfer
attributes.

| Writer | Thread in NestWorld |
|---|---|
| the panel's own tick (generation, push to neighbours) | the panel's region thread, unless pinned |
| `SolarPanelTileMixin.ae$extractAEPower` / inject → `SolarPanelTile.extractEnergy`: `setBaseValue` on an attribute, `putfield energy`, `setChanged()` | whichever thread holds the AE2 energy section (main at END tick, or any region thread entering AE2 through Forge Energy) |

Plain fields, no synchronization: a lost or duplicated energy update is possible.

`setChanged()` also issues vanilla neighbour output-signal notifications, which run neighbour
blocks' code. That is the path behind the lock-inversion analysis in AE2-COMPAT-DESIGN-001.1 §1.

## Node lifecycle
The mixin creates an AE2 managed node in the constructor and on NBT read. It destroys the node on
`setRemoved` and on chunk unload. These are AE2 topology changes on the thread where the panel is
loaded, removed or unloaded.

## Next
- Audit the panel tick and the push path.
- Decide whether the panel follows the AE2 domain while it is an AE2 node, or stays pinned.
- Reproduce with panels feeding an AE2 network from a region thread while AE2 extracts on main.
