# 1.21.1 / NeoForge migration — scope findings and conditions (2026-08-11)

## Why this exists

A "just curious" question ("подивись на масштаб роботи на перехід на 1.21.1") turned into two real,
measured audits (not estimates) of what moving NestWorldCore from Minecraft 1.20.1 to 1.21.1 would
actually cost. This doc preserves those findings and the conditions that should hold before anyone
seriously proposes attempting this migration. **No migration work has been started or scheduled.**

## Audit 1 — vanilla 1.20.1 → 1.21.1 diff, measured against NestWorld's own patches

Method: real deobfuscation of 1.21.1 via `ForgeAutoRenamingTool` + official Mojang mappings, bytecode
method-signature comparison against this project's own clean 1.20.1 source
(`projects/clean`), cross-referenced against every file NestWorld has patched
(`patches/minecraft/**/*.patch`).

- **694 total patches**: 537 server-side, 157 client-side (client side not audited — needs a
  separate `client.jar` deobfuscation pass).
- Heaviest NestWorld patches by diff size: `ChunkMap.java` (1040 lines), `LivingEntity.java` (839),
  `Entity.java` (684), `ServerLevel.java` (622), `ServerChunkCache.java` (521), `Explosion.java` (430).
- Server-side classes, 1.20.1 → 1.21.1:
  - **30 / 537** vanished or renamed outright (class not found at the same path).
  - **121 / 537** method signatures identical — candidates for a clean rebase.
  - **386 / 537** structurally changed to some degree.
- Per-file bytecode method diff (changed/total) for the files NestWorld's region-sharding
  architecture leans on most:

  | File | Methods changed / total | Read |
  |---|---|---|
  | `LevelTicks.java` | 0 / N | identical |
  | `LevelChunk.java` | 4 / 62 | nearly clean |
  | `LevelAccessor.java` | 9 / 28 | small |
  | `DistanceManager.java` | 15 / 55 | small |
  | `Explosion.java` | 21 / 33 | moderate |
  | `ServerChunkCache.java` | 26 / 73 | moderate |
  | `ChunkHolder.java` | 65 / 69 | **nearly everything** |
  | `ChunkMap.java` | 164 / 170 | **near-total rewrite** |

**Caveat**: these numbers measure the cost of rebasing the *vanilla-side* patches (method
signatures). They do **not** cover: semantic changes hiding inside unchanged signatures (an
"identical" `LevelTicks` still needs its behavior re-verified, not just its shape); the cost of
re-validating NestWorld's own region-sharding logic (`RegionThread`, `WorldRegion`, the mailbox
system, `EntityOwnershipGuard`, `BoundaryEntityTransfer`, etc.) against whatever the new chunk/entity
lifecycle actually does; or the 157 untouched client-side patches.

## Audit 2 — NeoForge itself vs Forge (the toolchain layer, not vanilla MC)

Forge's own maven (`maven.minecraftforge.net`) has **zero** releases for 1.21.1 — the modding
ecosystem (including the large modpacks this project tests against) has moved to **NeoForge**. This
means the migration is not just a vanilla version bump; it's also a toolchain swap.

Compared the official `neoforged/NeoForge` GitHub repo's `1.20.1` branch against its `1.21.1` branch:

- `patches/minecraft/net/minecraft/server/level/ChunkHolder.java.patch` (1.20.1) **no longer exists**
  — replaced by `GenerationChunkHolder.java.patch` (1.21.1). Confirms the vanilla-side class was
  genuinely split/renamed, independent of anything NestWorld does.
- `ChunkMap.java.patch`: **74 lines** (1.20.1) → **107 lines** (1.21.1). A direct diff between
  NeoForge's own two patch files: **177 of 181 lines differ** — even NeoForge's *own* hooks into
  `ChunkMap` were rewritten almost entirely between these releases, before NestWorld's patches ever
  enter the picture.
- Patch directory layout itself changed: `patches/minecraft/net/...` → `patches/net/...` (the
  `minecraft` path segment was dropped).
- Namespace: `net.minecraftforge` → `net.neoforged` is a **full replacement**, not a parallel
  addition. NestWorld's own code (`src/main/java/net/nestworld/`) references the old namespace in
  only **5 files** (`CrossRegionCapabilityBus`, `NestworldCommand`, `NestworldCompat`,
  `NestworldMod`, `NestworldRegionSystem`) — mechanically small on its own. But **402 patch files**
  (inherited Forge base infrastructure, not NestWorld's own authored code) also reference
  `net.minecraftforge` — those aren't hand-edits, they require rebasing onto NeoForge's own patch
  base entirely, which loops back to the point above.
- Build tooling: confirmed to be a different Gradle plugin set (`net.neoforged.gradleutils` and
  related NeoForge-specific plugins), not classic ForgeGradle. Whether a direct equivalent of this
  project's `genPatches`/`applyPatches` workflow exists under NeoForge's tooling was **not verified
  in the time available** — flagged as an open question, not assumed either way.
- NeoForge's event-bus / mod-registration API changes relative to Forge: **not verified** — left as
  an explicit research gap rather than relying on possibly-stale general knowledge.

## The combined picture: three compounding layers, not one

1. Vanilla 1.20.1 → vanilla 1.21.1 (measured, Audit 1).
2. Forge's own 1.20.1 patches → NeoForge's own 1.21.1 patches to the *same* vanilla files (measured,
   Audit 2 — e.g. `ChunkMap`'s patch is 177/181 lines different even at this base layer).
3. NestWorld's own 694 patches on top of layer 2 (not yet attempted or measured against the new
   base — everything in Audit 1 was measured against *clean vanilla*, not against NeoForge's own
   already-modified `ChunkMap`/`ChunkHolder`, so the real number for NestWorld's own rebase effort is
   likely higher than Audit 1's numbers alone suggest).

`ChunkMap` and `ChunkHolder` — the two files NestWorld's region-sharding architecture depends on most
heavily — sit at the "near-total rewrite" end of layer 1, *and* get rewritten almost as heavily again
at layer 2. The ground those patches stand on has moved twice, not once.

## Conditions before anyone seriously proposes attempting this migration

These are the logical prerequisites the findings above point to — not a decision, not a plan, no
timeline attached:

1. **Confirm an actual driver exists.** 1.20.1 remains the dominant version for the large modpacks
   this project validates against (ATM9 and friends); no forced urgency was visible in this
   research. Don't migrate because a newer version exists — migrate when something concrete requires
   it (an ecosystem shift, a specific mod dependency, etc.).
2. **Budget it as a new project phase, not an incremental update.** Given `ChunkMap`/`ChunkHolder`
   near-total rewrite at two compounding layers, the vanilla+NeoForge-base rebase alone is a
   multi-week effort at minimum, before NestWorld's own region-sharding logic is even re-validated
   against whatever the new chunk lifecycle turns out to do.
3. **Close the NeoForge event-bus/registration API gap first.** NestWorld's own code only directly
   touches 5 files referencing `net.minecraftforge`, but if the underlying event/registration model
   changed structurally (not just a renamed package), those 5 files may not be mechanical renames
   either — this needs a real answer before estimating the modding-API-surface side of the cost.
4. **Verify NeoForge's patch-development workflow before committing.** If NeoGradle/ModDevGradle
   doesn't have a direct `genPatches`/`applyPatches` equivalent, the entire day-to-day patch-editing
   loop this project's whole development process depends on needs a replacement, not just a
   toolchain repoint — this changes the shape of the whole effort, not just its size.
5. **Treat `ChunkMap` + `ChunkHolder` as the actual go/no-go gate.** Since the region-sharding
   architecture's heaviest hooks live specifically in these two files, prototype a rebase of *just*
   those two onto the new NeoForge 1.21.1 base before committing to anything else. If that piece
   alone proves intractable, the rest of the migration's cost is moot and doesn't need estimating
   further.

## Status

Research-only, both audits completed via real tooling (not guesses). No migration work started, no
timeline proposed. Revisit this doc if/when a concrete driver for moving off 1.20.1 appears.
