# AE2-COMPAT-ALLOWLIST-001 — audited `IAEPowerStorage` participant set (M5c)

**Status: AUDIT (2026-10-14).**
- Source: the SkyBlock island pack (`/root/skyblock-island-deploy/mods_new_extract/mods`), with
  jar-in-jar included.
- Tool: `compat/nestworld-compat-ae2/tools/power_storage_closure.py`, a transitive
  super/interface closure to `appeng.api.networking.energy.IAEPowerStorage`. It prototypes the
  M5c startup gate (001.2 §1a).

## Closure (19 classes)
| Class | Mod | Version | Overrides a power-path method? |
|---|---|---|---|
| `appeng.blockentity.grid.AENetworkPowerBlockEntity` | ae2 | 15.4.10 | AE2 (audited family) |
| `appeng.blockentity.misc.ChargerBlockEntity`, `InscriberBlockEntity` | ae2 | 15.4.10 | AE2 |
| `appeng.blockentity.networking.ControllerBlockEntity`, `CreativeEnergyCellBlockEntity`, `EnergyAcceptorBlockEntity`, `EnergyCellBlockEntity` | ae2 | 15.4.10 | AE2 |
| `appeng.blockentity.powersink.AEBasePoweredBlockEntity`, `IExternalPowerSink` (interface) | ae2 | 15.4.10 | AE2 (public inject, extract and getters are `final`) |
| `appeng.blockentity.storage.ChestBlockEntity`, `appeng.me.energy.GridEnergyStorage`, `appeng.parts.networking.EnergyAcceptorPart` | ae2 | 15.4.10 | AE2 |
| `com.glodblock.github.extendedae.common.tileentities.TileExCharger`, `TileExInscriber`, **`TileCaner`**, **`TileCircuitCutter`** | expatternprovider | 1.20-1.4.15-forge | **no.** `javap -p` shows no override of `extractAEPower(D,Actionable)`, `getInternal*`, `emitPowerStateEvent` or `isInternalPublicPowerStorage` |
| **`net.pedroksl.advanced_ae.common.entities.QuantumCrafterEntity`**, **`ReactionChamberEntity`** | advanced_ae | 1.3.5-1.20.1 | **no** (same check) |
| `org.zeith.solarflux.mixins.compat.ae2.SolarPanelTileMixin`, runtime target `org.zeith.solarflux.block.SolarPanelTile` | solarflux | 20.1.11 | third party, audited in 001.1 §1 and §2 (M1 path). There are no subclasses of the target in the pack |

## Correction to 001.1 §2 (participant table)
- Bold rows (TileCaner, TileCircuitCutter, QuantumCrafterEntity, ReactionChamberEntity) are
  **missing** from 001.1 §2.
  - All extend `AENetworkPowerBlockEntity` and override no power-path method. Under a domain lock
    they execute only AE2's audited code, the same as the `AEBasePoweredBlockEntity` family.
    CONFIRMED (bytecode).
- 001.1 listed `Crankable` as a participant. It is **not** in the closure, so it is not an
  `IAEPowerStorage`.
- megacells adds no class of its own; its cells use AE2's classes.
- The proof sketch of 001.1–001.2 is unchanged: the audited code under the lock is still AE2 plus
  SolarFlux. Only the table was incomplete.

## Runtime-barrier note (glue)
A mixin's *runtime* class is its target, not the mixin class. The runtime barrier therefore
allowlists `org.zeith.solarflux.block.SolarPanelTile`, and the startup gate must map mixin classes
that declare `IAEPowerStorage` onto their targets **and the targets' subclasses**.
