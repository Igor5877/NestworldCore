# PKG-002 — Mod API distribution model (investigation, 2026-10-01)

**Status: PKG-002 — CLOSED / VERIFIED (2026-10-01). Packaging is frozen.**

Commits: `72d5b3430` decision record · `b092a61a8` provider artifact · `a086b62a9` jar-in-jar
packaging + Core version range · `21c0644ca` Gate 2 (SPI ABI) · `e7c6f08c4` Maven publication ·
`347fd775c` reproducible fixtures (`tests/modapi-compat`).

## Distribution invariant

```
Consumer never owns net.nestworld.modapi classes.
Exactly one FML provider supplies the API namespace at runtime.
FML establishes declared version compatibility (Gate 1).
NestWorldCore establishes required SPI ABI compatibility
  before SPI-dependent Core initialization (Gate 2).
Stock Forge and NestWorld may use different execution
  backends while consuming the same consumer jar.
```

## Closure evidence — `tests/modapi-compat/run-matrix.sh` (NestWorld 47.4.241, upstream Forge 1.20.1-47.4.20)

| Runtime | Consumer | Provider | Expected | Got | Linkage errors |
|---|---|---|---|---|---|
| Stock Forge | compileOnly | external | PASS | PASS — setVelocity 1.500/1.5 | 0 |
| Stock Forge | compileOnly | missing | FML refusal | `Mod ID: 'nestworld_modapi' … Actual version: '[MISSING]'` | 0 |
| Stock Forge | jarJar | nested | PASS | PASS — 1.500/1.5 | 0 |
| NestWorld | compileOnly | built-in | PASS | PASS — 1.470/1.5 | 0 |
| NestWorld | jarJar | built-in + nested | PASS | PASS — 1.500/1.5 | 0 |
| NestWorld | compileOnly | unsupported newer 1.0.1 | FML refusal | `Requested by: 'nestworld', Expected range: '[1.0.0-SNAPSHOT,1.0.0-SNAPSHOT]', Actual version: '1.0.1'` | 0 |
| NestWorld | compileOnly | supported version, `setSlot` removed (S5) | ABI gate refusal | `InventorySetBackend no longer declares setSlot(…)` | 0 |
| NestWorld | compileOnly | supported version, abstract method added | ABI gate refusal | `requires flush()V … does not implement` | 0 |
| NestWorld | compileOnly | supported version, registry deleted | ABI gate refusal | `missing class …InventorySetBackendRegistry` | 0 |
| NestWorld | compileOnly | 9.0.0, `setSlot` removed (E7) | FML refusal | refused by Gate 1 (the consumer's own range is the first line FML prints) | 0 |

10/10 as expected; no server process left behind. The rest of this document is the chronological
record (experiments, decisions, implementation, Gate 2) that led here.

---

**Earlier status: model PROVEN by experiment (E1–E6); decisions TAKEN (architecture owner, 2026-10-01); implementation NOT started; PKG-002 PASS additionally requires E7 (below).**

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

---

# Implementation verification (real built artifacts, no hand-made jars or jar surgery)

Build `47.4.240`: the forge module contains **0** `net/nestworld/modapi` classes; the universal jar
carries `META-INF/jarjar/nestworld-modapi-provider-1.0.0-SNAPSHOT.jar` plus `metadata.json`
(identifier `net.nestworld:nestworld-modapi-provider`); Core's `mods.toml` declares
`nestworld_modapi` mandatory, `versionRange="[1.0.0-SNAPSHOT]"` (the only verified version).

Build-time canaries (each negative-tested, then restored byte-identical):
- API classes leaked into the forge module → `16 net/nestworld/modapi classes inside the forge
  module … the API must ship only as the jar-in-jar provider`.
- Core range excluding the built-in provider → `Core's supported Mod API range [2.0.0,3.0.0)
  rejects the built-in provider 1.0.0-SNAPSHOT; widen it only after compatibility verification`.
- Provider assembled from un-reobfuscated classes → `… is not SRG-named (expected m_20148_)`
  (commit b092a61a8).
(A first attempt at the leak test injected the merge in a way that failed `:forge:jar` before the
canary ran; it proved nothing and was replaced by a valid variant.)

| # | Platform / `mods/` | Result |
|---|---|---|
| R | NestWorld, `mods/` EMPTY (API only from built-in jar-in-jar) | `Mod API provider nestworld_modapi 1.0.0-SNAPSHOT selected by FML from nestworld-modapi-provider-1.0.0-SNAPSHOT.jar`; `setVelocity` smoke APPLIED; API-1B regression A, B, C-SIM, D-routed, E-respawn-routed, R-routed, OFFLINE-routed, W-routed all PASS; linkage errors 0 |
| S1 (E2) | NestWorld + `compileOnly` consumer (0 API classes) | boots; consumer fixture PASS |
| S2 (E6b) | NestWorld + same-version provider in `mods/` | deduplicated; boots |
| S3 (D1) | NestWorld + newer, ABI-identical but UNVERIFIED provider 1.0.1 | **controlled refusal**: `Mod ID: 'nestworld_modapi', Requested by: 'nestworld', Expected range: '[1.0.0-SNAPSHOT,1.0.0-SNAPSHOT]', Actual version: '1.0.1'` |
| **E7** | NestWorld + ABI-incompatible provider 9.0.0 (`InventorySetBackend.setSlot` removed with ASM from the real artifact) | **PASS — controlled refusal** by FML `ModSorter` before any Core class loads; no `NoSuchMethodError`/`AbstractMethodError`/`NoClassDefFoundError` |
| E4 | stock Forge 47.4.20 + real provider + `compileOnly` consumer | STOCK-A, STOCK-D, STOCK-STALE PASS (`NestWorldCore present=false`) |
| E5 | stock Forge + consumer, no provider | clean refusal: `Expected range: '[1.0.0-SNAPSHOT,2.0.0)', Actual version: '[MISSING]'` |
| B1 (D2 form B) | stock Forge + ONE consumer jar carrying the provider as jar-in-jar (ForgeGradle `jarJar`) | bundled provider activates; STOCK-A, STOCK-D PASS |
| B2 (D2 form B) | NestWorld (built-in provider) + the same consumer jar | both nested providers unified, boots; Core D-routed PASS; consumer's write applied (`knowledge_book x5` in slot 0) |

**E7 verdict**: with an honest version, an ABI-incompatible provider is rejected in a controlled
way, because the check is declarative (Core's `mods.toml` range) and FML enforces it during early
loading — before the JVM has any reason to link a Core class against the provider. The linkage-
ordering concern from D1 does not arise on this path.

## Finding PKG-002-S5 — version metadata is trusted (boundary of D1) — RESOLVED by Gate 2 (below)

Same ABI-broken provider, but **claiming** the verified version `1.0.0-SNAPSHOT`: FML selects it
(equal versions; the copy in `mods/` won), Core's range accepts it, the server boots, and the
first `setSlot` fails with
`NoSuchMethodError: 'void net.nestworld.modapi.inventory.InventorySetBackend.setSlot(net.nestworld.modapi.inventory.SetSlotRequest)'`.
Any version-based policy has this limit: it protects against honest incompatibility, not against
a mislabelled build. Possible mitigation (not implemented; decision pending): a Core self-check at
mod construction that reflectively verifies the SPI members Core needs (e.g.
`InventorySetBackend.setSlot(SetSlotRequest)`), catching `LinkageError`, and refusing to start with
a readable message — cheap, and it turns S5 into a controlled rejection too. Physically separating
the SPI (D1a) would narrow it further.

## Consumer-contract findings from this run

- **Snapshot ordering**: Maven orders `1.0.0-SNAPSHOT` BELOW `1.0.0`. A consumer declaring
  `versionRange="[1.0.0,)"` is refused against a `1.0.0-SNAPSHOT` provider (observed). Consumers must
  declare e.g. `[1.0.0-SNAPSHOT,2.0.0)` while the API is a snapshot — or the API moves to release
  versions.
- **Form B build recipe**: ForgeGradle's `jarJar` output needs its own reobfuscation
  (`reobf { jarJar { } }`, `jarJar.finalizedBy('reobfJarJar')`); without it the consumer's own
  classes stay MCP-named (observed before the fix).
- **The jar-in-jar identifier is contract**: `net.nestworld:nestworld-modapi-provider`. The provider
  is not yet published to a Maven repository by this build; form B was tested against a local
  `mavenLocal()` install of the built artifact (publication is part of the follow-up fixtures step).

## Test-harness notes (not product results)

- The stock probe's FIFO-drain barrier only covers main-thread tasks. On NestWorld with the player
  region-owned (left assigned by an earlier routed Core fixture), `setSlot` applies on a region
  thread after that barrier, so the probe read the slot too early and printed FAIL; the end state
  (`knowledge_book x5`) showed the write applied correctly.


---

# Provider SPI Compatibility Gate (Gate 2) — resolves S5 (decision: architecture owner, 2026-10-01)

```
FML version gate (Gate 1, Core mods.toml range)  →  provider selected
  →  NestWorldCore bootstrap: NestworldMod constructor, first statement
  →  Gate 2: SPI ABI preflight (ModApiAbiGate)
       required classes exist · required methods/constructors/fields resolve with the exact
       descriptor and invocation kind · implemented SPI types match in BOTH directions
  →  PASS → only then is NestworldRegionSystem (first SPI-dependent Core class) touched
```
- **Expected ABI is generated, not maintained**: `:forge:spiAbiManifest` scans Core's compiled
  bytecode (ASM tree API) for every reference to a `net/nestworld/modapi/**` class/member and every
  modapi type a Core class implements; for implemented types it also records the type's abstract
  methods as compiled in `:modapi` ("contract"). Output `META-INF/nestworld/spi-abi.json`
  (`builtAgainstModApi`, 12 classes, 25 members, 2 implemented types for this build). A universalJar
  canary fails the build if it is missing or empty (negative-tested).
- **No compile-time SPI references in the gate**: classes are loaded with
  `Class.forName(name, false, loader)` (no initialization); members are resolved with
  `MethodHandles.Lookup.find{Static,Virtual,Constructor,Getter,...}` — the same resolution the JVM
  performs at the call site, without running provider code.
- **Not a security mechanism**: a malicious provider can pass and misbehave. Scope: no late
  linkage errors from a damaged, mis-built or swapped provider.

Gate run 1 found a gap in the gate itself: the S5 provider (`InventorySetBackend.setSlot` removed)
PASSED, because Core never *calls* that method — Core *implements* it and the provider calls it.
The first version only checked "provider's abstract methods ⊆ Core's implementation". Fixed by
making implemented types a two-way contract (the provider must still declare every method Core
implements for it). Run 2 (same build version 47.4.241, same scratch instance, fresh boot each):

| # | Provider in `mods/` (besides the built-in one) | Gate 1 | Gate 2 | Linkage errors | Gameplay executed |
|---|---|---|---|---|---|
| G0 | none (built-in only) | PASS | PASS — 39 checks | 0 | boots; setVelocity smoke, A, D-routed, E-respawn-routed PASS |
| **G1 (S5)** | claims `1.0.0-SNAPSHOT`, `InventorySetBackend.setSlot` removed | PASS | **FAIL**: `InventorySetBackend no longer declares setSlot(Lnet/nestworld/modapi/inventory/SetSlotRequest;)V, which net.nestworld.region.NestworldInventorySetBackend implements and the provider is expected to call` | 0 | 0 (`Preparing level`, `Done`, Core backend install: all 0) |
| G2 | claims `1.0.0-SNAPSHOT`, abstract `flush()V` added to `InventorySetBackend` | PASS | **FAIL**: `requires flush()V, which … NestworldInventorySetBackend … does not implement` | 0 | 0 |
| G3 | claims `1.0.0-SNAPSHOT`, `InventorySetBackendRegistry` deleted | PASS | **FAIL**: missing class + 2 unresolvable members (`install`, `uninstall`) | 0 | 0 |
| G4 (E7) | `9.0.0`, `setSlot` removed | **FAIL** (`Expected range: '[1.0.0-SNAPSHOT,1.0.0-SNAPSHOT]', Actual version: '9.0.0'`) | not reached | 0 | 0 |

Diagnostic shape (G1): `NestWorld Mod API provider 1.0.0-SNAPSHOT (g-remove-setSlot.jar) is
ABI-incompatible with this NestWorldCore build (built against Mod API 1.0.0-SNAPSHOT). Startup
aborted before any SPI-dependent Core initialization. Problems (1 of 39 checks): - …`.
Without Gate 2 the same G1 provider booted and failed at the first `setSlot` with
`NoSuchMethodError` (S5, recorded above).

Variants were produced from the REAL built provider with ASM (version string rewritten in
`mods.toml`; the named class/method removed, added or deleted), not from hand-assembled classes.
