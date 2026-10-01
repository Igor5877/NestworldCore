# PKG-002 — Mod API distribution model (investigation, 2026-10-01)

**Status: model PROVEN by experiment (E1–E6); decisions TAKEN (architecture owner, 2026-10-01); implementation NOT started; PKG-002 PASS additionally requires E7 (below).**

## The problem (API1B7-PKG-002)

A consumer mod that embeds `net.nestworld.modapi.*` cannot boot on NestWorld:
`ResolutionException: Modules nwinvprobe and forge export package net.nestworld.modapi.entity to
module minecraft`. NestWorld's `forge` module carries the same package (Core calls it directly).
API-PKG-1's "same consumer jar on both platforms" was proven before that merge (47.4.234) and is no
longer true.

## Target model (architecture owner, 2026-10-01)

```
consumer-mod.jar   compileOnly net.nestworld.modapi.*   (never bundles API classes)
                   mods.toml: mandatory dependency modId="nestworld_modapi"
        │
        ├── stock Forge  ── nestworld_modapi provider mod (installed by the server owner)
        └── NestWorld    ── API supplied by the platform
```

## Loader facts this depends on (read from this repo's FML source)

- `UniqueModListBuilder.buildUniqueList()` groups mod files by **first mod id** and keeps the
  **newest version**, silently (`selectNewestModInfo`, DEBUG log only). Non-mod library jars are
  grouped the same way by module name.
- It hard-fails (`Duplicate mods found`) only when the same mod id appears in files whose FIRST ids
  differ — e.g. if NestWorld's `forge` jar declared a second `[[mods]] nestworld_modapi`. So
  "forge declares the mod id" is not a viable way for NestWorld to supply it.
- `JarInJarDependencyLocator` receives every located mod file, including the forge jar.

## Experiments (scratch instances; repo build untouched)

Provider mod = the already-SRG-named `net.nestworld.modapi` classes from a deployed jar + a
`lowcodefml` `mods.toml` declaring `modId="nestworld_modapi"` (versions 1.0.0 and 1.0.1 built).
Consumer = the stock probe rebuilt with `compileOnly` on the published artifact and a mandatory
`nestworld_modapi` dependency — no API classes inside it.

| # | Setup | Result |
|---|---|---|
| E1 | NestWorld, `net.nestworld.modapi` **removed from the forge jar** (19 entries), provider 1.0.0 in `mods/` | Boots; Core in the `forge` module links to the API in another module. `setVelocity` smoke `TERMINAL APPLIED`; fixtures A, D-routed (clean rerun), E-respawn-routed, R-routed, C-SIM all PASS |
| E2 | E1 + `compileOnly` consumer | Loads, dependency satisfied; consumer's snapshot fixture PASS on NestWorld |
| E3 | Providers 1.0.0 **and** 1.0.1 in `mods/` | `Found 2 mods for first modid nestworld_modapi … Selected nestworld-modapi-provider-1.0.1.jar`; boots |
| E6 | NestWorld supplies the provider **as jar-in-jar inside the forge jar** (`META-INF/jarjar/`), nothing in `mods/` | FML extracts `nestworld_modapi 1.0.0`; consumer dependency satisfied; boots |
| E6b | E6 + server owner also drops provider 1.0.1 into `mods/` | Deduplicated: `nestworld_modapi (version 1.0.0 -> 1.0.1)` — the user's copy wins; Core fixture A PASS; consumer snapshot PASS (on a fresh incarnation) |
| E4 | Genuine stock Forge 47.4.20 + provider 1.0.0 + `compileOnly` consumer | STOCK-A, STOCK-D (cross-boundary), STOCK-STALE all PASS, `NestWorldCore present=false` |
| E5 | Stock Forge + consumer, **no provider** | Clean FML refusal before any game code: `Mod ID: 'nestworld_modapi', Requested by: 'nwinvprobe', Expected range: '[1.0.0,)', Actual version: '[MISSING]'` |

Experiment hygiene notes: one D run on the E1 server initially reported FAIL; the trace showed two
harness causes (a previously-aborted D had legitimately applied V1000 to the same object, and a
stale driver ran an E fixture concurrently, firing its probe inside D's respawn and resetting
shared telemetry). Primitive behaviour in that trace was correct (`V1001 → INCARNATION_MISMATCH
… versionTrackerTouched=NO`); the clean rerun passed. One consumer snapshot reported FAIL because
it reused a slot domain a Core fixture had written seconds earlier; on a fresh incarnation it passed.

## Conclusion

The owner's model works on both platforms with ONE consumer jar, **provided NestWorld stops
merging the API classes into its `forge` module** and ships the API as the same provider mod —
built in as jar-in-jar (zero install for NestWorld server owners), deduplicated by FML against a
copy the owner may also have dropped into `mods/`.

## Decisions (architecture owner, 2026-10-01)

### D1 — API version on NestWorld: FML may select, Core decides

FML's "newest wins" is the **artifact selection mechanism, not the compatibility policy**.
```
NestWorld bundles Mod API 1.2.0   +   mods/ contains Mod API 1.4.0
        → FML selects 1.4.0
        → NestWorldCore validates compatibility
             compatible   → boot
             incompatible → FAIL FAST (controlled, readable startup rejection)
```
- Core carries an **explicit supported range** (concept: `Mod API >= 1.2.0 && < 2.0.0`). SemVer
  alone is not trusted ("any 1.x is compatible" is NOT assumed). The range is widened only after
  compatibility verification against the new API version.
- Known limit, stated up front: a startup version check is not an absolute guard against an
  arbitrarily ABI-incompatible provider. If the JVM links a Core class that implements an
  incompatible SPI type before the check runs, the failure is `NoSuchMethodError` /
  `AbstractMethodError` / `NoClassDefFoundError`, not a controlled rejection. Hypothesis to be
  proven by E7: linkage is lazy, so a check placed early (in a class that references no SPI type,
  before `NestworldRegionSystem.onServerStarting` constructs the backends) runs first.

### D1a — Two contracts, documented now, physically split later if needed

```
PUBLIC MOD API              net.nestworld.modapi.*  facades and value types mods call
                            (NestWorldEntities, NestWorldInventory, InventoryTargetKey, ...)
                            → the stable contract for mods

PLATFORM PROVIDER SPI       VelocityExecutionBackend, VelocityBackendRegistry,
                            InventorySetBackend, InventorySetBackendRegistry, SetSlotRequest,
                            and the trackers Core calls directly
                            → implementation detail between Core and the provider; NOT part of
                              the public API's stability promise, even though it currently lives
                              in the same artifact and package
```
Moving the SPI into a separate artifact is not required now. If a third primitive confirms this
architecture, it can be moved without breaking any promise made to mods.

### D2 — Consumer jar-in-jar of the provider: ALLOWED; shading API classes: FORBIDDEN

```
FORBIDDEN                                  ALLOWED
consumer.jar                               consumer.jar
 └── net/nestworld/modapi/*.class           └── META-INF/jarjar/nestworld-modapi-provider.jar
```
The first creates the split-package collision (PKG-002 itself). The second keeps the API a
separate FML mod/module, which FML deduplicates (E3, E6b). Mod developers always compile with
`compileOnly`; jar-in-jar is a distribution choice, not shading. Both distribution forms are
supported and neither is mandatory:
- A. consumer + externally installed provider
- B. consumer + jar-in-jar provider

```
one consumer mod jar
   ├── stock Forge → provider (external or jar-in-jar) activates, ForgeInventorySetExecution backend
   └── NestWorld   → FML resolves one provider (built-in, external or jar-in-jar) + Core backend
```

### E7 — required before PKG-002 PASS

Deliberately ABI-incompatible provider (e.g. a method removed or changed in
`InventorySetBackend`, version 9.0.0) next to NestWorld's compatible built-in one:
```
FML chooses the incompatible 9.0.0
EXPECTED: controlled startup rejection naming the version and Core's supported range
NOT:      NoSuchMethodError / AbstractMethodError / NoClassDefFoundError
```
If a controlled rejection proves impossible because of JVM linkage ordering, "newest provider wins
+ runtime version check" is insufficient, and the ABI boundary (D1a physical split, or another
mechanism) must be decided before PKG-002 closes. That outcome would be a finding, not a failure.

## Implementation plan (separate atomic commits)

1. `feat(modapi): package Mod API as FML provider` — provider artifact from `:modapi`:
   `mods.toml` (`nestworld_modapi`, `lowcodefml`), SRG reobf against the patched-vanilla
   hierarchy, version metadata Core can read; keep publishing the MCP-named library for
   `fg.deobf` consumers; javadoc marks the SPI types as non-public (D1a).
2. `fix(packaging): consume Mod API provider through jar-in-jar` — stop merging `:modapi` classes
   into the forge module; embed the provider as jar-in-jar; artifact canaries (jar-in-jar present,
   SRG names inside it, zero `net/nestworld/modapi` entries in the forge module); Core
   compatibility check per D1, placed per E7's linkage evidence.
3. `test(modapi): add reproducible distribution fixtures` (if needed) — the stock-Forge probe
   (A and B consumer forms) in the repo, not only in a scratchpad.
4. `docs/PACKAGING.md`: retract API-PKG-1's "consumer embeds the API" guidance; document D1/D2.
5. Re-run E1–E6 with real built artifacts (no hand-made jars or jar surgery), then E7, plus the
   API-1B regression fixtures and a `setVelocity` smoke.
