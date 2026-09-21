# Mod compatibility fixes

These fixes adapt known single-threaded mod assumptions to NestWorld region execution without
changing a mod JAR. They target the exact class and method shapes from the installed 1.20.1 mod
pack. A structural mismatch leaves the original class untouched and emits
`COMPATIBILITY FIX NOT APPLIED`; no transformer may return a partially edited class.

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

These fixes do not turn every mod callback into a main-thread callback. They protect only the
shared-state contracts recovered from the inspected bytecode. Unknown versions fail visibly so
an operator can pin that mod until its new contract is reviewed.

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
