# Mod compatibility fixes

## Contract (owner decision 2026-10-26; replaces the 2026-09-22 wording)

**GMEIL default:** observation and attribution never mutate third-party mod bytecode.

**Exception:** an explicitly reviewed, version-pinned compatibility fix MAY transform third-party mod
bytecode when all of these hold: the root cause is reproduced; a deterministic negative control exists;
the transformation has an exact structural preflight (a mismatch leaves the class untouched and is reported
as `COMPATIBILITY FIX NOT APPLIED` / DEGRADED); a regression exists; the fix is documented and frozen.

**Delivery for new mod-specific fixes: compat addon > Core coremod.** Core keeps generic ownership,
threading and diagnostics and does not encode the internals of a specific mod version. Mod-specific fixes
ship as separate jars (`nestworld-compat-ae2`, `nestworld-compat-mekanism`) with their own exact
eligibility gate.

### What production Core actually registers (`src/main/resources/META-INF/coremods.json`)

| coremod | production | status |
|---|---|---|
| `static_collection_race_transformer.js` | **registered** (since d405f5187, 2026-09-29) | legacy exception, frozen |
| `tomsstorage_terminal_atomic_publish.js` | **registered** (since d405f5187) | legacy exception, frozen |
| `mod_compound_races.js` | **not registered** | offline research oracle / reference implementation |

The 2026-09-22 text ("the three NestWorld mod transformers are absent from production
`coremods.json`") became false with d405f5187; the two registered transformers are kept as legacy
exceptions rather than removed, and no new mod-specific transformer is added to Core.

`mod_compound_races.js` (including the MEKANISM-REGISTRY-RACE-001 M-A in 868be3340) stays an oracle:
its value is the real-bytecode negative control and the proof of the algorithm. Production delivery of
M-A is the `nestworld-compat-mekanism` addon (`docs/compat/AIRIS-REPRO-2026-10-26.md`). Until a fix
passes in its delivery form, an unpatched modpack is **not** established as safe for concurrent region
execution.

The old Mixin error handler that skipped a failing Fabric lifecycle injection and fired its
event from NestWorld code was also removed. The underlying `ChunkMap` lambda ordinal is restored
in core source so the original mod Mixin can apply and execute normally; this still requires
validation with that mod installed.

Canary is the user's explicit exception because its collection and POI optimizations overlap
NestWorld's. The loader disables only four conflicting Canary options in `canary.properties`
before Mixin loading. A late detection writes the same options and refuses to continue until a
restart; it never edits a mod JAR. Other mod configs stay untouched. The old opt-in, next-tick
`ContainerListener` router remains disabled until GMEIL has a main-thread bridge that preserves
the callback's synchronous timing contract.

The scripts target exact class and method shapes from the installed 1.20.1 modpack without
changing a mod JAR on disk. In offline tests, a structural mismatch leaves the original class
untouched and emits `COMPATIBILITY FIX NOT APPLIED`.

## Covered contracts

- **Ex Deorum 1.50** and **Forestry 2.10.2:** concurrent visual-update maps and per-chunk sets.
- **Mekanism 10.4.16.80:** concurrent `flowingStacks` containers and one monitor covering all
  `PathfinderCache` reads, writes, invalidation, and reset operations.
- **Tom's Storage 1.7.1:** rebuild terminal contents privately, then publish the complete map with
  one volatile reference write.
- **Sophisticated Core 1.3.40.1925:** serialize recipe-cache creation, lookup, publication, and
  invalidation. Recipe-change callbacks execute after releasing the cache monitor.
- **Draconic Evolution 3.1.2.621:** atomically detach a complete per-player update batch before
  packet serialization. New updates go to the next batch. A failed send restores the detached
  entries ahead of newer arrivals before rethrowing the failure. The crystal-ID registry uses a
  concurrent map, atomic first-writer registration, and value-conditional unload so a stale tile
  cannot remove a newer position with the same integer hash.
- **Applied Energistics 2 15.4.10:** use a concurrent event-type registry. Subscriber invocation
  remains outside map computation, preserving the original callback and reentrancy behavior.
- **Flux Networks 7.2.1.15:** serialize queue/map lifecycle operations, return stable connection
  snapshots to readers, and invoke disconnect callbacks after releasing the network monitor.
- **Industrial Foregoing 3.5.22:** use copy-on-write active-process lists so processes persist
  across ticks while registration and dead-process removal remain safe.

These legacy scripts did not turn every mod callback into a main-thread callback. Their offline
regressions cover only the shared-state contracts recovered from inspected bytecode; they are
not a production safety mechanism for the current unmodified modpack.

## Regression test

Run the offline harness against the server libraries and the installed mod directory:

```bash
python3 tests/coremods/run.py \
  --libraries /path/to/server/libraries \
  --mods /path/to/mods
```

The harness executes Forge's Nashorn version with the CoreMods 5.2.4 class filter, transforms
the real mod classes, checks rejection paths for changed and ambiguous bytecode, verifies
transformed methods with ASM, and exercises the recovered concurrency contracts with controlled
game/network stubs. It never writes to the mod JARs. The class filter matters: `ClassNode`,
`ClassReader`, `ClassWriter`, and `java.lang.Object[]` are not exposed to scripts despite the
corresponding JVM classes being present. The 47.4.223 bootstrap regression came from using
those blocked classes in coremods; a classpath-only Nashorn harness did not catch it.
