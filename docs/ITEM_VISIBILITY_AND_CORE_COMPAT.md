# Invisible dropped items — investigation, root cause, and core/modpack compatibility

## Symptom
On a heavy modpack (ZombieCraft 3.4, ~428 client mods), dropped items were invisible to
the client (server had them). Client log spammed
`IllegalStateException: Invalid entity data item type for field N`.

## Root cause (proven — NOT the core)
A **client↔server entity-data field-numbering mismatch caused by different mod sets**:
the client loaded 428 mods, the test server 351. Mods register `SynchedEntityData`
accessors; a different *set* shifts the field-id space, so the client reads
`ItemEntity` field 8 (the `ItemStack`) as a different serializer and rejects it → the
item never gets its stack → renders invisible. Living entities/players throw the same
but still render their base model.

How it was proven:
- **Single-player** (client == integrated server, same mods) → items **visible**.
- Captured server packet at pairing → `field 8, serializer=ITEM_STACK, <item>` (correct).
- **Stock Forge** with the same server mods → same invisibility → not the core.
- Client `latest.log` `old=/new=` types showed the per-field type shift.

Fix: build the **server from the modpack manifest** (same versions as the client), not
from a hand-assembled jar set. Then numbering matches and items appear.

## Core thread-safety fixes shipped while investigating (real latent bugs)
These are correct and worth keeping even though they were not the headline cause:
- **`SynchedEntityData.defineId` is `synchronized`** — the shared static `ENTITY_ID_POOL`
  read-modify-write raced when entity classes first-initialised on parallel region
  threads, producing *non-deterministic* field ids (a second, core-side flavour of the
  same numbering bug). Per-class ordering is deterministic, so the lock reproduces the
  single-threaded numbering.
- **`SynchedEntityData` volatile `value`/`dirty` + `packDirty` clears the dirty gate
  *before* the scan** — entities `set()` on region threads while `sendChanges` packs on
  the main thread; the old unconditional post-scan `isDirty=false` (outside the lock)
  clobbered a concurrent set's dirty flag and dropped the update. Values are snapshotted
  via a defensive `serializer.copy()` under the read lock, so data is never corrupted —
  only the dirty bookkeeping needed hardening.
- **`ServerEntity.sendPairingData` reads `getNonDefaultValues()` fresh** instead of the
  cached `trackedDataValues`; on the region core the cache could be a stale empty
  construction-time snapshot for a still, not-yet-watched item → invisible until re-dirtied.
- **`ChunkMap` periodic tracker** pushes `sendChanges()` whenever the entity has viewers
  (`!seenBy.isEmpty()`), not only when its chunk reads "entity-ticking" (that signal is
  computed on the main thread while entities tick on region threads).
- **`LanguageHook.loadLocaleData` tolerates malformed mod lang JSON** (catch
  `RuntimeException` per file) — our Forge base is older/stricter than 47.4.18 and a mod
  shipping non-strict `en_us.json` threw out of `handleServerStarting`, stopping the
  dedicated server right after "Done".

## Running a modpack on a NestworldCore server — remove conflicting perf mods
The core supersedes/patches these; they crash or deadlock if also present:
| Mod | Conflict |
|---|---|
| `alternate_current` | core bundles it → duplicate `alternate.current` Java module → startup crash |
| `harichunk` | threaded chunk-I/O → class-init deadlock vs region threads (hang after "Done") |
| `radium`, `saturn` | Lithium-port `PalettedContainer` mixins vs the core's patched `PalettedContainer` → `@Shadow` FATAL |

Client may keep these (compat mods `BetterCompatibilityChecker`/`MyServerIsCompatible`
allow the connection mismatch).

## Validated (47.4.122, ZombieCraft 3.4)
~16k dropped items → 20 TPS (regions auto-split 2→5). 2500 zombies targeting a player →
15.7 TPS (regions 5→16); the entity-by-type / nav races the core warns about stayed clean.
Per-region load: `/nestworld status` (cost=ms per region) or
`/spark profiler start --thread *` (each `NestWorld-Region-N` thread shown separately).
